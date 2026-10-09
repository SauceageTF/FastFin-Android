@file:OptIn(ExperimentalLayoutApi::class)

package com.veeha.fastfin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.Repository
import com.veeha.fastfin.data.formatRuntime
import com.veeha.fastfin.data.isEpisode
import com.veeha.fastfin.data.isMovie
import com.veeha.fastfin.data.isResumable
import com.veeha.fastfin.data.isSeries
import com.veeha.fastfin.data.isWatched
import com.veeha.fastfin.data.playedFraction
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalImages
import com.veeha.fastfin.ui.components.Artwork
import com.veeha.fastfin.ui.components.CarouselRow
import com.veeha.fastfin.ui.components.CenterSpinner
import com.veeha.fastfin.ui.components.EpisodeCard
import com.veeha.fastfin.ui.components.ErrorCard
import com.veeha.fastfin.ui.components.Glass
import com.veeha.fastfin.ui.components.GlassButton
import com.veeha.fastfin.ui.components.GlassPillButton
import com.veeha.fastfin.ui.components.GlassStyle
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.PosterCard
import com.veeha.fastfin.ui.components.ProgressLine
import com.veeha.fastfin.ui.components.TopBar
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.nav.Navigator
import com.veeha.fastfin.ui.nav.Route
import com.veeha.fastfin.ui.px
import com.veeha.fastfin.ui.rememberLoad
import com.veeha.fastfin.ui.windowSizeDp
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.LocalAccent
import com.veeha.fastfin.ui.theme.hover

private const val ITEM_TTL = 5 * Repository.MINUTE
private val TopScrim = Brush.verticalGradient(0f to Color(0x730A0A0E), 0.35f to Color.Transparent)
private val BottomScrim = Brush.verticalGradient(0.35f to Color.Transparent, 0.8f to Color(0xB30A0A0E), 1f to FF.Background)

@Composable
fun ItemDetailScreen(route: Route.Detail, nav: Navigator, bottomInset: Dp) {
    val graph = LocalGraph.current
    val images = LocalImages.current
    val load by rememberLoad("item:${route.id}", ITEM_TTL, seed = route.seed) { graph.api.item(route.id) }
    val item = load.data

    if (item == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("") { nav.pop() }
            if (load.error != null) ErrorCard("Couldn't load this title: ${load.error}") else CenterSpinner()
        }
        return
    }

    val window = windowSizeDp()
    val heroHeight = minOf(440.dp, window.height * 0.52f)
    val screenPx = window.width.px()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 48.dp)) {
            item("hero") {
                Box(Modifier.fillMaxWidth().height(heroHeight).background(FF.Elevated)) {
                    Artwork(images.backdrop(item, screenPx) ?: images.primary(item, screenPx), Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(TopScrim))
                    Box(Modifier.fillMaxSize().background(BottomScrim))
                }
            }
            item("info") { InfoBlock(item, nav) }
            if (item.isSeries) item("seasons") { SeasonsRow(item, nav) }
            if (item.isEpisode && item.seriesId != null && item.seasonId != null) item("next") { NextUpRow(item) }
            if (item.isMovie || item.isSeries) item("similar") { SimilarRow(item, nav) }
        }
        GlassButton(
            Lucide.ChevronLeft, "Back", { nav.pop() },
            Modifier.statusBarsPadding().padding(start = 14.dp, top = 6.dp),
            diameter = 40.dp, style = GlassStyle.Clear,
        )
    }
}

/** Pulls the block 72 dp up over the hero's fade without leaving a gap below. */
private fun Modifier.overlapUp(amount: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = amount.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
}

