package com.synctra.app.domain.sync

import com.synctra.app.domain.model.DriftCauseKey
import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.WindowSyncResult
import kotlin.math.abs
import kotlin.math.roundToLong

data class WindowAudioInput(
    val window: SampleWindow,
    val speechEnvelope: FloatArray,
)

data class CinemaFpsCandidate(
    val speedRatio: Double,
    val label: String,
    val causeKey: DriftCauseKey,
)

data class SpeedMatchResult(
    val windowResults: List<WindowSyncResult>,
    /** Exact speed ratio if a standard cinema FPS conversion won; 1.0 for normal 1:1 matching. */
    val matchedFpsCandidate: CinemaFpsCandidate? = null,
    /** Consensus constant base offset `b` in `t_audio = t_sub * speedRatio + b` when [matchedFpsCandidate] != null. */
    val baseOffsetMs: Long = 0L,
)

/**
 * Correlates extracted audio windows against subtitles at 1.0x speed first, and automatically
 * tests standard cinema FPS conversion ratios (23.976 <-> 25, 24 <-> 25, 23.976 <-> 24)
 * whenever 1.0x correlation is weak or shows large drift across windows.
 *
 * Testing standard FPS ratios operates directly on the already-extracted 50 Hz envelopes
 * (~20-30 ms CPU total, zero extra audio decoding) and eliminates the ~3.8s intra-window smear
 * that breaks naive 1.0x correlation on PAL <-> NTSC mismatches.
 */
object FpsStretchMatcher {

    val STANDARD_FPS_CANDIDATES: List<CinemaFpsCandidate> = listOf(
        // 25 fps subtitle on 23.976 fps movie (stretch subtitle by +4.2709%)
        CinemaFpsCandidate(
            speedRatio = 25.0 / (24_000.0 / 1001.0),
            label = "25 → 23.976 fps",
            causeKey = DriftCauseKey.FPS_23976_VS_25,
        ),
        // 23.976 fps subtitle on 25 fps movie (compress subtitle by -4.096%)
        CinemaFpsCandidate(
            speedRatio = (24_000.0 / 1001.0) / 25.0,
            label = "23.976 → 25 fps",
            causeKey = DriftCauseKey.FPS_23976_VS_25,
        ),
        // 25 fps subtitle on 24.000 fps movie (+4.1667%)
        CinemaFpsCandidate(
            speedRatio = 25.0 / 24.0,
            label = "25 → 24 fps",
            causeKey = DriftCauseKey.FPS_23976_VS_25,
        ),
        // 24.000 fps subtitle on 25 fps movie (-4.0%)
        CinemaFpsCandidate(
            speedRatio = 24.0 / 25.0,
            label = "24 → 25 fps",
            causeKey = DriftCauseKey.FPS_23976_VS_25,
        ),
        // 24.000 fps subtitle on 23.976 fps movie (+0.1%)
        CinemaFpsCandidate(
            speedRatio = 24.0 / (24_000.0 / 1001.0),
            label = "24 → 23.976 fps",
            causeKey = DriftCauseKey.FPS_23976_VS_24,
        ),
        // 23.976 fps subtitle on 24.000 fps movie (-0.1%)
        CinemaFpsCandidate(
            speedRatio = (24_000.0 / 1001.0) / 24.0,
            label = "23.976 → 24 fps",
            causeKey = DriftCauseKey.FPS_23976_VS_24,
        ),
    )

