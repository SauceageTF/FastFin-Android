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
private val TopShade = Brush.verticalGradient(listOf(Color(0x8C000000), Color.Transparent))
private val BottomShade = Brush.verticalGradient(listOf(Color.Transparent, Color(0x8C000000)))

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

        if (buffering && !visible) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 3.dp)
        }

        AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(220))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(140.dp).background(TopShade))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp).background(BottomShade))

                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    TopRow(
                        playback, ui, menuOpen,
                        onMenu = {
                            menuOpen = it
                            interactions++
                        },
                        Modifier.align(Alignment.TopCenter),
                    )

                    if (ui.source != null) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            SkipButton(back = true) {
                                playback.seekBy(-10_000)
                                interactions++
                            }
                            Box(
                                Modifier.size(80.dp).pressable(pressedScale = 0.94f, haptic = true) {
                                    playback.togglePlay()
                                    interactions++
                                }
                            ) {
                                Glass(Modifier.fillMaxSize(), shape = CircleShape, contentAlignment = Alignment.Center) {
                                    if (buffering) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                                    else Icon(if (isPlaying) Lucide.Pause else Lucide.Play, if (isPlaying) "Pause" else "Play", Modifier.size(30.dp), tint = FF.Text)
                                }
                            }
                            SkipButton(back = false) {
                                playback.seekBy(10_000)
                                interactions++
                            }
                        }

                        Glass(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = FF.ShapeXl, style = GlassStyle.Clear) {
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(formatClock(scrubMs ?: positionMs), Modifier.widthIn(min = 48.dp), color = FF.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                Scrubber(
                                    position = { scrubMs ?: positionMs },
                                    buffered = { bufferedMs },
                                    durationMs = durationMs,
                                    onScrub = { scrubMs = it },
                                    onScrubEnd = { target ->
                                        playback.seekTo(target)
                                        positionMs = target
                                        scrubMs = null
                                        interactions++
                                    },
                                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                                )
                                Text(
                                    "-" + formatClock((durationMs - (scrubMs ?: positionMs)).coerceAtLeast(0)),
                                    Modifier.widthIn(min = 52.dp), color = FF.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                                )
                            }
                        }
                    }
                }
            }
        }

        ui.notice?.let {
            Glass(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp), shape = FF.Pill) {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = FF.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TopRow(playback: PlaybackManager, ui: PlayerUi, menuOpen: Boolean, onMenu: (Boolean) -> Unit, modifier: Modifier) {
    val pip = LocalPip.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassButton(Lucide.ChevronDown, "Minimise player", { playback.collapse() }, diameter = 42.dp, style = GlassStyle.Clear)
        Glass(Modifier.weight(1f).height(46.dp), shape = FF.Pill, style = GlassStyle.Clear) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
                Text(ui.item.seriesName ?: ui.item.name, color = FF.Text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitleFor(ui.item, ui.source?.hdr)?.let {
                    Text(it, color = FF.TextSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (pip.supported) GlassButton(Lucide.PictureInPicture, "Picture in Picture", pip::enter, diameter = 42.dp, style = GlassStyle.Clear)
        TrackMenu(playback, ui.source, menuOpen, onMenu)
        GlassButton(Lucide.Close, "Close player", { playback.close() }, diameter = 42.dp, style = GlassStyle.Clear)
    }
}

@Composable
private fun SkipButton(back: Boolean, onClick: () -> Unit) {
    GlassButton(
        if (back) Lucide.RotateCcw else Lucide.RotateCw,
        if (back) "Back 10 seconds" else "Forward 10 seconds",
        onClick, diameter = 56.dp, style = GlassStyle.Clear,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (back) Lucide.RotateCcw else Lucide.RotateCw, null, Modifier.size(28.dp), tint = FF.Text)
            Text("10", color = FF.Text, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** Audio and subtitle choices, plus how the stream is being delivered. */
@Composable
private fun TrackMenu(playback: PlaybackManager, source: PlaybackSource?, open: Boolean, onOpen: (Boolean) -> Unit) {
    Box {
        GlassButton(
            Lucide.Captions, "Audio and subtitles", { onOpen(true) }, diameter = 42.dp, style = GlassStyle.Clear,
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

/** Thin track with buffered range and a knob. Drawn from lambdas, so the
 * playhead moving only redraws this strip. */
@Composable
private fun Scrubber(
    position: () -> Long,
    buffered: () -> Long,
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .height(40.dp)
            .pointerInput(durationMs) {
                fun toMs(x: Float): Long {
                    if (durationMs <= 0 || size.width == 0) return 0
                    return ((x / size.width).coerceIn(0f, 1f) * durationMs).toLong().coerceAtMost((durationMs - 500).coerceAtLeast(0))
                }
                var last = 0L
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        last = toMs(offset.x)
                        onScrub(last)
                    },
                    onDragEnd = { onScrubEnd(last) },
                    onDragCancel = { onScrubEnd(last) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = toMs(change.position.x)
                        onScrub(last)
                    },
                )
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0 && size.width > 0) {
                        onScrubEnd(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
            }
            .drawBehind {
                val total = durationMs.coerceAtLeast(1).toFloat()
                val played = (position() / total).coerceIn(0f, 1f)
                val loaded = (buffered() / total).coerceIn(0f, 1f)
                val track = 4.dp.toPx()
                val top = (size.height - track) / 2
                val corner = CornerRadius(track / 2)
                drawRoundRect(Color(0x40FFFFFF), Offset(0f, top), Size(size.width, track), corner)
                drawRoundRect(Color(0x59FFFFFF), Offset(0f, top), Size(size.width * loaded, track), corner)
                drawRoundRect(FF.Text, Offset(0f, top), Size(size.width * played, track), corner)
                drawCircle(FF.Text, 7.dp.toPx(), Offset((size.width * played).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx()), size.height / 2))
            }
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
