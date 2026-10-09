package com.veeha.fastfin.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veeha.fastfin.data.formatClock
import com.veeha.fastfin.playback.PlaybackSource
import com.veeha.fastfin.playback.TrackOption
import com.veeha.fastfin.ui.LocalLayout
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.theme.FF
import java.util.Locale

private val TopShade = Brush.verticalGradient(listOf(Color(0xB3000000), Color(0x40000000), Color.Transparent))
private val BottomShade = Brush.verticalGradient(listOf(Color.Transparent, Color(0x59000000), Color(0xCC000000)))

/**
 * Text whose box is exactly its line, with no font padding above or below,
 * so it can be centred against icons and badges optically, not just by box.
 */
internal fun tight(size: TextUnit) = TextStyle(
    fontSize = size,
    lineHeight = size,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** Tabular figures, so the clock doesn't jitter as digits change. */
private val Clock = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold)

/**
 * HDR format as a small outlined badge, the way streaming apps and TVs label
 * it, instead of words tacked onto the end of a subtitle line.
 */
@Composable
internal fun HdrBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), color = FF.Text, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp, style = tight(9.sp), maxLines = 1)
    }
}

/** Subtitle line with the HDR badge centred against it. */
@Composable
internal fun SubtitleWithBadge(subtitle: String?, hdr: String?, color: Color, size: TextUnit) {
    if (subtitle == null && hdr == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (subtitle != null) {
            Text(
                subtitle, Modifier.weight(1f, fill = false), color = color, style = tight(size),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (hdr != null) HdrBadge(hdr)
    }
}

/** A bare icon with a generous touch target. The film behind it is the surface. */
@Composable
internal fun BareIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    iconSize: Dp = 22.dp,
    touch: Dp = 44.dp,
    enabled: Boolean = true,
    overlay: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier.size(touch).pressable(enabled = enabled, pressedScale = 0.88f, haptic = true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(iconSize), tint = if (enabled) FF.Text else FF.TextDim)
        overlay?.invoke()
    }
}

/** "English · EAC3 · Subtitles off": what is playing, and the way to change it. */
@Composable
internal fun TrackChip(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .pressable(enabled = enabled, pressedScale = 0.96f, haptic = true, onClick = onClick)
            .background(Color(0x26FFFFFF))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Captions, null, Modifier.size(16.dp), tint = FF.Text)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.widthIn(max = 300.dp), color = FF.Text, fontWeight = FontWeight.SemiBold, style = tight(12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun trackSummary(source: PlaybackSource?): String {
    if (source == null) return "Audio & Subtitles"
    val audio = source.audioTracks.firstOrNull { it.index == source.selectedAudio }?.let(::shortTrack)
    val subtitle = source.subtitleTracks.firstOrNull { it.index == source.selectedSubtitle }?.let { "${shortTrack(it)} subtitles" }
        ?: if (source.subtitleTracks.isEmpty()) null else "Subtitles off"
    return listOfNotNull(audio, subtitle).joinToString("  ·  ").ifEmpty { "Audio & Subtitles" }
}

private fun shortTrack(track: TrackOption): String {
    val language = track.language
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        ?.takeIf { it.isNotBlank() && !it.equals(track.language, ignoreCase = true) }
    val codec = track.codec?.uppercase()?.takeIf { it.length <= 6 }
    return when {
        language != null && codec != null -> "$language $codec"
        language != null -> language
        else -> track.title
    }
}

/**
 * The full-screen HUD, cinematic style: plain type and bare icons over soft
 * gradients at the top and bottom edges, and nothing over the middle of the
 * picture. Stateless, so screenshot tests can render it.
 */
@Composable
internal fun PlayerControls(
    title: String,
    subtitle: String?,
    hdr: String?,
    ready: Boolean,
    isPlaying: Boolean,
    position: () -> Long,
    buffered: () -> Long,
    durationMs: Long,
    scrubbing: Boolean,
    pipSupported: Boolean,
    /** Big transport in the middle of the screen instead of the bottom band. */
    centered: Boolean = false,
    buffering: Boolean = false,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onPip: () -> Unit,
    onSkip: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    trackMenu: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(150.dp).background(TopShade))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(190.dp).background(BottomShade))

        // The player is always landscape, so "wide" says nothing here; the short side tells a tablet from a phone.
        val m = LocalLayout.current.let { if (minOf(it.width, it.height) >= 600.dp) HudMetrics.Wide else HudMetrics.Phone }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = m.sideMargin, vertical = m.edgeMargin)) {
            // Top: what is playing, and the ways out.
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth(), verticalAlignment = Alignment.Top) {
                BareIconButton(Lucide.ChevronDown, "Minimise player", onCollapse, iconSize = m.icon + 4.dp, touch = m.touch)
                Column(
                    Modifier.weight(1f).padding(start = 6.dp, top = m.titleTop, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(title, color = FF.Text, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, style = tight(m.title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SubtitleWithBadge(subtitle, hdr, FF.TextSecondary, m.subtitle)
                }
                if (pipSupported) BareIconButton(Lucide.PictureInPicture, "Picture in Picture", onPip, iconSize = m.icon, touch = m.touch)
                BareIconButton(Lucide.Close, "Close player", onClose, iconSize = m.icon, touch = m.touch)
            }

            // Centre layout: large transport in the middle, easy to hit blind.
            if (ready && centered) {
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(m.centerGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CenterButton(Lucide.RotateCcw, "Back 10 seconds", m.centerSkip, { onSkip(-10_000) }) {
                        Text("10", color = FF.Text, fontWeight = FontWeight.ExtraBold, style = tight(if (m == HudMetrics.Wide) 12.sp else 10.5.sp))
                    }
                    CenterButton(
                        if (isPlaying) Lucide.Pause else Lucide.Play, if (isPlaying) "Pause" else "Play", m.centerPlay, onTogglePlay,
                        // The play triangle's visual centre sits left of its box; nudge it.
                        iconOffset = if (isPlaying) 0.dp else 3.dp,
                        busy = buffering,
                    )
                    CenterButton(Lucide.RotateCw, "Forward 10 seconds", m.centerSkip, { onSkip(10_000) }) {
                        Text("10", color = FF.Text, fontWeight = FontWeight.ExtraBold, style = tight(if (m == HudMetrics.Wide) 12.sp else 10.5.sp))
                    }
                }
            }

            // Bottom: the timeline, then transport (bottom layout) and tracks in one band.
            if (ready) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Scrubber(position, buffered, durationMs, scrubbing, onScrub, onScrubEnd, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                        Text(formatClock(position()), color = FF.Text, style = Clock)
                        Spacer(Modifier.weight(1f))
                        Text("-" + formatClock((durationMs - position()).coerceAtLeast(0)), color = FF.TextSecondary, style = Clock)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!centered) {
                            BareIconButton(
                                if (isPlaying) Lucide.Pause else Lucide.Play, if (isPlaying) "Pause" else "Play", onTogglePlay,
                                iconSize = m.icon + 4.dp, touch = m.touch + 4.dp,
                            )
                            SkipIcon(back = true, m) { onSkip(-10_000) }
                            SkipIcon(back = false, m) { onSkip(10_000) }
                        }
                        Spacer(Modifier.weight(1f))
                        trackMenu()
                    }
                }
            }
        }
    }
}

