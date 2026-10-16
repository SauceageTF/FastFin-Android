package com.veeha.fastfin.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.veeha.fastfin.data.TICKS_PER_MS
import com.veeha.fastfin.data.formatClock
import com.veeha.fastfin.playback.Delivery
import com.veeha.fastfin.playback.PlayMethod
import com.veeha.fastfin.playback.PlaybackManager
import com.veeha.fastfin.playback.PlaybackSource
import com.veeha.fastfin.playback.PlayerUi
import com.veeha.fastfin.playback.PlaybackStats
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalSession
import com.veeha.fastfin.playback.TrickplaySource
import com.veeha.fastfin.ui.LocalPip
import com.veeha.fastfin.data.PlayerLayout
import com.veeha.fastfin.ui.components.Panel
import com.veeha.fastfin.ui.components.RoundButton
import com.veeha.fastfin.ui.components.PanelStyle
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.LocalAccent
import com.veeha.fastfin.ui.theme.hover
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

private const val CONTROLS_TIMEOUT_MS = 4_000L
/** How long the double-tap "+10s" marker stays after the last tap. */
private const val SKIP_MARKER_MS = 700L

/**
 * The full-screen HUD, ported from the iOS player: close, title, PiP
 * and track menu along the top; ±10 s and play/pause in the middle; time and
 * scrubber at the bottom. Hides itself after four seconds of playback.
 *
 * Android additions: double-tap either half to skip, swipe down to drop into
 * the mini player.
 */
