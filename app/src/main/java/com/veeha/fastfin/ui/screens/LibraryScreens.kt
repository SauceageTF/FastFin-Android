package com.veeha.fastfin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.Library
import com.veeha.fastfin.data.Page
import com.veeha.fastfin.data.Repository
import com.veeha.fastfin.data.libraryLabel
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalImages
import com.veeha.fastfin.ui.components.Artwork
import com.veeha.fastfin.ui.components.CenterSpinner
import com.veeha.fastfin.ui.components.ErrorCard
import com.veeha.fastfin.ui.components.Glass
import com.veeha.fastfin.ui.components.GlassStyle
import com.veeha.fastfin.ui.components.LargeTitle
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.PosterCard
import com.veeha.fastfin.ui.components.SearchField
import com.veeha.fastfin.ui.components.TopBar
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.nav.Navigator
import com.veeha.fastfin.ui.nav.Route
import com.veeha.fastfin.ui.px
import com.veeha.fastfin.ui.rememberLoad
import com.veeha.fastfin.ui.windowSizeDp
import com.veeha.fastfin.ui.theme.FF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

private const val LIBRARY_TTL = 10 * Repository.MINUTE
private const val PAGE_SIZE = 100

fun libraryIcon(type: String?): ImageVector = when (type) {
    "movies" -> Lucide.Film
    "tvshows" -> Lucide.Tv
    "music" -> Lucide.Music
    "homevideos" -> Lucide.Video
    "photos" -> Lucide.Image
    "books" -> Lucide.Book
    else -> Lucide.Folder
}

@Composable
fun LibraryScreen(nav: Navigator, bottomInset: Dp) {
    val graph = LocalGraph.current
    val load by rememberLoad("libraries", LIBRARY_TTL) { graph.api.libraries() }
    val libraries = load.data
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    when {
        libraries == null && load.error == null -> CenterSpinner()
        libraries == null -> ErrorCard("Couldn't load your libraries: ${load.error}")
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = top + 8.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("title") { LargeTitle("Library") }
            if (libraries.isEmpty()) item("empty") { Text("No libraries found.", color = FF.TextDim, modifier = Modifier.padding(top = 40.dp)) }
            items(libraries, key = { it.id }) { library ->
                LibraryCard(library) { nav.push(Route.LibraryGrid(library.id, library.name)) }
            }
        }
    }
}

private val CardScrim = Brush.verticalGradient(listOf(Color(0x260A0A0E), Color(0xD90A0A0E)))

@Composable
private fun LibraryCard(library: Library, onClick: () -> Unit) {
    val graph = LocalGraph.current
    val images = LocalImages.current
    // Same key and size as Home's shelf, so after Home this is a cache hit.
    val latest by rememberLoad("latest:${library.id}", LIBRARY_TTL) { graph.api.latest(library.id) }
    val cover = latest.data?.firstOrNull()
    val width = windowSizeDp().width
    Box(
        Modifier
            .fillMaxWidth()
            .height(128.dp)
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .clip(FF.ShapeXl)
            .background(FF.Elevated)
            .border(Dp.Hairline, FF.GlassRim, FF.ShapeXl)
    ) {
        if (cover != null) {
            Artwork(images.backdrop(cover, width.px()) ?: images.poster(cover, width.px()), Modifier.fillMaxSize().blur(2.dp))
        }
        Box(Modifier.fillMaxSize().background(CardScrim))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Glass(Modifier.size(40.dp), shape = FF.Pill, style = GlassStyle.Clear, contentAlignment = Alignment.Center) {
                Icon(libraryIcon(library.collectionType), null, Modifier.size(18.dp), tint = FF.Text)
            }
            Column(Modifier.weight(1f)) {
                Text(library.name, color = FF.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
                Text(libraryLabel(library.collectionType), color = FF.TextSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Lucide.ChevronRight, null, Modifier.size(14.dp), tint = FF.TextDim)
        }
    }
}

/**
 * A library, paged: the iOS build loads 500 items in one response and
 * filters on the phone. Here pages of 100 arrive as you scroll, and the
 * filter runs on the server, so a 5,000-film library opens as fast as a
 * 50-film one.
 */
@Stable
class PagedItems(private val fetch: suspend (start: Int) -> Page<Item>) {
    var items by mutableStateOf<List<Item>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var total = Int.MAX_VALUE
    private var loading = false

    val exhausted: Boolean get() = (items?.size ?: 0) >= total

    suspend fun loadMore() {
        if (loading || exhausted) return
        loading = true
        try {
            val loaded = items.orEmpty()
            val page = fetch(loaded.size)
            items = loaded + page.items
            total = if (page.items.isEmpty()) loaded.size else page.totalRecordCount
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
            if (items == null) items = emptyList()
        } finally {
            loading = false
        }
    }
}

@Composable
fun LibraryGridScreen(route: Route.LibraryGrid, nav: Navigator, bottomInset: Dp) {
    val graph = LocalGraph.current
    var query by rememberSaveable { mutableStateOf("") }
    var applied by rememberSaveable { mutableStateOf(query) }

    // Debounced like Spotifast's search (280 ms); typing restarts the wait.
    LaunchedEffect(query) {
        if (query.trim() == applied.trim()) return@LaunchedEffect
        delay(280)
        applied = query
    }

    val paged = remember(applied) {
        val term = applied.trim().ifEmpty { null }
        val fetch: suspend (Int) -> Page<Item> = { start -> graph.api.items(route.id, start, PAGE_SIZE, term) }
        if (term == null) {
            // The unfiltered list survives leaving and re-entering the library.
            graph.repo.peek<PagedItems>("grid:${route.id}") ?: PagedItems(fetch).also { graph.repo.put("grid:${route.id}", it) }
        } else PagedItems(fetch)
    }
    val grid = rememberLazyGridState()

    LaunchedEffect(paged) { if (paged.items == null) paged.loadMore() }
    LaunchedEffect(paged, grid) {
        snapshotFlow {
            val info = grid.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.distinctUntilChanged().collect { (last, count) ->
            if (count > 0 && last >= count - 30) paged.loadMore()
        }
    }

    val columnWidth = (windowSizeDp().width - 60.dp) / 3

    Column(Modifier.fillMaxSize()) {
        TopBar(route.name) { nav.pop() }
        SearchField(query, { query = it }, "Filter ${route.name}", Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
        val items = paged.items
        when {
            items == null -> CenterSpinner()
            items.isEmpty() && paged.error != null -> ErrorCard("Couldn't load this library: ${paged.error}")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = bottomInset + 40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(items, key = { it.id }, contentType = { "poster" }) { item ->
                    PosterCard(
                        item, onClick = { nav.push(Route.Detail(item.id, item)) }, width = null,
                        title = item.name, subtitle = item.productionYear?.toString(), imageWidth = columnWidth,
                    )
                }
                if (items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (applied.isNotBlank()) "Nothing matches “${applied.trim()}”." else "This library is empty.",
                            color = FF.TextDim, modifier = Modifier.padding(top = 60.dp), fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
