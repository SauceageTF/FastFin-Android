package com.veeha.fastfin.data

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Watched and favourite as the user just set them, ahead of the next fetch. */
@Immutable
data class UserOverride(val played: Boolean? = null, val favorite: Boolean? = null)

/** The item as the user sees it now: server data with their latest taps on top. */
fun Item.withOverride(override: UserOverride?): Item {
    if (override == null) return this
    val data = userData ?: UserData()
    return copy(
        userData = data.copy(
            played = override.played ?: data.played,
            // Either way the resume point is gone: watched, or back to the start.
            playedPercentage = if (override.played != null) null else data.playedPercentage,
            playbackPositionTicks = if (override.played != null) 0 else data.playbackPositionTicks,
            isFavorite = override.favorite ?: data.isFavorite,
        ),
    )
}

/**
 * Mark watched and favourite, optimistically (Spotifast): the change shows
 * the moment it is tapped, the request runs behind it, and a failure puts it
 * back with a short message. Lists refresh afterwards so Continue Watching
 * and Home catch up with the server.
 */
class UserActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: JellyfinApi,
    private val repo: Repository,
) {
    private val _overrides = MutableStateFlow<Map<String, UserOverride>>(emptyMap())
    val overrides: StateFlow<Map<String, UserOverride>> = _overrides.asStateFlow()

    fun setPlayed(item: Item, played: Boolean) = change(item, { it.copy(played = played) }, if (played) "mark it watched" else "mark it unwatched") {
        api.setPlayed(item.id, played)
    }

    fun setFavorite(item: Item, favorite: Boolean) = change(item, { it.copy(favorite = favorite) }, if (favorite) "add it to favourites" else "remove it from favourites") {
        api.setFavorite(item.id, favorite)
    }

    private fun change(item: Item, edit: (UserOverride) -> UserOverride, what: String, request: suspend () -> Boolean) {
        val before = _overrides.value[item.id]
        _overrides.update { it + (item.id to edit(before ?: UserOverride())) }
        scope.launch {
            if (request()) {
                repo.markStale("home", "item:", "episodes:", "latest:")
            } else {
                _overrides.update { if (before == null) it - item.id else it + (item.id to before) }
                Toast.makeText(context, "Couldn't $what. Check your connection.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clear() = _overrides.update { emptyMap() }
}
