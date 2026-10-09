@file:OptIn(UnstableApi::class)

package com.veeha.fastfin.ui.player

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.episodeLabel
import com.veeha.fastfin.playback.PlayMethod
import com.veeha.fastfin.playback.PlaybackManager
import com.veeha.fastfin.playback.PlayerUi
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalImages
import com.veeha.fastfin.ui.LocalPip
import com.veeha.fastfin.ui.MiniPlayerHeight
import com.veeha.fastfin.ui.components.Artwork
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.px
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.LocalAccent
import com.veeha.fastfin.ui.theme.color
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt

private val MiniMargin = 10.dp
private val MiniInset = 6.dp
private val MiniShape = RoundedCornerShape(18.dp)
/** Opaque on purpose: the thumbnail's rounded corners are painted in this
 * colour over the video, which a SurfaceView cannot be clipped to. */
private val MiniBackground = Color(0xFF1B1B21)

private class Geometry(val card: IntRect, val thumb: IntRect)

/**
 * The player in all its sizes: full screen, the mini player docked above the
 * tab bar, and Picture in Picture.
 *
 * There is exactly one SurfaceView. Expanding and collapsing animate its
 * bounds in the layout phase (no recomposition per frame), so the decoder,
 * buffers and HDR output path are never torn down. A SurfaceView rather than
 * a TextureView because only a SurfaceView hands HDR10/HLG/Dolby Vision
 * frames to the display compositor untouched (a TextureView squeezes them
 * through the 8-bit SDR UI pipeline) and it costs less power: the compositor
 * scans video out directly instead of the GPU copying every frame.
 */
@Composable
fun PlayerHost(miniBottom: Dp, inPip: Boolean, miniWidth: Dp? = null) {
    val playback = LocalGraph.current.playback
    val ui = playback.state.collectAsStateWithLifecycle().value ?: return
    val player = playback.player.collectAsStateWithLifecycle().value
    val aspect = playback.videoAspect.collectAsStateWithLifecycle()
    val firstFrame by playback.firstFrame.collectAsStateWithLifecycle()

    val expanded = ui.expanded || inPip
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded, inPip) {
        val target = if (expanded) 1f else 0f
        if (inPip) progress.snapTo(target) else progress.animateTo(target, tween(320, easing = FastOutSlowInEasing))
    }
    BackHandler(enabled = ui.expanded && !inPip) { playback.collapse() }

    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val geometry = remember(width, height, miniBottom, miniWidth, density) {
            with(density) {
                val inset = MiniInset.roundToPx()
                val bottom = height - miniBottom.roundToPx()
                // Phones: a full-width bar. Wide layouts: a card in the bottom-right corner.
                val margin = (if (miniWidth != null) 20.dp else MiniMargin).roundToPx()
                val left = if (miniWidth != null) (width - margin - miniWidth.roundToPx()).coerceAtLeast(margin) else margin
                val card = IntRect(left, bottom - MiniPlayerHeight.roundToPx(), width - margin, bottom)
                val thumbHeight = card.height - inset * 2
                val thumbWidth = (thumbHeight * 16f / 9f).roundToInt()
                Geometry(card, IntRect(card.left + inset, card.top + inset, card.left + inset + thumbWidth, card.bottom - inset))
            }
        }
        // Both ends keep the video's own aspect ratio. ExoPlayer scales frames
        // to fill the surface, so a surface of the wrong shape would stretch
        // the picture (a 2.39:1 film squeezed into a 16:9 thumbnail).
        val full = { fitIn(IntRect(0, 0, width, height), aspect.value) }
        val thumb = { fitIn(geometry.thumb, aspect.value) }
        val videoRect = { lerp(thumb(), full(), progress.value) }

        // Tell Picture in Picture where the video is once it settles, so the
        // system can animate from exactly that rectangle.
        val pip = LocalPip.current
        LaunchedEffect(geometry, inPip) {
            if (inPip) return@LaunchedEffect
            snapshotFlow { Triple(progress.isRunning, progress.value, aspect.value) }
                .filter { !it.first }
                .collect {
                    val r = videoRect()
                    pip.setSourceRect(android.graphics.Rect(r.left, r.top, r.right, r.bottom))
                }
        }

        // Black ground of the full-screen player (letterbox bars).
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }.background(Color.Black))

        // Mini player card, under the video so the video punches through it.
        Box(
            Modifier
                .placeAt { geometry.card }
                .graphicsLayer { alpha = 1f - progress.value }
                .clip(MiniShape)
                .background(MiniBackground)
                .border(Dp.Hairline, FF.Rim, MiniShape)
        )

        if (player != null) {
            key(player) { VideoSurface(player, Modifier.placeAt(videoRect)) }
        }

        // Artwork until the first frame: playback looks instant even while
        // the server is still negotiating.
        if (!firstFrame) LoadingCover(ui, showSpinner = ui.error == null && expanded, Modifier.placeAt(videoRect))

        // Rounded corners for the mini thumbnail.
        Canvas(Modifier.placeAt(thumb).graphicsLayer { alpha = 1f - progress.value }) {
            val radius = 9.dp.toPx()
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
            }
            drawPath(path, MiniBackground)
        }

        if (ui.expanded && !inPip && player != null && ui.error == null) {
            Subtitles(player, Modifier.placeAt(full))
        }

        if (!ui.expanded && !inPip) MiniControls(playback, ui, player, geometry, progress)

        if (ui.expanded && !inPip) PlayerOverlay(playback, ui, player)
    }
}