/** Centre-screen feedback, shown only while the stream is actually stalled. */
@Composable
internal fun BufferingIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp)
}

/**
 * A large centre control: the icon on a soft dark disc so it stays legible
 * over bright scenes without the weight of a glass bubble.
 */
@Composable
private fun CenterButton(
    icon: ImageVector,
    label: String,
    diameter: Dp,
    onClick: () -> Unit,
    iconOffset: Dp = 0.dp,
    busy: Boolean = false,
    overlay: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier
            .size(diameter)
            .pressable(pressedScale = 0.92f, haptic = true, onClick = onClick)
            .clip(CircleShape)
            .background(Color(0x4D000000)),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(diameter * 0.4f), color = Color.White, strokeWidth = 3.dp)
        } else {
            Icon(icon, label, Modifier.padding(start = iconOffset).size(diameter * 0.46f), tint = FF.Text)
            overlay?.invoke()
        }
    }
}

@Composable
private fun SkipIcon(back: Boolean, m: HudMetrics, onClick: () -> Unit) {
    BareIconButton(
        if (back) Lucide.RotateCcw else Lucide.RotateCw,
        if (back) "Back 10 seconds" else "Forward 10 seconds",
        onClick, iconSize = m.icon + 5.dp, touch = m.touch + 4.dp,
    ) {
        Text("10", color = FF.Text, fontWeight = FontWeight.ExtraBold, style = tight(8.5.sp))
    }
}

/**
 * Full-width hairline timeline with the buffered range. It thickens while
 * dragged so the finger has something to hold. Drawn from lambdas, so the
 * playhead moving only redraws this strip.
 */
@Composable
private fun Scrubber(
    position: () -> Long,
    buffered: () -> Long,
    durationMs: Long,
    active: Boolean,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .height(30.dp)
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
                val track = (if (active) 6.dp else 3.dp).toPx()
                val knob = (if (active) 8.dp else 6.dp).toPx()
                val top = (size.height - track) / 2
                val corner = CornerRadius(track / 2)
                drawRoundRect(Color(0x40FFFFFF), Offset(0f, top), Size(size.width, track), corner)
                drawRoundRect(Color(0x66FFFFFF), Offset(0f, top), Size(size.width * loaded, track), corner)
                drawRoundRect(FF.Text, Offset(0f, top), Size(size.width * played, track), corner)
                drawCircle(FF.Text, knob, Offset((size.width * played).coerceIn(knob, size.width - knob), size.height / 2))
            }
    )
}

/** HUD sizes: phone in landscape, and wide screens where the same numbers would look lost. */
internal enum class HudMetrics(
    val sideMargin: Dp,
    val edgeMargin: Dp,
    val title: TextUnit,
    val subtitle: TextUnit,
    val titleTop: Dp,
    val icon: Dp,
    val touch: Dp,
    val centerPlay: Dp,
    val centerSkip: Dp,
    val centerGap: Dp,
) {
    Phone(sideMargin = 20.dp, edgeMargin = 12.dp, title = 19.sp, subtitle = 13.sp, titleTop = 12.dp, icon = 22.dp, touch = 44.dp,
        centerPlay = 76.dp, centerSkip = 58.dp, centerGap = 40.dp),
    Wide(sideMargin = 40.dp, edgeMargin = 24.dp, title = 26.sp, subtitle = 15.sp, titleTop = 13.dp, icon = 26.dp, touch = 52.dp,
        centerPlay = 96.dp, centerSkip = 72.dp, centerGap = 64.dp),
}
