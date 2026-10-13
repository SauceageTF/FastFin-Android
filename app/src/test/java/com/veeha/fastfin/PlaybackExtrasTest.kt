package com.veeha.fastfin

import com.veeha.fastfin.data.AppJson
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.ItemStream
import com.veeha.fastfin.data.SUBTITLES_OFF_PREF
import com.veeha.fastfin.data.TrickplayInfo
import com.veeha.fastfin.data.UserData
import com.veeha.fastfin.data.UserOverride
import com.veeha.fastfin.data.withOverride
import com.veeha.fastfin.playback.SUBTITLES_OFF
import com.veeha.fastfin.playback.TrackPreferences
import com.veeha.fastfin.playback.TrickplaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Language preferences, trickplay sheets and optimistic watched/favourite. */
class PlaybackExtrasTest {

    // An anime episode: Japanese and English audio, full and signs-only English subtitles.
    private val streams = listOf(
        ItemStream(0, "Video"),
        ItemStream(1, "Audio", "jpn", isDefault = true),
        ItemStream(2, "Audio", "eng"),
        ItemStream(3, "Subtitle", "eng", isForced = true),
        ItemStream(4, "Subtitle", "eng"),
        ItemStream(5, "Subtitle", "fre"),
    )

    @Test
    fun languageCodesAgreeAcrossSpellings() {
        assertTrue(TrackPreferences.sameLanguage("fre", "fra"))
        assertTrue(TrackPreferences.sameLanguage("ger", "deu"))
        assertTrue(TrackPreferences.sameLanguage("en", "eng"))
        assertTrue(TrackPreferences.sameLanguage("ENG", "eng"))
        assertFalse(TrackPreferences.sameLanguage("eng", "jpn"))
        assertFalse(TrackPreferences.sameLanguage(null, "eng"))
    }

    @Test
    fun preferencesPickStreamsByLanguage() {
        val selection = TrackPreferences.select(streams, audio = "eng", subtitle = "eng")
        assertEquals(2, selection.audio)
        // Full subtitles over the forced "signs and songs" track.
        assertEquals(4, selection.subtitle)
        assertEquals(5, TrackPreferences.select(streams, null, "fra").subtitle)
    }

    @Test
    fun noPreferenceOrNoMatchLeavesTheServerToChoose() {
        val none = TrackPreferences.select(streams, null, null)
        assertNull(none.audio)
        assertNull(none.subtitle)
        val missing = TrackPreferences.select(streams, audio = "kor", subtitle = "kor")
        assertNull(missing.audio)
        assertNull(missing.subtitle)
    }

    @Test
    fun subtitlesOffIsExplicit() {
        assertEquals(SUBTITLES_OFF, TrackPreferences.select(streams, null, SUBTITLES_OFF_PREF).subtitle)
    }

    @Test
    fun trickplayFindsTheRightCell() {
        val info = TrickplayInfo(width = 320, height = 180, tileWidth = 10, tileHeight = 10, thumbnailCount = 250, interval = 10_000)
        val source = TrickplaySource("item", "src", info, "http://jf:8096")
        // 0:00 is the first cell of sheet 0.
        val first = source.locate(0)
        assertEquals("http://jf:8096/Videos/item/Trickplay/320/0.jpg?mediaSourceId=src", first.sheetUrl)
        assertEquals(0 to 0, first.x to first.y)
        // Thumbnail 123 (20:30): sheet 1, cell 23, row 2, column 3.
        val later = source.locate(1_230_000)
        assertTrue(later.sheetUrl.endsWith("/1.jpg?mediaSourceId=src"))
        assertEquals(3 * 320 to 2 * 180, later.x to later.y)
        // Past the end clamps to the last thumbnail rather than a missing one.
        assertTrue(source.locate(10_000_000).sheetUrl.endsWith("/2.jpg?mediaSourceId=src"))
    }

    @Test
    fun trickplayIsReadFromTheItem() {
        val json = """
            {"Id":"e1","Type":"Episode","Trickplay":{"src1":{
              "320":{"Width":320,"Height":180,"TileWidth":10,"TileHeight":10,"ThumbnailCount":140,"Interval":10000},
              "640":{"Width":640,"Height":360,"TileWidth":10,"TileHeight":10,"ThumbnailCount":140,"Interval":10000}}}}
        """.trimIndent()
        val item = AppJson.decodeFromString(Item.serializer(), json)
        val source = TrickplaySource.of(item, "src1", "http://jf")!!
        assertEquals(320, source.info.width)
        assertNull(TrickplaySource.of(item.copy(trickplay = null), "src1", "http://jf"))
    }

    @Test
    fun overridesShowTheLatestTap() {
        val item = Item("e1", userData = UserData(playbackPositionTicks = 9_000_000_000, playedPercentage = 40.0))
        val watched = item.withOverride(UserOverride(played = true))
        assertTrue(watched.userData!!.played)
        assertNull(watched.userData.playedPercentage) // no half-watched bar on a watched episode
        val favourite = item.withOverride(UserOverride(favorite = true))
        assertTrue(favourite.userData!!.isFavorite)
        assertEquals(40.0, favourite.userData.playedPercentage!!, 0.0) // progress untouched
        assertEquals(item, item.withOverride(null))
    }
}
