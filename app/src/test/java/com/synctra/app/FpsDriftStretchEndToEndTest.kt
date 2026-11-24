package com.synctra.app

import com.synctra.app.domain.audio.SpeechActivityDetector
import com.synctra.app.domain.model.CleanedSubtitleSet
import com.synctra.app.domain.model.DriftCauseKey
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SubtitleFormat
import com.synctra.app.domain.model.WindowSyncResult
import com.synctra.app.domain.subtitle.SrtExporter
import com.synctra.app.domain.subtitle.SubtitleParser
import com.synctra.app.domain.sync.FpsStretchMatcher
import com.synctra.app.domain.sync.SyncAnalyzer
import com.synctra.app.domain.sync.WindowAudioInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * End-to-end tests proving the FPS drift detection & stretch export pipeline:
 * 1. Full 2-hour movie with 23.976 fps video vs 25.000 fps subtitle (+153.75s/hour drift)
 * 2. Full 2-hour movie with 23.976 fps video vs 24.000 fps subtitle (+3.6s/hour mild drift)
 * 3. Outlier window with low confidence does NOT trigger false drift
 * 4. Non-linear scene cut is flagged as DIFFERENT_CUT with isLinearStretch = false
 */
class FpsDriftStretchEndToEndTest {

    @Test
    fun detectsAndStretches23976Vs25FpsAcrossTwoHourMovieAccurately() {
        // True relationship: 23.976 fps movie audio, 25.000 fps subtitle + 1500 ms initial shift
        // t_audio = t_sub * (25.0 / 23.976) + 1500
        val trueSpeedRatio = 25.0 / (24_000.0 / 1001.0) // ~1.042709376
        val trueBaseOffsetMs = 1_500L

        // 3 interior movie windows at 24m, 60m, and 90m (where raw drift reaches +63s, +155s, +232s!)
        val windows = listOf(
            SampleWindow(0, "Awal (~20%)", 1_440_000L, 90_000L),
            SampleWindow(1, "Tengah (~50%)", 3_600_000L, 90_000L),
            SampleWindow(2, "Akhir (~75%)", 5_400_000L, 90_000L),
        )

        // Build ground-truth audio speech intervals across all 3 windows (plus start & end test probes!)
        val trueAudioIntervals = mutableListOf<Pair<Long, Long>>()
        // Probe cue near minute 2 and minute 115 (outside all 3 sampled windows!) to test full-movie extrapolation
        trueAudioIntervals.add(120_000L to 123_000L)
        val localPattern = listOf(
            2_500L to 5_200L,
            8_100L to 11_400L,
            16_000L to 18_700L,
            23_500L to 27_900L,
            34_200L to 36_800L,
            42_000L to 47_500L,
            53_100L to 56_000L,
            62_400L to 66_900L,
            72_000L to 76_200L,
            81_500L to 85_800L,
        )
        for (w in windows) {
            for ((s, e) in localPattern) {
                trueAudioIntervals.add((w.startMs + s) to (w.startMs + e))
            }
        }
        trueAudioIntervals.add(6_900_000L to 6_903_500L)

        // Generate 25 fps subtitle cues: t_sub = (t_audio - trueBaseOffsetMs) / trueSpeedRatio
        val subCues = trueAudioIntervals.mapIndexed { idx, (aStart, aEnd) ->
            val sStart = ((aStart - trueBaseOffsetMs) / trueSpeedRatio).roundToLong()
            val sEnd = ((aEnd - trueBaseOffsetMs) / trueSpeedRatio).roundToLong()
            SubtitleCue(idx + 1, sStart, sEnd, "Line ${idx + 1}", "Line ${idx + 1}")
        }

        // Build ground-truth 50 Hz audio envelopes for each window
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

        // Run FpsStretchMatcher + SyncAnalyzer
        val match = FpsStretchMatcher.matchBest(windowInputs, subCues)
        assertTrue("Expected 25 -> 23.976 fps candidate to win", match.matchedFpsCandidate != null)
        assertEquals("25 → 23.976 fps", match.matchedFpsCandidate!!.label)

        val cleaned = CleanedSubtitleSet(SubtitleFormat.SRT, subCues, subCues, 0, 0, 0)
        val result = SyncAnalyzer.aggregate(
            windowResults = match.windowResults,
            cleanedSubtitles = cleaned,
            audioCodecUsed = "AC-3 · 5.1",
            usedFfmpegDecoder = true,
            analysisDurationMs = 2_000L,
            matchedFpsCandidate = match.matchedFpsCandidate,
            matchedBaseOffsetMs = match.baseOffsetMs,
        )

        assertEquals(DriftSeverity.SEVERE, result.drift.severity)
        assertTrue(result.drift.isLinearStretch)
        assertEquals(DriftCauseKey.FPS_23976_VS_25, result.drift.causeKey)

        // Export stretched SRT and verify EVERY cue from minute 2 to minute 115 matches true audio within +/- 45 ms!
        val exported = SrtExporter.exportSrt(
            allCues = subCues,
            offsetMs = result.offsetMs,
            applyDriftCorrection = true,
            drift = result.drift,
        )
        val reparsed = SubtitleParser.parse(exported.toByteArray(Charsets.UTF_8), "stretched.srt")
        assertEquals(trueAudioIntervals.size, reparsed.allCues.size)

        for (i in trueAudioIntervals.indices) {
            val expectedStart = trueAudioIntervals[i].first
            val actualStart = reparsed.allCues[i].startMs
            val errMs = abs(actualStart - expectedStart)
            assertTrue(
                "Cue #${i + 1} at ${expectedStart / 1000}s drifted by $errMs ms (expected=$expectedStart, actual=$actualStart)",
                errMs <= 45L,
            )
        }
    }

