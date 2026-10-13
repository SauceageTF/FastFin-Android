package com.veeha.fastfin.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.Segment
import com.veeha.fastfin.data.SegmentKind
import com.veeha.fastfin.data.SkipMode
import com.veeha.fastfin.playback.PlaybackManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalLayout
import com.veeha.fastfin.playback.UpNext
import com.veeha.fastfin.ui.LocalImages
import com.veeha.fastfin.ui.components.Artwork
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.Panel
import com.veeha.fastfin.ui.components.PanelStyle
import com.veeha.fastfin.ui.components.PillButton
import com.veeha.fastfin.ui.components.ProgressLine
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.px
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.LocalAccent
import com.veeha.fastfin.ui.theme.color
import kotlinx.coroutines.delay

internal fun skipLabel(segment: Segment): String = when (segment.kind) {
    SegmentKind.Intro -> "Skip intro"
    SegmentKind.Recap -> "Skip recap"
    SegmentKind.Outro -> "Skip credits"
    SegmentKind.Preview -> "Skip preview"
}

/** The white "Skip intro" pill: the one bright control on screen, easy to find. */
@Composable
internal fun SkipButton(segment: Segment, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(46.dp)
            .shadow(12.dp, FF.Pill, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(FF.Pill)
            .background(FF.Text)
            .pressable(pressedScale = 0.95f, role = Role.Button, haptic = true, onClick = onSkip)
            .padding(start = 20.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(skipLabel(segment), color = FF.OnLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = FF.OnLight)
    }
}

/** "S2 E4 · Title", or just the title for a special. */
private fun nextLabel(item: Item): String {
    val code = listOfNotNull(item.parentIndexNumber?.let { "S$it" }, item.indexNumber?.let { "E$it" }).joinToString(" ")
    return if (code.isEmpty()) item.name else "$code · ${item.name}"
}

/**
 * The next episode, offered at the credits: its still, its name, and a
 * countdown that plays it unless someone says otherwise. Without a deadline
 * (paused, or after three episodes untouched) it simply waits for a tap.
 */
@Composable
internal fun UpNextCard(
    upNext: UpNext,
    /** During the credits the way out is to watch them; at the very end, to stop. */
    inCredits: Boolean,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val images = LocalImages.current
    val accent = LocalAccent.current.color
    val deadline = upNext.deadline
    val total = remember(deadline) { deadline?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(1L) } }
    val remaining by rememberRemaining(deadline)

    Panel(
        modifier.width(360.dp).shadow(18.dp, FF.ShapeLg, ambientColor = Color.Black, spotColor = Color.Black),
        shape = FF.ShapeLg,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(136.dp)
                        .aspectRatio(16f / 9f)
                        .clip(FF.ShapeMd)
                        .background(FF.Field)
                        .pressable(pressedScale = 0.97f, onClick = onPlay),
                ) {
                    Artwork(images.landscape(upNext.item, 136.dp.px()), Modifier.fillMaxSize())
                    Panel(
                        Modifier.align(Alignment.Center).size(34.dp),
                        shape = FF.Pill, style = PanelStyle.Overlay, contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Play, "Play next episode", Modifier.size(13.dp), tint = FF.Text)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (deadline != null) "Next episode in ${secondsLeft(remaining)}" else "Up next",
                        color = FF.TextDim, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp,
                    )
                    Text(
                        nextLabel(upNext.item), color = FF.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    upNext.item.seriesName?.let {
                        Text(it, color = FF.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (deadline != null && total != null) {
                CountdownBar(deadline, total, accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Play now", onPlay, Modifier.weight(1f), icon = Lucide.Play, prominent = true, height = 40.dp)
                Box(
                    Modifier
                        .height(40.dp)
                        .clip(FF.Pill)
                        .pressable(pressedScale = 0.96f, role = Role.Button, onClick = onDismiss)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (inCredits) "Watch credits" else "Cancel", color = FF.TextSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Skip and Up Next, bottom right. They never wait for the controls: above the
 * timeline while the controls show, lower when they hide.
 */
@Composable
internal fun SkipLayer(playback: PlaybackManager, controlsVisible: Boolean, modifier: Modifier = Modifier) {
    val segment by playback.segment.collectAsStateWithLifecycle()
    val upNext by playback.upNext.collectAsStateWithLifecycle()
    val skipMode = LocalGraph.current.settings.flow.collectAsStateWithLifecycle().value.skipIntros
    val wide = LocalLayout.current.let { minOf(it.width, it.height) >= 600.dp }
    val metrics = if (wide) HudMetrics.Wide else HudMetrics.Phone
    val lift by animateDpAsState(
        if (controlsVisible) (if (wide) 150.dp else 116.dp) else 28.dp,
        tween(220), label = "skipLift",
    )
    val skippable = segment?.takeIf {
        upNext == null && (skipMode != SkipMode.Off || it.kind == SegmentKind.Outro || it.kind == SegmentKind.Preview)
    }
    // The last thing shown, kept through its fade-out.
    val lastSkip = remember { mutableStateOf<Segment?>(null) }
    val lastCard = remember { mutableStateOf<UpNext?>(null) }
    SideEffect {
        if (skippable != null) lastSkip.value = skippable
        upNext?.let { lastCard.value = it }
    }

    Box(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(end = metrics.sideMargin, bottom = lift),
        contentAlignment = Alignment.BottomEnd,
    ) {
        AnimatedVisibility(skippable != null, enter = fadeIn(tween(180)) + slideInHorizontally { it / 4 }, exit = fadeOut(tween(160))) {
            (skippable ?: lastSkip.value)?.let { SkipButton(it, onSkip = { playback.skipSegment() }) }
        }
        // Full card when the screen is otherwise clear; a pill when the controls are up.
        AnimatedVisibility(upNext != null && controlsVisible, enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
            (upNext ?: lastCard.value)?.let { UpNextPill(it, onPlay = { playback.playNext() }) }
        }
        AnimatedVisibility(upNext != null && !controlsVisible, enter = fadeIn(tween(220)) + slideInHorizontally { it / 3 }, exit = fadeOut(tween(140))) {
            (upNext ?: lastCard.value)?.let { card ->
                UpNextCard(
                    card,
                    inCredits = segment?.kind == SegmentKind.Outro || segment?.kind == SegmentKind.Preview,
                    onPlay = { playback.playNext() },
                    onDismiss = { playback.dismissUpNext() },
                )
            }
        }
    }
}

/**
 * Milliseconds left on a countdown, for the seconds shown. Wakes once per
 * second, exactly when the number changes; a waiting card does no work.
 */
@Composable
private fun rememberRemaining(deadline: Long?): State<Long> =
    produceState(deadline?.let { it - SystemClock.elapsedRealtime() } ?: 0L, deadline) {
        if (deadline == null) return@produceState
        while (true) {
            value = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (value == 0L) break
            delay((value - 1) % 1000 + 1)
        }
    }

/**
 * The countdown as a bar that moves every frame, timed from the real clock
 * so it lands exactly when the next episode starts (and still moves with
 * system animations turned off). Only the bar redraws: the fill is read in
 * the draw phase, so the card around it never recomposes.
 */
@Composable
private fun CountdownBar(deadline: Long, total: Long, color: Color, modifier: Modifier = Modifier) {
    fun now() = (1f - (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L).toFloat() / total).coerceIn(0f, 1f)
    val progress = remember(deadline) { mutableFloatStateOf(now()) }
    LaunchedEffect(deadline) {
        while (progress.floatValue < 1f) {
            withFrameNanos { }
            progress.floatValue = now()
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .drawBehind {
                drawRect(Color(0x40FFFFFF))
                drawRect(color, size = Size(size.width * progress.floatValue, size.height))
            },
    )
}

/**
 * The card folded into a pill while the controls are up, in the same spot as
 * "Skip intro", so it never sits on top of the transport (the centre layout's
 * skip buttons especially). The countdown keeps running behind it.
 */
@Composable
internal fun UpNextPill(upNext: UpNext, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val deadline = upNext.deadline
    val remaining by rememberRemaining(deadline)
    Row(
        modifier
            .height(46.dp)
            .shadow(12.dp, FF.Pill, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(FF.Pill)
            .background(FF.Text)
            .pressable(pressedScale = 0.95f, role = Role.Button, haptic = true, onClick = onPlay)
            .padding(start = 16.dp, end = if (deadline != null) 8.dp else 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Play, null, Modifier.size(13.dp), tint = FF.OnLight)
        Text("Next episode", color = FF.OnLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (deadline != null) {
            Box(Modifier.size(30.dp).clip(FF.Pill).background(FF.OnLight.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Text("${secondsLeft(remaining)}", color = FF.OnLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Whole seconds left, rounded up so the count never shows 0 before it fires. */
private fun secondsLeft(remainingMs: Long): Long =
    (remainingMs.coerceIn(0L, 99_000L) + 999L) / 1000L
