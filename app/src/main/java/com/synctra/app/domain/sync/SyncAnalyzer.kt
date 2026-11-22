package com.synctra.app.domain.sync

import com.synctra.app.domain.model.CleanedSubtitleSet
import com.synctra.app.domain.model.ConfidenceLevel
import com.synctra.app.domain.model.DriftAssessment
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SyncResult
import com.synctra.app.domain.model.WindowSyncResult
import kotlin.math.abs

/**
 * Aggregates per-window cross-correlation measurements into a single consensus offset,
 * detects progressive drift / FPS mismatches, and chooses the best scene for Test Dengar.
 */
object SyncAnalyzer {

    fun aggregate(
        windowResults: List<WindowSyncResult>,
        cleanedSubtitles: CleanedSubtitleSet,
        audioCodecUsed: String,
        usedFfmpegDecoder: Boolean,
        analysisDurationMs: Long,
    ): SyncResult {
        require(windowResults.isNotEmpty()) { "Minimal satu jendela sampel dibutuhkan." }

        // Sort by quality weight (confidence * prominence)
        val validWindows = windowResults.filter { it.confidence >= 0.18f }
            .ifEmpty { listOf(windowResults.maxBy { it.confidence }) }

        val consensusOffsetMs = computeConsensusOffset(validWindows)
        val drift = assessDrift(validWindows)

        // Overall confidence: boosted when multiple windows agree, slightly damped when severe drift exists
        val avgConf = validWindows.map { it.confidence }.average().toFloat()
        val agreementBoost = if (validWindows.size >= 2 && drift.severity == DriftSeverity.NONE) 0.10f else 0f
        val finalConfidence = (avgConf + agreementBoost).coerceIn(0f, 0.99f)

        val confidenceLevel = when {
            finalConfidence >= 0.68f && drift.severity != DriftSeverity.SEVERE -> ConfidenceLevel.HIGH
            finalConfidence >= 0.38f -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        // Pick best timestamp inside the highest-confidence window that has rich dialogue right there
        val bestWindow = validWindows.maxBy { it.confidence * (1f + it.peakProminence * 0.2f) }
        val previewAudioMs = pickPreviewTimestamp(
            window = bestWindow,
            offsetMs = consensusOffsetMs,
            cues = cleanedSubtitles.dialogueCues,
        )

        return SyncResult(
            offsetMs = consensusOffsetMs,
            confidence = finalConfidence,
            confidenceLevel = confidenceLevel,
            windowResults = windowResults,
            drift = drift,
            audioCodecUsed = audioCodecUsed,
            usedFfmpegDecoder = usedFfmpegDecoder,
            analysisDurationMs = analysisDurationMs,
            totalDialogueCues = cleanedSubtitles.dialogueCues.size,
            filteredJunkCues = cleanedSubtitles.totalFilteredCount,
            recommendedPreviewAudioMs = previewAudioMs,
        )
    }

    /**
     * Weighted median consensus so a single noisy action window cannot skew the offset.
     */
    internal fun computeConsensusOffset(windows: List<WindowSyncResult>): Long {
        if (windows.size == 1) return windows.first().offsetMs

        // If 3+ windows and 2+ agree within 350ms, average the inlier cluster
        if (windows.size >= 3) {
            var bestCluster: List<WindowSyncResult> = emptyList()
            var bestWeight = -1.0
            for (pivot in windows) {
                val cluster = windows.filter { abs(it.offsetMs - pivot.offsetMs) <= 350L }
                val weight = cluster.sumOf { (it.confidence * (1f + it.peakProminence * 0.15f)).toDouble() }
                if (cluster.size > bestCluster.size || (cluster.size == bestCluster.size && weight > bestWeight)) {
                    bestCluster = cluster
                    bestWeight = weight
                }
            }
            if (bestCluster.size >= 2) {
                val totalW = bestCluster.sumOf { it.confidence.toDouble().coerceAtLeast(0.05) }
                val weightedSum = bestCluster.sumOf { it.offsetMs * it.confidence.toDouble().coerceAtLeast(0.05) }
                return (weightedSum / totalW).toLong()
            }
        }

        // Weighted median fallback (ideal when there's drift: gives midpoint offset)
        val sorted = windows.sortedBy { it.offsetMs }
        val totalWeight = sorted.sumOf { it.confidence.toDouble().coerceAtLeast(0.05) }
        var acc = 0.0
        for (w in sorted) {
            acc += w.confidence.toDouble().coerceAtLeast(0.05)
            if (acc >= totalWeight / 2.0) return w.offsetMs
        }
        return sorted[sorted.size / 2].offsetMs
    }

    internal fun assessDrift(windows: List<WindowSyncResult>): DriftAssessment {
        if (windows.size < 2) {
            return DriftAssessment(
                severity = DriftSeverity.NONE,
                driftMsPerHour = 0.0,
                spreadMs = 0L,
                suspectedCause = null,
            )
        }

        val sortedByTime = windows.sortedBy { it.window.centerMs }
        val minOffset = sortedByTime.minOf { it.offsetMs }
        val maxOffset = sortedByTime.maxOf { it.offsetMs }
        val spreadMs = maxOffset - minOffset

        // Ordinary least squares slope: offsetMs as a function of timeHours
        val xs = sortedByTime.map { it.window.centerMs / 3_600_000.0 }
        val ys = sortedByTime.map { it.offsetMs.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()

        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            num += dx * (ys[i] - meanY)
            den += dx * dx
        }
        val slopeMsPerHour = if (den > 1e-6) num / den else 0.0
        val absSlope = abs(slopeMsPerHour)

        val severity = when {
            spreadMs >= 1_500L || absSlope >= 5_000.0 -> DriftSeverity.SEVERE
            spreadMs >= 450L || absSlope >= 1_200.0 -> DriftSeverity.MILD
            else -> DriftSeverity.NONE
        }

        val cause = when (severity) {
            DriftSeverity.NONE -> null
            DriftSeverity.MILD, DriftSeverity.SEVERE -> classifyDriftCause(absSlope, spreadMs)
        }

        return DriftAssessment(
            severity = severity,
            driftMsPerHour = slopeMsPerHour,
            spreadMs = spreadMs,
            suspectedCause = cause,
        )
    }

    private fun classifyDriftCause(absSlopeMsPerHour: Double, spreadMs: Long): String {
        // 23.976 fps vs 25.000 fps => ~4.27% speed difference => ~153,750 ms/hour
        // 24.000 fps vs 25.000 fps => ~4.17% speed difference => ~150,000 ms/hour
        // 23.976 fps vs 24.000 fps => ~0.10% speed difference => ~3,600 ms/hour
        return when {
            absSlopeMsPerHour in 120_000.0..185_000.0 -> "Kemungkinan besar beda FPS (23.976 fps vs 25 fps)"
            absSlopeMsPerHour in 2_200.0..5_500.0 -> "Kemungkinan beda FPS tipis (23.976 fps vs 24 fps)"
            spreadMs >= 2_000L -> "Kemungkinan beda versi rilis (misal Theatrical vs Extended / beda potongan iklan)"
            else -> "Offset bergeser bertahap — cek beberapa titik di Test Dengar"
        }
    }

    /**
     * Finds a dialogue-rich 10-second spot inside [window] so Test Dengar immediately plays spoken lines.
     */
    internal fun pickPreviewTimestamp(
        window: WindowSyncResult,
        offsetMs: Long,
        cues: List<SubtitleCue>,
    ): Long {
        val winStart = window.window.startMs
        val winEnd = (window.window.endMs - 10_000L).coerceAtLeast(winStart)
        if (cues.isEmpty()) return window.window.centerMs

        var bestPos = window.window.centerMs
        var bestCueCount = -1

        var pos = winStart
        while (pos <= winEnd) {
            // Audio range [pos, pos + 10_000] corresponds to original subtitle times [pos - offsetMs, pos + 10_000 - offsetMs]
            val subStart = pos - offsetMs
            val subEnd = subStart + 10_000L
            val count = cues.count { it.startMs >= subStart && it.startMs <= subEnd - 1_500L }
            if (count > bestCueCount) {
                bestCueCount = count
                bestPos = pos
            }
            pos += 5_000L
        }
        return bestPos
    }
}
