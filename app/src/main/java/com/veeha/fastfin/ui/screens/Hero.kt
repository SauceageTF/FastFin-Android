package com.veeha.fastfin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.formatRuntime
import com.veeha.fastfin.data.isResumable
import com.veeha.fastfin.data.isSeries
import com.veeha.fastfin.data.playedFraction
import com.veeha.fastfin.ui.LocalImages
import com.veeha.fastfin.ui.components.Artwork
import com.veeha.fastfin.ui.components.Glass
import com.veeha.fastfin.ui.components.GlassButton
import com.veeha.fastfin.ui.components.GlassPillButton
import com.veeha.fastfin.ui.components.GlassStyle
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.ProgressLine
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.components.NoShape
import com.veeha.fastfin.ui.px
import com.veeha.fastfin.ui.windowSizeDp
import com.veeha.fastfin.ui.theme.FF
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_ADVANCE_MS = 6_000L
private val Dim = Color(0x8C0A0A0E)
private val PosterShape = FF.ShapeLg

/**
 * Featured slideshow: the poster itself, full 2:3 and undimmed, floating in
 * front of a blurred copy of the same art. Swipeable, auto-advancing, with
 * Play and Info underneath.
 *
 * The blurred ground is a 64-pixel image scaled up: a few hundred bytes, and
 * the GPU blur (Android 12+) or plain upscaling (older) does the rest.
 * Auto-advance only runs while the hero is composed, the app is resumed and
 * the player is not covering it.
 */
@Composable
fun Hero(slides: List<Item>, autoAdvance: Boolean, onPrimary: (Item) -> Unit, onInfo: (Item) -> Unit, onSearch: () -> Unit) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val posterWidth = min(windowSizeDp().width * 0.7f, 360.dp)
    val posterHeight = posterWidth * 1.5f
    val topPadding = statusBar + 64.dp
    val height = topPadding + posterHeight + 132.dp
    val pager = rememberPagerState { slides.size }

    // Restarts on every settle, so a swipe resets the timer like on iOS.
    LaunchedEffect(pager.settledPage, autoAdvance, slides.size) {
        if (!autoAdvance || slides.size < 2) return@LaunchedEffect
        delay(AUTO_ADVANCE_MS)
        if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % slides.size)
    }

    Box(Modifier.fillMaxWidth().height(height).background(FF.Background)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { slides[it].id }) { page ->
            HeroSlide(slides[page], topPadding, posterWidth, posterHeight, onPrimary, onInfo)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = statusBar + 8.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glass(Modifier.height(40.dp), shape = FF.Pill, style = GlassStyle.Clear, contentAlignment = Alignment.Center) {
                Text(
                    "FastFin", Modifier.padding(horizontal = 16.dp), color = FF.Text, fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp,
                )
            }
            Box(Modifier.weight(1f))
            GlassButton(Lucide.Search, "Search", onSearch, style = GlassStyle.Clear)
        }

        if (slides.size > 1) Dots(pager, slides.size, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

@Composable
private fun HeroSlide(item: Item, topPadding: Dp, posterWidth: Dp, posterHeight: Dp, onPrimary: (Item) -> Unit, onInfo: (Item) -> Unit) {
    val images = LocalImages.current
    val posterPx = posterWidth.px()
    val label = when {
        item.isSeries -> "View Episodes"
        item.isResumable -> "Resume"
        else -> "Play"
    }
    val meta = listOfNotNull(
        item.productionYear?.toString(),
        formatRuntime(item.runTimeTicks).ifEmpty { null },
        item.officialRating,
    ).joinToString("  ·  ")

    Box(Modifier.fillMaxSize()) {
        // Ground: the same art, blurred and darkened. The poster in front is
        // drawn at full brightness.
        Artwork(images.poster(item, 64), Modifier.fillMaxSize().blur(40.dp))
        Box(Modifier.fillMaxSize().background(Dim))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to FF.Background)))

        Column(Modifier.fillMaxSize().padding(top = topPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(posterWidth, posterHeight)
                    .shadow(24.dp, PosterShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .pressable(pressedScale = 0.98f) { onPrimary(item) }
                    .clip(PosterShape)
                    .background(FF.Elevated)
                    .border(0.5.dp, Color(0x38FFFFFF), PosterShape)
            ) {
                Artwork(images.poster(item, posterPx), Modifier.fillMaxSize())
                if (item.isResumable && item.playedFraction > 0f) {
                    ProgressLine(
                        item.playedFraction, Modifier.align(Alignment.BottomCenter), height = 4.dp,
                        track = Color(0x73000000), shape = NoShape,
                    )
                }
            }
            Column(Modifier.width(posterWidth).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (meta.isNotEmpty()) {
                    Text(
                        meta, Modifier.fillMaxWidth(), color = FF.TextSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassPillButton(label, { onPrimary(item) }, Modifier.weight(1f), icon = Lucide.Play, prominent = true)
                    GlassButton(Lucide.Info, "Details", { onInfo(item) }, diameter = 46.dp)
                }
            }
        }
    }
}

@Composable
private fun Dots(pager: PagerState, count: Int, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    Glass(modifier.height(22.dp), shape = FF.Pill, style = GlassStyle.Clear, contentAlignment = Alignment.Center) {
        Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { index ->
                val active = index == pager.currentPage
                Box(
                    Modifier
                        .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                        .clip(FF.Pill)
                        .background(if (active) FF.Text else Color(0x73FFFFFF))
                        .pressable(pressedScale = 0.9f) { scope.launch { pager.animateScrollToPage(index) } }
                )
            }
        }
    }
}
