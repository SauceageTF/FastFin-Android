package com.veeha.fastfin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veeha.fastfin.AppGraph
import com.veeha.fastfin.data.ImageUrls
import com.veeha.fastfin.ui.components.Glass
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.nav.Navigator
import com.veeha.fastfin.ui.nav.Route
import com.veeha.fastfin.ui.nav.Tab
import com.veeha.fastfin.ui.player.PlayerHost
import com.veeha.fastfin.ui.screens.HomeScreen
import com.veeha.fastfin.ui.screens.ItemDetailScreen
import com.veeha.fastfin.ui.screens.LibraryGridScreen
import com.veeha.fastfin.ui.screens.LibraryScreen
import com.veeha.fastfin.ui.screens.LoginScreen
import com.veeha.fastfin.ui.screens.SearchScreen
import com.veeha.fastfin.ui.screens.SeasonScreen
import com.veeha.fastfin.ui.screens.SettingsScreen
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.FastFinTheme
import com.veeha.fastfin.ui.theme.LocalAccent
import com.veeha.fastfin.ui.theme.color

private val TabBarHeight = 62.dp
private val TabBarBlock = TabBarHeight + 16.dp
private val TopTabBarHeight = 52.dp
/** What tab screens reserve under the top tab bar: margin, bar, breathing room. */
private val TopTabBarBlock = TopTabBarHeight + 20.dp
val MiniPlayerHeight = 64.dp
/** The wide-layout mini player is a corner card rather than a full-width bar. */
val WideMiniPlayerWidth = 420.dp

