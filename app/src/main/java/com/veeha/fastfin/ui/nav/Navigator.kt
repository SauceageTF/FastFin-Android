package com.veeha.fastfin.ui.nav

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.veeha.fastfin.data.Item

/** Screens pushed over the tabs (the iOS root-stack routes). */
sealed interface Route {
    /** `seed` is the item as the list already knows it: the page draws its
     * header from it instantly while the full item loads. */
    data class Detail(val id: String, val seed: Item? = null) : Route
    data class LibraryGrid(val id: String, val name: String) : Route
    data class Season(val seriesId: String, val seasonId: String, val name: String) : Route
}

enum class Tab(val label: String) { Home("Home"), Library("Library"), Settings("Settings"), Search("Search") }

@Immutable
data class Entry(val route: Route, val key: String)

/**
 * The whole navigation model: a selected tab and a stack of pushed screens.
 * Each entry has a unique key that its saved UI state (scroll positions,
 * text fields) is filed under.
 */
@Stable
class Navigator {
    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Entry>()

    /** Lets the transition pick its direction. */
    var lastWasPop = false
        private set

    private var counter = 0

    val top: Entry? get() = stack.lastOrNull()

    fun push(route: Route) {
        lastWasPop = false
        stack.add(Entry(route, "route-${counter++}"))
    }

    fun pop(): Entry? {
        if (stack.isEmpty()) return null
        lastWasPop = true
        return stack.removeAt(stack.lastIndex)
    }

    fun select(tab: Tab) {
        lastWasPop = false
        this.tab = tab
    }
}
