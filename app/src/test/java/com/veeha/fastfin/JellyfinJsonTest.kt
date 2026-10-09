package com.veeha.fastfin

import com.veeha.fastfin.data.AppJson
import com.veeha.fastfin.data.HomeData
import com.veeha.fastfin.data.ImageUrls
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.Page
import com.veeha.fastfin.data.formatClock
import com.veeha.fastfin.data.formatRuntime
import com.veeha.fastfin.data.isResumable
import com.veeha.fastfin.data.playedFraction
import com.veeha.fastfin.data.resumeMs
import com.veeha.fastfin.playback.Negotiator
import com.veeha.fastfin.playback.PlaybackInfoResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jellyfin's PascalCase JSON through the naming strategy, including the
 * awkward names (ETag, IsDefault) and both TranscodeReasons shapes. */
class JellyfinJsonTest {

    @Test
    fun decodesItemsWithUserDataAndImageTags() {
        val json = """
            {"Items":[{"Id":"abc","Name":"Dune","Type":"Movie","ProductionYear":2021,"RunTimeTicks":93000000000,
              "UserData":{"PlaybackPositionTicks":36000000000,"PlayedPercentage":38.7,"Played":false},
              "ImageTags":{"Primary":"p1","Logo":"l1"},"BackdropImageTags":["b1"],"Unknown":{"Nested":1}}],
             "TotalRecordCount":1}
        """.trimIndent()
        val page = AppJson.decodeFromString<Page<Item>>(json)
        val item = page.items.single()
        assertEquals(1, page.totalRecordCount)
        assertEquals("Dune", item.name)
        assertEquals(3_600_000L, item.resumeMs)
        assertTrue(item.isResumable)
        assertEquals(0.387f, item.playedFraction, 0.001f)
        assertEquals("p1", item.imageTags!!["Primary"])
    }

    @Test
    fun decodesPlaybackInfoIncludingEtagAndStreams() {
        val json = """
            {"PlaySessionId":"s1","MediaSources":[{"Id":"m1","Container":"mkv","ETag":"e1",
              "SupportsDirectPlay":true,"TranscodeReasons":["ContainerNotSupported","AudioCodecNotSupported"],
              "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":-1,
              "MediaStreams":[
                {"Index":0,"Type":"Video","Codec":"hevc","BitDepth":10,"VideoRangeType":"DOVIWithHDR10"},
                {"Index":1,"Type":"Audio","Codec":"eac3","Language":"eng","IsDefault":true,"DisplayTitle":"English - Dolby Digital+ - 5.1"},
                {"Index":2,"Type":"Subtitle","Codec":"PGSSUB","Language":"fre","DeliveryMethod":"Embed"},
                {"Index":3,"Type":"Subtitle","Codec":"srt","IsExternal":true,"DeliveryMethod":"External","DeliveryUrl":"/Videos/x/m1/Subtitles/3/0/Stream.srt"}]}]}
        """.trimIndent()
        val info = AppJson.decodeFromString<PlaybackInfoResponse>(json)
        val source = info.mediaSources.single()
        assertEquals("s1", info.playSessionId)
        assertEquals("e1", source.eTag)
        assertTrue(source.supportsDirectPlay)
        assertEquals(-1, source.defaultSubtitleStreamIndex)
        assertEquals("DOVIWithHDR10", source.mediaStreams[0].videoRangeType)
        assertTrue(source.mediaStreams[1].isDefault)
        assertTrue(source.mediaStreams[3].isExternal)
        assertEquals("External", source.mediaStreams[3].deliveryMethod)
    }

    @Test
    fun acceptsTranscodeReasonsAsCommaString() {
        val json = """{"MediaSources":[{"Id":"m","TranscodeReasons":"VideoCodecNotSupported, AudioCodecNotSupported"}]}"""
        val info = AppJson.decodeFromString<PlaybackInfoResponse>(json)
        assertTrue(info.mediaSources.single().transcodeReasons != null)
    }

    @Test
    fun hdrLabels() {
        assertEquals("Dolby Vision", Negotiator.hdrLabel("DOVIWithHDR10"))
        assertEquals("HDR10+", Negotiator.hdrLabel("HDR10Plus"))
        assertEquals("HLG", Negotiator.hdrLabel("HLG"))
        assertNull(Negotiator.hdrLabel("SDR"))
        assertNull(Negotiator.hdrLabel("DOVIWithSDR"))
    }

    @Test
    fun homeSnapshotRoundTrips() {
        val home = HomeData(featured = listOf(Item("a", "A")), latest = mapOf("lib" to listOf(Item("b", "B"))))
        val decoded = AppJson.decodeFromString<HomeData>(AppJson.encodeToString(HomeData.serializer(), home))
        assertEquals(home, decoded)
    }

    @Test
    fun artworkWidthsShareBuckets() {
        assertEquals(320, ImageUrls.bucket(300))
        assertEquals(320, ImageUrls.bucket(320))
        assertEquals(1920, ImageUrls.bucket(5000))
        val urls = ImageUrls("http://jf:8096")
        val episode = Item("ep", "Pilot", type = "Episode", seriesId = "show", seriesPrimaryImageTag = "st", imageTags = mapOf("Primary" to "t"))
        assertEquals("http://jf:8096/Items/show/Images/Primary?maxWidth=320&quality=90&tag=st", urls.poster(episode, 300))
        // A known-empty tag map means no request at all, not a 404.
        assertNull(urls.primary(Item("x", imageTags = emptyMap()), 300))
    }

    @Test
    fun clocksAndRuntimes() {
        assertEquals("0:05", formatClock(5_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("2h 35m", formatRuntime(93_000_000_000))
        assertEquals("", formatRuntime(null))
    }
}
