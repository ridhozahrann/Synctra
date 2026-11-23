package com.synctra.app

import com.synctra.app.domain.model.DriftAssessment
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.FlagReason
import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SubtitleFormat
import com.synctra.app.domain.subtitle.SrtExporter
import com.synctra.app.domain.subtitle.SubtitleCleaner
import com.synctra.app.domain.subtitle.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtExporterTest {

    @Test
    fun exportsShiftedSrtRespectingUserKeptAndExcludedAds() {
        val cues = listOf(
            SubtitleCue(1, 2_000, 6_000, "Terjemahan oleh Lebah Ganteng", "Terjemahan oleh Lebah Ganteng"),
            SubtitleCue(2, 10_000, 14_000, "Visit www.example.com", "Visit www.example.com"),
            SubtitleCue(3, 30_000, 33_000, "Halo, apa kabar?", "Halo, apa kabar?"),
            SubtitleCue(4, 35_000, 38_500, "Baik, ayo jalan.", "Baik, ayo jalan."),
        )
        val cleaned = SubtitleCleaner.clean(ParsedSubtitleFile(SubtitleFormat.SRT, cues, "UTF-8"))
        assertEquals(2, cleaned.flaggedAdOrCreditCues.size)
        assertEquals(FlagReason.CREDIT, cleaned.flaggedAdOrCreditCues[0].reason)
        assertEquals(FlagReason.URL, cleaned.flaggedAdOrCreditCues[1].reason)

        // User confirms removing cue #1 (credit), but REJECTS removing cue #2
        val exported = SrtExporter.exportSrt(
            allCues = cues,
            offsetMs = 2_400L,
            excludedCueIndices = setOf(1),
        )

        val reparsed = SubtitleParser.parse(exported.toByteArray(Charsets.UTF_8), "out.srt")
        assertEquals(3, reparsed.allCues.size)
        assertEquals("Visit www.example.com", reparsed.allCues[0].cleanText)
        assertEquals(12_400L, reparsed.allCues[0].startMs)
        assertEquals(32_400L, reparsed.allCues[1].startMs)
        assertEquals(37_400L, reparsed.allCues[2].startMs)
    }

    @Test
    fun appliesLinearDriftCorrectionAcrossMovieTimeline() {
        val cues = listOf(
            SubtitleCue(1, 600_000L, 603_000L, "Awal film", "Awal film"),
            SubtitleCue(2, 4_200_000L, 4_203_000L, "Satu jam kemudian", "Satu jam kemudian"),
        )
        // Drift of +3600 ms/hour anchored at t=2_400_000ms with offset +1000ms:
        // At t=600_000ms (-0.5h): offset = 1000 - 1800 = -800ms -> start = 599_200ms
        // At t=4_200_000ms (+0.5h): offset = 1000 + 1800 = +2800ms -> start = 4_202_800ms
        val drift = DriftAssessment(
            severity = DriftSeverity.MILD,
            driftMsPerHour = 3_600.0,
            spreadMs = 3_600L,
            suspectedCause = null,
            anchorTimeMs = 2_400_000L,
            anchorOffsetMs = 1_000L,
        )

        val exported = SrtExporter.exportSrt(
            allCues = cues,
            offsetMs = 1_000L,
            applyDriftCorrection = true,
            drift = drift,
        )

        val reparsed = SubtitleParser.parse(exported.toByteArray(Charsets.UTF_8), "drift.srt")
        assertEquals(2, reparsed.allCues.size)
        assertEquals(599_200L, reparsed.allCues[0].startMs)
        assertEquals(4_202_800L, reparsed.allCues[1].startMs)
        assertTrue(SrtExporter.suggestedOutputFileName("Inception.2010.ass") == "Inception.2010.synced.srt")
    }
}
