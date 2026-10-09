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
import java.util.concurrent.TimeUnit

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

/**
 * Server base URLs to try for what the user typed, in order.
 *
 * People paste whatever is in their browser's address bar, so the web
 * client's path, fragment and query are dropped
 * ("http://nas:8096/web/#/home.html" becomes "http://nas:8096"); a reverse
 * proxy prefix like "/jellyfin" is kept. With no scheme, LAN-looking
 * addresses (IP literals, localhost, .local/.lan/.home, the default port
 * 8096) try plain HTTP first, since that is what they almost always speak;
 * anything else tries HTTPS first.
 */
internal fun serverCandidates(raw: String): List<String> {
    var input = raw.trim().substringBefore('#').substringBefore('?')
    if (input.isEmpty()) return emptyList()
    val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").find(input)?.value?.lowercase()
    if (scheme != null) input = input.substring(scheme.length)
    Regex("/web(/|$)", RegexOption.IGNORE_CASE).find(input)?.let { input = input.substring(0, it.range.first) }
    input = input.trimEnd('/')
    if (input.isEmpty()) return emptyList()
    if (scheme != null) return listOf(scheme + input)

    val authority = input.substringBefore('/')
    val ipv6 = authority.startsWith("[")
    val host = if (ipv6) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    val port = if (ipv6) authority.substringAfter("]:", "") else authority.substringAfter(':', "")
    val lanLike = ipv6 ||
        Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) ||
        host.equals("localhost", ignoreCase = true) ||
        listOf(".local", ".lan", ".home", ".internal").any { host.endsWith(it, ignoreCase = true) } ||
        port == "8096"
    val httpsFirst = port == "8920" || !lanLike
    return if (httpsFirst) listOf("https://$input", "http://$input") else listOf("http://$input", "https://$input")
}

@Serializable
private data class PublicInfo(val id: String = "", val serverName: String = "", val version: String = "")

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
    /**
     * Returns null on success, or a message for the login screen. Never
     * throws: whatever goes wrong, the login screen gets a sentence back.
     *
     * Each candidate address is first checked with the anonymous
     * /System/Info/Public on short timeouts, so a wrong address or scheme
     * fails in seconds with a specific reason instead of hanging on a long
     * connect timeout, and only a confirmed Jellyfin server is sent the password.
     */
    suspend fun signIn(http: OkHttpClient, rawServer: String, username: String, password: String): String? =
        withContext(Dispatchers.IO) {
            try {
                authenticate(http, rawServer, username, password)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "Sign in failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

    private fun authenticate(http: OkHttpClient, rawServer: String, username: String, password: String): String? {
        val candidates = serverCandidates(rawServer)
        if (candidates.isEmpty()) return "Enter your server address."
        val probe = http.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        val deviceId = deviceId()
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString().toRequestBody(JSON)

        var lastError = "Couldn't reach that server."
        for (server in candidates) {
            val infoUrl = "$server/System/Info/Public".toHttpUrlOrNull()
                ?: return "That doesn't look like a server address."
            val isJellyfin = try {
                probe.newCall(Request.Builder().url(infoUrl).build()).execute().use { response ->
                    response.isSuccessful &&
                        runCatching { AppJson.decodeFromString<PublicInfo>(response.body!!.string()).id.isNotEmpty() }.getOrDefault(false)
                }
            } catch (e: IOException) {
                lastError = "Couldn't reach ${server.substringAfter("://")}: ${reason(e)}."
                continue
            }
            if (!isJellyfin) {
                lastError = "${server.substringAfter("://")} answered, but it isn't a Jellyfin server. Check the address and port."
                continue
            }

            val request = Request.Builder()
                .url("$server/Users/AuthenticateByName")
                .header("Authorization", Auth.header(deviceId, null))
                .post(body)
                .build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                return "Lost the connection while signing in: ${reason(e)}."
            }
            response.use {
                when {
                    it.code == 401 -> return "Wrong username or password."
                    !it.isSuccessful -> return "The server refused sign in (HTTP ${it.code})."
                }
                val auth = runCatching { AppJson.decodeFromString<AuthResponse>(it.body!!.string()) }.getOrNull()
                val token = auth?.accessToken
                val user = auth?.user
                if (token == null || user == null) return "Sign in didn't return a session."
                // The server said yes; from here nothing local may block
                // sign-in. A storage failure just means asking again next launch.
                secure.write(mapOf(KEY_SERVER to server, KEY_TOKEN to token, KEY_USER to user.id, KEY_NAME to user.name))
                _session.value = Session(server, token, user.id, user.name, deviceId)
                return null
            }
        }
        return lastError
    }

    private fun reason(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "address not found"
        is java.net.ConnectException -> "nothing is answering there"
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "it took too long to answer"
        is javax.net.ssl.SSLException -> "secure connection failed"
        else -> e.message ?: e.javaClass.simpleName
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
