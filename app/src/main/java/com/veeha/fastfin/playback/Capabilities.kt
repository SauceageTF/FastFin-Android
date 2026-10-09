@file:OptIn(UnstableApi::class)

package com.veeha.fastfin.playback

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel as PL
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import kotlin.math.max
import kotlin.math.min

enum class HdrType(val label: String) { Hdr10("HDR10"), Hdr10Plus("HDR10+"), Hlg("HLG"), DolbyVision("Dolby Vision") }

@Immutable
data class VideoDecoder(
    /** Jellyfin's codec name. */
    val codec: String,
    val label: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val tenBit: Boolean,
    val hdr10: Boolean,
    val hdr10Plus: Boolean,
    val hardware: Boolean,
)

@Immutable
data class DolbyVisionDecoder(val profile5: Boolean, val profile7: Boolean, val profile8: Boolean)

/**
 * What this device can really play, read from MediaCodecList and the display
 * instead of assumed. The iOS build can hard-code AVFoundation's abilities;
 * Android phones range from an 8-bit 1080p SoC to a 4K Dolby Vision TV box,
 * so the DeviceProfile sent to Jellyfin is built from this at runtime.
 */
@Immutable
data class DeviceCapabilities(
    val video: List<VideoDecoder>,
    val dolbyVision: DolbyVisionDecoder?,
    val audioDecoders: Set<String>,
    /** Bitstream formats the current audio route accepts (HDMI/ARC receivers). */
    val passthrough: Set<String>,
    val displayHdr: Set<HdrType>,
) {
    val audioCodecs: Set<String> get() = audioDecoders + passthrough

    fun decoder(codec: String): VideoDecoder? = video.firstOrNull { it.codec == codec }

    val decodableHdr: Set<HdrType>
        get() = buildSet {
            if (video.any { it.hdr10 }) add(HdrType.Hdr10)
            if (video.any { it.hdr10Plus }) add(HdrType.Hdr10Plus)
            if (decoder("hevc")?.tenBit == true) add(HdrType.Hlg)
            if (dolbyVision != null) add(HdrType.DolbyVision)
        }

    val maxResolution: String
        get() = video.maxByOrNull { it.maxWidth * it.maxHeight }?.let { "${it.maxWidth}×${it.maxHeight}" } ?: "Unknown"
}

object CapabilityProbe {
    private val VIDEO = listOf(
        Triple("video/hevc", "hevc", "HEVC"),
        Triple("video/avc", "h264", "H.264"),
        Triple("video/av01", "av1", "AV1"),
        Triple("video/x-vnd.on2.vp9", "vp9", "VP9"),
        Triple("video/x-vnd.on2.vp8", "vp8", "VP8"),
        Triple("video/mpeg2", "mpeg2video", "MPEG-2"),
        Triple("video/mp4v-es", "mpeg4", "MPEG-4"),
    )

    private val AUDIO = mapOf(
        "audio/mp4a-latm" to "aac",
        "audio/mpeg" to "mp3",
        "audio/opus" to "opus",
        "audio/vorbis" to "vorbis",
        "audio/flac" to "flac",
        "audio/alac" to "alac",
        "audio/ac3" to "ac3",
        "audio/eac3" to "eac3",
        "audio/eac3-joc" to "eac3",
        "audio/vnd.dts" to "dts",
        "audio/vnd.dts.hd" to "dts",
        "audio/true-hd" to "truehd",
    )

    fun detect(context: Context): DeviceCapabilities {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        }.getOrDefault(emptyList())

        val video = VIDEO.mapNotNull { (mime, codec, label) -> videoDecoder(decoders, mime, codec, label) }

        val audio = buildSet {
            // ExoPlayer always handles these (framework decoders or PCM).
            addAll(listOf("aac", "mp3", "pcm_s16le", "pcm_s24le"))
            for (info in decoders) for (type in info.supportedTypes) AUDIO[type.lowercase()]?.let(::add)
        }

