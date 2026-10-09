package com.veeha.fastfin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veeha.fastfin.AppGraph
import com.veeha.fastfin.data.ImageUrls
import com.veeha.fastfin.data.Load
import com.veeha.fastfin.data.Session

interface PipController {
    val supported: Boolean
    fun enter()

    /** Where the video sits in the window, for the PiP enter animation. */
    fun setSourceRect(rect: android.graphics.Rect)
}

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("No AppGraph") }
val LocalSession = staticCompositionLocalOf<Session> { error("Not signed in") }
val LocalImages = staticCompositionLocalOf<ImageUrls> { error("Not signed in") }
val LocalPip = staticCompositionLocalOf<PipController> {
    object : PipController {
        override val supported = false
        override fun enter() = Unit
        override fun setSourceRect(rect: android.graphics.Rect) = Unit
    }
}

/** The app window's size: right in split screen and freeform windows too. */
@Composable
fun windowSizeDp(): DpSize {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { DpSize(size.width.toDp(), size.height.toDp()) }
}

/** Pixel width for an artwork request at this size on screen. */
@Composable
fun Dp.px(): Int = with(LocalDensity.current) { roundToPx() }

/**
 * Observes a repository key: cached data on the very first frame, a refresh
 * behind it when stale, collection paused while the app is in the background.
 */
@Composable
fun <T : Any> rememberLoad(key: String, ttlMs: Long, seed: T? = null, fetch: suspend () -> T): State<Load<T>> {
    val repo = LocalGraph.current.repo
    val flow = remember(key) { repo.observe(key, ttlMs, fetch = fetch) }
    return flow.collectAsStateWithLifecycle(Load(repo.peek<T>(key) ?: seed, loading = true))
}