@Composable
fun FastFinRoot(graph: AppGraph, inPip: Boolean, pip: PipController) {
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val session by graph.sessions.session.collectAsStateWithLifecycle()
    val restoring by graph.sessions.restoring.collectAsStateWithLifecycle()
    val window = windowSizeDp()
    val layout = remember(window) { Layout.of(window) }

    FastFinTheme(settings.accent) {
        CompositionLocalProvider(LocalGraph provides graph, LocalPip provides pip, LocalLayout provides layout) {
            Box(Modifier.fillMaxSize().background(FF.Background)) {
                val current = session
                when {
                    restoring -> Unit
                    current == null -> LoginScreen()
                    // Keyed by user: signing in as someone else starts clean.
                    else -> key(current.userId) {
                        val images = remember(current.serverUrl) { ImageUrls(current.serverUrl) }
                        CompositionLocalProvider(LocalSession provides current, LocalImages provides images) {
                            MainShell(remember { Navigator() }, inPip)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tabs, pushed screens, the floating tab bar and the player. The insets every
 * screen pads by are computed here once from what is actually showing:
 * system bars, tab bar (bottom on phones, top on wide layouts), mini player.
 */
@Composable
private fun MainShell(nav: Navigator, inPip: Boolean) {
    val playback = LocalGraph.current.playback
    val layout = LocalLayout.current
    val player by playback.state.collectAsStateWithLifecycle()
    val routeStates = rememberSaveableStateHolder()
    val tabStates = rememberSaveableStateHolder()

    val top = nav.top
    val tabBarVisible = top == null
    val miniVisible = player != null && player?.expanded == false
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val miniBottom: Dp = navBar + when {
        layout.wide -> 16.dp
        tabBarVisible -> TabBarBlock
        else -> 10.dp
    }
    val contentBottom: Dp = miniBottom + if (miniVisible) MiniPlayerHeight + 10.dp else 0.dp
    val tabTop = if (layout.wide) TopTabBarBlock else 0.dp

    BackHandler(enabled = top != null) {
        nav.pop()?.let { routeStates.removeState(it.key) }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = top,
            contentKey = { it?.key ?: "tabs" },
            transitionSpec = {
                if (nav.lastWasPop) {
                    (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(260)) { it } + fadeOut(tween(200)))
                } else {
                    (slideInHorizontally(tween(260)) { it } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it / 4 } + fadeOut(tween(200)))
                }
            },
            label = "nav",
        ) { entry ->
            Box(Modifier.fillMaxSize().background(FF.Background)) {
                if (entry == null) {
                    CompositionLocalProvider(LocalTabBarTop provides tabTop) {
                        tabStates.SaveableStateProvider(nav.tab.name) {
                            when (nav.tab) {
                                Tab.Home -> HomeScreen(nav, contentBottom)
                                Tab.Library -> LibraryScreen(nav, contentBottom)
                                Tab.Settings -> SettingsScreen(contentBottom)
                                Tab.Search -> SearchScreen(nav, contentBottom)
                            }
                        }
                    }
                } else {
                    routeStates.SaveableStateProvider(entry.key) {
                        when (val route = entry.route) {
                            is Route.Detail -> ItemDetailScreen(route, nav, contentBottom)
                            is Route.LibraryGrid -> LibraryGridScreen(route, nav, contentBottom)
                            is Route.Season -> SeasonScreen(route, nav, contentBottom)
                        }
                    }
                }
            }
        }

        if (tabBarVisible) {
            if (layout.wide) TopTabBar(nav.tab, nav::select, Modifier.align(Alignment.TopCenter))
            else BottomTabBar(nav.tab, nav::select, Modifier.align(Alignment.BottomCenter))
        }

        PlayerHost(
            miniBottom = miniBottom,
            miniWidth = if (layout.wide) WideMiniPlayerWidth else null,
            inPip = inPip,
        )
    }
}

private val BottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60A0A0E)))
private val TopScrim = Brush.verticalGradient(listOf(Color(0xD90A0A0E), Color.Transparent))

/** Phones: the iOS 26 floating tab bar, a glass capsule for the tabs and a
 * separate glass circle for Search. Capped in width so a portrait tablet
 * doesn't stretch it edge to edge. */
@Composable
internal fun BottomTabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.color
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        // Fades content out under the bar so labels stay legible.
        Box(Modifier.matchParentSize().background(BottomScrim))
        Row(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(TabBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glass(Modifier.weight(1f).fillMaxHeight(), shape = FF.Pill) {
                Row(Modifier.fillMaxSize().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((tab, icon) in TABS) {
                        StackedTabItem(tab, icon, selected == tab, accent, onSelect, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            SearchCircle(selected == Tab.Search, accent, TabBarHeight, onSelect)
        }
    }
}

/**
 * Wide layouts: the iPadOS-style tab bar, a compact capsule floating at the
 * top centre with icon and label side by side. On a landscape screen height
 * is the scarce dimension, so the bottom stays free for content and the
 * corner mini player.
 */
@Composable
internal fun TopTabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.color
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.matchParentSize().background(TopScrim))
        Row(
            Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 16.dp).height(TopTabBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glass(Modifier.fillMaxHeight(), shape = FF.Pill) {
                Row(Modifier.fillMaxHeight().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((tab, icon) in TABS) InlineTabItem(tab, icon, selected == tab, accent, onSelect)
                }
            }
            Spacer(Modifier.width(8.dp))
            SearchCircle(selected == Tab.Search, accent, TopTabBarHeight, onSelect)
        }
    }
}

private val TABS = listOf(Tab.Home to Lucide.Home, Tab.Library to Lucide.Grid, Tab.Settings to Lucide.Sliders)

@Composable
private fun SearchCircle(selected: Boolean, accent: Color, size: Dp, onSelect: (Tab) -> Unit) {
    Glass(
        Modifier.size(size).pressable(role = Role.Tab) { onSelect(Tab.Search) },
        shape = CircleShape,
        tint = if (selected) Color(0xE6303038) else null,
        contentAlignment = Alignment.Center,
    ) {
        Icon(Lucide.Search, "Search", Modifier.size(size * 0.36f), tint = if (selected) accent else FF.Text)
    }
}

@Composable
private fun StackedTabItem(tab: Tab, icon: ImageVector, selected: Boolean, accent: Color, onSelect: (Tab) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(FF.Pill)
            .background(if (selected) FF.GlassFillStrong else Color.Transparent)
            .pressable(role = Role.Tab) { onSelect(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (selected) accent else FF.Text
        Icon(icon, null, Modifier.size(21.dp), tint = tint)
        Text(tab.label, color = tint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun InlineTabItem(tab: Tab, icon: ImageVector, selected: Boolean, accent: Color, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxHeight()
            .clip(FF.Pill)
            .background(if (selected) FF.GlassFillStrong else Color.Transparent)
            .pressable(role = Role.Tab) { onSelect(tab) }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) accent else FF.Text
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(tab.label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
