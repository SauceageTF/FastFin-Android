package com.veeha.fastfin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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

/**
 * Phone or tablet-style layout, decided by the window, not the device: a
 * landscape tablet, a phone turned sideways, and a wide split-screen pane are
 * all `wide`. Wide layouts move the tab bar to the top, use a cinematic
 * landscape hero, enlarge cards and cap reading widths.
 */
@Immutable
data class Layout(val wide: Boolean, val width: Dp, val height: Dp) {
    /** Side margin for shelves and pages. */
    val gutter: Dp get() = if (wide) 32.dp else 18.dp
    val poster: Dp get() = if (wide) 136.dp else 108.dp
    val landscapeCard: Dp get() = if (wide) 264.dp else 196.dp
    /** Grid cell minimum for poster grids. */
    val gridCell: Dp get() = if (wide) 136.dp else 104.dp
    /** Artwork request width for one grid cell (cells grow a little past the minimum). */
    val gridImage: Dp get() = if (wide) gridCell + 40.dp else (width - 60.dp) / 3

    /** Horizontal padding that centres a column no wider than `max`. */
    fun centered(max: Dp): Dp = maxOf(gutter, (width - max) / 2)

    companion object {
        fun of(size: DpSize) = Layout(
            wide = size.width >= 840.dp || (size.width >= 600.dp && size.width > size.height),
            width = size.width,
            height = size.height,
        )
    }
}

val LocalLayout = staticCompositionLocalOf { Layout(false, 400.dp, 800.dp) }

/** Space tab screens leave at the top for the floating top tab bar (wide only). */
val LocalTabBarTop = staticCompositionLocalOf { 0.dp }

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
