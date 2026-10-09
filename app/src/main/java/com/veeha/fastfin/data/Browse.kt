package com.veeha.fastfin.data

import androidx.compose.runtime.Immutable

/**
 * Library sort orders, as Jellyfin `sortBy` values. Every order ends in
 * SortName so ties (same day added, same rating) page in a stable order.
 * `descendingFirst` is the natural direction: newest, highest rated.
 */
enum class LibrarySort(val label: String, val sortBy: String, val descendingFirst: Boolean) {
    Name("Name", "SortName", false),
    Added("Date added", "DateCreated,SortName", true),
    Released("Release date", "PremiereDate,SortName", true),
    Rating("Rating", "CommunityRating,SortName", true),
    Runtime("Runtime", "Runtime,SortName", false),
}

/** What a library grid asks the server for. Sorting and filtering happen on
 * the server, so only the visible page is ever downloaded. */
@Immutable
data class LibraryQuery(
    val sort: LibrarySort = LibrarySort.Name,
    val descending: Boolean = false,
    val unwatched: Boolean = false,
    val inProgress: Boolean = false,
    val favorites: Boolean = false,
    /** Shows still releasing episodes (Jellyfin `seriesStatus=Continuing`). */
    val airing: Boolean = false,
    val genre: String? = null,
    val search: String? = null,
) {
    val filtered: Boolean get() = unwatched || inProgress || favorites || airing || genre != null

    val seriesStatus: String? get() = if (airing) "Continuing" else null

    /** Jellyfin `filters`, comma separated; null when none apply. */
    val filters: String?
        get() = listOfNotNull(
            "IsUnplayed".takeIf { unwatched },
            "IsResumable".takeIf { inProgress },
            "IsFavorite".takeIf { favorites },
        ).joinToString(",").ifEmpty { null }

    /** Cache key: the same query is the same list. */
    val key: String get() = "${sort.name}:$descending:$filters:$airing:$genre:${search.orEmpty().lowercase()}"
}
