package com.synctra.app.domain.subtitle

import com.synctra.app.domain.model.CleanedSubtitleSet
import com.synctra.app.domain.model.FlagReason
import com.synctra.app.domain.model.FlaggedSubtitleCue
import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SubtitleCue

/**
 * Filters out non-dialogue subtitle entries (translator credits, website ads/watermarks,
 * hearing-impaired SDH sound descriptions, music lyrics, and optional intro/outro edges)
 * so sync correlation relies purely on genuine spoken dialogue.
 *
 * Also retains the exact list of flagged cues with reasons so users can review and override
 * which lines are kept or deleted when exporting a synced `.srt` file.
 */
object SubtitleCleaner {

    private val urlPattern = Regex(
        """(?i)(https?://|www\.|\b[a-z0-9-]+\.(com|org|net|id|tv|xyz|info|io|co|me|cc|to|vip|site)\b)"""
    )

    private val adOrCreditKeywords = listOf(
        // English credits / ripper / subtitle sites
        "synced and corrected",
        "sync and corrected",
        "synced by",
        "sync by",
        "resync by",
        "re-sync by",
        "subtitles by",
        "subtitle by",
        "captioning by",
        "captioned by",
        "translated by",
        "translation by",
        "encoded by",
        "ripped by",
        "corrected by",
        "timing by",
        "opensubtitles",
        "subscene",
        "addic7ed",
        "yify",
        "yts.",
        "rarbg",
        "advertise your product",
        "support us and become",
        "become vip member",
        "remove all ads",
        "download movie",
        "watch online",
        "best watched using",
        "api.opensubtitles",
        // Indonesian translator credits / ads
        "terjemahan oleh",
        "diterjemahkan oleh",
        "alih bahasa",
        "subtitle indonesia oleh",
        "sub indo oleh",
        "disinkronkan oleh",
        "lebah ganteng",
        "pein akatsuki",
        "kakek salep",
        "idfl",
        "download film",
        "nonton film",
        "kunjungi situs",
        "iklan",
        "saweria",
        "trakteer",
        "donasi",
        "telegram",
        "t.me/",
        "instagram.com",
        "twitter.com",
        "facebook.com",
        "tiktok.com",
        "follow ig",
        "subscribe channel",
    )

    private val pureBracketSdhRegex = Regex("""^[\s\-–—]*(\[[^]]+]|(\([^)]+\))|\*[^*]+\*)[\s\-–—.,!?]*$""")
    private val musicSymbolRegex = Regex("""[♪♫♬♩]""")
    private val hasLetterRegex = Regex("""\p{L}""")

    fun clean(parsed: ParsedSubtitleFile, mediaDurationMs: Long = 0L): CleanedSubtitleSet {
        var edgeCount = 0
        val flaggedAds = mutableListOf<FlaggedSubtitleCue>()
        val flaggedSdh = mutableListOf<FlaggedSubtitleCue>()

        val applyEdgeTrim = mediaDurationMs >= 15L * 60L * 1000L
        val introGuardMs = 35_000L
        val outroGuardMs = if (applyEdgeTrim) mediaDurationMs - 60_000L else Long.MAX_VALUE

        val kept = mutableListOf<SubtitleCue>()

        for (cue in parsed.allCues) {
            val adReason = detectAdOrCreditReason(cue)
            if (adReason != null) {
                flaggedAds.add(FlaggedSubtitleCue(cue, adReason))
                continue
            }

            if (isNonSpeechSdh(cue.cleanText)) {
                flaggedSdh.add(FlaggedSubtitleCue(cue, FlagReason.SDH))
                continue
            }

            if (applyEdgeTrim && (cue.endMs <= introGuardMs || cue.startMs >= outroGuardMs)) {
                edgeCount++
                continue
            }

            kept.add(cue)
        }

        val finalDialogue = if (kept.size >= 5) {
            kept
        } else {
            edgeCount = 0
            parsed.allCues.filterNot { detectAdOrCreditReason(it) != null || isNonSpeechSdh(it.cleanText) }
        }

        return CleanedSubtitleSet(
            format = parsed.format,
            allCues = parsed.allCues,
            dialogueCues = finalDialogue,
            filteredAdOrCreditCount = flaggedAds.size,
            filteredSdhCount = flaggedSdh.size,
            filteredEdgeCount = edgeCount,
            flaggedAdOrCreditCues = flaggedAds,
            flaggedSdhCues = flaggedSdh,
        )
    }

    internal fun detectAdOrCreditReason(cue: SubtitleCue): FlagReason? {
        val lower = cue.cleanText.lowercase()
        if (urlPattern.containsMatchIn(lower)) return FlagReason.URL
        if (adOrCreditKeywords.any { lower.contains(it) }) return FlagReason.CREDIT
        if (cue.durationMs > 14_000L && cue.cleanText.length < 120) return FlagReason.LONG_BANNER
        return null
    }

    internal fun isAdOrCredit(cue: SubtitleCue): Boolean = detectAdOrCreditReason(cue) != null

    internal fun isNonSpeechSdh(cleanText: String): Boolean {
        val trimmed = cleanText.trim()
        if (trimmed.isEmpty()) return true
        if (!hasLetterRegex.containsMatchIn(trimmed)) return true
        if (musicSymbolRegex.containsMatchIn(trimmed)) return true

        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty() && lines.all { pureBracketSdhRegex.matches(it) }) {
            return true
        }
        return false
    }
}