@Composable
internal fun PlayerOverlay(playback: PlaybackManager, ui: PlayerUi, player: ExoPlayer?) {
    if (ui.error != null) {
        ErrorOverlay(playback, ui)
        return
    }

    val isPlaying by playback.isPlaying.collectAsStateWithLifecycle()
    val stalled by playback.isBuffering.collectAsStateWithLifecycle()
    val firstFrame by playback.firstFrame.collectAsStateWithLifecycle()
    // One spinner for both waits: starting up (no picture yet) and buffering
    // mid-film. Drawn here only, so it never doubles up with another.
    val buffering = stalled || !firstFrame
    val centered = LocalGraph.current.settings.flow.collectAsStateWithLifecycle().value.playerLayout == PlayerLayout.Center
    var visible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf((ui.item.runTimeTicks ?: 0L) / TICKS_PER_MS) }
    // The double-tap marker: seconds jumped so far in this burst (signed; 0 = hidden),
    // and a count of taps. The fade timer is keyed on the count, which changes on
    // every tap; keyed on the value, fast taps could go 1, 0, 1 inside one frame,
    // never restart it, and leave the marker stuck on screen.
    var skipTotal by remember { mutableIntStateOf(0) }
    var skipTaps by remember { mutableIntStateOf(0) }

    // Scrubbing thumbnails: decoded only when the playhead crosses into a new
    // one, and a decode the finger has already left behind is dropped.
    val graph = LocalGraph.current
    val serverUrl = LocalSession.current.serverUrl
    val trickplay = remember(ui.item, ui.source?.mediaSourceId) { TrickplaySource.of(ui.item, ui.source?.mediaSourceId, serverUrl) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(trickplay) {
        preview = null
        val source = trickplay ?: return@LaunchedEffect
        snapshotFlow { scrubMs }
            .filterNotNull()
            .map { it / source.info.interval }
            .distinctUntilChanged()
            .collectLatest { step -> graph.trickplay.thumbnail(source, step * source.info.interval)?.let { preview = it } }
    }

    // The clock is only sampled while it is on screen, four times a second
    // while playing, once when paused. Hidden controls cost nothing.
    LaunchedEffect(player, visible, isPlaying, buffering, interactions) {
        val p = player ?: return@LaunchedEffect
        do {
            positionMs = p.currentPosition
            bufferedMs = p.bufferedPosition
            p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { durationMs = it }
            delay(250)
        } while (visible && isPlaying)
    }

    LaunchedEffect(visible, isPlaying, scrubMs == null, menuOpen, interactions) {
        if (visible && isPlaying && scrubMs == null && !menuOpen) {
            delay(CONTROLS_TIMEOUT_MS)
            visible = false
        }
    }

    LaunchedEffect(skipTaps) {
        if (skipTotal != 0) {
            delay(SKIP_MARKER_MS)
            skipTotal = 0
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        visible = !visible
                        interactions++
                    },
                    onDoubleTap = { offset ->
                        val forward = offset.x > size.width / 2
                        playback.seekBy(if (forward) 10_000 else -10_000)
                        val step = if (forward) 10 else -10
                        // Same direction adds up (+10s, +20s...); switching starts over.
                        skipTotal = if (skipTotal != 0 && (skipTotal > 0) == forward) skipTotal + step else step
                        skipTaps++
                        interactions++
                    },
                )
            }
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > 110.dp.toPx()) playback.collapse() },
                    onVerticalDrag = { change, amount ->
                        dragged += amount
                        change.consume()
                    },
                )
            }
    ) {
        if (skipTotal != 0) {
            Panel(
                Modifier.align(if (skipTotal > 0) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 56.dp),
                shape = FF.Pill, style = PanelStyle.Overlay,
            ) {
                Text(if (skipTotal > 0) "+${skipTotal}s" else "−${-skipTotal}s", Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = FF.Text, fontWeight = FontWeight.Bold)
            }
        }


        AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(220))) {
            val pip = LocalPip.current
            PlayerControls(
                title = ui.item.seriesName ?: ui.item.name,
                subtitle = subtitleFor(ui.item),
                hdr = ui.source?.hdr,
                transcoding = ui.source?.playMethod == PlayMethod.Transcode,
                ready = ui.source != null,
                isPlaying = isPlaying,
                position = { scrubMs ?: positionMs },
                buffered = { bufferedMs },
                durationMs = durationMs,
                scrubbing = scrubMs != null,
                scrubPreview = preview.takeIf { scrubMs != null },
                scrubPreviewAspect = trickplay?.aspect ?: (16f / 9f),
                centered = centered,
                buffering = buffering,
                pipSupported = pip.supported,
                onCollapse = { playback.collapse() },
                onClose = { playback.close() },
                onPip = pip::enter,
                onSkip = { delta ->
                    playback.seekBy(delta)
                    interactions++
                },
                onTogglePlay = {
                    playback.togglePlay()
                    interactions++
                },
                onScrub = { scrubMs = it },
                onScrubEnd = { target ->
                    playback.seekTo(target)
                    positionMs = target
                    scrubMs = null
                    interactions++
                },
                trackMenu = {
                    TrackMenu(playback, ui.source, menuOpen) {
                        menuOpen = it
                        interactions++
                    }
                },
            )
        }

        // The one buffering spinner, above the controls. It stays put while they fade
        // in and out (two spinners swapping places overlapped during the fade), and
        // it is centred on the same safe area as the controls, so in the centre
        // layout it sits exactly inside the (then empty) play button.
        if (buffering) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
                BufferingIndicator()
            }
        }

        SkipLayer(playback, controlsVisible = visible, Modifier.align(Alignment.BottomEnd))

        ui.notice?.let {
            Panel(Modifier.align(Alignment.BottomCenter).padding(bottom = 132.dp), shape = FF.Pill) {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = FF.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun speedLabel(speed: Float): String = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

/** Speed, audio and subtitle choices, plus how the stream is being delivered. */
@Composable
private fun TrackMenu(playback: PlaybackManager, source: PlaybackSource?, open: Boolean, onOpen: (Boolean) -> Unit) {
    val speed by playback.speed.collectAsStateWithLifecycle()
    Box {
        TrackChip(
            (if (speed != 1f) speedLabel(speed) + "  ·  " else "") + trackSummary(source), { onOpen(true) },
            enabled = source != null,
        )
        if (source != null) {
            DropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }) {
                MenuHeader("Speed")
                // Stays open: the choice shows at once, and it's easy to try another.
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in SPEEDS) SpeedPill(speedLabel(option), option == speed) { playback.setSpeed(option) }
                }
                if (source.audioTracks.isNotEmpty()) HorizontalDivider(color = FF.Rim)
                if (source.audioTracks.isNotEmpty()) {
                    MenuHeader("Audio")
                    source.audioTracks.forEach { track ->
                        MenuOption(track.title, track.index == source.selectedAudio) {
                            onOpen(false)
                            playback.selectAudio(track.index)
                        }
                    }
                }
                if (source.subtitleTracks.isNotEmpty()) {
                    HorizontalDivider(color = FF.Rim)
                    MenuHeader("Subtitles")
                    MenuOption("Off", source.selectedSubtitle == null) {
                        onOpen(false)
                        playback.selectSubtitle(null)
                    }
                    source.subtitleTracks.forEach { track ->
                        val note = if (track.delivery == Delivery.Encode) "  (burned in)" else ""
                        MenuOption(track.title + note, track.index == source.selectedSubtitle) {
                            onOpen(false)
                            playback.selectSubtitle(track.index)
                        }
                    }
                }
                HorizontalDivider(color = FF.Rim)
                val stats by playback.stats.collectAsStateWithLifecycle()
                Text(
                    listOfNotNull(source.summary, statsLine(stats)).joinToString("\n"),
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 280.dp),
                    color = FF.TextDim, fontSize = 11.5.sp, lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(text.uppercase(), Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp), color = FF.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
}

@Composable
private fun MenuOption(title: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, color = FF.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        leadingIcon = {
            Box(Modifier.size(18.dp)) { if (selected) Icon(Lucide.Check, null, Modifier.size(18.dp), tint = LocalAccent.current.hover) }
        },
    )
}

