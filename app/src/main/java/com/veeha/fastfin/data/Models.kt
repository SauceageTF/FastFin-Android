package com.veeha.fastfin.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** Jellyfin speaks PascalCase. One naming strategy instead of an annotation on
 * every property; kotlinx.serialization is code-generated, so decoding never
 * touches reflection. */
@OptIn(ExperimentalSerializationApi::class)
private object PascalCase : JsonNamingStrategy {
    override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String =
        serialName.replaceFirstChar { it.uppercaseChar() }
}

@OptIn(ExperimentalSerializationApi::class)
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    namingStrategy = PascalCase
}

@Serializable
data class Page<T>(val items: List<T> = emptyList(), val totalRecordCount: Int = 0)

@Immutable
@Serializable
data class Library(
    val id: String,
    val name: String = "",
    val collectionType: String? = null,
)

@Immutable
@Serializable
data class UserData(
    val playbackPositionTicks: Long = 0,
    val playedPercentage: Double? = null,
    val played: Boolean = false,
    val isFavorite: Boolean = false,
)

/** Mirrors the iOS `Item`: the subset of BaseItemDto the UI reads. List
 * endpoints are called without `Fields`, so payloads stay small; the detail
 * page asks for the whole item. */
@Immutable
@Serializable
data class Item(
    val id: String,
    val name: String = "",
    val type: String = "",
    val overview: String? = null,
    val productionYear: Int? = null,
    val runTimeTicks: Long? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val seriesPrimaryImageTag: String? = null,
    val userData: UserData? = null,
    val imageTags: Map<String, String>? = null,
    val backdropImageTags: List<String>? = null,
    val parentBackdropItemId: String? = null,
    val parentBackdropImageTags: List<String>? = null,
    val parentLogoItemId: String? = null,
    val parentLogoImageTag: String? = null,
    val parentThumbItemId: String? = null,
    val parentThumbImageTag: String? = null,
    val genres: List<String>? = null,
    val communityRating: Double? = null,
    val officialRating: String? = null,
    val taglines: List<String>? = null,
)

/** Home in one value, so it can be written to disk and shown instantly on the
 * next cold start while a fresh copy loads behind it. */
@Immutable
@Serializable
data class HomeData(
    val libraries: List<Library> = emptyList(),
    val resume: List<Item> = emptyList(),
    val featured: List<Item> = emptyList(),
    val latest: Map<String, List<Item>> = emptyMap(),
)

val Item.isSeries: Boolean get() = type == "Series"
val Item.isEpisode: Boolean get() = type == "Episode"
val Item.isMovie: Boolean get() = type == "Movie"

/** 0..1, how much has been watched. */
val Item.playedFraction: Float
    get() = ((userData?.playedPercentage ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)

val Item.resumeMs: Long get() = (userData?.playbackPositionTicks ?: 0L) / TICKS_PER_MS
val Item.isResumable: Boolean get() = resumeMs > 0
val Item.isWatched: Boolean get() = userData?.played == true || (userData?.playedPercentage ?: 0.0) >= 95.0

val Item.episodeLabel: String get() = indexNumber?.let { "E$it · $name" } ?: name

/** Title for the player and the system media session. */
val Item.displayTitle: String get() = if (seriesName != null) episodeLabel else name

const val TICKS_PER_MS = 10_000L

fun formatRuntime(ticks: Long?): String {
    if (ticks == null || ticks <= 0) return ""
    val totalMinutes = Math.round(ticks / 10_000_000.0 / 60.0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

fun formatClock(ms: Long): String {
    if (ms < 0) return "0:00"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

fun libraryLabel(type: String?): String = when (type) {
    "movies" -> "Movies"
    "tvshows" -> "TV Shows"
    "music" -> "Music"
    "homevideos" -> "Home Videos"
    "photos" -> "Photos"
    "books" -> "Books"
    else -> "Mixed content"
}
