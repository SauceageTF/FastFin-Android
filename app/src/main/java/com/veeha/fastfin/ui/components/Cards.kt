package com.veeha.fastfin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.episodeLabel
import com.veeha.fastfin.data.formatRuntime
import com.veeha.fastfin.data.playedFraction
import com.veeha.fastfin.ui.LocalImages
import com.veeha.fastfin.ui.px
import com.veeha.fastfin.ui.theme.FF

/** Network artwork, or the elevated placeholder when the item has none. */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
) {
    if (url == null) Box(modifier.background(FF.Elevated))
    else AsyncImage(model = url, contentDescription = null, modifier = modifier, contentScale = contentScale, alignment = alignment)
}

/** Watched-progress line, drawn without layout or recomposition of its own. */
@Composable
fun ProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    track: Color = Color(0x40FFFFFF),
    fill: Color = FF.Text,
    shape: Shape = RoundedCornerShape(2.dp),
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .drawBehind {
                drawRect(track)
                drawRect(fill, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
            }
    )
}

private val PosterShape = RoundedCornerShape(FF.RadiusMd)

/** 2:3 poster with a hairline rim so it reads as a card on the glass around it. */
@Composable
fun PosterCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 108.dp,
    title: String = item.seriesName ?: item.name,
    subtitle: String? = if (item.seriesName != null) item.name else item.productionYear?.toString(),
    imageWidth: Dp = width ?: 120.dp,
) {
    val images = LocalImages.current
    val url = images.poster(item, imageWidth.px())
    Column(
        (if (width != null) modifier.width(width) else modifier.fillMaxWidth())
            .pressable(onClick = onClick)
    ) {
        Artwork(
            url,
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(PosterShape)
                .background(FF.Elevated)
                .border(Dp.Hairline, FF.GlassRim, PosterShape),
        )
        Text(
            title, modifier = Modifier.padding(top = 7.dp), color = FF.Text, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle, modifier = Modifier.padding(top = 2.dp), color = FF.TextDim, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val CardScrim = Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color(0xD1000000))

/** 16:9 resume card: still, title, time left, progress. */
@Composable
fun ContinueWatchingCard(item: Item, onClick: () -> Unit, width: Dp = 196.dp) {
    val images = LocalImages.current
    val played = item.playedFraction
    val remaining = item.runTimeTicks?.let { ticks ->
        maxOf(1L, Math.round(ticks * (1 - played) / 10_000_000.0 / 60.0))
    }
    val subtitle = (if (item.seriesName != null) item.episodeLabel else formatRuntime(item.runTimeTicks)) +
        (remaining?.let { "  ·  ${it}m left" } ?: "")
    Box(
        Modifier
            .width(width)
            .height(width * 9f / 16f)
            .pressable(onClick = onClick)
            .clip(FF.ShapeLg)
            .background(FF.Elevated)
            .border(Dp.Hairline, FF.GlassRim, FF.ShapeLg)
    ) {
        Artwork(images.landscape(item, width.px()), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(CardScrim))
        Glass(
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(30.dp),
            shape = FF.Pill, style = GlassStyle.Clear, contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Play, null, Modifier.size(12.dp), tint = FF.Text)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            Text(item.seriesName ?: item.name, color = FF.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle, modifier = Modifier.padding(top = 2.dp, bottom = 7.dp), color = FF.TextSecondary,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            ProgressLine(played)
        }
    }
}

/** 16:9 episode still with a play badge, for Next Up. */
@Composable
fun EpisodeCard(item: Item, onClick: () -> Unit) {
    val images = LocalImages.current
    Column(Modifier.width(196.dp).pressable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(FF.ShapeLg)
                .background(FF.Elevated)
                .border(Dp.Hairline, FF.GlassRim, FF.ShapeLg)
        ) {
            Artwork(images.primary(item, 196.dp.px()), Modifier.fillMaxSize())
            Glass(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).size(30.dp),
                shape = FF.Pill, style = GlassStyle.Clear, contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Play, null, Modifier.size(12.dp), tint = FF.Text)
            }
        }
        Text(item.episodeLabel, modifier = Modifier.padding(top = 7.dp), color = FF.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        formatRuntime(item.runTimeTicks).takeIf { it.isNotEmpty() }?.let {
            Text(it, modifier = Modifier.padding(top = 2.dp), color = FF.TextDim, fontSize = 11.sp)
        }
    }
}

/** Titled horizontal shelf. A LazyRow, so off-screen cards never compose. */
@Composable
fun CarouselRow(title: String, onSeeAll: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), color = FF.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            if (onSeeAll != null) {
                Row(Modifier.pressable(onClick = onSeeAll), verticalAlignment = Alignment.CenterVertically) {
                    Text("See all", color = FF.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(3.dp))
                    Icon(Lucide.ChevronRight, null, Modifier.size(12.dp), tint = FF.TextDim)
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

val NoShape: Shape = RectangleShape
