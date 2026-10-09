package com.veeha.fastfin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalLayout
import com.veeha.fastfin.ui.LocalTabBarTop
import com.veeha.fastfin.ui.components.EmptyState
import com.veeha.fastfin.ui.components.LargeTitle
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.PosterCard
import com.veeha.fastfin.ui.components.SearchField
import com.veeha.fastfin.ui.nav.Navigator
import com.veeha.fastfin.ui.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val SEARCH_DEBOUNCE_MS = 280L

@Composable
fun SearchScreen(nav: Navigator, bottomInset: Dp) {
    val graph = LocalGraph.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // A new keystroke cancels the pending search and any request in flight;
    // a query seen before answers from the cache instantly.
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        val key = "search:${term.lowercase()}"
        graph.repo.peek<List<Item>>(key)?.let {
            results = it
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(SEARCH_DEBOUNCE_MS)
        results = try {
            graph.repo.load(key) { graph.api.search(term) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        searching = false
    }

    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }

    // Scrolling the results puts the keyboard away, like iOS's on-drag dismiss.
    val dismissKeyboard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }
    val layout = LocalLayout.current
    val columnWidth = layout.gridImage

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = LocalTabBarTop.current)) {
        Column(Modifier.padding(horizontal = layout.gutter)) {
            LargeTitle("Search")
            SearchField(query, { query = it }, "Movies, shows, episodes", Modifier.widthIn(max = 600.dp), focusRequester = focus, onSearch = { focusManager.clearFocus() })
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(layout.gridCell),
            modifier = Modifier.fillMaxSize().nestedScroll(dismissKeyboard),
            contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, top = 16.dp, bottom = bottomInset + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(results, key = { it.id }, contentType = { "poster" }) { item ->
                PosterCard(
                    item, onClick = { nav.push(Route.Detail(item.id, item)) }, width = null,
                    title = item.name, subtitle = item.productionYear?.toString(), imageWidth = columnWidth,
                )
            }
            if (results.isEmpty() && !searching) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (query.isBlank()) {
                        EmptyState(Lucide.Sparkles, "Search your library", "Movies and series across every library on your server.")
                    } else {
                        EmptyState(Lucide.Search, "No results for “${query.trim()}”", "Check the spelling or try a shorter title.")
                    }
                }
            }
        }
    }
}
