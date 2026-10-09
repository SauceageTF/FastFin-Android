package com.veeha.fastfin

import com.veeha.fastfin.data.AppSettings
import com.veeha.fastfin.data.HdrMode
import com.veeha.fastfin.playback.DeviceCapabilities
import com.veeha.fastfin.playback.DeviceProfile
import com.veeha.fastfin.playback.DolbyVisionDecoder
import com.veeha.fastfin.playback.HdrType
import com.veeha.fastfin.playback.VideoDecoder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HDR contract: a range type is offered only when it will look right. */
class HdrPolicyTest {
    private val hevcHdr = VideoDecoder("hevc", "HEVC", 3840, 2160, tenBit = true, hdr10 = true, hdr10Plus = true, hardware = true)
    private val hevc8 = VideoDecoder("hevc", "HEVC", 1920, 1080, tenBit = false, hdr10 = false, hdr10Plus = false, hardware = true)
    private val h264 = VideoDecoder("h264", "H.264", 3840, 2160, tenBit = false, hdr10 = false, hdr10Plus = false, hardware = true)

    private fun caps(
        video: List<VideoDecoder> = listOf(hevcHdr, h264),
        display: Set<HdrType> = setOf(HdrType.Hdr10, HdrType.Hlg),
        dv: DolbyVisionDecoder? = null,
    ) = DeviceCapabilities(video, dv, setOf("aac", "mp3", "ac3", "eac3"), emptySet(), display)

    @Test
    fun hdrPhoneInAutoPlaysHdr10AndDolbyVisionBaseLayers() {
        val types = DeviceProfile.rangeTypes(hevcHdr, caps(), HdrMode.Auto)
        assertTrue(types.containsAll(listOf("SDR", "HDR10", "HDR10Plus", "HLG", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR")))
        // Profile 5 has no HDR10 base layer: never without a real DV decoder.
        assertFalse("DOVI" in types)
        assertFalse("DOVIWithEL" in types)
    }

    @Test
    fun sdrScreenInAutoLetsTheServerToneMap() {
        val types = DeviceProfile.rangeTypes(hevcHdr, caps(display = emptySet()), HdrMode.Auto)
        assertEquals(listOf("SDR", "DOVIWithSDR"), types)
    }

    @Test
    fun alwaysModeTrustsTheDecoderOverTheDisplay() {
        val types = DeviceProfile.rangeTypes(hevcHdr, caps(display = emptySet()), HdrMode.Always)
        assertTrue("HDR10" in types && "HLG" in types && "DOVIWithHDR10" in types)
    }

    @Test
    fun toneMapModeNeverSendsHdr() {
        val types = DeviceProfile.rangeTypes(hevcHdr, caps(), HdrMode.Sdr)
        assertEquals(listOf("SDR", "DOVIWithSDR"), types)
    }

    @Test
    fun eightBitDecoderGetsNoHdr() {
        val types = DeviceProfile.rangeTypes(hevc8, caps(video = listOf(hevc8)), HdrMode.Always)
        assertEquals(listOf("SDR", "DOVIWithSDR"), types)
    }

    @Test
    fun realDolbyVisionDecoderAndDisplayUnlockProfiles5And7() {
        val dv = DolbyVisionDecoder(profile5 = true, profile7 = true, profile8 = true)
        val types = DeviceProfile.rangeTypes(hevcHdr, caps(display = setOf(HdrType.DolbyVision, HdrType.Hdr10), dv = dv), HdrMode.Auto)
        assertTrue("DOVI" in types && "DOVIWithEL" in types)
    }

    @Test
    fun dolbyVisionDecoderWithoutDolbyVisionDisplayStaysOnBaseLayers() {
        val dv = DolbyVisionDecoder(profile5 = true, profile7 = true, profile8 = true)
        val types = DeviceProfile.rangeTypes(hevcHdr, caps(display = setOf(HdrType.Hdr10), dv = dv), HdrMode.Auto)
        assertFalse("DOVI" in types)
        assertTrue("DOVIWithHDR10" in types)
    }

    @Test
    fun profileDirectPlaysMkvAndCapsResolution() {
        val profile = DeviceProfile.build(caps(), AppSettings())
        val direct = profile["DirectPlayProfiles"]!!.jsonArray.first().jsonObject
        assertTrue("mkv" in direct["Container"]!!.jsonPrimitive.content.split(','))
        val hevc = profile["CodecProfiles"]!!.jsonArray.map { it.jsonObject }.first { it["Codec"]!!.jsonPrimitive.content == "hevc" }
        val width = hevc["Conditions"]!!.jsonArray.map { it.jsonObject }.first { it["Property"]!!.jsonPrimitive.content == "Width" }
        assertEquals("3840", width["Value"]!!.jsonPrimitive.content)
    }

    @Test
    fun bitmapSubtitlesAreEmbeddedNotBurnedIn() {
        val profile = DeviceProfile.build(caps(), AppSettings())
        val embedded = profile["SubtitleProfiles"]!!.jsonArray.map { it.jsonObject }
            .filter { it["Method"]!!.jsonPrimitive.content == "Embed" }
            .map { it["Format"]!!.jsonPrimitive.content }
        assertTrue("pgssub" in embedded && "dvdsub" in embedded && "ass" in embedded)
    }
}
