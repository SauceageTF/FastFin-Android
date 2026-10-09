package com.veeha.fastfin.playback

import com.veeha.fastfin.data.AppSettings
import com.veeha.fastfin.data.HdrMode
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The DeviceProfile sent with every PlaybackInfo call, built from what
 * [CapabilityProbe] found on this device.
 *
 * The big win over iOS: ExoPlayer demuxes MKV, WebM, MP4, TS and AVI itself,
 * so an HEVC/HDR MKV is played straight off the disk (`Static=true`, zero
 * server CPU, instant seeking) instead of being remuxed into fMP4 HLS. Embedded
 * PGS, VobSub, ASS and SRT subtitles render on the device too, so subtitles
 * never force a burn-in transcode during direct play, and audio and subtitle
 * switches happen locally without reloading the stream.
 *
 * HDR policy (see [HdrMode]): a range type is only advertised when the decoder
 * can decode it and, in Auto, the display can show it. Otherwise Jellyfin
 * tone-maps on the server, which looks right instead of washed out.
 */
object DeviceProfile {
    private const val VIDEO_CONTAINERS = "mkv,webm,mp4,m4v,mov,ts,mpegts,avi,3gp,ogv,flv"
    private const val AUDIO_CONTAINERS = "mp3,aac,m4a,m4b,flac,ogg,oga,opus,wav,webma,mka"

