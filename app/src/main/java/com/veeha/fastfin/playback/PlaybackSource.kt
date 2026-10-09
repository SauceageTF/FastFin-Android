package com.veeha.fastfin.playback

import androidx.compose.runtime.Immutable
import com.veeha.fastfin.data.AppSettings
import com.veeha.fastfin.data.JellyfinApi
import com.veeha.fastfin.data.Session
import com.veeha.fastfin.data.TICKS_PER_MS
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

// MARK: PlaybackInfo DTOs

@Serializable
data class PlaybackInfoResponse(
    val playSessionId: String? = null,
    val errorCode: String? = null,
    val mediaSources: List<MediaSourceInfo> = emptyList(),
)

@Serializable
data class MediaSourceInfo(
    val id: String,
    val container: String? = null,
    val eTag: String? = null,
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val supportsTranscoding: Boolean = true,
    val transcodingUrl: String? = null,
    val transcodingSubProtocol: String? = null,
    val transcodingContainer: String? = null,
    /** An array in 10.9+, a comma list in older servers. */
    val transcodeReasons: JsonElement? = null,
    val defaultAudioStreamIndex: Int? = null,
    val defaultSubtitleStreamIndex: Int? = null,
    val mediaStreams: List<MediaStreamInfo> = emptyList(),
)

@Serializable
data class MediaStreamInfo(
    val index: Int,
    val type: String = "",
    val codec: String? = null,
    val displayTitle: String? = null,
    val language: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isExternal: Boolean = false,
    val deliveryMethod: String? = null,
    val deliveryUrl: String? = null,
    val videoRangeType: String? = null,
    val bitDepth: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
)

// MARK: What the player gets

enum class PlayMethod { DirectPlay, DirectStream, Transcode }

/** How a subtitle reaches the screen. Embed = inside the container ExoPlayer
 * is reading; External = side-loaded file; Hls = in the manifest; Encode =
 * burned into the picture by the server. */
enum class Delivery { Embed, External, Hls, Encode }

@Immutable
data class TrackOption(
    val index: Int,
    val title: String,
    val codec: String?,
    val language: String?,
    val isDefault: Boolean,
    val delivery: Delivery = Delivery.Embed,
    val deliveryUrl: String? = null,
)

@Immutable
data class PlaybackSource(
    val url: String,
    val isHls: Boolean,
    val playMethod: PlayMethod,
    val mediaSourceId: String,
    val playSessionId: String,
    val audioTracks: List<TrackOption>,
    val subtitleTracks: List<TrackOption>,
    val selectedAudio: Int?,
    val selectedSubtitle: Int?,
    val transcodeReasons: List<String>,
    /** Delivery and codec line for the track menu and diagnostics. */
    val summary: String,
    /** "HDR10", "Dolby Vision"... when the picture reaches the device in HDR. */
    val hdr: String?,
    val isFallback: Boolean = false,
)

class PlaybackError(message: String, val reasons: List<String> = emptyList()) : Exception(message)

/** Server-side track choice. `subtitle` is a stream index, [SUBTITLES_OFF],
 * or null for "whatever the server's user preferences pick". */
data class Selection(val audio: Int? = null, val subtitle: Int? = null)

const val SUBTITLES_OFF = -1

private val VIDEO_REENCODE = Regex(
    "^Video|Anamorphic|Interlaced|RefFrames|SubtitleCodec|BitrateExceeds|Unknown|DirectPlayError",
    RegexOption.IGNORE_CASE,
)

/**
 * Describe the device, let the server pick, use its verdict. Same three tiers
 * as iOS and jellyfin-web:
 *
 *   1. Direct play: the file as-is over byte ranges. On Android this covers MKV.
 *   2. Remux/direct stream: streams copied into HLS, audio maybe converted.
 *   3. Transcode: anything the device can't decode, or above the bitrate cap.
 */
object Negotiator {

