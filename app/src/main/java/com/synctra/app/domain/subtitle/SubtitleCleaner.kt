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
        """(?i)(https?://\S+|\bwww\.[a-z0-9-]+(?:\.[a-z0-9-]+)+|\b[a-z0-9-]{2,}\.(com|org|net|xyz|info|vip|site)\b|\b[a-z0-9-]+(?:\.[a-z0-9-]+)+\.(id|tv|io|co|me|cc|to)\b|\b[a-z0-9-]+\.(id|tv|io|co|me|cc|to)/\S+)"""
    )

    private val shortAcronymCreditRegex = Regex("""(?i)\bidfl\b""")

    private const val LONG_BANNER_INTRO_WINDOW_MS = 3L * 60L * 1000L
    private const val LONG_BANNER_OUTRO_WINDOW_MS = 5L * 60L * 1000L
    private const val LONG_BANNER_MIN_OUTRO_MEDIA_MS = 10L * 60L * 1000L

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
        "yts.mx",
        "yts.lt",
        "yts.ag",
        "yts.am",
        "yts.rs",
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
        "download film",
        "nonton film gratis",
        "nonton film online",
        "nonton film sub",
        "kunjungi situs",
        "pasang iklan",
        "iklan di sini",
        "slot iklan",
        "open iklan",
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
        val timelineEndMs = maxOf(mediaDurationMs, parsed.allCues.maxOfOrNull { it.endMs } ?: 0L)

        val kept = mutableListOf<SubtitleCue>()

        for (cue in parsed.allCues) {
            val adReason = detectAdOrCreditReason(cue, timelineEndMs)
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
            parsed.allCues.filterNot { detectAdOrCreditReason(it, timelineEndMs) != null || isNonSpeechSdh(it.cleanText) }
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

    internal fun detectAdOrCreditReason(cue: SubtitleCue, timelineEndMs: Long = 0L): FlagReason? {
        val lower = cue.cleanText.lowercase()
        if (urlPattern.containsMatchIn(lower)) return FlagReason.URL
        if (adOrCreditKeywords.any { lower.contains(it) } || shortAcronymCreditRegex.containsMatchIn(lower)) {
            return FlagReason.CREDIT
        }
        val inIntroWindow = cue.startMs < LONG_BANNER_INTRO_WINDOW_MS
        val inOutroWindow = timelineEndMs >= LONG_BANNER_MIN_OUTRO_MEDIA_MS &&
            cue.endMs > timelineEndMs - LONG_BANNER_OUTRO_WINDOW_MS
        if (cue.durationMs > 14_000L && cue.cleanText.length < 120 && (inIntroWindow || inOutroWindow)) {
            return FlagReason.LONG_BANNER
        }
        return null
    }

    internal fun isAdOrCredit(cue: SubtitleCue, timelineEndMs: Long = 0L): Boolean =
        detectAdOrCreditReason(cue, timelineEndMs) != null

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
