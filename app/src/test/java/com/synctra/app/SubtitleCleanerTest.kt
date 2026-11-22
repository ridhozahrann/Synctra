package com.synctra.app

import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SubtitleFormat
import com.synctra.app.domain.subtitle.SubtitleCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCleanerTest {

    @Test
    fun filtersAdsTranslatorCreditsAndSdhWhileKeepingDialogue() {
        val cues = listOf(
            cue(1, 2_000, 7_000, "Terjemahan oleh Lebah Ganteng"),
            cue(2, 8_000, 14_000, "Download film terbaru di www.examplemovie.xyz"),
            cue(3, 50_000, 53_000, "[Musik dramatis dimulai]"),
            cue(4, 55_000, 58_000, "♪ Oh baby don't go ♪"),
            cue(5, 120_000, 123_000, "Kamu yakin pintu ini aman?"),
            cue(6, 124_000, 127_000, "Tenang saja, aku sudah cek semuanya."),
            cue(7, 128_000, 131_000, "Baik, ayo kita masuk."),
            cue(8, 132_000, 135_000, "Hati-hati di tangga."),
            cue(9, 136_000, 139_000, "Siap, aku di belakangmu."),
            cue(10, 5_350_000, 5_356_000, "Synced and corrected by OpenSubtitles.org"),
        )
        val parsed = ParsedSubtitleFile(SubtitleFormat.SRT, cues, "UTF-8")
        val cleaned = SubtitleCleaner.clean(parsed, mediaDurationMs = 5_400_000L) // 90 min film

        assertEquals(5, cleaned.dialogueCues.size)
        assertEquals(3, cleaned.filteredAdOrCreditCount)
        assertEquals(2, cleaned.filteredSdhCount)
        assertTrue(cleaned.dialogueCues.none { it.cleanText.contains("Lebah") })
        assertTrue(cleaned.dialogueCues.none { it.cleanText.contains("www.") })
        assertTrue(cleaned.dialogueCues.none { it.cleanText.contains("♪") })
    }

    private fun cue(idx: Int, start: Long, end: Long, text: String) =
        SubtitleCue(idx, start, end, text, text)
}
