package com.synctra.app

import com.synctra.app.domain.audio.SpeechActivityDetector
import com.synctra.app.domain.model.CleanedSubtitleSet
import com.synctra.app.domain.model.DriftCauseKey
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SubtitleFormat
import com.synctra.app.domain.model.WindowSyncResult
import com.synctra.app.domain.subtitle.SrtExporter
import com.synctra.app.domain.subtitle.SubtitleCleaner
import com.synctra.app.domain.subtitle.SubtitleParser
import com.synctra.app.domain.sync.SyncAnalyzer
import com.synctra.app.domain.sync.WindowAudioInput
import com.synctra.app.domain.sync.WindowSyncMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SyncPipelineDriftAndExportTest {

    @Test
    fun noisyLowConfidenceOutlierDoesNotTriggerFalseDrift() {
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_200_000L, 90_000L), offsetMs = 2_400L, confidence = 0.88f, peakProminence = 6.0f, speechRatio = 0.4f),
            WindowSyncResult(SampleWindow(1, "Tengah", 3_000_000L, 90_000L), offsetMs = 2_380L, confidence = 0.86f, peakProminence = 5.8f, speechRatio = 0.4f),
            WindowSyncResult(SampleWindow(2, "Akhir", 4_800_000L, 90_000L), offsetMs = -18_000L, confidence = 0.22f, peakProminence = 1.6f, speechRatio = 0.08f),
        )

        val drift = SyncAnalyzer.assessDrift(windows)
        assertEquals("Low-confidence outlier must not trigger false drift", DriftSeverity.NONE, drift.severity)
    }

    @Test
    fun nonLinearSceneCutIsFlaggedAsDifferentCut() {
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_200_000L, 90_000L), offsetMs = 0L, confidence = 0.88f, peakProminence = 6.0f, speechRatio = 0.4f),
            WindowSyncResult(SampleWindow(1, "Tengah", 3_000_000L, 90_000L), offsetMs = 20L, confidence = 0.87f, peakProminence = 5.9f, speechRatio = 0.4f),
            WindowSyncResult(SampleWindow(2, "Akhir", 4_800_000L, 90_000L), offsetMs = 12_000L, confidence = 0.85f, peakProminence = 5.5f, speechRatio = 0.4f),
        )

        val drift = SyncAnalyzer.assessDrift(windows)
        assertEquals(DriftSeverity.SEVERE, drift.severity)
        assertEquals(DriftCauseKey.DIFFERENT_CUT, drift.causeKey)
    }

    @Test
    fun roundTripSaveSrtRemovingAdsAndRecheckReturnsZeroOffset() {
        val trueOffsetMs = -4_650L
        val windows = listOf(
            SampleWindow(0, "Awal", 1_200_000L, 90_000L),
            SampleWindow(1, "Tengah", 3_000_000L, 90_000L),
            SampleWindow(2, "Akhir", 4_500_000L, 90_000L),
        )
        val localPattern = listOf(
            3_000L to 6_200L,
            10_500L to 14_000L,
            21_000L to 24_500L,
            31_000L to 35_500L,
            44_000L to 48_000L,
            56_000L to 60_500L,
            68_000L to 72_200L,
            79_000L to 84_000L,
        )
        val trueAudioIntervals = windows.flatMap { w -> localPattern.map { (s, e) -> (w.startMs + s) to (w.startMs + e) } }
        val adCue = SubtitleCue(1, 5_000L, 12_000L, "Sub indo oleh www.filmgratis.xyz", "Sub indo oleh www.filmgratis.xyz")
        val dialogueCues = trueAudioIntervals.mapIndexed { idx, (aS, aE) ->
            SubtitleCue(idx + 2, aS - trueOffsetMs, aE - trueOffsetMs, "Dialog ${idx + 1}", "Dialog ${idx + 1}")
        }
        val allCues = listOf(adCue) + dialogueCues

        val frameMs = SpeechActivityDetector.FRAME_DURATION_MS
        val windowInputs = windows.map { w ->
            val count = (w.durationMs / frameMs).toInt()
            val env = FloatArray(count)
            for ((aS, aE) in trueAudioIntervals) {
                if (aE <= w.startMs || aS >= w.endMs) continue
                val i0 = ((aS - w.startMs) / frameMs).toInt().coerceIn(0, count - 1)
                val i1 = ((aE - w.startMs) / frameMs).toInt().coerceIn(i0, count - 1)
                for (i in i0..i1) env[i] = 1.0f
            }
            WindowAudioInput(w, env)
        }

        val cleaned1 = SubtitleCleaner.clean(
            ParsedSubtitleFile(SubtitleFormat.SRT, allCues, "UTF-8"),
            mediaDurationMs = 6_000_000L,
        )
        val match1 = WindowSyncMatcher.matchBest(windowInputs, cleaned1.dialogueCues)
        val result1 = SyncAnalyzer.aggregate(
            windowResults = match1.windowResults,
            cleanedSubtitles = cleaned1,
            audioCodecUsed = "AAC",
            usedFfmpegDecoder = false,
            analysisDurationMs = 500L,
            usedJointPooling = match1.usedJointPooling,
        )
        assertTrue("Run 1 should recover ~-4650ms, got ${result1.offsetMs}", abs(result1.offsetMs - trueOffsetMs) <= 45L)
        assertEquals(DriftSeverity.NONE, result1.drift.severity)

        val exported = SrtExporter.exportSrt(
            allCues = allCues,
            offsetMs = result1.offsetMs,
            excludedCueIndices = cleaned1.flaggedAdOrCreditCues.map { it.cue.index }.toSet(),
        )

        val reparsed = SubtitleParser.parse(exported.toByteArray(Charsets.UTF_8), "movie.synced.srt")
        val cleaned2 = SubtitleCleaner.clean(reparsed, mediaDurationMs = 6_000_000L)
        assertEquals("Ad cue must be gone in exported SRT", 0, cleaned2.flaggedAdOrCreditCues.size)

        val match2 = WindowSyncMatcher.matchBest(windowInputs, cleaned2.dialogueCues)
        val result2 = SyncAnalyzer.aggregate(
            windowResults = match2.windowResults,
            cleanedSubtitles = cleaned2,
            audioCodecUsed = "AAC",
            usedFfmpegDecoder = false,
            analysisDurationMs = 500L,
            usedJointPooling = match2.usedJointPooling,
        )
        assertTrue("Run 2 on exported SRT must be synced (~0ms), got ${result2.offsetMs}ms", abs(result2.offsetMs) <= 40L)
        assertTrue("Run 2 must report isAlreadySynced", result2.isAlreadySynced)
        assertEquals(DriftSeverity.NONE, result2.drift.severity)
    }

    @Test
    fun jointPooledCorrelationRecoversSharedOffsetWhenIndividualWindowsAreNoisy() {
        val trueOffsetMs = 4_200L
        val windows = listOf(
            SampleWindow(0, "Awal", 1_200_000L, 90_000L),
            SampleWindow(1, "Tengah", 3_000_000L, 90_000L),
            SampleWindow(2, "Akhir", 4_500_000L, 90_000L),
        )
        val patterns = listOf(
            listOf(4_000L to 7_500L, 18_000L to 22_000L, 41_000L to 45_500L, 67_000L to 71_000L),
            listOf(6_000L to 9_000L, 25_000L to 29_500L, 49_000L to 53_000L, 74_000L to 79_000L),
            listOf(8_000L to 12_000L, 29_000L to 33_000L, 55_000L to 59_500L, 76_000L to 80_500L),
        )
        val cues = mutableListOf<SubtitleCue>()
        var id = 1
        val frameMs = SpeechActivityDetector.FRAME_DURATION_MS
        val windowInputs = windows.mapIndexed { wIdx, w ->
            val count = (w.durationMs / frameMs).toInt()
            val env = FloatArray(count)
            val distractorShiftMs = listOf(-17_000L, 23_000L, -29_000L)[wIdx]
            for ((s, e) in patterns[wIdx]) {
                cues.add(SubtitleCue(id++, w.startMs + s - trueOffsetMs, w.startMs + e - trueOffsetMs, "Line $id", "Line $id"))
                val i0 = (s / frameMs).toInt().coerceIn(0, count - 1)
                val i1 = (e / frameMs).toInt().coerceIn(i0, count - 1)
                for (i in i0..i1) env[i] = maxOf(env[i], 0.55f)
                val d0 = ((s - trueOffsetMs + distractorShiftMs) / frameMs).toInt().coerceIn(0, count - 1)
                val d1 = ((e - trueOffsetMs + distractorShiftMs) / frameMs).toInt().coerceIn(d0, count - 1)
                if (d1 > d0) {
                    for (i in d0..d1) env[i] = maxOf(env[i], 0.85f)
                }
            }
            WindowAudioInput(w, env)
        }

        val match = WindowSyncMatcher.matchBest(windowInputs, cues)
        val cleaned = CleanedSubtitleSet(SubtitleFormat.SRT, cues, cues, 0, 0, 0)
        val result = SyncAnalyzer.aggregate(
            windowResults = match.windowResults,
            cleanedSubtitles = cleaned,
            audioCodecUsed = "AAC",
            usedFfmpegDecoder = false,
            analysisDurationMs = 400L,
            usedJointPooling = match.usedJointPooling,
        )
        assertTrue(
            "Joint pooled correlation should recover ~+4200ms despite per-window distractors, got ${result.offsetMs}ms",
            abs(result.offsetMs - trueOffsetMs) <= 100L,
        )
    }

    @Test
    fun roundTripExportWithManualNudgeReflectsSingleNudgeOffset() {
        val trueOffsetMs = 2_200L
        val manualNudgeMs = 100L
        val windows = listOf(
            SampleWindow(0, "Awal (~20%)", 1_440_000L, 90_000L),
            SampleWindow(1, "Tengah (~50%)", 3_600_000L, 90_000L),
            SampleWindow(2, "Akhir (~75%)", 5_400_000L, 90_000L),
        )
        val localPattern = listOf(
            3_000L to 6_000L,
            10_000L to 13_500L,
            20_000L to 24_000L,
            32_000L to 36_200L,
            45_000L to 49_500L,
            58_000L to 62_000L,
            70_000L to 74_500L,
            80_000L to 84_800L,
        )
        val trueAudioIntervals = windows.flatMap { w -> localPattern.map { (s, e) -> (w.startMs + s) to (w.startMs + e) } }
        val adCue = SubtitleCue(1, 3_000L, 9_000L, "Download film di www.contohfilm.xyz", "Download film di www.contohfilm.xyz")
        val subCues = listOf(adCue) + trueAudioIntervals.mapIndexed { idx, (aStart, aEnd) ->
            SubtitleCue(idx + 2, aStart - trueOffsetMs, aEnd - trueOffsetMs, "Dialog ${idx + 1}", "Dialog ${idx + 1}")
        }

        val frameMs = SpeechActivityDetector.FRAME_DURATION_MS
        val windowInputs = windows.map { w ->
            val count = (w.durationMs / frameMs).toInt()
            val env = FloatArray(count)
            for ((aStart, aEnd) in trueAudioIntervals) {
                if (aEnd <= w.startMs || aStart >= w.endMs) continue
                val i0 = ((aStart - w.startMs) / frameMs).toInt().coerceIn(0, count - 1)
                val i1 = ((aEnd - w.startMs) / frameMs).toInt().coerceIn(i0, count - 1)
                for (i in i0..i1) env[i] = 1.0f
            }
            WindowAudioInput(w, env)
        }

        val cleaned1 = SubtitleCleaner.clean(
            ParsedSubtitleFile(SubtitleFormat.SRT, subCues, "UTF-8"),
            mediaDurationMs = 7_200_000L,
        )
        val match1 = WindowSyncMatcher.matchBest(windowInputs, cleaned1.dialogueCues)
        val result1 = SyncAnalyzer.aggregate(
            windowResults = match1.windowResults,
            cleanedSubtitles = cleaned1,
            audioCodecUsed = "AC-3",
            usedFfmpegDecoder = true,
            analysisDurationMs = 1_000L,
            usedJointPooling = match1.usedJointPooling,
        )

        val exportStats = SrtExporter.exportSrtWithStats(
            allCues = subCues,
            offsetMs = result1.offsetMs + manualNudgeMs,
            excludedCueIndices = cleaned1.flaggedAdOrCreditCues.map { it.cue.index }.toSet(),
        )
        assertEquals(trueAudioIntervals.size, exportStats.writtenCount)
        assertEquals(1, exportStats.excludedCount)
        assertEquals(0, exportStats.droppedByShiftCount)

        val reparsed = SubtitleParser.parse(exportStats.content.toByteArray(Charsets.UTF_8), "movie.synced.srt")
        val cleaned2 = SubtitleCleaner.clean(reparsed, mediaDurationMs = 7_200_000L)
        val match2 = WindowSyncMatcher.matchBest(windowInputs, cleaned2.dialogueCues)
        val result2 = SyncAnalyzer.aggregate(
            windowResults = match2.windowResults,
            cleanedSubtitles = cleaned2,
            audioCodecUsed = "AC-3",
            usedFfmpegDecoder = true,
            analysisDurationMs = 1_000L,
            usedJointPooling = match2.usedJointPooling,
        )

        assertTrue(
            "Expected residual near -100ms (proving nudge applied once), got ${result2.offsetMs}ms",
            abs(result2.offsetMs - (-manualNudgeMs)) <= 50L,
        )
        assertEquals(DriftSeverity.NONE, result2.drift.severity)
    }
}
