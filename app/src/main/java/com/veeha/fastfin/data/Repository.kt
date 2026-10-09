package com.veeha.fastfin.data

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File

/** What a screen draws: the best data we have, whether a fresher copy is on
 * its way, and the last failure. Data and loading are independent so a
 * refresh never blanks what is already on screen. */
@Immutable
data class Load<out T>(val data: T? = null, val loading: Boolean = true, val error: String? = null)

/**
 * Spotifast's caching rules, applied to Jellyfin:
 *
 * - Stale-while-revalidate. Cached data is shown immediately; a page younger
 *   than its TTL makes no request at all; an older one stays visible while a
 *   fresh copy loads behind it. Nothing the user is looking at flickers away.
 * - Request coalescing. Two screens asking for the same key share one network
 *   call. Fetches run on the app scope, so leaving a screen mid-load still
 *   fills the cache for when you come back.
 * - A bounded LRU, so memory stays flat however long you browse.
 * - Disk snapshots for the few keys that make a cold start feel instant
 *   (Home), written atomically: a temp file, then a rename.
 */
class Repository(private val scope: CoroutineScope, private val snapshotDir: File) {
    private class Entry(val value: Any, var at: Long)

    private val memory = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
    }
    private val inflight = HashMap<String, Deferred<Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): T? = synchronized(memory) { memory[key]?.value as T? }

    fun put(key: String, value: Any) {
        synchronized(memory) { memory[key] = Entry(value, SystemClock.elapsedRealtime()) }
    }

    /** Keeps the data (it is still the best we have) but forces the next
     * observer to refresh it. Used after playback changes watch state. */
    fun markStale(prefix: String) {
        synchronized(memory) { memory.forEach { (key, entry) -> if (key.startsWith(prefix)) entry.at = 0 } }
    }

    fun clear() {
        synchronized(memory) { memory.clear() }
        scope.launch(Dispatchers.IO) { snapshotDir.deleteRecursively() }
    }

    private fun isFresh(key: String, ttlMs: Long): Boolean = synchronized(memory) {
        memory[key]?.let { SystemClock.elapsedRealtime() - it.at < ttlMs } ?: false
    }

    fun <T : Any> observe(
        key: String,
        ttlMs: Long,
        force: Boolean = false,
        snapshot: KSerializer<T>? = null,
        fetch: suspend () -> T,
    ): Flow<Load<T>> = flow {
        var cached: T? = peek(key)
        if (cached == null && snapshot != null) {
            cached = readSnapshot(key, snapshot)?.also { value ->
                synchronized(memory) { memory[key] = Entry(value, 0) }
            }
        }
        if (!force && cached != null && isFresh(key, ttlMs)) {
            emit(Load(cached, loading = false))
            return@flow
        }
        emit(Load(cached, loading = true))
        try {
            emit(Load(load(key, snapshot, fetch), loading = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Load(cached, loading = false, error = e.message ?: e.javaClass.simpleName))
        }
    }

    /** Fetches `key`, sharing an in-flight request when there is one. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> load(key: String, snapshot: KSerializer<T>? = null, fetch: suspend () -> T): T {
        val deferred = synchronized(inflight) {
            inflight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    fetch().also { value ->
                        put(key, value)
                        if (snapshot != null) writeSnapshot(key, value, snapshot)
                    } as Any
                } finally {
                    synchronized(inflight) { inflight.remove(key) }
                }
            }.also {
                inflight[key] = it
                it.start()
            }
        }
        return deferred.await() as T
    }

    private fun fileFor(key: String) = File(snapshotDir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    private suspend fun <T> readSnapshot(key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        runCatching { AppJson.decodeFromString(serializer, fileFor(key).readText()) }.getOrNull()
    }

    private suspend fun <T> writeSnapshot(key: String, value: T, serializer: KSerializer<T>) = withContext(Dispatchers.IO) {
        runCatching {
            snapshotDir.mkdirs()
            val target = fileFor(key)
            val temporary = File(target.path + ".tmp")
            temporary.writeText(AppJson.encodeToString(serializer, value))
            if (!temporary.renameTo(target)) temporary.delete()
        }
    }

    companion object {
        const val MAX_ENTRIES = 96
        const val MINUTE = 60_000L
    }
}
