package com.veeha.fastfin

import com.veeha.fastfin.data.AppJson
import com.veeha.fastfin.data.IntroSkipperDto
import com.veeha.fastfin.data.MediaSegmentDto
import com.veeha.fastfin.data.Page
import com.veeha.fastfin.data.Segment
import com.veeha.fastfin.data.SegmentKind
import com.veeha.fastfin.data.segmentsFromIntroSkipper
import com.veeha.fastfin.data.segmentsFromMediaSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intro and credits timings, as Jellyfin and the Intro Skipper plugin send them. */
class SegmentsTest {

    @Test
    fun mediaSegmentsBecomeIntroAndCredits() {
        // GET /MediaSegments/{id} on Jellyfin 10.10+, ticks of 100 ns.
        val json = """
            {"Items":[
              {"Id":"a","ItemId":"e1","Type":"Outro","StartTicks":12600000000,"EndTicks":13800000000},
              {"Id":"b","ItemId":"e1","Type":"Intro","StartTicks":300000000,"EndTicks":1200000000},
              {"Id":"c","ItemId":"e1","Type":"Commercial","StartTicks":5000000000,"EndTicks":5600000000}
            ],"TotalRecordCount":3,"StartIndex":0}
        """.trimIndent()
        val page = AppJson.decodeFromString(Page.serializer(MediaSegmentDto.serializer()), json)
        assertEquals(
            listOf(
                Segment(SegmentKind.Intro, 30_000, 120_000),
                Segment(SegmentKind.Outro, 1_260_000, 1_380_000),
            ),
            segmentsFromMediaSegments(page.items),
        )
    }

    @Test
    fun introSkipperEndpointIsTheFallback() {
        // GET /Episode/{id}/IntroSkipperSegments on older plugin versions, seconds.
        val json = """
            {"Introduction":{"EpisodeId":"e1","Valid":true,"IntroStart":12.5,"IntroEnd":101.0,"ShowSkipPromptAt":7.5},
             "Credits":{"EpisodeId":"e1","Valid":false,"IntroStart":0,"IntroEnd":0}}
        """.trimIndent()
        val dto = AppJson.decodeFromString(IntroSkipperDto.serializer(), json)
        assertEquals(listOf(Segment(SegmentKind.Intro, 12_500, 101_000)), segmentsFromIntroSkipper(dto))
    }

    @Test
    fun glitchesAndEmptyRangesAreDropped() {
        val tiny = MediaSegmentDto(type = "Intro", startTicks = 0, endTicks = 10_000_000) // one second
        val backwards = MediaSegmentDto(type = "Outro", startTicks = 50_000_000, endTicks = 0)
        assertTrue(segmentsFromMediaSegments(listOf(tiny, backwards)).isEmpty())
    }

    @Test
    fun aSegmentContainsItsStartButNotItsEnd() {
        val intro = Segment(SegmentKind.Intro, 10_000, 90_000)
        assertTrue(10_000L in intro)
        assertTrue(89_999L in intro)
        assertFalse(90_000L in intro)
        assertFalse(9_999L in intro)
    }
}