@Composable
private fun InfoBlock(item: Item, nav: Navigator) {
    val graph = LocalGraph.current
    val images = LocalImages.current
    val accent = LocalAccent.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val logo = images.logo(item, 240.dp.px())
    val seasonsLoad by rememberLoad("seasons:${item.id}", ITEM_TTL) { if (item.isSeries) graph.api.seasons(item.id) else emptyList() }

    Column(
        Modifier.overlapUp(72.dp).padding(horizontal = 18.dp).padding(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (logo != null) {
            Artwork(logo, Modifier.width(240.dp).height(84.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
        } else {
            Text(item.name, color = FF.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        }

        if (item.seriesName != null) {
            val line = item.seriesName + (item.indexNumber?.let { "  ·  Episode $it" } ?: "")
            Text(
                line, color = FF.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = if (item.seriesId != null) Modifier.pressable { nav.push(Route.Detail(item.seriesId)) } else Modifier,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            item.productionYear?.let { Meta(it.toString()) }
            formatRuntime(item.runTimeTicks).takeIf { it.isNotEmpty() }?.let { Meta(it) }
            item.officialRating?.let { Chip(it) }
            item.communityRating?.let { rating ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Star, null, Modifier.size(11.dp), tint = accent.hover)
                    Spacer(Modifier.width(4.dp))
                    Text("%.1f".format(rating), color = accent.hover, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (!item.isSeries) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassPillButton(
                        if (item.isResumable) "Resume" else "Play", { graph.playback.open(item) },
                        Modifier.weight(1f), icon = Lucide.Play, prominent = true,
                    )
                    if (item.isResumable) {
                        GlassButton(Lucide.RotateCcw, "Play from beginning", { graph.playback.open(item, restart = true) }, diameter = 46.dp)
                    }
                }
                if (item.playedFraction > 0f) ProgressLine(item.playedFraction, track = Color(0x33FFFFFF))
            }
        } else {
            seasonsLoad.data?.firstOrNull()?.let { first ->
                GlassPillButton(
                    "Browse Episodes", { nav.push(Route.Season(item.id, first.id, first.name)) },
                    Modifier.fillMaxWidth(), icon = Lucide.Rows, prominent = true,
                )
            }
        }

        item.taglines?.firstOrNull()?.let {
            Text(it, color = FF.TextSecondary, fontSize = 14.sp, fontStyle = FontStyle.Italic)
        }

        item.overview?.let {
            Text(
                it, color = FF.TextSecondary, fontSize = 14.5.sp, lineHeight = 21.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.pressable(pressedScale = 1f) { expanded = !expanded },
            )
        }

        item.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                genres.forEach { genre ->
                    Glass(shape = FF.Pill, style = GlassStyle.Clear) {
                        Text(genre, Modifier.padding(horizontal = 13.dp, vertical = 7.dp), color = FF.TextSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Meta(text: String) = Text(text, color = FF.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Chip(text: String) {
    Box(Modifier.border(1.dp, FF.GlassRim, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, color = FF.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SeasonsRow(series: Item, nav: Navigator) {
    val graph = LocalGraph.current
    val seasons = rememberLoad("seasons:${series.id}", ITEM_TTL) { graph.api.seasons(series.id) }.value.data
    if (seasons.isNullOrEmpty()) return
    Column(Modifier.padding(bottom = 30.dp)) {
        CarouselRow("Seasons") {
            items(seasons, key = { it.id }) { season ->
                PosterCard(season, onClick = { nav.push(Route.Season(series.id, season.id, season.name)) }, title = season.name, subtitle = null)
            }
        }
    }
}

@Composable
private fun NextUpRow(episode: Item) {
    val graph = LocalGraph.current
    val seriesId = episode.seriesId ?: return
    val seasonId = episode.seasonId ?: return
    val all = rememberLoad("episodes:$seriesId:$seasonId", ITEM_TTL) { graph.api.episodes(seriesId, seasonId) }.value.data
    val next = all?.filter { it.id != episode.id && (it.indexNumber ?: 0) > (episode.indexNumber ?: 0) }
    if (next.isNullOrEmpty()) return
    Column(Modifier.padding(bottom = 30.dp)) {
        CarouselRow("Next Up") {
            items(next, key = { it.id }) { item -> EpisodeCard(item, onClick = { graph.playback.open(item) }) }
        }
    }
}

@Composable
private fun SimilarRow(item: Item, nav: Navigator) {
    val graph = LocalGraph.current
    val similar = rememberLoad("similar:${item.id}", 30 * Repository.MINUTE) { graph.api.similar(item.id) }.value.data
    if (similar.isNullOrEmpty()) return
    Column(Modifier.padding(bottom = 30.dp)) {
        CarouselRow("More Like This") {
            items(similar, key = { it.id }) { other ->
                PosterCard(other, onClick = { nav.push(Route.Detail(other.id, other)) }, title = other.name, subtitle = other.productionYear?.toString())
            }
        }
    }
}

// MARK: Season

@Composable
fun SeasonScreen(route: Route.Season, nav: Navigator, bottomInset: Dp) {
    val graph = LocalGraph.current
    val images = LocalImages.current
    var seasonId by rememberSaveable { mutableStateOf(route.seasonId) }
    var seasonName by rememberSaveable { mutableStateOf(route.name) }
    val seasons = rememberLoad("seasons:${route.seriesId}", ITEM_TTL) { graph.api.seasons(route.seriesId) }.value.data.orEmpty()
    val episodesLoad by rememberLoad("episodes:${route.seriesId}:$seasonId", ITEM_TTL) { graph.api.episodes(route.seriesId, seasonId) }
    val thumbPx = 132.dp.px()

    Column(Modifier.fillMaxSize()) {
        TopBar(seasonName) { nav.pop() }
        val episodes = episodesLoad.data
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = bottomInset + 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (seasons.size > 1) {
                item("seasons") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
                        items(seasons, key = { it.id }) { season ->
                            val selected = season.id == seasonId
                            val chip = Modifier.height(34.dp).pressable {
                                seasonId = season.id
                                seasonName = season.name
                            }
                            if (selected) {
                                Box(chip.clip(FF.Pill).background(FF.Text).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    Text(season.name, color = FF.OnLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Glass(chip, shape = FF.Pill, style = GlassStyle.Clear, contentAlignment = Alignment.Center) {
                                    Text(season.name, Modifier.padding(horizontal = 14.dp), color = FF.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            when {
                episodes == null && episodesLoad.error == null -> item("loading") { CenterSpinner(Modifier.padding(top = 60.dp)) }
                episodes == null -> item("error") { ErrorCard("Couldn't load episodes: ${episodesLoad.error}") }
                episodes.isEmpty() -> item("empty") { Text("No episodes in this season.", color = FF.TextDim, modifier = Modifier.padding(top = 60.dp)) }
                else -> items(episodes, key = { it.id }) { episode ->
                    EpisodeRow(episode, images.primary(episode, thumbPx)) { graph.playback.open(episode) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Item, thumb: String?, onClick: () -> Unit) {
    val played = episode.playedFraction
    val watched = episode.isWatched
    Glass(Modifier.fillMaxWidth().pressable(pressedScale = 0.985f, onClick = onClick), shape = FF.ShapeLg) {
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp, 74.dp).clip(FF.ShapeMd).background(FF.Background)) {
                Artwork(thumb, Modifier.fillMaxSize())
                if (played > 0f && !watched) {
                    ProgressLine(played, Modifier.align(Alignment.BottomCenter), shape = androidx.compose.ui.graphics.RectangleShape)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                episode.indexNumber?.let {
                    Text("EPISODE $it", color = FF.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                }
                Text(episode.name, color = FF.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    formatRuntime(episode.runTimeTicks) + if (watched) "  ·  Watched" else "",
                    color = FF.TextDim, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                if (watched) Lucide.CheckCircle else Lucide.PlayCircle, null, Modifier.size(26.dp),
                tint = if (watched) FF.TextDim else FF.Text,
            )
        }
    }
}
