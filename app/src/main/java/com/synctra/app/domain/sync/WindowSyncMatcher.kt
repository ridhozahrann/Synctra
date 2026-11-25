package com.synctra.app.domain.sync

import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.WindowSyncResult
import kotlin.math.abs

data class WindowAudioInput(
    val window: SampleWindow,
    val speechEnvelope: FloatArray,
)

data class WindowMatchResult(
    val windowResults: List<WindowSyncResult>,
    /** True when the result came from multi-window joint pooled correlation fallback. */
    val usedJointPooling: Boolean = false,
)

/**
 * Correlates extracted audio windows against subtitles at 1.0x speed, automatically expanding
 * the lag range from +/- 60s to +/- 120s when initial confidence is weak, and falling back to
 * multi-window joint pooled correlation when individual windows lack cluster agreement.
 */
object WindowSyncMatcher {

    fun matchBest(
        windows: List<WindowAudioInput>,
        dialogueCues: List<SubtitleCue>,
        onExpandingSearch: () -> Unit = {},
    ): WindowMatchResult {
        if (windows.isEmpty() || dialogueCues.isEmpty()) {
            return WindowMatchResult(emptyList())
        }

        val preparedWindows = windows.map {
            FftCrossCorrelator.prepareAudioWindow(it.window, it.speechEnvelope)
        }

        // 1. Standard 1.0x correlation within +/- 60s
        var activeMaxLagMs = 60_000L
        var baseResults = correlateAll(preparedWindows, dialogueCues, maxLagMs = activeMaxLagMs)

        // Expand to +/- 120s if initial confidence is weak
        val maxConf60 = baseResults.maxOfOrNull { it.confidence } ?: 0f
        if (maxConf60 < 0.35f) {
            onExpandingSearch()
            val wideResults = correlateAll(preparedWindows, dialogueCues, maxLagMs = 120_000L)
            val wideMaxConf = wideResults.maxOfOrNull { it.confidence } ?: 0f
            if (wideMaxConf > maxConf60 + 0.05f) {
                baseResults = wideResults
                activeMaxLagMs = 120_000L
            }
        }

        if (windows.size < 2) {
            return WindowMatchResult(windowResults = baseResults)
        }

        val baseCluster400 = findBestCluster(baseResults, toleranceMs = 400L)
        val baseQuality = scoreWindowSet(baseResults)

        val hasStrongConflictingWindow = baseResults.any { wr ->
            wr !in baseCluster400.windows && wr.confidence >= 0.55f
        }
        if (baseCluster400.size >= 2 && baseCluster400.avgConfidence >= 0.58f && !hasStrongConflictingWindow) {
            return WindowMatchResult(windowResults = baseResults)
        }

        // 2. Joint multi-window pooled correlation fallback when individual windows fail to agree
        if (baseCluster400.size < 2) {
            val jointResults = FftCrossCorrelator.correlateJointlyPrepared(
                preparedWindows = preparedWindows,
                dialogueCues = dialogueCues,
                maxLagMs = activeMaxLagMs,
            )
            if (jointResults != null) {
                val jointQuality = scoreWindowSet(jointResults)
                if (jointQuality.combinedScore > baseQuality.combinedScore + 0.05) {
                    return WindowMatchResult(
                        windowResults = jointResults,
                        usedJointPooling = true,
                    )
                }
            }
        }

        return WindowMatchResult(windowResults = baseResults)
    }

    private fun correlateAll(
        preparedWindows: List<FftCrossCorrelator.PreparedAudioWindow>,
        cues: List<SubtitleCue>,
        maxLagMs: Long,
    ): List<WindowSyncResult> = preparedWindows.map { prepared ->
        FftCrossCorrelator.correlateWindow(
            prepared = prepared,
            dialogueCues = cues,
            maxLagMs = maxLagMs,
        )
    }

    private data class OffsetCluster(
        val windows: List<WindowSyncResult>,
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
        if (reliable.isEmpty()) return OffsetCluster(emptyList(), 0f)
        if (reliable.size == 1) return OffsetCluster(reliable, reliable[0].confidence)

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

        val avgConf = best.map { it.confidence }.average().toFloat()
        return OffsetCluster(best, avgConf)
    }

    private data class SetQuality(
        val combinedScore: Double,
    )

    private fun scoreWindowSet(results: List<WindowSyncResult>): SetQuality {
        if (results.isEmpty()) return SetQuality(0.0)
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
            combinedScore = avgConf * 0.65 + agreeingRatio * 0.18 + agreementBonus,
        )
    }
}
