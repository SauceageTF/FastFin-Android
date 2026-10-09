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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.Role
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
import com.veeha.fastfin.data.isPlayable
import com.veeha.fastfin.data.isResumable
import com.veeha.fastfin.data.isSeries
import com.veeha.fastfin.data.isWatched
import com.veeha.fastfin.data.playedFraction
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalImages
import com.veeha.fastfin.ui.LocalLayout
import com.veeha.fastfin.ui.components.Artwork
import com.veeha.fastfin.ui.components.CarouselRow
import com.veeha.fastfin.ui.components.CenterSpinner
import com.veeha.fastfin.ui.components.EpisodeCard
import com.veeha.fastfin.ui.components.ErrorCard
import com.veeha.fastfin.ui.components.Panel
import com.veeha.fastfin.ui.components.RoundButton
import com.veeha.fastfin.ui.components.PillButton
import com.veeha.fastfin.ui.components.PanelStyle
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.PosterCard
import com.veeha.fastfin.ui.components.ProgressLine
import com.veeha.fastfin.ui.components.rememberPlainText
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
    val layout = LocalLayout.current
    // Landscape tablets get a taller, cinematic header; phones keep the iOS proportions.
    val heroHeight = if (layout.wide) minOf(560.dp, window.height * 0.62f) else minOf(440.dp, window.height * 0.52f)
    val screenPx = window.width.px()

    // A show keeps its seasons and episodes on this page. The chosen season
    // drives both the episode carousel and what the Play button starts.
    val seasons = if (item.isSeries) {
        rememberLoad("seasons:${item.id}", ITEM_TTL) { graph.api.seasons(item.id) }.value.data
    } else null
    var chosenSeason by rememberSaveable(item.id) { mutableStateOf(route.seasonId) }
    val season = seasons?.let { list ->
        list.firstOrNull { it.id == chosenSeason }
            // Specials (season 0) are rarely where anyone starts.
            ?: list.firstOrNull { (it.indexNumber ?: 1) > 0 } ?: list.firstOrNull()
    }
    val episodesLoad = season?.let { s ->
        rememberLoad("episodes:${item.id}:${s.id}", ITEM_TTL) { graph.api.episodes(item.id, s.id) }.value
    }
    val episodes = episodesLoad?.data
    val upNext = episodes?.let(::upNextIn)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 48.dp)) {
            item("hero") {
                Box(Modifier.fillMaxWidth().height(heroHeight).background(FF.Elevated)) {
                    Artwork(images.backdrop(item, screenPx) ?: images.primary(item, screenPx), Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(TopScrim))
                    Box(Modifier.fillMaxSize().background(BottomScrim))
                }
            }
            item("info") { InfoBlock(item, nav, upNext) }
            if (item.isSeries && !seasons.isNullOrEmpty() && season != null) {
                item("episodes") {
                    EpisodesSection(seasons, season, episodes, episodesLoad?.error, upNext) { chosenSeason = it.id }
                }
            }
            if (item.isEpisode && item.seriesId != null && item.seasonId != null) item("next") { NextUpRow(item) }
            if (item.isMovie || item.isSeries) item("similar") { SimilarRow(item, nav) }
        }
        RoundButton(
            Lucide.ChevronLeft, "Back", { nav.pop() },
            Modifier.statusBarsPadding().padding(start = LocalLayout.current.gutter - 4.dp, top = 6.dp),
            diameter = 40.dp, style = PanelStyle.Overlay,
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
private fun InfoBlock(item: Item, nav: Navigator, upNext: Item?) {
    val graph = LocalGraph.current
    val images = LocalImages.current
    val accent = LocalAccent.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val logo = images.logo(item, 240.dp.px())

    Column(
        Modifier.overlapUp(72.dp).padding(horizontal = LocalLayout.current.gutter).widthIn(max = 760.dp).padding(bottom = 30.dp),
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

        if (item.isPlayable) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(
                        if (item.isResumable) "Resume" else "Play", { graph.playback.open(item) },
                        Modifier.weight(1f), icon = Lucide.Play, prominent = true,
                    )
                    if (item.isResumable) {
                        RoundButton(Lucide.RotateCcw, "Play from beginning", { graph.playback.open(item, restart = true) }, diameter = 46.dp)
                    }
                }
                if (item.playedFraction > 0f) ProgressLine(item.playedFraction, track = Color(0x33FFFFFF))
            }
        } else if (item.isSeries && upNext != null) {
            // The show's Play starts the episode the carousel highlights.
            val verb = if (upNext.isResumable) "Resume" else "Play"
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    "$verb ${shortCode(upNext)}", { graph.playback.open(upNext) },
                    Modifier.fillMaxWidth(), icon = Lucide.Play, prominent = true,
                )
                if (upNext.isResumable) ProgressLine(upNext.playedFraction, track = Color(0x33FFFFFF))
            }
        }

        item.taglines?.firstOrNull()?.let {
            Text(it, color = FF.TextSecondary, fontSize = 14.sp, fontStyle = FontStyle.Italic)
        }

        item.overview?.let {
            Text(
                rememberPlainText(it), color = FF.TextSecondary, fontSize = 14.5.sp, lineHeight = 21.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.pressable(pressedScale = 1f) { expanded = !expanded },
            )
        }

        item.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                genres.forEach { genre ->
                    Panel(shape = FF.Pill, tint = FF.Field) {
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
    Box(Modifier.border(1.dp, FF.Rim, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, color = FF.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EpisodesSection(
    seasons: List<Item>,
    season: Item,
    episodes: List<Item>?,
    error: String?,
    upNext: Item?,
    onSeason: (Item) -> Unit,
) {
    val graph = LocalGraph.current
    val layout = LocalLayout.current
    val gutter = layout.gutter
    val cardWidth = layout.landscapeCard
    Column(Modifier.padding(bottom = 30.dp)) {
        Text(
            "Episodes", Modifier.padding(horizontal = gutter).padding(bottom = 12.dp),
            color = FF.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
        )
        // Season toggles: one is always on, and switching swaps the carousel in place.
        if (seasons.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                items(seasons, key = { it.id }) { s -> SeasonToggle(s.name, s.id == season.id) { onSeason(s) } }
            }
        }
        // A fresh list state per season, so each one starts at its own beginning.
        key(season.id) {
            val listState = rememberLazyListState()
            // Land on the episode Play would start, once the list arrives.
            LaunchedEffect(episodes != null) {
                val index = episodes?.indexOf(upNext) ?: -1
                if (index > 0) listState.scrollToItem(index)
            }
            when {
                episodes == null && error == null -> Box(Modifier.fillMaxWidth().height(cardWidth * 9f / 16f + 44.dp)) { CenterSpinner() }
                episodes == null -> Text(
                    "Couldn't load episodes: $error", Modifier.padding(horizontal = gutter),
                    color = FF.TextDim, fontSize = 13.sp,
                )
                episodes.isEmpty() -> Text(
                    "No episodes in this season yet.", Modifier.padding(horizontal = gutter),
                    color = FF.TextDim, fontSize = 13.sp,
                )
                else -> LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(episodes, key = { it.id }, contentType = { "episode" }) { episode ->
                        EpisodeCard(episode, onClick = { graph.playback.open(episode) }, width = cardWidth)
                    }
                }
            }
        }
    }
}

/** A season choice: white when on, a solid field when off. */
@Composable
private fun SeasonToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val chip = Modifier.height(36.dp).pressable(role = Role.Tab, onClick = onClick)
    if (selected) {
        Box(chip.clip(FF.Pill).background(FF.Text).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, color = FF.OnLight, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        Panel(chip, shape = FF.Pill, tint = FF.Field, contentAlignment = Alignment.Center) {
            Text(label, Modifier.padding(horizontal = 16.dp), color = FF.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The episode to start: one in progress, else the first unwatched, else the first. */
private fun upNextIn(episodes: List<Item>): Item? =
    episodes.firstOrNull { it.isResumable && !it.isWatched }
        ?: episodes.firstOrNull { !it.isWatched }
        ?: episodes.firstOrNull()

/** "S2 E3", or just "E3" when the season number is unknown. */
private fun shortCode(episode: Item): String = listOfNotNull(
    episode.parentIndexNumber?.let { "S$it" },
    episode.indexNumber?.let { "E$it" },
).joinToString(" ").ifEmpty { episode.name }

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
                PosterCard(other, onClick = { nav.open(other) }, title = other.name, subtitle = other.productionYear?.toString())
            }
        }
    }
}
