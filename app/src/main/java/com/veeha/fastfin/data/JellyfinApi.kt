package com.veeha.fastfin.data

import com.veeha.fastfin.playback.PlaybackInfoResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failed API call. `detail` is the server's own explanation (Jellyfin answers
 * 400s with a problem-details body naming the field it rejected). */
class ApiException(path: String, val code: Int, val detail: String? = null) :
    IOException("$path failed: HTTP $code" + (detail?.let { " — $it" } ?: ""))

/** Suspends on OkHttp's own dispatcher; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * The Jellyfin REST client (10.9+ routes, same set the iOS build uses).
 *
 * List calls leave `Fields` unset so the server returns its compact DTO, cap
 * image tags at one per type, and skip the total count unless we page. JSON is
 * decoded straight off the socket stream on the IO pool.
 */
class JellyfinApi(private val http: OkHttpClient, private val sessions: SessionStore) {
    private val session: Session get() = sessions.current ?: throw IOException("Not signed in")
    private val userId: String get() = session.userId

    fun url(path: String, query: Map<String, Any?> = emptyMap()): HttpUrl {
        val builder = (session.serverUrl + path).toHttpUrl().newBuilder()
        for ((key, value) in query) if (value != null) builder.addQueryParameter(key, value.toString())
        return builder.build()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> execute(request: Request, strategy: DeserializationStrategy<T>): T {
        val response = http.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw ApiException(request.url.encodedPath, it.code, errorDetail(it))
                AppJson.decodeFromStream(strategy, it.body!!.byteStream())
            }
        }
    }

    private suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap()): T =
        execute(Request.Builder().url(url(path, query)).build(), serializer<T>())

    // MARK: Browsing

    suspend fun libraries(): List<Library> = get<Page<Library>>("/UserViews", mapOf("userId" to userId)).items

    suspend fun latest(libraryId: String, limit: Int = 16): List<Item> =
        get("/Items/Latest", LIST + mapOf("userId" to userId, "parentId" to libraryId, "limit" to limit))

    suspend fun resume(limit: Int = 16): List<Item> =
        get<Page<Item>>("/UserItems/Resume", LIST + mapOf("userId" to userId, "limit" to limit, "mediaTypes" to "Video")).items

    /** Fresh random movies and series for the hero; the server shuffles. */
    suspend fun random(limit: Int = 8): List<Item> = get<Page<Item>>(
        "/Items",
        LIST + mapOf(
            "userId" to userId, "recursive" to true, "includeItemTypes" to "Movie,Series",
            "sortBy" to "Random", "imageTypes" to "Primary", "limit" to limit,
            // Eight items only: worth it for the wide hero's synopsis and genres.
            "fields" to "Overview,Genres",
        ),
    ).items

    /** One page of a library, A to Z, optionally filtered server-side. */
    suspend fun items(parentId: String, startIndex: Int, limit: Int, query: LibraryQuery = LibraryQuery()): Page<Item> = get(
        "/Items",
        LIST + mapOf(
            "userId" to userId, "parentId" to parentId, "recursive" to true,
            "includeItemTypes" to "Movie,Series", "sortBy" to query.sort.sortBy,
            "sortOrder" to if (query.descending) "Descending" else "Ascending",
            "filters" to query.filters, "genres" to query.genre, "seriesStatus" to query.seriesStatus,
            "startIndex" to startIndex, "limit" to limit, "searchTerm" to query.search,
            "enableTotalRecordCount" to true,
        ),
    )

    /** How many films and shows a library holds: a count, no items. */
    suspend fun itemCount(parentId: String): Int = get<Page<Item>>(
        "/Items",
        mapOf(
            "userId" to userId, "parentId" to parentId, "recursive" to true,
            "includeItemTypes" to "Movie,Series", "limit" to 0, "enableTotalRecordCount" to true,
        ),
    ).totalRecordCount

    /** Genres that actually occur in a library, for its filter. */
    suspend fun genres(parentId: String): List<String> = get<Page<Item>>(
        "/Genres",
        mapOf("userId" to userId, "parentId" to parentId, "sortBy" to "SortName", "enableTotalRecordCount" to false),
    ).items.map { it.name }.filter { it.isNotBlank() }

    suspend fun search(term: String, limit: Int = 50): List<Item> = get<Page<Item>>(
        "/Items",
        LIST + mapOf(
            "userId" to userId, "searchTerm" to term, "recursive" to true,
            "includeItemTypes" to "Movie,Series", "sortBy" to "SortName", "limit" to limit,
        ),
    ).items

    suspend fun item(id: String): Item = get("/Items/$id", mapOf("userId" to userId))

    suspend fun seasons(seriesId: String): List<Item> =
        get<Page<Item>>("/Shows/$seriesId/Seasons", LIST + mapOf("userId" to userId)).items

    suspend fun episodes(seriesId: String, seasonId: String): List<Item> =
        get<Page<Item>>("/Shows/$seriesId/Episodes", LIST + mapOf("userId" to userId, "seasonId" to seasonId)).items

    suspend fun similar(id: String, limit: Int = 16): List<Item> =
        get<Page<Item>>("/Items/$id/Similar", LIST + mapOf("userId" to userId, "limit" to limit)).items

    // MARK: Playback

    suspend fun playbackInfo(itemId: String, body: JsonObject): PlaybackInfoResponse = execute(
        Request.Builder()
            .url(url("/Items/$itemId/PlaybackInfo", mapOf("userId" to userId)))
            .post(body.toString().toRequestBody(JSON))
            .build(),
        PlaybackInfoResponse.serializer(),
    )

    /** Fire-and-forget for 204 endpoints. Never throws: reporting must not be
     * able to take the player down with it. */
    suspend fun send(method: String, path: String, body: JsonObject? = null, query: Map<String, Any?> = emptyMap()) {
        try {
            val requestBody = body?.toString()?.toRequestBody(JSON) ?: if (method == "POST") EMPTY else null
            http.newCall(Request.Builder().url(url(path, query)).method(method, requestBody).build()).await().close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or server restarting: nothing useful to do here.
        }
    }

    /** ExoPlayer reports "Source error"; ask the server what it actually said. */
    suspend fun probe(url: String): String = try {
        val response = http.newCall(Request.Builder().url(url).header("Range", "bytes=0-256").build()).await()
        withContext(Dispatchers.IO) {
            response.use {
                val type = it.header("Content-Type")?.substringBefore(';').orEmpty()
                val textual = !it.isSuccessful || "text" in type || "json" in type || "mpegurl" in type
                val body = if (textual) it.body?.string()?.replace(Regex("\\s+"), " ")?.trim()?.take(200).orEmpty() else ""
                "HTTP ${it.code} $type${if (body.isNotEmpty()) " — $body" else ""}".trim()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "no response: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Revokes the token server-side; bounded so sign-out never hangs. */
    suspend fun logout() {
        withTimeoutOrNull(3_000) { send("POST", "/Sessions/Logout") }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody(null)
        val LIST: Map<String, Any?> = mapOf(
            "enableImageTypes" to "Primary,Backdrop,Logo,Thumb",
            "imageTypeLimit" to 1,
            "enableTotalRecordCount" to false,
        )
    }
}

/** The useful part of an error body: ProblemDetails title and field errors,
 * or the first line of plain text. Bounded, and never includes the request. */
private fun errorDetail(response: Response): String? = runCatching {
    val text = response.body?.string()?.trim().orEmpty()
    if (text.isEmpty()) return null
    val json = runCatching { AppJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
    if (json != null) {
        val title = (json["title"] ?: json["Title"])?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val errors = (json["errors"] ?: json["Errors"]) as? kotlinx.serialization.json.JsonObject
        val fields = errors?.entries?.joinToString("; ") { (field, messages) ->
            val list = (messages as? kotlinx.serialization.json.JsonArray)?.joinToString(" ") {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            } ?: messages.toString()
            "$field: $list"
        }
        listOfNotNull(title, fields).joinToString(" · ").ifEmpty { text }
    } else {
        text.lineSequence().first()
    }.take(400)
}.getOrNull()
