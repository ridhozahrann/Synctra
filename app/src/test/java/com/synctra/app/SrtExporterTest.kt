package com.synctra.app

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
    fun appliesConstantOffsetAndGeneratesSyncedFilename() {
        val cues = listOf(
            SubtitleCue(1, 600_000L, 603_000L, "Awal film", "Awal film"),
            SubtitleCue(2, 4_200_000L, 4_203_000L, "Satu jam kemudian", "Satu jam kemudian"),
        )

        val exported = SrtExporter.exportSrt(
            allCues = cues,
            offsetMs = 1_250L,
        )

        val reparsed = SubtitleParser.parse(exported.toByteArray(Charsets.UTF_8), "constant.srt")
        assertEquals(2, reparsed.allCues.size)
        assertEquals(601_250L, reparsed.allCues[0].startMs)
        assertEquals(4_201_250L, reparsed.allCues[1].startMs)
        assertTrue(SrtExporter.suggestedOutputFileName("Inception.2010.ass") == "Inception.2010.synced.srt")
    }

    @Test
    fun reportsAccurateCountsAndDropsNegativeShiftedStubCues() {
        val cues = listOf(
            SubtitleCue(1, 1_000L, 3_000L, "Excluded ad", "Excluded ad"),
            SubtitleCue(2, 2_000L, 4_000L, "Completely before zero", "Completely before zero"),
            SubtitleCue(3, 3_000L, 5_100L, "Tiny 100ms stub after -5s", "Tiny 100ms stub after -5s"),
            SubtitleCue(4, 4_000L, 6_500L, "Clamped valid cue (1.5s left)", "Clamped valid cue (1.5s left)"),
            SubtitleCue(5, 10_000L, 13_000L, "Normal shifted cue", "Normal shifted cue"),
        )

        val stats = SrtExporter.exportSrtWithStats(
            allCues = cues,
            offsetMs = -5_000L,
            excludedCueIndices = setOf(1),
        )

        assertEquals(2, stats.writtenCount)
        assertEquals(1, stats.excludedCount)
        assertEquals(2, stats.droppedByShiftCount)

        val reparsed = SubtitleParser.parse(stats.content.toByteArray(Charsets.UTF_8), "clamped.srt")
        assertEquals(2, reparsed.allCues.size)
        assertEquals("Clamped valid cue (1.5s left)", reparsed.allCues[0].cleanText)
        assertEquals(0L, reparsed.allCues[0].startMs)
        assertEquals(1_500L, reparsed.allCues[0].endMs)
        assertEquals("Normal shifted cue", reparsed.allCues[1].cleanText)
        assertEquals(5_000L, reparsed.allCues[1].startMs)
    }
}
