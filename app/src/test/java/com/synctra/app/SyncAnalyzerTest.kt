package com.synctra.app

import com.synctra.app.domain.model.CleanedSubtitleSet
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.ExportVerificationResult
import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SubtitleFormat
import com.synctra.app.domain.model.SyncResultSource
import com.synctra.app.domain.model.WindowSyncResult
import com.synctra.app.domain.sync.SpeedMatchResult
import com.synctra.app.domain.sync.SyncAnalyzer
import com.synctra.app.domain.sync.SyncDiagnosticsFormatter
import com.synctra.app.domain.sync.WindowPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SyncAnalyzerTest {

    private val dummySubtitles = CleanedSubtitleSet(
        format = SubtitleFormat.SRT,
        allCues = List(20) { i ->
            val t = 1_800_000L + i * 4_000L
            SubtitleCue(i + 1, t, t + 2_000L, "Line $i", "Line $i")
        },
        dialogueCues = List(20) { i ->
            val t = 1_800_000L + i * 4_000L
            SubtitleCue(i + 1, t, t + 2_000L, "Line $i", "Line $i")
        },
        filteredAdOrCreditCount = 2,
        filteredSdhCount = 1,
        filteredEdgeCount = 0,
    )

    @Test
    fun rejectsNoisyOutlierWindowAndReturnsAccurateConsensus() {
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_000_000L, 90_000L), offsetMs = 2_420L, confidence = 0.88f, peakProminence = 6.2f, speechRatio = 0.42f),
            WindowSyncResult(SampleWindow(1, "Tengah", 2_700_000L, 90_000L), offsetMs = -9_500L, confidence = 0.25f, peakProminence = 1.9f, speechRatio = 0.10f),
            WindowSyncResult(SampleWindow(2, "Akhir", 4_300_000L, 90_000L), offsetMs = 2_380L, confidence = 0.84f, peakProminence = 5.8f, speechRatio = 0.39f),
        )

        val result = SyncAnalyzer.aggregate(
            windowResults = windows,
            cleanedSubtitles = dummySubtitles,
            audioCodecUsed = "AC-3",
            usedFfmpegDecoder = true,
            analysisDurationMs = 4_200L,
        )

        assertTrue("Expected consensus near +2400ms, got ${result.offsetMs}", abs(result.offsetMs - 2_400L) <= 50L)
    }

    @Test
    fun detectsSevereFpsDriftBetween23976And25Fps() {
        // 23.976 vs 25 fps => ~153.7s drift per hour (~42.7ms per second)
        // Over 1 hour (from t=0.3h to t=1.3h), offset shifts by ~153,000 ms
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_080_000L, 90_000L), offsetMs = 1_000L, confidence = 0.85f, peakProminence = 5.5f, speechRatio = 0.40f),
            WindowSyncResult(SampleWindow(1, "Tengah", 2_880_000L, 90_000L), offsetMs = 77_500L, confidence = 0.85f, peakProminence = 5.5f, speechRatio = 0.40f),
            WindowSyncResult(SampleWindow(2, "Akhir", 4_680_000L, 90_000L), offsetMs = 154_000L, confidence = 0.85f, peakProminence = 5.5f, speechRatio = 0.40f),
        )

        val result = SyncAnalyzer.aggregate(
            windowResults = windows,
            cleanedSubtitles = dummySubtitles,
            audioCodecUsed = "AAC",
            usedFfmpegDecoder = false,
            analysisDurationMs = 3_800L,
        )

        assertEquals(DriftSeverity.SEVERE, result.drift.severity)
        assertEquals(com.synctra.app.domain.model.DriftCauseKey.FPS_23976_VS_25, result.drift.causeKey)
        assertNotNull(result.drift.suspectedCause)
        assertTrue(result.drift.suspectedCause!!.contains("25") && result.drift.suspectedCause!!.contains("23.976"))
    }

    @Test
    fun disagreeingWindowsTrustStrongestPeakOverWeakMedian() {
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_200_000L, 90_000L), offsetMs = -40_000L, confidence = 0.34f, peakProminence = 2.0f, speechRatio = 0.25f),
            WindowSyncResult(SampleWindow(1, "Tengah", 3_000_000L, 90_000L), offsetMs = -18_500L, confidence = 0.36f, peakProminence = 2.1f, speechRatio = 0.28f),
            WindowSyncResult(SampleWindow(2, "Akhir", 4_800_000L, 90_000L), offsetMs = 4_000L, confidence = 0.78f, peakProminence = 6.2f, speechRatio = 0.45f),
        )

        val consensus = SyncAnalyzer.computeConsensusOffset(windows)
        assertEquals("Strongest peak (+4000ms) must win over weak median (-18500ms)", 4_000L, consensus)
    }

    @Test
    fun jointPooledResultSkipsAgreementBoostCapsConfidenceAndFlagsSource() {
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_200_000L, 90_000L), offsetMs = 1_500L, confidence = 0.72f, peakProminence = 4.5f, speechRatio = 0.35f),
            WindowSyncResult(SampleWindow(1, "Tengah", 3_000_000L, 90_000L), offsetMs = 1_520L, confidence = 0.70f, peakProminence = 4.4f, speechRatio = 0.38f),
            WindowSyncResult(SampleWindow(2, "Akhir", 4_500_000L, 90_000L), offsetMs = 1_490L, confidence = 0.74f, peakProminence = 4.6f, speechRatio = 0.36f),
        )

        val standard = SyncAnalyzer.aggregate(windows, dummySubtitles, "AAC", false, 1_000L, usedJointPooling = false)
        val pooled = SyncAnalyzer.aggregate(windows, dummySubtitles, "AAC", false, 1_000L, usedJointPooling = true)

        assertEquals(SyncResultSource.STANDARD, standard.source)
        assertEquals(SyncResultSource.JOINT_POOLED, pooled.source)
        assertTrue("Joint pooled confidence must be capped <= 0.75, got ${pooled.confidence}", pooled.confidence <= 0.75f)
        assertTrue("Joint pooled must not receive agreementBoost above standard", pooled.confidence < standard.confidence)
    }

    @Test
    fun adaptiveExtraWindowsTriggerOnlyOnLowConfidenceOrDisagreementAndAvoidOverlap() {
        val movieMs = 5_400_000L // 90 min
        val cues = List(400) { i ->
            val t = 120_000L + i * 12_000L
            SubtitleCue(i + 1, t, t + 3_000L, "Line $i", "Line $i")
        }
        val initial = WindowPlanner.planWindows(movieMs, cues)
        assertEquals(3, initial.size)

        val extra = WindowPlanner.planExtraWindows(movieMs, cues, initial, maxExtra = 2)
        assertEquals(2, extra.size)
        for (e in extra) {
            for (existing in initial) {
                val gap = minOf(abs(e.startMs - existing.endMs), abs(existing.startMs - e.endMs))
                val overlaps = e.startMs < existing.endMs + 60_000L && e.endMs + 60_000L > existing.startMs
                assertFalse("Extra window $e must stay >= 60s away from $existing (gap=$gap)", overlaps)
            }
        }
        // Short clip (< 15 min) should never spawn extra windows
        assertTrue(WindowPlanner.planExtraWindows(600_000L, cues, initial.take(2)).isEmpty())

        // Decision helper: agreeing strong windows -> false; weak or disagreeing -> true
        val strongAgree = SpeedMatchResult(
            windowResults = listOf(
                WindowSyncResult(initial[0], 1_000L, 0.80f, 5.5f, 0.4f),
                WindowSyncResult(initial[1], 1_040L, 0.78f, 5.2f, 0.4f),
                WindowSyncResult(initial[2], 980L, 0.75f, 5.0f, 0.4f),
            )
        )
        assertFalse(SyncAnalyzer.shouldSampleExtraWindows(strongAgree, movieMs))

        val weakDisagree = SpeedMatchResult(
            windowResults = listOf(
                WindowSyncResult(initial[0], -12_000L, 0.36f, 2.0f, 0.2f),
                WindowSyncResult(initial[1], 8_500L, 0.38f, 2.1f, 0.2f),
                WindowSyncResult(initial[2], 24_000L, 0.32f, 1.8f, 0.2f),
            )
        )
        assertTrue(SyncAnalyzer.shouldSampleExtraWindows(weakDisagree, movieMs))
    }

    @Test
    fun diagnosticsFormatterOutputsCodecWindowsAndVerificationDetails() {
        val windows = listOf(
            WindowSyncResult(SampleWindow(0, "Awal", 1_000_000L, 90_000L), offsetMs = 1_200L, confidence = 0.85f, peakProminence = 5.4f, speechRatio = 0.41f),
        )
        val result = SyncAnalyzer.aggregate(windows, dummySubtitles, "AC-3 · 5.1", true, 2_300L, audioTrackTitle = "Japanese")
        val verify = ExportVerificationResult(residualOffsetMs = -20L, confidence = 0.88f, hasDrift = false, verifiedCuesCount = 812)

        val text = SyncDiagnosticsFormatter.format(
            result = result,
            videoName = "Movie.mkv",
            subtitleName = "Movie.srt",
            encodingName = "Shift_JIS",
            manualNudgeMs = 100L,
            verification = verify,
        )

        assertTrue(text.contains("Movie.mkv"))
        assertTrue(text.contains("Shift_JIS"))
        assertTrue(text.contains("AC-3 · 5.1"))
        assertTrue(text.contains("FFmpeg"))
        assertTrue(text.contains("+1200 ms"))
        assertTrue(text.contains("Export verify: residual=-20ms"))
    }

    @Test
    fun audioTimeToOriginalSubtitleTimeMatchesSrtExporterWithDriftMsPerHour() {
        val drift = com.synctra.app.domain.model.DriftAssessment(
            severity = DriftSeverity.MILD,
            driftMsPerHour = 3_600.0,
            spreadMs = 3_600L,
            suspectedCause = null,
            anchorTimeMs = 2_400_000L,
            anchorOffsetMs = 1_000L,
            speedRatio = 1.0,
            isLinearStretch = true,
        )
        val origSubMs = 4_200_000L
        val nudgeMs = 250L
        val cue = SubtitleCue(1, origSubMs, origSubMs + 3_000L, "Test", "Test")
        val exported = com.synctra.app.domain.subtitle.SrtExporter.exportSrt(
            allCues = listOf(cue),
            offsetMs = 1_000L,
            applyDriftCorrection = true,
            drift = drift,
            manualNudgeMs = nudgeMs,
        )
        val reparsed = com.synctra.app.domain.subtitle.SubtitleParser.parse(
            exported.toByteArray(Charsets.UTF_8),
            "out.srt",
        )
        val exportedStartMs = reparsed.allCues.first().startMs
        val recoveredOrigMs = SyncAnalyzer.audioTimeToOriginalSubtitleTime(
            audioPosMs = exportedStartMs,
            drift = drift,
            manualNudgeMs = nudgeMs,
        )
        assertTrue(
            "Expected recovered subtitle time near $origSubMs, got $recoveredOrigMs",
            abs(recoveredOrigMs - origSubMs) <= 2L,
        )
    }

    @Test
    fun pickPreviewTimestampRespectsManualNudgeUnderLinearDrift() {
        val drift = com.synctra.app.domain.model.DriftAssessment(
            severity = DriftSeverity.SEVERE,
            driftMsPerHour = 150_000.0,
            spreadMs = 50_000L,
            suspectedCause = null,
            anchorTimeMs = 0L,
            anchorOffsetMs = 0L,
            speedRatio = 25.0 / (24_000.0 / 1001.0),
            isLinearStretch = true,
        )
        val window = SampleWindow(0, "Awal", 1_000_000L, 60_000L)
        val wr = WindowSyncResult(window, offsetMs = 42_000L, confidence = 0.85f, peakProminence = 5.0f, speechRatio = 0.4f)
        // Place cues around t_audio = 1_040_000 when nudge = +15_000ms
        val targetSubBase = SyncAnalyzer.audioTimeToOriginalSubtitleTime(1_040_000L, drift, manualNudgeMs = 15_000L)
        val cues = List(4) { i ->
            val s = targetSubBase + i * 1_800L
            SubtitleCue(i + 1, s, s + 1_200L, "Line $i", "Line $i")
        }
        val picked = SyncAnalyzer.pickPreviewTimestamp(
            window = wr,
            offsetMs = 42_000L,
            cues = cues,
            drift = drift,
            manualNudgeMs = 15_000L,
        )
        assertTrue("Expected preview timestamp near 1_040_000ms, got $picked", picked in 1_030_000L..1_045_000L)
    }
}
