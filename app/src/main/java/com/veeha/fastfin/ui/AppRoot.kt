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
import androidx.compose.foundation.layout.width
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
val MiniPlayerHeight = 64.dp

@Composable
fun FastFinRoot(graph: AppGraph, inPip: Boolean, pip: PipController) {
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val session by graph.sessions.session.collectAsStateWithLifecycle()
    val restoring by graph.sessions.restoring.collectAsStateWithLifecycle()

    FastFinTheme(settings.accent) {
        CompositionLocalProvider(LocalGraph provides graph, LocalPip provides pip) {
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
 * Tabs, pushed screens, the floating tab bar and the player. The bottom
 * inset every screen pads by is computed here once from what is actually
 * showing: navigation bar, tab bar, mini player.
 */
@Composable
private fun MainShell(nav: Navigator, inPip: Boolean) {
    val playback = LocalGraph.current.playback
    val player by playback.state.collectAsStateWithLifecycle()
    val routeStates = rememberSaveableStateHolder()
    val tabStates = rememberSaveableStateHolder()

    val top = nav.top
    val tabBarVisible = top == null
    val miniVisible = player != null && player?.expanded == false
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val miniBottom: Dp = navBar + if (tabBarVisible) TabBarBlock else 10.dp
    val contentBottom: Dp = miniBottom + if (miniVisible) MiniPlayerHeight + 10.dp else 0.dp

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
                    tabStates.SaveableStateProvider(nav.tab.name) {
                        when (nav.tab) {
                            Tab.Home -> HomeScreen(nav, contentBottom)
                            Tab.Library -> LibraryScreen(nav, contentBottom)
                            Tab.Settings -> SettingsScreen(contentBottom)
                            Tab.Search -> SearchScreen(nav, contentBottom)
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

        if (tabBarVisible) TabBar(nav.tab, nav::select, Modifier.align(Alignment.BottomCenter))

        PlayerHost(miniBottom = miniBottom, inPip = inPip)
    }
}

private val BarScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60A0A0E)))

/** The iOS 26 floating tab bar: a glass capsule for the tabs and a separate
 * glass circle for Search. */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.color
    Box(modifier.fillMaxWidth()) {
        // Fades content out under the bar so labels stay legible.
        Box(Modifier.matchParentSize().background(BarScrim))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(TabBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glass(Modifier.weight(1f).fillMaxHeight(), shape = FF.Pill) {
                Row(Modifier.fillMaxSize().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TabItem(Tab.Home, Lucide.Home, selected == Tab.Home, accent, onSelect, Modifier.weight(1f))
                    TabItem(Tab.Library, Lucide.Grid, selected == Tab.Library, accent, onSelect, Modifier.weight(1f))
                    TabItem(Tab.Settings, Lucide.Sliders, selected == Tab.Settings, accent, onSelect, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.width(10.dp))
            Glass(
                Modifier.size(TabBarHeight).pressable(role = Role.Tab) { onSelect(Tab.Search) },
                shape = CircleShape,
                tint = if (selected == Tab.Search) Color(0xE6303038) else null,
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Search, "Search", Modifier.size(22.dp), tint = if (selected == Tab.Search) accent else FF.Text)
            }
        }
    }
}

@Composable
private fun TabItem(tab: Tab, icon: ImageVector, selected: Boolean, accent: Color, onSelect: (Tab) -> Unit, modifier: Modifier) {
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
