package com.synctra.app.domain.subtitle

import com.synctra.app.domain.model.CleanedSubtitleSet
import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SubtitleCue

/**
 * Filters out non-dialogue subtitle entries (translator credits, website ads/watermarks,
 * hearing-impaired SDH sound descriptions, music lyrics, and optional intro/outro edges)
 * so sync correlation relies purely on genuine spoken dialogue.
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

    // Whole cue is purely bracketed sound effect like [music playing], (sighs), (tense music)
    private val pureBracketSdhRegex = Regex("""^[\s\-–—]*(\[[^]]+]|(\([^)]+\))|\*[^*]+\*)[\s\-–—.,!?]*$""")

    // Music symbols
    private val musicSymbolRegex = Regex("""[♪♫♬♩]""")

    // Pure punctuation or numbers
    private val hasLetterRegex = Regex("""\p{L}""")

    /**
     * Cleans parsed subtitles for alignment.
     *
     * @param parsed Parsed subtitle file.
     * @param mediaDurationMs Known media duration in ms (used to exclude extreme intro/outro edges on long films).
     *                        Pass 0L if duration is unknown yet.
     */
    fun clean(parsed: ParsedSubtitleFile, mediaDurationMs: Long = 0L): CleanedSubtitleSet {
        var adCreditCount = 0
        var sdhCount = 0
        var edgeCount = 0

        val applyEdgeTrim = mediaDurationMs >= 15L * 60L * 1000L // Only trim edges on videos >= 15 min
        val introGuardMs = 35_000L
        val outroGuardMs = if (applyEdgeTrim) mediaDurationMs - 60_000L else Long.MAX_VALUE

        val kept = mutableListOf<SubtitleCue>()

        for (cue in parsed.allCues) {
            // 1. Check ads / credits / URLs
            if (isAdOrCredit(cue)) {
                adCreditCount++
                continue
            }

            // 2. Check SDH / music / non-speech cues
            if (isNonSpeechSdh(cue.cleanText)) {
                sdhCount++
                continue
            }

            // 3. Check extreme intro/outro edges where studio logos / credits live
            if (applyEdgeTrim && (cue.endMs <= introGuardMs || cue.startMs >= outroGuardMs)) {
                edgeCount++
                continue
            }

            kept.add(cue)
        }

        // Safety fallback: if filtering was too aggressive (e.g. very short clip), fall back to non-ad cues
        val finalDialogue = if (kept.size >= 5) {
            kept
        } else {
            edgeCount = 0
            parsed.allCues.filterNot { isAdOrCredit(it) || isNonSpeechSdh(it.cleanText) }
        }

        return CleanedSubtitleSet(
            format = parsed.format,
            allCues = parsed.allCues,
            dialogueCues = finalDialogue,
            filteredAdOrCreditCount = adCreditCount,
            filteredSdhCount = sdhCount,
            filteredEdgeCount = edgeCount,
        )
    }

    internal fun isAdOrCredit(cue: SubtitleCue): Boolean {
        val lower = cue.cleanText.lowercase()
        if (urlPattern.containsMatchIn(lower)) return true
        if (adOrCreditKeywords.any { lower.contains(it) }) return true

        // Suspiciously long static banner (> 14 seconds) is almost always a credit/watermark/disclaimer
        if (cue.durationMs > 14_000L && cue.cleanText.length < 120) return true

        return false
    }

    internal fun isNonSpeechSdh(cleanText: String): Boolean {
        val trimmed = cleanText.trim()
        if (trimmed.isEmpty()) return true
        if (!hasLetterRegex.containsMatchIn(trimmed)) return true
        if (musicSymbolRegex.containsMatchIn(trimmed)) return true

        // Check if every line is a pure SDH bracketed description, e.g. "[gunshots]\n(screaming)"
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty() && lines.all { pureBracketSdhRegex.matches(it) }) {
            return true
        }
        return false
    }
}
