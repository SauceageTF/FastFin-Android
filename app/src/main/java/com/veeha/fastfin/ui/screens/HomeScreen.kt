@file:OptIn(ExperimentalMaterial3Api::class)

package com.veeha.fastfin.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.veeha.fastfin.AppGraph
import com.veeha.fastfin.data.HomeData
import com.veeha.fastfin.data.Load
import com.veeha.fastfin.data.Repository
import com.veeha.fastfin.data.isSeries
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalSession
import com.veeha.fastfin.ui.LocalTabBarTop
import com.veeha.fastfin.ui.components.CarouselRow
import com.veeha.fastfin.ui.components.CenterSpinner
import com.veeha.fastfin.ui.components.ContinueWatchingCard
import com.veeha.fastfin.ui.components.ErrorCard
import com.veeha.fastfin.ui.components.PosterCard
import com.veeha.fastfin.ui.nav.Navigator
import com.veeha.fastfin.ui.nav.Route
import com.veeha.fastfin.ui.nav.Tab
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val HOME_TTL = 10 * Repository.MINUTE

/** Everything Home shows, in parallel. Library shelves start as soon as the
 * library list arrives and never wait on each other; a failing shelf is just
 * empty. The pieces are also filed under their own keys so the Library tab
 * opens with zero requests. */
suspend fun loadHome(graph: AppGraph): HomeData = coroutineScope {
    val api = graph.api
    val libraries = async { api.libraries() }
    val resume = async { api.resume() }
    val featured = async { runCatching { api.random(8) }.getOrDefault(emptyList()) }
    val libs = libraries.await()
    val latest = libs.map { lib -> async { lib.id to runCatching { api.latest(lib.id) }.getOrDefault(emptyList()) } }.awaitAll().toMap()
    graph.repo.put("libraries", libs)
    latest.forEach { (id, items) -> graph.repo.put("latest:$id", items) }
    HomeData(libs, resume.await(), featured.await(), latest)
}

@Composable
fun HomeScreen(nav: Navigator, bottomInset: Dp) {
    val graph = LocalGraph.current
    val session = LocalSession.current
    val key = "home:${session.userId}"
    // Not saveable: a pull-to-refresh forces one fetch, and coming back to
    // Home later goes through the normal TTL again.
    var refreshes by remember { mutableIntStateOf(0) }
    val flow = remember(refreshes) {
        graph.repo.observe(key, HOME_TTL, force = refreshes > 0, snapshot = HomeData.serializer()) { loadHome(graph) }
    }
    val load by flow.collectAsStateWithLifecycle(Load(graph.repo.peek<HomeData>(key)))

    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val playerExpanded = graph.playback.state.collectAsStateWithLifecycle().value?.expanded == true
    val heroAnimates = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !playerExpanded

    val data = load.data
    when {
        data == null && load.error == null -> CenterSpinner()
        data == null -> ErrorCard("Couldn't load your library: ${load.error}") { refreshes++ }
        else -> PullToRefreshBox(
            isRefreshing = refreshes > 0 && load.loading,
            onRefresh = { refreshes++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 24.dp)) {
                if (data.featured.isNotEmpty()) {
                    item("hero", contentType = "hero") {
                        Hero(
                            slides = data.featured,
                            autoAdvance = heroAnimates,
                            onPrimary = { item -> if (item.isSeries) nav.push(Route.Detail(item.id, item)) else graph.playback.open(item) },
                            onInfo = { item -> nav.push(Route.Detail(item.id, item)) },
                            onSearch = { nav.select(Tab.Search) },
                        )
                    }
                } else {
                    item("top") {
                        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                        Spacer(Modifier.height(LocalTabBarTop.current))
                    }
                }

                if (data.resume.isNotEmpty()) {
                    item("resume", contentType = "shelf") {
                        Spacer(Modifier.height(22.dp))
                        CarouselRow("Continue Watching") {
                            items(data.resume, key = { it.id }) { item ->
                                ContinueWatchingCard(item, onClick = { graph.playback.open(item) })
                            }
                        }
                    }
                }

                items(
                    data.libraries.filter { !data.latest[it.id].isNullOrEmpty() },
                    key = { "lib:${it.id}" },
                    contentType = { "shelf" },
                ) { library ->
                    Spacer(Modifier.height(30.dp))
                    CarouselRow("New in ${library.name}", onSeeAll = { nav.push(Route.LibraryGrid(library.id, library.name)) }) {
                        items(data.latest[library.id].orEmpty(), key = { it.id }) { item ->
                            PosterCard(item, onClick = { nav.push(Route.Detail(item.id, item)) })
                        }
                    }
                }
            }
        }
    }
}
