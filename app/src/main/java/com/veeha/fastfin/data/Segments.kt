package com.veeha.fastfin.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class SegmentKind { Intro, Recap, Outro, Preview }

/** A stretch of an episode worth skipping: its intro, recap, credits or preview. */
@Immutable
data class Segment(val kind: SegmentKind, val startMs: Long, val endMs: Long) {
    operator fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/** Shorter than this is a detection glitch, not an intro. */
private const val MIN_SEGMENT_MS = 3_000L

/**
 * Jellyfin 10.10+ media segments (`/MediaSegments/{id}`), which the Intro
 * Skipper plugin and others provide. `type` is the server's MediaSegmentType.
 */
@Serializable
data class MediaSegmentDto(val type: String? = null, val startTicks: Long = 0, val endTicks: Long = 0)

/** Intro Skipper's own endpoint, for plugin versions before media segments. Times are seconds. */
@Serializable
data class IntroSkipperDto(val introduction: IntroSkipperRange? = null, val credits: IntroSkipperRange? = null)

@Serializable
data class IntroSkipperRange(val valid: Boolean = false, val introStart: Double = 0.0, val introEnd: Double = 0.0)

fun segmentsFromMediaSegments(items: List<MediaSegmentDto>): List<Segment> = items.mapNotNull { dto ->
    val kind = when (dto.type?.lowercase()) {
        "intro" -> SegmentKind.Intro
        "recap" -> SegmentKind.Recap
        "outro" -> SegmentKind.Outro
        "preview" -> SegmentKind.Preview
        else -> return@mapNotNull null // Commercial, Unknown
    }
    Segment(kind, dto.startTicks / TICKS_PER_MS, dto.endTicks / TICKS_PER_MS)
}.cleaned()

fun segmentsFromIntroSkipper(dto: IntroSkipperDto): List<Segment> = listOfNotNull(
    dto.introduction?.toSegment(SegmentKind.Intro),
    dto.credits?.toSegment(SegmentKind.Outro),
).cleaned()

private fun IntroSkipperRange.toSegment(kind: SegmentKind): Segment? =
    if (!valid) null else Segment(kind, (introStart * 1000).toLong(), (introEnd * 1000).toLong())

private fun List<Segment>.cleaned(): List<Segment> =
    filter { it.startMs >= 0 && it.endMs - it.startMs >= MIN_SEGMENT_MS }.sortedBy { it.startMs }