    suspend fun negotiate(
        api: JellyfinApi,
        session: Session,
        caps: DeviceCapabilities,
        settings: AppSettings,
        itemId: String,
        startMs: Long,
        selection: Selection,
        allowDirectPlay: Boolean,
    ): PlaybackSource {
        val body = buildJsonObject {
            put("UserId", session.userId)
            put("DeviceProfile", DeviceProfile.build(caps, settings))
            put("MaxStreamingBitrate", settings.quality.bitrate)
            put("StartTimeTicks", startMs * TICKS_PER_MS)
            selection.audio?.let { put("AudioStreamIndex", it) }
            selection.subtitle?.let { put("SubtitleStreamIndex", it) }
            put("EnableDirectPlay", allowDirectPlay)
            put("EnableDirectStream", true)
            put("EnableTranscoding", true)
            put("AllowVideoStreamCopy", true)
            put("AllowAudioStreamCopy", true)
            put("AutoOpenLiveStream", true)
        }
        val response = api.playbackInfo(itemId, body)
        response.errorCode?.let { throw PlaybackError("The server refused playback ($it).") }

        val sources = response.mediaSources
        val source = (if (allowDirectPlay) sources.firstOrNull { it.supportsDirectPlay } else null)
            ?: sources.firstOrNull { it.transcodingUrl != null }
            ?: sources.firstOrNull()
            ?: throw PlaybackError("The server didn't return a media source for this item.")

        val playSessionId = response.playSessionId ?: UUID.randomUUID().toString().replace("-", "")
        val reasons = parseReasons(source.transcodeReasons)

        val audio = ArrayList<TrackOption>()
        val subtitles = ArrayList<TrackOption>()
        var video = source.container?.substringBefore(',')?.uppercase().orEmpty()
        var hdr: String? = null
        for (stream in source.mediaStreams) {
            when (stream.type) {
                "Audio" -> audio += option(stream, Delivery.Embed, null)
                "Subtitle" -> {
                    val delivery = when (stream.deliveryMethod) {
                        "External" -> Delivery.External
                        "Hls" -> Delivery.Hls
                        "Encode" -> Delivery.Encode
                        "Embed" -> Delivery.Embed
                        else -> if (stream.isExternal) Delivery.External else Delivery.Embed
                    }
                    val url = stream.deliveryUrl?.let { if (it.startsWith("http")) it else session.serverUrl + it }
                    subtitles += option(stream, delivery, url)
                }
                "Video" -> stream.codec?.let { codec ->
                    video += " · ${codec.uppercase()}"
                    if ((stream.bitDepth ?: 8) > 8) video += " ${stream.bitDepth}-bit"
                    hdrLabel(stream.videoRangeType)?.let {
                        hdr = it
                        video += " $it"
                    }
                }
            }
        }

        val selectedAudio = selection.audio ?: source.defaultAudioStreamIndex ?: audio.firstOrNull()?.index
        // -1 is how Jellyfin spells "no subtitles", both ways.
        val selectedSubtitle = (selection.subtitle ?: source.defaultSubtitleStreamIndex)?.takeIf { it >= 0 }

        fun make(url: String, isHls: Boolean, method: PlayMethod, summary: String, hdrOut: String?) = PlaybackSource(
            url = url, isHls = isHls, playMethod = method, mediaSourceId = source.id, playSessionId = playSessionId,
            audioTracks = audio, subtitleTracks = subtitles, selectedAudio = selectedAudio,
            selectedSubtitle = selectedSubtitle, transcodeReasons = reasons, summary = summary, hdr = hdrOut,
        )

        if (allowDirectPlay && source.supportsDirectPlay) {
            // ffprobe can report a list ("mov,mp4,m4a,..."); the route takes one.
            val container = source.container?.substringBefore(',')?.trim()?.ifEmpty { null } ?: "mkv"
            val url = api.url(
                "/Videos/$itemId/stream.$container",
                mapOf(
                    "Static" to true, "MediaSourceId" to source.id, "DeviceId" to session.deviceId,
                    "PlaySessionId" to playSessionId, "Tag" to source.eTag,
                ),
            ).toString()
            return make(url, isHls = false, PlayMethod.DirectPlay, "Direct play · $video", hdr)
        }

        source.transcodingUrl?.let { raw ->
            val url = if (raw.startsWith("http")) raw else session.serverUrl + raw
            val isHls = source.transcodingSubProtocol.equals("hls", ignoreCase = true) || ".m3u8" in url
            val videoReencoded = reasons.any { VIDEO_REENCODE.containsMatchIn(it) }
            val audioReencoded = reasons.any { it.startsWith("Audio", ignoreCase = true) }
            val remux = !videoReencoded && !audioReencoded
            val target = (source.transcodingContainer ?: "ts").lowercase().let { if (it == "mp4") "fMP4" else it.uppercase() }
            val label = when {
                remux -> "Remux"
                videoReencoded -> "Transcode"
                else -> "Video copy · audio → AAC"
            }
            return make(
                url, isHls,
                if (remux) PlayMethod.DirectStream else PlayMethod.Transcode,
                "$label · $video → $target",
                // A re-encoded picture is tone-mapped to SDR by the server.
                if (videoReencoded) null else hdr,
            )
        }

        // No URL from the server (ffmpeg missing, transcode limit hit): try the
        // hand-built H.264 HLS URL that is known to work.
        if (source.supportsTranscoding) {
            val url = fallbackUrl(api, session, itemId, source.id, playSessionId, startMs, selectedAudio, selectedSubtitle)
            return make(url, isHls = true, PlayMethod.Transcode, "Transcode (fallback) · $video", null).copy(isFallback = true)
        }

        throw PlaybackError("This file can't be played on this device and the server won't transcode it.", reasons)
    }

