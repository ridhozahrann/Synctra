package com.synctra.app

import com.synctra.app.domain.model.FlagReason
import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SubtitleFormat
import com.synctra.app.domain.subtitle.SubtitleCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun avoidsFalsePositivesOnOrdinaryDialogueAndMidMovieLongCues() {
        val timelineEndMs = 5_400_000L // 90 min movie
        val innocentDialogue = listOf(
            cue(1, 600_000, 603_000, "Let's go.to the station right now."),
            cue(2, 610_000, 613_000, "Please tell.me what happened."),
            cue(3, 620_000, 623_000, "Aku paling benci iklan di televisi."),
            cue(4, 630_000, 633_000, "Cuma iklan sebentar kok, tunggu saja."),
            cue(5, 640_000, 643_000, "He showed his id.card at the gate."),
            cue(6, 650_000, 653_000, "Ayo kita menonton film malam ini."),
            cue(7, 2_400_000, 2_418_000, "Aku masih ingat hari itu dengan jelas sekali."),
        )

        for (c in innocentDialogue) {
            assertNull(
                "Ordinary dialogue should NOT be flagged: '${c.cleanText}'",
                SubtitleCleaner.detectAdOrCreditReason(c, timelineEndMs),
            )
        }
    }

    @Test
    fun detectsTrueUrlsAdPhrasesAndIntroOutroLongBanners() {
        val timelineEndMs = 5_400_000L // 90 min movie
        val expectedFlags = listOf(
            cue(1, 500_000, 504_000, "Kunjungi www.subindo.to sekarang") to FlagReason.URL,
            cue(2, 510_000, 514_000, "Join t.me/subfilmindo") to FlagReason.URL,
            cue(3, 520_000, 524_000, "Download di sub.film.id") to FlagReason.URL,
            cue(4, 530_000, 534_000, "OpenSubtitles.org") to FlagReason.URL,
            cue(5, 540_000, 544_000, "Mau pasang iklan murah? Hubungi kami") to FlagReason.CREDIT,
            cue(6, 550_000, 554_000, "Official YTS.MX release") to FlagReason.CREDIT,
            cue(7, 560_000, 564_000, "[IDFL] Subtitle") to FlagReason.CREDIT,
            cue(8, 10_000, 28_000, "Selamat menonton film ini bersama keluarga") to FlagReason.LONG_BANNER,
            cue(9, 5_200_000, 5_218_000, "Terima kasih telah menonton sampai akhir") to FlagReason.LONG_BANNER,
        )

        for ((c, expected) in expectedFlags) {
            assertEquals(
                "Expected $expected for '${c.cleanText}'",
                expected,
                SubtitleCleaner.detectAdOrCreditReason(c, timelineEndMs),
            )
        }
    }

    private fun cue(idx: Int, start: Long, end: Long, text: String) =
        SubtitleCue(idx, start, end, text, text)
}