    fun build(caps: DeviceCapabilities, settings: AppSettings): JsonObject = buildJsonObject {
        val bitrate = settings.quality.bitrate
        val videoCodecs = caps.video.joinToString(",") { it.codec }
        val audioCodecs = caps.audioCodecs.sorted().joinToString(",")
        val hlsAudio = listOf("aac", "mp3", "ac3", "eac3").filter { it in caps.audioCodecs }.joinToString(",")
        val hlsVideo = listOfNotNull(caps.decoder("hevc")?.let { "hevc" }, "h264").joinToString(",")

        put("Name", "FastFin Android")
        put("MaxStreamingBitrate", bitrate)
        put("MaxStaticBitrate", bitrate)
        put("MusicStreamingTranscodingBitrate", 384_000)

        putJsonArray("DirectPlayProfiles") {
            addJsonObject {
                put("Type", "Video")
                put("Container", VIDEO_CONTAINERS)
                put("VideoCodec", videoCodecs)
                put("AudioCodec", audioCodecs)
            }
            addJsonObject {
                put("Type", "Audio")
                put("Container", AUDIO_CONTAINERS)
                put("AudioCodec", audioCodecs)
            }
        }

        // Only reached when direct play is impossible (bitrate cap, codec the
        // device lacks, tone-mapping). fMP4 HLS first so HEVC can be copied
        // rather than re-encoded; TS H.264 is the universal last resort.
        putJsonArray("TranscodingProfiles") {
            addJsonObject {
                put("Type", "Video")
                put("Container", "mp4")
                put("VideoCodec", hlsVideo)
                put("AudioCodec", hlsAudio)
                put("Protocol", "hls")
                put("Context", "Streaming")
                put("MaxAudioChannels", "6")
                put("MinSegments", 2)
                put("BreakOnNonKeyFrames", true)
            }
            addJsonObject {
                put("Type", "Video")
                put("Container", "ts")
                put("VideoCodec", "h264")
                put("AudioCodec", listOf("aac", "mp3", "ac3").filter { it in caps.audioCodecs }.joinToString(","))
                put("Protocol", "hls")
                put("Context", "Streaming")
                put("MaxAudioChannels", "6")
                put("MinSegments", 2)
                put("BreakOnNonKeyFrames", true)
            }
            addJsonObject {
                put("Type", "Audio")
                put("Container", "mp4")
                put("AudioCodec", "aac")
                put("Protocol", "hls")
                put("Context", "Streaming")
                put("MaxAudioChannels", "2")
            }
        }

        putJsonArray("ContainerProfiles") {}

        putJsonArray("CodecProfiles") {
            for (decoder in caps.video) {
                addJsonObject {
                    put("Type", "Video")
                    put("Codec", decoder.codec)
                    putJsonArray("Conditions") { videoConditions(decoder, caps, settings.hdr) }
                }
            }
        }

        putJsonArray("SubtitleProfiles") {
            // Inside the container while direct playing: ExoPlayer renders all of these.
            for (format in listOf(
                "srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml", "mov_text",
                "pgssub", "pgs", "dvdsub", "dvd_subtitle", "dvbsub", "dvb_subtitle",
            )) subtitle(format, "Embed")
            // Side-loaded next to a transcoded stream (or an external .srt file).
            for (format in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml")) subtitle(format, "External")
            subtitle("vtt", "Hls")
            subtitle("webvtt", "Hls")
        }

        putJsonArray("ResponseProfiles") {
            addJsonObject {
                put("Type", "Video")
                put("Container", "m4v")
                put("MimeType", "video/mp4")
            }
        }
    }

    private fun JsonArrayBuilder.subtitle(format: String, method: String) = addJsonObject {
        put("Format", format)
        put("Method", method)
    }

    private fun JsonArrayBuilder.condition(condition: String, property: String, value: String) = addJsonObject {
        put("Condition", condition)
        put("Property", property)
        put("Value", value)
        put("IsRequired", false)
    }

    private fun JsonArrayBuilder.videoConditions(decoder: VideoDecoder, caps: DeviceCapabilities, mode: HdrMode) {
        condition("LessThanEqual", "Width", decoder.maxWidth.toString())
        condition("LessThanEqual", "Height", decoder.maxHeight.toString())
        when (decoder.codec) {
            "h264" -> {
                // 10-bit "High 10" H.264 has no hardware decoder on phones.
                condition("LessThanEqual", "VideoBitDepth", "8")
                condition("EqualsAny", "VideoProfile", "high|main|baseline|constrained baseline")
                condition("NotEquals", "IsInterlaced", "true")
            }
            "hevc" -> {
                condition("LessThanEqual", "VideoBitDepth", if (decoder.tenBit) "10" else "8")
                condition("EqualsAny", "VideoProfile", if (decoder.tenBit) "main|main 10" else "main")
                condition("EqualsAny", "VideoRangeType", rangeTypes(decoder, caps, mode).joinToString("|"))
                condition("NotEquals", "IsInterlaced", "true")
            }
            "av1", "vp9" -> {
                condition("LessThanEqual", "VideoBitDepth", if (decoder.tenBit) "10" else "8")
                condition("EqualsAny", "VideoRangeType", rangeTypes(decoder, caps, mode).joinToString("|"))
            }
            else -> condition("LessThanEqual", "VideoBitDepth", "8")
        }
    }

    /**
     * Jellyfin VideoRangeType values this decoder can show correctly.
     *
     * Dolby Vision profiles 8.1/8.4 carry an HDR10/HLG base layer: ExoPlayer
     * plays that layer on the plain HEVC decoder when there is no Dolby Vision
     * decoder, so they follow HDR10/HLG support. Profile 5 (no compatible base
     * layer) and profile 7 (enhancement layer) need a real Dolby Vision
     * decoder and display.
     */
    fun rangeTypes(decoder: VideoDecoder, caps: DeviceCapabilities, mode: HdrMode): List<String> {
        val out = mutableListOf("SDR")
        if (decoder.codec == "hevc") out += "DOVIWithSDR"
        if (mode == HdrMode.Sdr) return out

        val always = mode == HdrMode.Always
        val display = caps.displayHdr
        val hdr10 = decoder.hdr10 && (always || HdrType.Hdr10 in display)
        val hlg = decoder.tenBit && (always || HdrType.Hlg in display || HdrType.Hdr10 in display)

        if (hdr10) out += listOf("HDR10", "HDR10Plus")
        if (hlg) out += "HLG"
        if (decoder.codec == "hevc") {
            if (hdr10) out += listOf("DOVIWithHDR10", "DOVIWithHDR10Plus")
            if (hlg) out += "DOVIWithHLG"
            val dv = caps.dolbyVision
            val dvDisplay = always || HdrType.DolbyVision in display
            if (dv != null && dvDisplay) {
                if (dv.profile5) out += "DOVI"
                if (dv.profile7) out += listOf("DOVIWithEL", "DOVIWithELHDR10Plus")
            }
        }
        return out
    }
}
