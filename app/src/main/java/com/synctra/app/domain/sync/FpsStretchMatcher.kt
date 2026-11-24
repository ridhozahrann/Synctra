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
        var activeMaxLagMs = 60_000L
        var baseResults = correlateAll(windows, dialogueCues, maxLagMs = activeMaxLagMs)

        // Expand to +/- 120s if initial confidence is weak
        val maxConf60 = baseResults.maxOfOrNull { it.confidence } ?: 0f
        if (maxConf60 < 0.35f) {
            onExpandingSearch()
            val wideResults = correlateAll(windows, dialogueCues, maxLagMs = 120_000L)
            val wideMaxConf = wideResults.maxOfOrNull { it.confidence } ?: 0f
            if (wideMaxConf > maxConf60 + 0.05f) {
                baseResults = wideResults
                activeMaxLagMs = 120_000L
            }
        }

        if (windows.size < 2) {
            return SpeedMatchResult(windowResults = baseResults)
        }

        // Cluster-aware quality check on 1.0x so a single noisy 36% outlier never destroys agreement
        val baseCluster400 = findBestCluster(baseResults, toleranceMs = 400L)
        val baseCluster750 = findBestCluster(baseResults, toleranceMs = 750L)
        val baseQuality = scoreWindowSet(baseResults)

        // Fast path: if >= 2 windows already agree tightly at 1.0x with strong confidence,
        // and no conflicting high-confidence window disagrees strongly, skip FPS sweep & pooling.
        val hasStrongConflictingWindow = baseResults.any { wr ->
            wr !in baseCluster400.windows && wr.confidence >= 0.55f
        }
        if (baseCluster400.size >= 2 && baseCluster400.avgConfidence >= 0.58f && !hasStrongConflictingWindow) {
            return SpeedMatchResult(windowResults = baseResults)
        }

        // 2. Test standard cinema FPS ratios on the already-extracted envelopes
        var bestCandidate: CinemaFpsCandidate? = null
        var bestStretchedResults: List<WindowSyncResult>? = null
        var bestCandCluster: OffsetCluster? = null
        var bestScore = baseQuality.combinedScore

        for (candidate in STANDARD_FPS_CANDIDATES) {
            val stretchedCues = scaleCues(dialogueCues, candidate.speedRatio)
            val candResults = correlateAll(windows, stretchedCues, maxLagMs = 60_000L)
            val candQuality = scoreWindowSet(candResults)

            val isTinyRatio = candidate.causeKey == DriftCauseKey.FPS_23976_VS_24
            val maxAllowedSpread = if (isTinyRatio) 450L else 650L
            val candCluster = findBestCluster(candResults, toleranceMs = maxAllowedSpread)

            // Candidate FPS ratio must form a tight agreeing cluster at least as large as 1.0x's cluster
            // and decisively beat 1.0x. For tiny 0.1% ratios (23.976 <-> 24), never override a movie
            // whose 1.0x windows already agree within 400ms.
            val isTightAgreement = candCluster.size >= 2 &&
                candCluster.spreadMs <= maxAllowedSpread &&
                candCluster.size >= baseCluster750.size
            val tinyRatioOk = !isTinyRatio || (baseCluster400.size < 2 && candCluster.avgConfidence >= 0.52f)
            val requiredMargin = if (isTinyRatio) 0.12 else 0.10
            val minAvgConf = if (isTinyRatio) 0.50f else 0.45f
            val beatsBase = candQuality.combinedScore > bestScore + requiredMargin &&
                candQuality.avgConfidence >= minAvgConf

            if (isTightAgreement && tinyRatioOk && beatsBase) {
                bestScore = candQuality.combinedScore
                bestCandidate = candidate
                bestStretchedResults = candResults
                bestCandCluster = candCluster
            }
        }

        if (bestCandidate != null && bestStretchedResults != null && bestCandCluster != null) {
            // Compute consensus constant residual offset `b` from the agreeing cluster (in t_audio = r * t_sub + b)
            val baseB = SyncAnalyzer.computeConsensusOffset(bestCandCluster.windows)
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

        // 3. Joint multi-window pooled correlation fallback:
        // If 1.0x individual windows failed to form an agreeing cluster (e.g. heavy BGM/SFX
        // masked the true peak in 1-2 windows), pool coarse correlation curves across all windows so
        // the true shared lag adds constructively while random local noise cancels out.
        // correlateJointly itself requires >= 2 windows to genuinely support the shared lag with
        // high pooled prominence, so true multi-cut movies safely return null.
        if (baseCluster400.size < 2) {
            val jointResults = FftCrossCorrelator.correlateJointly(
                windows = windows,
                dialogueCues = dialogueCues,
                maxLagMs = activeMaxLagMs,
            )
            if (jointResults != null) {
                val jointQuality = scoreWindowSet(jointResults)
                if (jointQuality.combinedScore > baseQuality.combinedScore + 0.05) {
                    return SpeedMatchResult(windowResults = jointResults)
                }
            }
        }

        return SpeedMatchResult(windowResults = baseResults)
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

    private data class OffsetCluster(
        val windows: List<WindowSyncResult>,
        val spreadMs: Long,
        val avgConfidence: Float,
    ) {
        val size: Int get() = windows.size
    }

    private fun findBestCluster(
        results: List<WindowSyncResult>,
        toleranceMs: Long,
        minConfidence: Float = 0.35f,
    ): OffsetCluster {
        val reliable = results.filter { it.confidence >= minConfidence }
        if (reliable.isEmpty()) return OffsetCluster(emptyList(), 0L, 0f)
        if (reliable.size == 1) return OffsetCluster(reliable, 0L, reliable[0].confidence)

        var best: List<WindowSyncResult> = emptyList()
        var bestWeight = -1.0

        for (pivot in reliable) {
            val cluster = reliable.filter { abs(it.offsetMs - pivot.offsetMs) <= toleranceMs }
            val weight = cluster.sumOf { (it.confidence * it.confidence).toDouble() }
            if (cluster.size > best.size || (cluster.size == best.size && weight > bestWeight)) {
                best = cluster
                bestWeight = weight
            }
        }

        val spread = offsetSpreadMs(best)
        val avgConf = best.map { it.confidence }.average().toFloat()
        return OffsetCluster(best, spread, avgConf)
    }

    private data class SetQuality(
        val avgConfidence: Float,
        val combinedScore: Double,
    )

    private fun scoreWindowSet(results: List<WindowSyncResult>): SetQuality {
        if (results.isEmpty()) return SetQuality(0f, 0.0)
        val avgConf = results.map { it.confidence }.average().toFloat()
        val cluster350 = findBestCluster(results, toleranceMs = 350L)
        val cluster750 = findBestCluster(results, toleranceMs = 750L)

        val agreementBonus = when {
            cluster350.size >= 3 -> 0.28
            cluster350.size == 2 -> 0.22
            cluster750.size >= 3 -> 0.18
            cluster750.size == 2 -> 0.12
            else -> 0.0
        }
        val agreeingRatio = maxOf(cluster350.size, cluster750.size).toDouble() / results.size
        return SetQuality(
            avgConfidence = avgConf,
            combinedScore = avgConf * 0.65 + agreeingRatio * 0.18 + agreementBonus,
        )
    }

    private fun offsetSpreadMs(results: List<WindowSyncResult>): Long {
        if (results.size < 2) return 0L
        return results.maxOf { it.offsetMs } - results.minOf { it.offsetMs }
    }
}