// MARK: Geometry

/** The largest rectangle of `aspect` centred inside `box`. */
private fun fitIn(box: IntRect, aspect: Float): IntRect {
    val ratio = aspect.coerceIn(0.3f, 4f)
    val width = box.width
    val height = box.height
    if (width <= 0 || height <= 0) return box
    return if (width.toFloat() / height > ratio) {
        val w = (height * ratio).roundToInt()
        val left = box.left + (width - w) / 2
        IntRect(left, box.top, left + w, box.bottom)
    } else {
        val h = (width / ratio).roundToInt()
        val top = box.top + (height - h) / 2
        IntRect(box.left, top, box.right, top + h)
    }
}

private fun lerp(a: IntRect, b: IntRect, t: Float): IntRect {
    fun mix(x: Int, y: Int) = (x + (y - x) * t).roundToInt()
    return IntRect(mix(a.left, b.left), mix(a.top, b.top), mix(a.right, b.right), mix(a.bottom, b.bottom))
}

/** Sizes and places the child at `rect` within a full-size parent. The rect
 * is read during layout, so animating it never recomposes. */
private fun Modifier.placeAt(rect: () -> IntRect) = layout { measurable, constraints ->
    val r = rect()
    val placeable = measurable.measure(Constraints.fixed(r.width.coerceAtLeast(0), r.height.coerceAtLeast(0)))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left, r.top) }
}

// MARK: Surfaces

@Composable
private fun VideoSurface(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { context -> SurfaceView(context).also(player::setVideoSurfaceView) },
        modifier = modifier,
        onRelease = { view -> runCatching { player.clearVideoSurfaceView(view) } },
    )
}

private class CueBridge : Player.Listener {
    var view: SubtitleView? = null
    override fun onCues(cueGroup: CueGroup) {
        view?.setCues(cueGroup.cues)
    }
}

/** Media3's SubtitleView: styled text plus bitmap subtitles (PGS, VobSub, DVB)
 * rendered on the device, so they never cost the server a burn-in transcode. */
@Composable
private fun Subtitles(player: ExoPlayer, modifier: Modifier) {
    val bridge = remember { CueBridge() }
    DisposableEffect(player) {
        player.addListener(bridge)
        onDispose { player.removeListener(bridge) }
    }
    AndroidView(
        factory = { context ->
            SubtitleView(context).apply {
                setUserDefaultStyle()
                setUserDefaultTextSize()
                setBottomPaddingFraction(0.06f)
                setCues(player.currentCues.cues)
                bridge.view = this
            }
        },
        modifier = modifier,
        onRelease = { bridge.view = null },
    )
}

@Composable
private fun LoadingCover(ui: PlayerUi, showSpinner: Boolean, modifier: Modifier) {
    val images = LocalImages.current
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Artwork(images.landscape(ui.item, 1280.dp.px().coerceAtMost(1920)), Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f })
        if (showSpinner) CircularProgressIndicator(Modifier.size(34.dp), color = Color.White, strokeWidth = 2.5.dp)
    }
}

// MARK: Mini player

@Composable
private fun MiniControls(playback: PlaybackManager, ui: PlayerUi, player: ExoPlayer?, geometry: Geometry, progress: Animatable<Float, *>) {
    val accent = LocalAccent.current.color
    val isPlaying by playback.isPlaying.collectAsStateWithLifecycle()
    var fraction by remember { mutableFloatStateOf(0f) }
    // Sampled once a second, and only while playing: a paused mini player is idle.
    LaunchedEffect(player, isPlaying) {
        val p = player ?: return@LaunchedEffect
        do {
            val duration = p.duration
            fraction = if (duration > 0) (p.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            delay(1_000)
        } while (isPlaying)
    }
    val thumbWidth = with(LocalDensity.current) { (geometry.thumb.width + MiniInset.roundToPx() * 2).toDp() }

    Box(
        Modifier
            .placeAt { geometry.card }
            .graphicsLayer { alpha = 1f - progress.value }
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -24.dp.toPx()) playback.expand() },
                    onVerticalDrag = { change, amount ->
                        dragged += amount
                        change.consume()
                    },
                )
            }
            .pressable(pressedScale = 0.985f) { playback.expand() }
    ) {
        Row(Modifier.fillMaxSize().padding(start = thumbWidth + 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui.item.seriesName ?: ui.item.name, color = FF.Text, fontWeight = FontWeight.Bold, style = tight(13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                SubtitleWithBadge(subtitleFor(ui.item), ui.source?.hdr, ui.source?.playMethod == PlayMethod.Transcode, FF.TextDim, 11.5.sp)
            }
            MiniButton(if (isPlaying) Lucide.Pause else Lucide.Play, if (isPlaying) "Pause" else "Play") { playback.togglePlay() }
            MiniButton(Lucide.Close, "Close player") { playback.close() }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .padding(horizontal = 14.dp)
                .drawBehind { drawRect(accent, size = Size(size.width * fraction, size.height)) }
        )
    }
}

@Composable
private fun MiniButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).pressable(pressedScale = 0.9f, haptic = true, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = FF.Text)
    }
}

/** Episode label or year. The HDR format is drawn separately, as a badge. */
internal fun subtitleFor(item: Item): String? =
    if (item.seriesName != null) item.episodeLabel else item.productionYear?.toString()
