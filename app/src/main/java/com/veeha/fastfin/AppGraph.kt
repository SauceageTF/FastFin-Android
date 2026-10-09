package com.veeha.fastfin

import android.app.Application
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.veeha.fastfin.data.AuthInterceptor
import com.veeha.fastfin.data.JellyfinApi
import com.veeha.fastfin.data.Repository
import com.veeha.fastfin.data.SecureStore
import com.veeha.fastfin.data.SessionStore
import com.veeha.fastfin.data.SettingsStore
import com.veeha.fastfin.playback.CapabilityProbe
import com.veeha.fastfin.playback.DeviceCapabilities
import com.veeha.fastfin.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The app's object graph, built by hand: no DI framework, no reflection, no
 * generated code to load at startup. Everything here is created once per
 * process and is cheap to construct; anything slow runs in the background.
 */
class AppGraph(val app: Application) {
    /** App-lifetime work: shared fetches, playback reporting. Main thread;
     * blocking work hops to IO itself. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsStore(app)
    val sessions = SessionStore(app, SecureStore(app))

    /** One HTTP client for the API, artwork, and every media request, so they
     * share a connection pool and one auth path. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { sessions.current })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val api = JellyfinApi(http, sessions)
    val repo = Repository(scope, File(app.filesDir, "snapshots"))

    /** Probing MediaCodecList takes tens of milliseconds, so it starts now, off
     * the main thread, and is long finished by the time anything is played. */
    val capabilities: Deferred<DeviceCapabilities> = scope.async(Dispatchers.Default) { CapabilityProbe.detect(app) }

    /**
     * Artwork: memory cache sized by bytes, never by time (Spotifast learned
     * that time-based eviction reloads visible images), 256 MB on disk.
     * Coil decodes at the size each image is drawn, not the source size, and
     * uses hardware bitmaps, so decoded pixels live in GPU memory.
     */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(app.cacheDir, "artwork").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()
    }

    val playback = PlaybackManager(app, this)

    init {
        scope.launch { sessions.restore() }
    }

    suspend fun signOut() {
        playback.close()
        api.logout()
        repo.clear()
        sessions.signOut()
    }
}
