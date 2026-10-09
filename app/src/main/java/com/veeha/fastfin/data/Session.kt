package com.veeha.fastfin.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import com.veeha.fastfin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom

@Immutable
data class Session(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    val deviceId: String,
) {
    val host: String get() = serverUrl.substringAfter("://")
}

/** The `Authorization: MediaBrowser ...` header Jellyfin expects, in the
 * quoted form the official SDKs send. */
object Auth {
    private val deviceName: String = (Build.MODEL ?: "Android")
        .filter { it.code in 32..126 && it != '"' && it != ',' && it != '=' }
        .trim()
        .ifEmpty { "Android" }

    fun header(deviceId: String, token: String?): String = buildString {
        append("MediaBrowser Client=\"FastFin\", Device=\"").append(deviceName)
        append("\", DeviceId=\"").append(deviceId)
        append("\", Version=\"").append(BuildConfig.VERSION_NAME).append('"')
        if (token != null) append(", Token=\"").append(token).append('"')
    }
}

/**
 * Adds the session's auth header to every request bound for the signed-in
 * server: API calls, artwork, direct-play streams, HLS playlists and
 * segments. Because one OkHttp client serves all of them, the token never has
 * to ride in a URL (so it is never in an image cache key, a log line or a
 * crash report), and every request shares one connection pool.
 */
class AuthInterceptor(private val session: () -> Session?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = session()
        if (current == null || request.header("Authorization") != null) return chain.proceed(request)
        val server = current.serverUrl.toHttpUrlOrNull()
        if (server == null || server.host != request.url.host || server.port != request.url.port) {
            return chain.proceed(request)
        }
        val builder = request.newBuilder().header("Authorization", Auth.header(current.deviceId, current.accessToken))
        // Jellyfin picks the artwork format from Accept: WebP is a third
        // smaller than JPEG at the same quality and Android decodes it natively.
        if (request.url.encodedPath.contains("/Images/")) builder.header("Accept", "image/webp,image/*;q=0.8")
        return chain.proceed(builder.build())
    }
}

@Serializable
private data class AuthUser(val id: String, val name: String = "")

@Serializable
private data class AuthResponse(val accessToken: String? = null, val user: AuthUser? = null)

class SessionStore(context: Context, private val secure: SecureStore) {
    private val device = context.getSharedPreferences("fastfin.device", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _restoring = MutableStateFlow(true)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    val current: Session? get() = _session.value

    /** Non-null when credentials had to be kept outside the Keystore. */
    val storageWarning: String? get() = secure.failure

    /** Stable per-install id Jellyfin uses to tell sessions apart. Not secret. */
    private fun deviceId(): String = device.getString(KEY_DEVICE, null)
        ?: randomId().also { id -> device.edit { putString(KEY_DEVICE, id) } }

    suspend fun restore() {
        try {
            _session.value = withContext(Dispatchers.IO) {
                val server = secure.read(KEY_SERVER)
                val token = secure.read(KEY_TOKEN)
                val user = secure.read(KEY_USER)
                if (server != null && token != null && user != null) {
                    Session(server, token, user, secure.read(KEY_NAME).orEmpty(), deviceId())
                } else null
            }
        } catch (e: Exception) {
            // Unreadable storage lands on the login screen instead of crashing.
            Log.w("FastFin", "Couldn't restore the saved session: ${e.javaClass.simpleName}")
        } finally {
            _restoring.value = false
        }
    }

    /** Returns null on success, or a message for the login screen. */
    suspend fun signIn(http: OkHttpClient, rawServer: String, username: String, password: String): String? =
        withContext(Dispatchers.IO) {
            val trimmed = rawServer.trim().trimEnd('/')
            if (trimmed.isEmpty()) return@withContext "Enter your server address."
            // No scheme typed: try HTTPS first, then the plain-HTTP LAN address
            // most home servers actually answer on.
            val candidates = if ("://" in trimmed) listOf(trimmed) else listOf("https://$trimmed", "http://$trimmed")
            val deviceId = deviceId()
            val body = buildJsonObject {
                put("Username", username)
                put("Pw", password)
            }.toString().toRequestBody(JSON)

            var lastError = "Couldn't reach that server."
            for (server in candidates) {
                val url = "$server/Users/AuthenticateByName".toHttpUrlOrNull()
                    ?: return@withContext "That doesn't look like a server address."
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", Auth.header(deviceId, null))
                    .post(body)
                    .build()
                val result = try {
                    http.newCall(request).execute()
                } catch (e: IOException) {
                    lastError = "Couldn't reach that server: ${e.message ?: e.javaClass.simpleName}"
                    continue
                }
                result.use { response ->
                    if (!response.isSuccessful) {
                        return@withContext "Sign in failed (${response.code}). Check your server address and credentials."
                    }
                    val auth = runCatching { AppJson.decodeFromString<AuthResponse>(response.body!!.string()) }.getOrNull()
                    val token = auth?.accessToken
                    val user = auth?.user
                    if (token == null || user == null) return@withContext "Sign in didn't return a session."
                    // The server said yes; from here nothing local may block
                    // sign-in. A storage failure just means asking again next launch.
                    secure.write(mapOf(KEY_SERVER to server, KEY_TOKEN to token, KEY_USER to user.id, KEY_NAME to user.name))
                    _session.value = Session(server, token, user.id, user.name, deviceId)
                    return@withContext null
                }
            }
            lastError
        }

    fun signOut() {
        secure.write(mapOf(KEY_SERVER to null, KEY_TOKEN to null, KEY_USER to null, KEY_NAME to null))
        _session.value = null
    }

    private companion object {
        const val KEY_SERVER = "serverUrl"
        const val KEY_TOKEN = "accessToken"
        const val KEY_USER = "userId"
        const val KEY_NAME = "userName"
        const val KEY_DEVICE = "deviceId"
        val JSON = "application/json".toMediaType()

        fun randomId(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