    fun matchBest(
        windows: List<WindowAudioInput>,
        dialogueCues: List<SubtitleCue>,
        onExpandingSearch: () -> Unit = {},
    ): SpeedMatchResult {
        if (windows.isEmpty() || dialogueCues.isEmpty()) {
            return SpeedMatchResult(emptyList())
        }

        // 1. Standard 1.0x correlation within +/- 60s
        var baseResults = correlateAll(windows, dialogueCues, maxLagMs = 60_000L)

        // Expand to +/- 120s if initial confidence is weak
        val maxConf60 = baseResults.maxOfOrNull { it.confidence } ?: 0f
        if (maxConf60 < 0.35f) {
            onExpandingSearch()
            val wideResults = correlateAll(windows, dialogueCues, maxLagMs = 120_000L)
            val wideMaxConf = wideResults.maxOfOrNull { it.confidence } ?: 0f
            if (wideMaxConf > maxConf60 + 0.05f) {
                baseResults = wideResults
            }
        }

        // Fast path: if 1.0x already locked strongly with small spread across windows, skip FPS sweep
        val baseQuality = scoreWindowSet(baseResults)
        val baseSpread = offsetSpreadMs(baseResults.filter { it.confidence >= 0.35f })
        if (windows.size < 2 || (baseQuality.avgConfidence >= 0.62f && baseSpread <= 400L)) {
            return SpeedMatchResult(windowResults = baseResults)
        }

        // 2. Test standard cinema FPS ratios on the already-extracted envelopes
        var bestCandidate: CinemaFpsCandidate? = null
        var bestStretchedResults: List<WindowSyncResult>? = null
        var bestScore = baseQuality.combinedScore

        for (candidate in STANDARD_FPS_CANDIDATES) {
            val stretchedCues = scaleCues(dialogueCues, candidate.speedRatio)
            val candResults = correlateAll(windows, stretchedCues, maxLagMs = 60_000L)
            val candQuality = scoreWindowSet(candResults)
            val candReliable = candResults.filter { it.confidence >= 0.35f }
            val candSpread = offsetSpreadMs(candReliable)

            // Candidate FPS ratio must achieve strong agreement across >= 2 windows and decisively beat 1.0x
            val isTightAgreement = candReliable.size >= 2 && candSpread <= 750L
            val beatsBase = candQuality.combinedScore > bestScore + 0.10 &&
                candQuality.avgConfidence >= 0.45f

            if (isTightAgreement && beatsBase) {
                bestScore = candQuality.combinedScore
                bestCandidate = candidate
                bestStretchedResults = candResults
            }
        }

        if (bestCandidate == null || bestStretchedResults == null) {
            return SpeedMatchResult(windowResults = baseResults)
        }

        // Compute consensus constant residual offset `b` (in t_audio = r * t_sub + b)
        val reliable = bestStretchedResults.filter { it.confidence >= 0.35f }
            .ifEmpty { bestStretchedResults }
        val baseB = SyncAnalyzer.computeConsensusOffset(reliable)
        val r = bestCandidate.speedRatio

        // Convert each window's reported offset back to the true un-stretched offset at that checkpoint
        // so the UI accurately shows how far off Awal / Tengah / Akhir were before stretching.
        val unstretchedWindowResults = bestStretchedResults.map { wr ->
            val audioCenter = wr.window.centerMs.toDouble()
            val origSubCenter = (audioCenter - wr.offsetMs) / r
            val rawOffsetAtWindow = (audioCenter - origSubCenter).roundToLong()
            wr.copy(offsetMs = rawOffsetAtWindow)
        }

        return SpeedMatchResult(
            windowResults = unstretchedWindowResults,
            matchedFpsCandidate = bestCandidate,
            baseOffsetMs = baseB,
        )
    }

    private fun correlateAll(
        windows: List<WindowAudioInput>,
        cues: List<SubtitleCue>,
        maxLagMs: Long,
    ): List<WindowSyncResult> = windows.map { input ->
        FftCrossCorrelator.correlateWindow(
            window = input.window,
            audioEnvelope = input.speechEnvelope,
            dialogueCues = cues,
            maxLagMs = maxLagMs,
        )
    }

    private fun scaleCues(cues: List<SubtitleCue>, ratio: Double): List<SubtitleCue> =
        cues.map { cue ->
            cue.copy(
                startMs = (cue.startMs * ratio).roundToLong(),
                endMs = (cue.endMs * ratio).roundToLong(),
            )
        }

    private data class SetQuality(
        val avgConfidence: Float,
        val combinedScore: Double,
    )

    private fun scoreWindowSet(results: List<WindowSyncResult>): SetQuality {
        if (results.isEmpty()) return SetQuality(0f, 0.0)
        val avgConf = results.map { it.confidence }.average().toFloat()
        val reliable = results.filter { it.confidence >= 0.35f }
        val spread = offsetSpreadMs(reliable)
        val agreementBonus = when {
            reliable.size >= 2 && spread <= 350L -> 0.25
            reliable.size >= 2 && spread <= 750L -> 0.14
            else -> 0.0
        }
        val coverageRatio = reliable.size.toDouble() / results.size
        return SetQuality(
            avgConfidence = avgConf,
            combinedScore = avgConf * 0.70 + coverageRatio * 0.15 + agreementBonus,
        )
    }

    private fun offsetSpreadMs(results: List<WindowSyncResult>): Long {
        if (results.size < 2) return 0L
        return results.maxOf { it.offsetMs } - results.minOf { it.offsetMs }
    }
}
