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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.veeha.fastfin.playback.PlaybackManager
import com.veeha.fastfin.playback.PlaybackSource
import com.veeha.fastfin.playback.PlayerUi
import com.veeha.fastfin.ui.LocalPip
import com.veeha.fastfin.ui.components.Glass
import com.veeha.fastfin.ui.components.GlassButton
import com.veeha.fastfin.ui.components.GlassStyle
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.LocalAccent
import com.veeha.fastfin.ui.theme.hover
import kotlinx.coroutines.delay

private const val CONTROLS_TIMEOUT_MS = 4_000L

/**
 * The full-screen HUD, ported from the iOS glass player: close, title, PiP
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
    val buffering by playback.isBuffering.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf((ui.item.runTimeTicks ?: 0L) / TICKS_PER_MS) }
    var skipFlash by remember { mutableIntStateOf(0) }

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

    LaunchedEffect(skipFlash) {
        if (skipFlash != 0) {
            delay(650)
            skipFlash = 0
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
                        skipFlash = if (forward) 1 else -1
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
        if (skipFlash != 0) {
            Glass(
                Modifier.align(if (skipFlash > 0) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 56.dp),
                shape = FF.Pill, style = GlassStyle.Clear,
            ) {
                Text(if (skipFlash > 0) "+10s" else "−10s", Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = FF.Text, fontWeight = FontWeight.Bold)
            }
        }

        if (buffering) {
            BufferingIndicator(Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(220))) {
            val pip = LocalPip.current
            PlayerControls(
                title = ui.item.seriesName ?: ui.item.name,
                subtitle = subtitleFor(ui.item),
                hdr = ui.source?.hdr,
                ready = ui.source != null,
                isPlaying = isPlaying,
                position = { scrubMs ?: positionMs },
                buffered = { bufferedMs },
                durationMs = durationMs,
                scrubbing = scrubMs != null,
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

        ui.notice?.let {
            Glass(Modifier.align(Alignment.BottomCenter).padding(bottom = 132.dp), shape = FF.Pill) {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = FF.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Audio and subtitle choices, plus how the stream is being delivered. */
@Composable
private fun TrackMenu(playback: PlaybackManager, source: PlaybackSource?, open: Boolean, onOpen: (Boolean) -> Unit) {
    Box {
        TrackChip(
            trackSummary(source), { onOpen(true) },
            enabled = source != null && (source.audioTracks.isNotEmpty() || source.subtitleTracks.isNotEmpty()),
        )
        if (source != null) {
            DropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }) {
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
                    HorizontalDivider(color = FF.GlassRim)
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
                HorizontalDivider(color = FF.GlassRim)
                Text(
                    source.summary, Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 280.dp),
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
        Glass(Modifier.widthIn(max = 440.dp).fillMaxWidth(), shape = FF.ShapeXl) {
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
                        Modifier.height(42.dp).pressable { playback.close() }.background(FF.GlassFillStrong, FF.Pill).padding(horizontal = 22.dp),
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