    /** One step down after a failure: an explicit H.264/AAC transcode of the
     * same item, keeping the play session so the server swaps jobs. */
    fun fallback(api: JellyfinApi, session: Session, itemId: String, from: PlaybackSource, startMs: Long): PlaybackSource {
        val subtitle = from.subtitleTracks.firstOrNull { it.index == from.selectedSubtitle }
        return from.copy(
            url = fallbackUrl(api, session, itemId, from.mediaSourceId, from.playSessionId, startMs, from.selectedAudio, from.selectedSubtitle),
            isHls = true,
            playMethod = PlayMethod.Transcode,
            summary = "Transcode (fallback) · H.264 · AAC",
            hdr = null,
            isFallback = true,
            // Burned in by this URL, so nothing to side-load or select.
            subtitleTracks = from.subtitleTracks.map { if (it == subtitle) it.copy(delivery = Delivery.Encode) else it },
        )
    }

    private fun fallbackUrl(
        api: JellyfinApi,
        session: Session,
        itemId: String,
        mediaSourceId: String,
        playSessionId: String,
        startMs: Long,
        audio: Int?,
        subtitle: Int?,
    ): String = api.url(
        "/Videos/$itemId/master.m3u8",
        mapOf(
            "DeviceId" to session.deviceId, "MediaSourceId" to mediaSourceId, "PlaySessionId" to playSessionId,
            "VideoCodec" to "h264", "AudioCodec" to "aac", "TranscodingMaxAudioChannels" to 2,
            "SegmentContainer" to "ts", "StartTimeTicks" to startMs * TICKS_PER_MS,
            "AudioStreamIndex" to audio, "SubtitleStreamIndex" to subtitle,
            "SubtitleMethod" to subtitle?.let { "Encode" },
        ),
    ).toString()

    private fun option(stream: MediaStreamInfo, delivery: Delivery, url: String?): TrackOption {
        val title = stream.displayTitle
            ?: listOfNotNull(stream.language, stream.codec?.uppercase()).joinToString(" · ").ifEmpty { "${stream.type} ${stream.index}" }
        return TrackOption(stream.index, title, stream.codec?.lowercase(), stream.language, stream.isDefault, delivery, url)
    }

    private fun parseReasons(raw: JsonElement?): List<String> = when (raw) {
        is JsonArray -> raw.mapNotNull { it.jsonPrimitive.contentOrNull }
        is JsonPrimitive -> raw.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }

    fun hdrLabel(rangeType: String?): String? = when {
        rangeType == null || rangeType == "SDR" || rangeType == "Unknown" -> null
        rangeType.startsWith("DOVI") && rangeType != "DOVIWithSDR" -> "Dolby Vision"
        rangeType == "HDR10Plus" -> "HDR10+"
        rangeType == "HDR10" -> "HDR10"
        rangeType == "HLG" -> "HLG"
        else -> null
    }
}
