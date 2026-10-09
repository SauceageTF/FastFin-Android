package com.veeha.fastfin.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.Library
import com.veeha.fastfin.data.LibraryQuery
import com.veeha.fastfin.data.LibrarySort
import com.veeha.fastfin.data.Page
import com.veeha.fastfin.data.Repository
import com.veeha.fastfin.data.formatRuntime
import com.veeha.fastfin.data.libraryLabel
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalImages
import com.veeha.fastfin.ui.LocalLayout
import com.veeha.fastfin.ui.LocalTabBarTop
import com.veeha.fastfin.ui.components.Artwork
import com.veeha.fastfin.ui.components.CenterSpinner
import com.veeha.fastfin.ui.components.ErrorCard
import com.veeha.fastfin.ui.components.LargeTitle
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.Panel
import com.veeha.fastfin.ui.components.PosterCard
import com.veeha.fastfin.ui.components.SearchField
import com.veeha.fastfin.ui.components.TopBar
import com.veeha.fastfin.ui.components.panel
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.nav.Navigator
import com.veeha.fastfin.ui.nav.Route
import com.veeha.fastfin.ui.px
import com.veeha.fastfin.ui.rememberLoad
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.LocalAccent
import com.veeha.fastfin.ui.theme.color
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
    val layout = LocalLayout.current

    when {
        libraries == null && load.error == null -> CenterSpinner()
        libraries == null -> ErrorCard("Couldn't load your libraries: ${load.error}")
        // One column on phones; two or three cards across on a landscape tablet.
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(340.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = layout.gutter, end = layout.gutter,
                top = top + LocalTabBarTop.current + 8.dp, bottom = bottomInset + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("title", span = { GridItemSpan(maxLineSpan) }) { LargeTitle("Library") }
            if (libraries.isEmpty()) {
                item("empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text("No libraries found.", color = FF.TextDim, modifier = Modifier.padding(top = 40.dp))
                }
            }
            items(libraries, key = { it.id }) { library ->
                LibraryCard(library) { nav.push(Route.LibraryGrid(library.id, library.name, library.collectionType)) }
            }
        }
    }
}

private val CardHeight = 164.dp
private val FanPoster = 92.dp

/**
 * A library as a card: its name and size on the left, and a fanned stack of
 * its newest posters on the right. Posters stay 2:3, the shape they were made
 * in, instead of being cropped into a banner. They come from the same
 * "latest" request Home makes, so after Home this costs one small count.
 */