        return DeviceCapabilities(
            video = video,
            dolbyVision = dolbyVision(decoders),
            audioDecoders = audio,
            passthrough = passthrough(context),
            displayHdr = displayHdr(context),
        )
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || ".sw." in name || name.startsWith("omx.ffmpeg"))
        }

    // Profile constants newer than minSdk are compile-time ints: on an older
    // device they simply never match, which is the right answer.
    @SuppressLint("InlinedApi")
    private fun videoDecoder(decoders: List<MediaCodecInfo>, mime: String, codec: String, label: String): VideoDecoder? {
        val all = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        if (all.isEmpty()) return null
        val hardware = all.filter(::isHardware)
        val pool = hardware.ifEmpty { all }

        var width = 0
        var height = 0
        val profiles = HashSet<Int>()
        for (info in pool) {
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            caps.videoCapabilities?.let {
                width = max(width, it.supportedWidths.upper)
                height = max(height, it.supportedHeights.upper)
            }
            caps.profileLevels.forEach { profiles += it.profile }
        }
        // A software decoder can technically open 4K but cannot keep up with it.
        if (hardware.isEmpty()) {
            width = min(width, 1920)
            height = min(height, 1088)
        }

        val tenBit: Boolean
        val hdr10: Boolean
        val hdr10Plus: Boolean
        when (codec) {
            "hevc" -> {
                hdr10Plus = PL.HEVCProfileMain10HDR10Plus in profiles
                hdr10 = hdr10Plus || PL.HEVCProfileMain10HDR10 in profiles
                tenBit = hdr10 || PL.HEVCProfileMain10 in profiles
            }
            "av1" -> {
                hdr10Plus = PL.AV1ProfileMain10HDR10Plus in profiles
                hdr10 = hdr10Plus || PL.AV1ProfileMain10HDR10 in profiles
                tenBit = hdr10 || PL.AV1ProfileMain10 in profiles
            }
            "vp9" -> {
                hdr10Plus = PL.VP9Profile2HDR10Plus in profiles
                hdr10 = hdr10Plus || PL.VP9Profile2HDR in profiles || PL.VP9Profile3HDR in profiles
                tenBit = hdr10 || PL.VP9Profile2 in profiles
            }
            else -> {
                tenBit = false
                hdr10 = false
                hdr10Plus = false
            }
        }
        return VideoDecoder(
            codec = codec,
            label = label,
            maxWidth = width.takeIf { it > 0 } ?: 1920,
            maxHeight = height.takeIf { it > 0 } ?: 1080,
            tenBit = tenBit,
            hdr10 = hdr10,
            hdr10Plus = hdr10Plus,
            hardware = hardware.isNotEmpty(),
        )
    }

    @SuppressLint("InlinedApi")
    private fun dolbyVision(decoders: List<MediaCodecInfo>): DolbyVisionDecoder? {
        val mime = "video/dolby-vision"
        val profiles = HashSet<Int>()
        var found = false
        for (info in decoders) {
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            found = true
            runCatching { info.getCapabilitiesForType(mime) }.getOrNull()?.profileLevels?.forEach { profiles += it.profile }
        }
        if (!found) return null
        return DolbyVisionDecoder(
            profile5 = PL.DolbyVisionProfileDvheStn in profiles,
            profile7 = PL.DolbyVisionProfileDvheDtb in profiles,
            profile8 = PL.DolbyVisionProfileDvheSt in profiles,
        )
    }

    private fun passthrough(context: Context): Set<String> = runCatching {
        // Bitstream support only; spatializer masks don't affect passthrough.
        val caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null, emptyList())
        buildSet {
            if (caps.supportsEncoding(C.ENCODING_AC3)) add("ac3")
            if (caps.supportsEncoding(C.ENCODING_E_AC3) || caps.supportsEncoding(C.ENCODING_E_AC3_JOC)) add("eac3")
            if (caps.supportsEncoding(C.ENCODING_DTS) || caps.supportsEncoding(C.ENCODING_DTS_HD)) add("dts")
            if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) add("truehd")
        }
    }.getOrDefault(emptySet())

    @SuppressLint("InlinedApi")
    private fun displayHdr(context: Context): Set<HdrType> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return emptySet()
        val types = buildSet {
            hdrTypes(display).forEach(::add)
            if (Build.VERSION.SDK_INT >= 34) display.mode.supportedHdrTypes.forEach(::add)
        }
        val result = types.mapNotNullTo(HashSet()) {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrType.Hdr10
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> HdrType.Hdr10Plus
                Display.HdrCapabilities.HDR_TYPE_HLG -> HdrType.Hlg
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrType.DolbyVision
                else -> null
            }
        }
        // Some panels only advertise HDR through the configuration flag.
        if (result.isEmpty() && context.resources.configuration.isScreenHdr) {
            result += HdrType.Hdr10
            result += HdrType.Hlg
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun hdrTypes(display: Display): IntArray = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
}
