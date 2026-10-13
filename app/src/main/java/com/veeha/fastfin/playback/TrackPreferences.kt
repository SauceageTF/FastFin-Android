package com.veeha.fastfin.playback

import com.veeha.fastfin.data.ItemStream
import com.veeha.fastfin.data.SUBTITLES_OFF_PREF
import java.util.Locale

/**
 * Turns language preferences into stream indices before playback starts, so
 * the server negotiates (and, if it must, transcodes) the right tracks the
 * first time instead of switching after.
 */
object TrackPreferences {

    /** `audio` and `subtitle` are language codes; `subtitle` may be [SUBTITLES_OFF_PREF]. Null leaves the server's choice. */
    fun select(streams: List<ItemStream>, audio: String?, subtitle: String?): Selection {
        val audioIndex = audio?.let { language ->
            val matches = streams.filter { it.type == "Audio" && sameLanguage(it.language, language) }
            (matches.firstOrNull { it.isDefault } ?: matches.firstOrNull())?.index
        }
        val subtitleIndex = when (subtitle) {
            null -> null
            SUBTITLES_OFF_PREF -> SUBTITLES_OFF
            else -> {
                val matches = streams.filter { it.type == "Subtitle" && sameLanguage(it.language, subtitle) }
                // Full subtitles over "signs and songs" forced tracks.
                (matches.firstOrNull { !it.isForced } ?: matches.firstOrNull())?.index
            }
        }
        return Selection(audio = audioIndex, subtitle = subtitleIndex)
    }

    fun sameLanguage(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return normalize(a) == normalize(b)
    }

    /** One spelling per language: 639-1 ("en"), 639-2/B ("fre") and 639-2/T ("fra") all agree. */
    fun normalize(code: String): String {
        val lower = code.trim().lowercase(Locale.ROOT).substringBefore('-')
        val three = if (lower.length == 2) runCatching { Locale.forLanguageTag(lower).isO3Language }.getOrNull() ?: lower else lower
        return BIBLIOGRAPHIC[three] ?: three
    }

    private val BIBLIOGRAPHIC = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
        "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
        "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
        "tib" to "bod", "wel" to "cym",
    )
}
