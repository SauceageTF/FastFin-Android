package com.veeha.fastfin.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit

/** Languages chosen for one show: audio, and subtitles (a language or [SUBTITLES_OFF_PREF]). */
@Immutable
data class TrackChoice(val audio: String? = null, val subtitle: String? = null)

/**
 * Per-show audio and subtitle choices. Picking Japanese audio with English
 * subtitles on one episode carries to the next, autoplay included, and wins
 * over the global language settings for that show only.
 */
class TrackMemory(context: Context) {
    private val prefs = context.getSharedPreferences("fastfin.tracks", Context.MODE_PRIVATE)

    fun get(seriesId: String): TrackChoice? {
        val audio = prefs.getString("a:$seriesId", null)
        val subtitle = prefs.getString("s:$seriesId", null)
        return if (audio == null && subtitle == null) null else TrackChoice(audio, subtitle)
    }

    fun rememberAudio(seriesId: String, language: String) = prefs.edit { putString("a:$seriesId", language) }

    fun rememberSubtitle(seriesId: String, languageOrOff: String) = prefs.edit { putString("s:$seriesId", languageOrOff) }

    fun clear() = prefs.edit { clear() }
}