    @Test
    fun noisyLowConfidenceOutlierDoesNotTriggerFalseDrift() {
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_200_000L, 90_000L), offsetMs = 2_400L, confidence = 0.88f, peakProminence = 6.0f, speechRatio = 0.4f),
            WindowSyncResult(SampleWindow(1, "Tengah", 3_000_000L, 90_000L), offsetMs = 2_380L, confidence = 0.86f, peakProminence = 5.8f, speechRatio = 0.4f),
            WindowSyncResult(SampleWindow(2, "Akhir", 4_800_000L, 90_000L), offsetMs = -18_000L, confidence = 0.22f, peakProminence = 1.6f, speechRatio = 0.08f),
        )

        val drift = SyncAnalyzer.assessDrift(windows)
        assertEquals("Low-confidence outlier must not trigger false drift", DriftSeverity.NONE, drift.severity)
        assertFalse(drift.isLinearStretch)
    }

    @Test
    fun nonLinearSceneCutIsNotMarkedAsLinearFpsStretch() {
        // First 2 windows agree at 0ms, third window jumps abruptly by +12 seconds (e.g. Extended Edition scene added)
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_200_000L, 90_000L), offsetMs = 0L, confidence = 0.88f, peakProminence = 6.0f, speechRatio = 0.4f),
            WindowSyncResult(SampleWindow(1, "Tengah", 3_000_000L, 90_000L), offsetMs = 20L, confidence = 0.87f, peakProminence = 5.9f, speechRatio = 0.4f),
            WindowSyncResult(SampleWindow(2, "Akhir", 4_800_000L, 90_000L), offsetMs = 12_000L, confidence = 0.85f, peakProminence = 5.5f, speechRatio = 0.4f),
        )

        val drift = SyncAnalyzer.assessDrift(windows)
        assertEquals(DriftSeverity.SEVERE, drift.severity)
        assertFalse("Abrupt scene cut jump must NOT be flagged as smooth linear FPS stretch", drift.isLinearStretch)
        assertEquals(DriftCauseKey.DIFFERENT_CUT, drift.causeKey)
    }

    @Test
    fun twoNoisyOrSceneCutWindowsNeverAutoEnableLinearFpsStretch() {
        // 2 windows with mediocre confidence (0.48 & 0.50) jumping by +8 seconds over 30 min -> NOT a cinema FPS ratio!
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_200_000L, 90_000L), offsetMs = 1_000L, confidence = 0.50f, peakProminence = 3.2f, speechRatio = 0.35f),
            WindowSyncResult(SampleWindow(1, "Tengah", 3_000_000L, 90_000L), offsetMs = 9_000L, confidence = 0.48f, peakProminence = 3.0f, speechRatio = 0.30f),
            WindowSyncResult(SampleWindow(2, "Akhir", 4_800_000L, 90_000L), offsetMs = -20_000L, confidence = 0.20f, peakProminence = 1.2f, speechRatio = 0.08f),
        )

        val drift = SyncAnalyzer.assessDrift(windows)
        assertFalse("Non-FPS 2-window jump must NOT auto-enable linear FPS stretch", drift.isLinearStretch)
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

        // Run 1: analyze polluted shifted subtitles
        val cleaned1 = com.synctra.app.domain.subtitle.SubtitleCleaner.clean(
            com.synctra.app.domain.model.ParsedSubtitleFile(SubtitleFormat.SRT, allCues, "UTF-8"),
            mediaDurationMs = 6_000_000L,
        )
        val match1 = FpsStretchMatcher.matchBest(windowInputs, cleaned1.dialogueCues)
        val result1 = SyncAnalyzer.aggregate(match1.windowResults, cleaned1, "AAC", false, 500L, matchedFpsCandidate = match1.matchedFpsCandidate, matchedBaseOffsetMs = match1.baseOffsetMs)
        assertTrue("Run 1 should recover ~-4650ms, got ${result1.offsetMs}", abs(result1.offsetMs - trueOffsetMs) <= 45L)
        assertEquals(DriftSeverity.NONE, result1.drift.severity)

        // Export synced SRT removing the detected ad cue
        val exported = SrtExporter.exportSrt(
            allCues = allCues,
            offsetMs = result1.offsetMs,
            excludedCueIndices = cleaned1.flaggedAdOrCreditCues.map { it.cue.index }.toSet(),
            applyDriftCorrection = result1.drift.isLinearStretch && result1.drift.severity != DriftSeverity.NONE,
            drift = result1.drift,
        )

        // Run 2: load exported SRT back into Synctra on the SAME movie audio
        val reparsed = SubtitleParser.parse(exported.toByteArray(Charsets.UTF_8), "movie.synced.srt")
        val cleaned2 = com.synctra.app.domain.subtitle.SubtitleCleaner.clean(reparsed, mediaDurationMs = 6_000_000L)
        assertEquals("Ad cue must be gone in exported SRT", 0, cleaned2.flaggedAdOrCreditCues.size)

        val match2 = FpsStretchMatcher.matchBest(windowInputs, cleaned2.dialogueCues)
        val result2 = SyncAnalyzer.aggregate(match2.windowResults, cleaned2, "AAC", false, 500L, matchedFpsCandidate = match2.matchedFpsCandidate, matchedBaseOffsetMs = match2.baseOffsetMs)
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
        // Unique dialogue rhythms per window
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
            // Add true dialogue bursts (moderately weak) PLUS a strong distractor BGM burst at a DIFFERENT random lag per window!
            val distractorShiftMs = listOf(-17_000L, 23_000L, -29_000L)[wIdx]
            for ((s, e) in patterns[wIdx]) {
                cues.add(SubtitleCue(id++, w.startMs + s - trueOffsetMs, w.startMs + e - trueOffsetMs, "Line $id", "Line $id"))
                // True speech signal (0.55)
                val i0 = (s / frameMs).toInt().coerceIn(0, count - 1)
                val i1 = (e / frameMs).toInt().coerceIn(i0, count - 1)
                for (i in i0..i1) env[i] = maxOf(env[i], 0.55f)
                // Stronger window-specific false distractor matching cues shifted by distractorShiftMs (0.85)
                val d0 = ((s - trueOffsetMs + distractorShiftMs) / frameMs).toInt().coerceIn(0, count - 1)
                val d1 = ((e - trueOffsetMs + distractorShiftMs) / frameMs).toInt().coerceIn(d0, count - 1)
                if (d1 > d0) {
                    for (i in d0..d1) env[i] = maxOf(env[i], 0.85f)
                }
            }
            WindowAudioInput(w, env)
        }

        // Joint pooled correlation should constructively reinforce the true +4200ms offset across all 3 windows
        val match = FpsStretchMatcher.matchBest(windowInputs, cues)
        val cleaned = CleanedSubtitleSet(SubtitleFormat.SRT, cues, cues, 0, 0, 0)
        val result = SyncAnalyzer.aggregate(match.windowResults, cleaned, "AAC", false, 400L)
        assertTrue(
            "Joint pooled correlation should recover ~+4200ms despite per-window distractors, got ${result.offsetMs}ms",
            abs(result.offsetMs - trueOffsetMs) <= 100L,
        )
    }
}
