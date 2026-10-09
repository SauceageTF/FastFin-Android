package com.veeha.fastfin.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AccentName(val label: String) { Frost("Frost"), Teal("Teal"), Ember("Ember") }

/** Sent to the server as MaxStreamingBitrate/MaxStaticBitrate. Anything above
 * the cap is transcoded down; "Original" lets a 4K remux direct play. */
enum class StreamingQuality(val label: String, val bitrate: Int) {
    Original("Original", 160_000_000),
    High("40 Mbps", 40_000_000),
    Medium("15 Mbps", 15_000_000),
    Low("6 Mbps", 6_000_000),
    Mobile("3 Mbps", 3_000_000),
}

/**
 * How HDR reaches the screen.
 *
 * - Auto: HDR streams play as HDR when both the decoder and the display can
 *   show them; otherwise the server tone-maps to SDR so colours stay right
 *   instead of looking washed out.
 * - Always: send HDR whenever the decoder can, even to a display that does not
 *   report HDR (for TV boxes whose HDMI sink reports late).
 * - Sdr: always tone-map on the server.
 */
enum class HdrMode(val label: String) { Auto("Auto"), Always("Always HDR"), Sdr("Tone-map") }

@Immutable
data class AppSettings(
    val accent: AccentName = AccentName.Ember,
    val quality: StreamingQuality = StreamingQuality.Original,
    val hdr: HdrMode = HdrMode.Auto,
    val autoPip: Boolean = true,
)

/**
 * Settings in SharedPreferences: `getSharedPreferences` starts reading the file
 * on a background thread the moment the store is built in Application.onCreate,
 * so the first frame never waits on disk, and `apply()` writes atomically
 * (temp file + rename) off the main thread.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("fastfin.settings", Context.MODE_PRIVATE)
    private val state by lazy { MutableStateFlow(read()) }

    val flow: StateFlow<AppSettings> get() = state
    val value: AppSettings get() = state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        if (next == state.value) return
        state.value = next
        prefs.edit {
            putString("accent", next.accent.name)
            putString("quality", next.quality.name)
            putString("hdr", next.hdr.name)
            putBoolean("autoPip", next.autoPip)
        }
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            accent = enumOr(prefs.getString("accent", null), defaults.accent),
            quality = enumOr(prefs.getString("quality", null), defaults.quality),
            hdr = enumOr(prefs.getString("hdr", null), defaults.hdr),
            autoPip = prefs.getBoolean("autoPip", defaults.autoPip),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback
}