@Composable
private fun LibraryCard(library: Library, onClick: () -> Unit) {
    val graph = LocalGraph.current
    val accent = LocalAccent.current.color
    val latest by rememberLoad("latest:${library.id}", LIBRARY_TTL) { graph.api.latest(library.id) }
    val count by rememberLoad("count:${library.id}", LIBRARY_TTL) { graph.api.itemCount(library.id) }
    // New episodes of one show arrive as several seasons; show each show once.
    val covers = latest.data?.distinctBy { it.seriesId ?: it.id }?.take(3)

    Box(
        Modifier
            .fillMaxWidth()
            .height(CardHeight)
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .panel(FF.ShapeXl)
            // A faint wash of the accent from the right, behind the posters.
            .background(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.10f))))
    ) {
        PosterFan(covers, Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).fillMaxHeight().width(FanPoster * 2.2f))
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.55f).padding(start = 18.dp)) {
            Panel(Modifier.size(38.dp), shape = FF.Pill, tint = FF.Field, contentAlignment = Alignment.Center) {
                Icon(libraryIcon(library.collectionType), null, Modifier.size(17.dp), tint = FF.Text)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                library.name, color = FF.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val size = count.data?.let { n -> if (n == 1) "1 title" else "$n titles" }
            Text(
                listOfNotNull(libraryLabel(library.collectionType), size).joinToString("  ·  "),
                Modifier.padding(top = 3.dp), color = FF.TextSecondary, fontSize = 12.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Three posters fanned from a point below the card, newest in front. */
@Composable
private fun PosterFan(covers: List<Item>?, modifier: Modifier) {
    val images = LocalImages.current
    val widthPx = FanPoster.px()
    // Back to front: left, right, then the newest in the middle on top.
    val slots = listOf(1 to -11f, 2 to 11f, 0 to 0f)
    val shape = RoundedCornerShape(10.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        for ((index, angle) in slots) {
            val item = covers?.getOrNull(index)
            // While loading, empty cards hold the shape; once loaded, missing ones are left out.
            if (covers != null && item == null) continue
            Box(
                Modifier
                    .offset(x = (angle * 2.6f).dp, y = if (angle == 0f) 6.dp else 16.dp)
                    .width(FanPoster)
                    .aspectRatio(2f / 3f)
                    .graphicsLayer {
                        rotationZ = angle
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(shape)
                    .background(FF.Field)
                    .border(1.dp, FF.Rim, shape),
            ) {
                if (item != null) Artwork(images.poster(item, widthPx), Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * A library, paged: the iOS build loads 500 items in one response and
 * filters on the phone. Here pages of 100 arrive as you scroll, and sorting
 * and filtering run on the server, so a 5,000-film library opens as fast as
 * a 50-film one.
 */
@Stable
class PagedItems(private val fetch: suspend (start: Int) -> Page<Item>) {
    var items by mutableStateOf<List<Item>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Matching titles on the server, once the first page says. */
    var total by mutableStateOf<Int?>(null)
        private set
    private var loading = false

    val exhausted: Boolean get() = (items?.size ?: 0) >= (total ?: Int.MAX_VALUE)

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
    var text by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(text) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Name) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var unwatched by rememberSaveable { mutableStateOf(false) }
    var inProgress by rememberSaveable { mutableStateOf(false) }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var airing by rememberSaveable { mutableStateOf(false) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    // Jellyfin tracks progress per episode, never per show, so a TV library
    // offers "Airing" where a film library offers "In progress".
    val tv = route.collectionType == "tvshows"

    // Debounced like Spotifast's search (280 ms); typing restarts the wait.
    LaunchedEffect(text) {
        if (text.trim() == search.trim()) return@LaunchedEffect
        delay(280)
        search = text
    }

    val query = LibraryQuery(
        sort = sort, descending = descending, unwatched = unwatched,
        inProgress = inProgress && !tv, favorites = favorites, airing = airing && tv,
        genre = genre, search = search.trim().ifEmpty { null },
    )
    val paged = remember(query) {
        val fetch: suspend (Int) -> Page<Item> = { start -> graph.api.items(route.id, start, PAGE_SIZE, query) }
        if (query.search == null) {
            // Each sort and filter's list survives leaving and re-entering the library.
            val key = "grid:${route.id}:${query.key}"
            graph.repo.peek<PagedItems>(key) ?: PagedItems(fetch).also { graph.repo.put(key, it) }
        } else PagedItems(fetch)
    }
    // One scroll position per query, so a new sort or filter starts at the top.
    val grid = rememberSaveable(query, saver = LazyGridState.Saver) { LazyGridState() }

    LaunchedEffect(paged) { if (paged.items == null) paged.loadMore() }
    LaunchedEffect(paged, grid) {
        snapshotFlow {
            val info = grid.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.distinctUntilChanged().collect { (last, count) ->
            if (count > 0 && last >= count - 30) paged.loadMore()
        }
    }

    val layout = LocalLayout.current
    val columnWidth = layout.gridImage

    Column(Modifier.fillMaxSize()) {
        TopBar(route.name) { nav.pop() }
        SearchField(text, { text = it }, "Search ${route.name}", Modifier.padding(horizontal = layout.gutter, vertical = 6.dp).widthIn(max = 600.dp))

        // Sort first, then the filters; the row scrolls sideways on a phone.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = layout.gutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortChip(sort, descending) { picked ->
                if (picked == sort) descending = !descending
                else {
                    sort = picked
                    descending = picked.descendingFirst
                }
            }
            Box(Modifier.width(1.dp).height(22.dp).background(FF.Rim))
            ToggleChip("Unwatched", unwatched) { unwatched = !unwatched }
            if (tv) ToggleChip("Airing", airing) { airing = !airing }
            else ToggleChip("In progress", inProgress) { inProgress = !inProgress }
            ToggleChip("Favorites", favorites) { favorites = !favorites }
            GenreChip(route.id, genre) { genre = it }
            if (query.filtered) {
                ChipBase(on = false, onClick = {
                    unwatched = false
                    inProgress = false
                    airing = false
                    favorites = false
                    genre = null
                }) {
                    Icon(Lucide.Close, null, Modifier.size(12.dp), tint = FF.TextSecondary)
                    Text("Clear", color = FF.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Outside the grid, so it never scrolls away or shifts the posters.
        val total = paged.total
        Text(
            when {
                total == null || paged.items.isNullOrEmpty() -> ""
                total == 1 -> "1 title"
                else -> "$total titles"
            },
            Modifier.padding(horizontal = layout.gutter).padding(top = 2.dp, bottom = 6.dp),
            color = FF.TextDim, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        )

        val items = paged.items
        when {
            items == null -> CenterSpinner()
            items.isEmpty() && paged.error != null -> ErrorCard("Couldn't load this library: ${paged.error}")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(layout.gridCell),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, top = 6.dp, bottom = bottomInset + 40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(items, key = { it.id }, contentType = { "poster" }) { item ->
                    PosterCard(
                        item, onClick = { nav.open(item) }, width = null,
                        title = item.name, subtitle = subtitleFor(item, sort), imageWidth = columnWidth,
                    )
                }
                if (items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            when {
                                query.search != null -> "Nothing matches “${query.search}”."
                                query.filtered -> "Nothing matches these filters."
                                else -> "This library is empty."
                            },
                            color = FF.TextDim, modifier = Modifier.padding(top = 60.dp), fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Under each poster: whatever the list is sorted by, so the order makes sense. */
private fun subtitleFor(item: Item, sort: LibrarySort): String? = when (sort) {
    LibrarySort.Rating -> item.communityRating?.let { "★ %.1f".format(it) } ?: item.productionYear?.toString()
    LibrarySort.Runtime -> formatRuntime(item.runTimeTicks).ifEmpty { null } ?: item.productionYear?.toString()
    else -> item.productionYear?.toString()
}

@Composable
private fun ChipBase(on: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val chip = Modifier.heightIn(min = 34.dp).pressable(pressedScale = 0.96f, role = Role.Button, onClick = onClick)
    Box(
        if (on) chip.clip(FF.Pill).background(FF.Text) else chip.panel(FF.Pill, tint = FF.Field),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    ChipBase(on, onClick) {
        if (on) Icon(Lucide.Check, null, Modifier.size(13.dp), tint = FF.OnLight)
        Text(label, color = if (on) FF.OnLight else FF.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "Name ↑": opens the sort list. Picking the current sort flips its direction. */
@Composable
private fun SortChip(sort: LibrarySort, descending: Boolean, onPick: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val accent = LocalAccent.current.color
    val arrow = if (descending) Lucide.ArrowDown else Lucide.ArrowUp
    Box {
        ChipBase(on = false, onClick = { open = true }) {
            Icon(Lucide.ArrowUpDown, null, Modifier.size(14.dp), tint = FF.Text)
            Text(sort.label, color = FF.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(arrow, if (descending) "Descending" else "Ascending", Modifier.size(13.dp), tint = FF.TextSecondary)
        }
        Menu(open, { open = false }) {
            MenuHeader("Sort by")
            for (option in LibrarySort.entries) {
                val current = option == sort
                DropdownMenuItem(
                    text = { Text(option.label, color = FF.Text, fontSize = 14.sp) },
                    leadingIcon = { MenuCheck(current, accent) },
                    trailingIcon = { if (current) Icon(arrow, null, Modifier.size(15.dp), tint = FF.TextSecondary) },
                    // The current sort stays open so its direction can be flipped and seen.
                    onClick = {
                        onPick(option)
                        if (!current) open = false
                    },
                )
            }
        }
    }
}

/** Genres that occur in this library, loaded the first time the menu opens. */
@Composable
private fun GenreChip(libraryId: String, genre: String?, onPick: (String?) -> Unit) {
    val graph = LocalGraph.current
    val accent = LocalAccent.current.color
    var open by remember { mutableStateOf(false) }
    val on = genre != null
    Box {
        ChipBase(on = on, onClick = { open = true }) {
            Icon(Lucide.Filter, null, Modifier.size(14.dp), tint = if (on) FF.OnLight else FF.Text)
            Text(genre ?: "Genre", color = if (on) FF.OnLight else FF.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(Lucide.ChevronDown, null, Modifier.size(13.dp), tint = if (on) FF.OnLight else FF.TextSecondary)
        }
        Menu(open, { open = false }) {
            val genres by rememberLoad("genres:$libraryId", LIBRARY_TTL) { graph.api.genres(libraryId) }
            val list = genres.data
            MenuHeader("Genre")
            DropdownMenuItem(
                text = { Text("Any genre", color = FF.Text, fontSize = 14.sp) },
                leadingIcon = { MenuCheck(genre == null, accent) },
                onClick = {
                    onPick(null)
                    open = false
                },
            )
            when {
                list == null && genres.error == null -> Box(Modifier.width(200.dp).height(56.dp)) { CenterSpinner() }
                list == null -> Text("Couldn't load genres.", Modifier.padding(16.dp), color = FF.TextDim, fontSize = 13.sp)
                else -> for (name in list) {
                    DropdownMenuItem(
                        text = { Text(name, color = FF.Text, fontSize = 14.sp) },
                        leadingIcon = { MenuCheck(name == genre, accent) },
                        onClick = {
                            onPick(name)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuCheck(on: Boolean, tint: Color) {
    Box(Modifier.size(16.dp)) { if (on) Icon(Lucide.Check, null, Modifier.size(16.dp), tint = tint) }
}

@Composable
private fun Menu(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        shape = FF.ShapeLg,
        containerColor = FF.Panel,
        border = BorderStroke(1.dp, FF.Rim),
        modifier = Modifier.heightIn(max = 420.dp),
    ) { content() }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text.uppercase(), Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        color = FF.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
    )
}
