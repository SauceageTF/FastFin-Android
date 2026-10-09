package com.veeha.fastfin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.ColorImage
import com.android.resources.ScreenOrientation
import com.veeha.fastfin.data.AccentName
import com.veeha.fastfin.data.ImageUrls
import com.veeha.fastfin.data.Item
import com.veeha.fastfin.data.UserData
import com.veeha.fastfin.ui.BottomTabBar
import com.veeha.fastfin.ui.Layout
import com.veeha.fastfin.ui.LocalImages
import com.veeha.fastfin.ui.LocalLayout
import com.veeha.fastfin.ui.LocalTabBarTop
import com.veeha.fastfin.ui.TopTabBar
import com.veeha.fastfin.ui.components.CarouselRow
import com.veeha.fastfin.ui.player.PlayerControls
import com.veeha.fastfin.ui.player.TrackChip
import com.veeha.fastfin.ui.components.ContinueWatchingCard
import com.veeha.fastfin.ui.components.PosterCard
import com.veeha.fastfin.ui.nav.Tab
import com.veeha.fastfin.ui.screens.Hero
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.FastFinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Visual evidence for the phone and tablet layouts (Spotifast reviews every
 * interface change at representative window sizes). Run with
 * `./gradlew :app:recordPaparazziDebug`; images land in app/src/test/snapshots.
 * Artwork is replaced by flat colours, since there is no server here.
 */
@RunWith(Parameterized::class)
class LayoutScreenshotTest(private val name: String, private val device: DeviceConfig) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun devices() = listOf(
            arrayOf("tablet_landscape", DeviceConfig.PIXEL_C),
            arrayOf("phone_portrait", DeviceConfig.PIXEL_5),
            arrayOf(
                "phone_landscape",
                DeviceConfig.PIXEL_5.copy(screenWidth = 2340, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
            ),
        )
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, theme = "android:Theme.Material.NoActionBar")

    private val films = listOf(
        film("1", "Dune: Part Two", 2024, "PG-13", listOf("Science Fiction", "Adventure")),
        film("2", "The Grand Budapest Hotel", 2014, "R", listOf("Comedy", "Drama")),
        film("3", "Arrival", 2016, "PG-13", listOf("Drama", "Mystery")),
    )

    private fun film(id: String, name: String, year: Int, rating: String, genres: List<String>, played: Double? = null) = Item(
        id = id, name = name, type = "Movie", productionYear = year, runTimeTicks = 99_000_000_000, officialRating = rating,
        genres = genres, imageTags = mapOf("Primary" to "p$id"), backdropImageTags = listOf("b$id"),
        overview = "A sweeping story told across three generations, as one family discovers that the future " +
            "they were promised was never theirs to keep.",
        userData = played?.let { UserData(playbackPositionTicks = 30_000_000_000, playedPercentage = it) },
    )

    @OptIn(ExperimentalCoilApi::class)
    private val artwork = AsyncImagePreviewHandler { request ->
        val url = request.data.toString()
        val tone = when {
            "Backdrop" in url -> 0xFF3A4A5E.toInt()
            else -> listOf(0xFF7A4A33, 0xFF2F5B57, 0xFF5A3E6E, 0xFF6B5B2E)[url.hashCode().mod(4)].toInt()
        }
        ColorImage(tone)
    }

    // The emulated device's configuration is the size Paparazzi renders at.
    @Suppress("ConfigurationScreenWidthHeight")
    @Test
    fun home() {
        paparazzi.snapshot {
            val config = LocalConfiguration.current
            val layout = Layout.of(androidx.compose.ui.unit.DpSize(config.screenWidthDp.dp, config.screenHeightDp.dp))
            @OptIn(ExperimentalCoilApi::class)
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalLayout provides layout,
                LocalImages provides ImageUrls("http://jellyfin.local"),
                LocalAsyncImagePreviewHandler provides artwork,
                LocalTabBarTop provides if (layout.wide) 72.dp else 0.dp,
            ) {
                FastFinTheme(AccentName.Ember) { HomeScene(layout) }
            }
        }
    }

    /** The full-screen HUD over a stand-in frame, bottom transport (the default). */
    @Test
    fun player() = renderPlayer(centered = false)

    /** The same HUD with the "Center" player-controls setting. */
    @Test
    fun playerCentered() = renderPlayer(centered = true)

    // The emulated device's configuration is the size Paparazzi renders at.
    @Suppress("ConfigurationScreenWidthHeight")
    private fun renderPlayer(centered: Boolean) {
        paparazzi.snapshot {
            val config = LocalConfiguration.current
            val layout = Layout.of(androidx.compose.ui.unit.DpSize(config.screenWidthDp.dp, config.screenHeightDp.dp))
            CompositionLocalProvider(LocalLayout provides layout) { FastFinTheme(AccentName.Ember) {
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(androidx.compose.ui.graphics.Color(0xFF3B2A1E), androidx.compose.ui.graphics.Color(0xFF1C2B3A)),
                        ),
                    ),
                ) {
                    PlayerControls(
                        title = "Severance", subtitle = "E4 · The You You Are", hdr = "Dolby Vision",
                        ready = true, isPlaying = true, scrubbing = false,
                        position = { 754_000 }, buffered = { 1_400_000 }, durationMs = 3_120_000,
                        pipSupported = true, centered = centered,
                        onCollapse = {}, onClose = {}, onPip = {}, onSkip = {}, onTogglePlay = {}, onScrub = {}, onScrubEnd = {},
                        trackMenu = { TrackChip("English EAC3  ·  Subtitles off", {}) },
                    )
                }
            } }
        }
    }

    @Composable
    private fun HomeScene(layout: Layout) {
        val resume = listOf(
            film("4", "Blade Runner 2049", 2017, "R", emptyList(), played = 42.0),
            film("5", "Past Lives", 2023, "PG-13", emptyList(), played = 71.0),
        )
        Box(Modifier.fillMaxSize().background(FF.Background)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Hero(films, autoAdvance = false, onPrimary = {}, onInfo = {}, onSearch = {}) }
                item {
                    Spacer(Modifier.height(22.dp))
                    CarouselRow("Continue Watching") { items(resume) { ContinueWatchingCard(it, onClick = {}) } }
                }
                item {
                    Spacer(Modifier.height(30.dp))
                    CarouselRow("New in Movies", onSeeAll = {}) { items(films + resume + films) { PosterCard(it, onClick = {}) } }
                }
            }
            if (layout.wide) TopTabBar(Tab.Home, {}, Modifier.align(Alignment.TopCenter))
            else BottomTabBar(Tab.Home, {}, Modifier.align(Alignment.BottomCenter))
        }
    }
}