@Composable
private fun ErrorOverlay(playback: PlaybackManager, ui: PlayerUi) {
    val error = ui.error ?: return
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.widthIn(max = 440.dp).fillMaxWidth(), shape = FF.ShapeXl) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Lucide.Alert, null, Modifier.size(28.dp), tint = accent.hover)
                Text("Couldn't play this video", Modifier.padding(top = 12.dp), color = FF.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(error.message, Modifier.padding(top = 8.dp), color = FF.TextSecondary, fontSize = 13.5.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
                error.detail?.let {
                    Text(it, Modifier.padding(top = 8.dp), color = FF.TextDim, fontSize = 11.sp, textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace)
                }
                Text("item ${ui.item.id.take(8)}", Modifier.padding(top = 8.dp), color = FF.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.height(42.dp).pressable { playback.close() }.background(FF.Selected, FF.Pill).padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Close", color = FF.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    Box(
                        Modifier.height(42.dp).pressable { playback.retry() }.background(FF.Text, FF.Pill).padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Try Again", color = FF.OnLight, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun SpeedPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(FF.Pill)
            .background(if (selected) FF.Text else FF.Field)
            .pressable(pressedScale = 0.94f, haptic = true, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) FF.OnLight else FF.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "Hardware decoder · 23.976 fps · 0 dropped frames": what to check when playback stutters. */
private fun statsLine(stats: PlaybackStats): String? {
    if (stats.decoder == null) return null
    val kind = when (stats.hardware) {
        true -> "hardware decoder"
        false -> "software decoder"
        null -> "decoder"
    }
    val fps = stats.frameRate?.let { "%.3f".format(it).trimEnd('0').trimEnd('.') + " fps" }
    val dropped = if (stats.droppedFrames == 1) "1 dropped frame" else "${stats.droppedFrames} dropped frames"
    val engine = if (stats.decoder.contains("dav1d")) " (dav1d)" else ""
    val decoded = (stats.codec?.let { "$it $kind" } ?: kind.replaceFirstChar(Char::uppercase)) + engine
    return listOfNotNull(decoded, fps, dropped).joinToString("  ·  ")
}
