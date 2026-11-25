package com.synctra.app.domain.sync

import com.synctra.app.domain.model.CleanedSubtitleSet
import com.synctra.app.domain.model.ConfidenceLevel
import com.synctra.app.domain.model.DriftAssessment
import com.synctra.app.domain.model.DriftCauseKey
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SyncResult
import com.synctra.app.domain.model.SyncResultSource
import com.synctra.app.domain.model.WindowSyncResult
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Aggregates per-window cross-correlation measurements into a single consensus offset,
 * assesses informational drift across checkpoints, and chooses the best scene for Test Dengar.
 */
object SyncAnalyzer {

    private const val JOINT_CONFIDENCE_CAP = 0.75f

    /**
     * Decides whether adaptive extra sampling windows should be extracted on a movie (>= 15 min)
     * because the initial checkpoints had weak confidence or lacked cluster agreement.
     */
    fun shouldSampleExtraWindows(
        matchResult: WindowMatchResult,
        mediaDurationMs: Long,
    ): Boolean {
        if (mediaDurationMs < 15L * 60L * 1000L) return false
        val results = matchResult.windowResults
        if (results.size !in 2..4) return false

        val maxConf = results.maxOf { it.confidence }
        if (maxConf < 0.50f) return true
        if (matchResult.usedJointPooling) return true

        val reliable = results.filter { it.confidence >= 0.35f }
        if (reliable.size < 2) return true
        val hasAgreeingPair = reliable.any { pivot ->
            reliable.count { abs(it.offsetMs - pivot.offsetMs) <= 450L } >= 2
        }
        return !hasAgreeingPair
    }

    fun aggregate(
        windowResults: List<WindowSyncResult>,
        cleanedSubtitles: CleanedSubtitleSet,
        audioCodecUsed: String,
        usedFfmpegDecoder: Boolean,
        analysisDurationMs: Long,
        audioTrackTitle: String = "",
        usedJointPooling: Boolean = false,
    ): SyncResult {
        require(windowResults.isNotEmpty()) { "Minimal satu jendela sampel dibutuhkan." }

        val maxConfAll = windowResults.maxOf { it.confidence }
        val minValidConf = maxOf(0.25f, maxConfAll * 0.45f)
        val validWindows = windowResults.filter { it.confidence >= minValidConf }
            .ifEmpty { listOf(windowResults.maxBy { it.confidence }) }

        val consensusOffsetMs = computeConsensusOffset(validWindows)
        val drift = assessDrift(windowResults)

        val avgConf = validWindows.map { it.confidence }.average().toFloat()
        val agreementBoost = if (!usedJointPooling && validWindows.size >= 2 && drift.severity == DriftSeverity.NONE) {
            0.10f
        } else {
            0f
        }
        val maxCap = if (usedJointPooling) JOINT_CONFIDENCE_CAP else 0.99f
        val finalConfidence = (avgConf + agreementBoost).coerceIn(0f, maxCap)

        val confidenceLevel = when {
            finalConfidence >= 0.68f && drift.severity != DriftSeverity.SEVERE -> ConfidenceLevel.HIGH
            finalConfidence >= 0.38f -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        val source = if (usedJointPooling) SyncResultSource.JOINT_POOLED else SyncResultSource.STANDARD

        val bestWindow = validWindows.maxBy { it.confidence * (1f + it.peakProminence * 0.2f) }
        val previewAudioMs = pickPreviewTimestamp(
            window = bestWindow,
            offsetMs = bestWindow.offsetMs,
            cues = cleanedSubtitles.dialogueCues,
        )

        return SyncResult(
            offsetMs = consensusOffsetMs,
            confidence = finalConfidence,
            confidenceLevel = confidenceLevel,
            windowResults = windowResults,
            drift = drift,
            audioCodecUsed = audioCodecUsed,
            audioTrackTitle = audioTrackTitle,
            usedFfmpegDecoder = usedFfmpegDecoder,
            analysisDurationMs = analysisDurationMs,
            totalDialogueCues = cleanedSubtitles.dialogueCues.size,
            filteredJunkCues = cleanedSubtitles.totalFilteredCount,
            recommendedPreviewAudioMs = previewAudioMs,
            source = source,
        )
    }

    /**
     * Squared-confidence cluster consensus so weak noisy peaks cannot outvote a strong lock.
     * When no 2 windows agree within 350ms, picks the highest-confidence window instead of
     * a meaningless median of unrelated offsets.
     */
    internal fun computeConsensusOffset(windows: List<WindowSyncResult>): Long {
        if (windows.size == 1) return windows.first().offsetMs

        var bestCluster: List<WindowSyncResult> = emptyList()
        var bestWeight = -1.0
        for (pivot in windows) {
            val cluster = windows.filter { abs(it.offsetMs - pivot.offsetMs) <= 350L }
            val weight = cluster.sumOf {
                val c = it.confidence.toDouble().coerceAtLeast(0.05)
                c * c * (1.0 + it.peakProminence * 0.15)
            }
            if (cluster.size > bestCluster.size || (cluster.size == bestCluster.size && weight > bestWeight)) {
                bestCluster = cluster
                bestWeight = weight
            }
        }
        if (bestCluster.size >= 2) {
            val totalW = bestCluster.sumOf {
                val c = it.confidence.toDouble().coerceAtLeast(0.05)
                c * c
            }
            val weightedSum = bestCluster.sumOf {
                val c = it.confidence.toDouble().coerceAtLeast(0.05)
                it.offsetMs * c * c
            }
            return (weightedSum / totalW).roundToLong()
        }

        // No 2 windows agree closely: trust the strongest individual peak rather than a weak median
        return windows.maxBy {
            val c = it.confidence.toDouble().coerceAtLeast(0.05)
            c * c * (1.0 + it.peakProminence * 0.20)
        }.offsetMs
    }

    /**
     * Outlier-resistant weighted least-squares drift assessment for informational diagnostics.
     */
    internal fun assessDrift(windows: List<WindowSyncResult>): DriftAssessment {
        if (windows.size < 2) {
            return DriftAssessment(
                severity = DriftSeverity.NONE,
                driftMsPerHour = 0.0,
                spreadMs = 0L,
                suspectedCause = null,
            )
        }

        // Only trust reliable windows so a single noisy low-confidence action scene cannot trigger false drift
        val maxConf = windows.maxOf { it.confidence }
        val minAllowedConf = maxOf(0.42f, maxConf * 0.58f)
        val reliable = windows
            .filter { it.confidence >= minAllowedConf }
            .sortedBy { it.window.centerMs }

        if (reliable.size < 2) {
            return DriftAssessment(
                severity = DriftSeverity.NONE,
                driftMsPerHour = 0.0,
                spreadMs = 0L,
                suspectedCause = null,
            )
        }

        val minOffset = reliable.minOf { it.offsetMs }
        val maxOffset = reliable.maxOf { it.offsetMs }
        val spreadMs = maxOffset - minOffset

        val xs = reliable.map { (it.window.centerMs - it.offsetMs).toDouble() }
        val ys = reliable.map { it.offsetMs.toDouble() }
        val ws = reliable.map { (it.confidence * it.confidence).toDouble().coerceAtLeast(0.01) }

        val sumW = ws.sum()
        val meanX = xs.indices.sumOf { ws[it] * xs[it] } / sumW
        val meanY = ys.indices.sumOf { ws[it] * ys[it] } / sumW

        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            num += ws[i] * dx * (ys[i] - meanY)
            den += ws[i] * dx * dx
        }
        val slopeMsPerMs = if (den > 1e-3) num / den else 0.0
        val slopeMsPerHour = slopeMsPerMs * 3_600_000.0
        val absSlope = abs(slopeMsPerHour)

        val maxResidualMs = if (reliable.size >= 3) {
            xs.indices.maxOf { i ->
                val predicted = meanY + slopeMsPerMs * (xs[i] - meanX)
                abs(ys[i] - predicted)
            }
        } else {
            0.0
        }

        val severity = when {
            spreadMs >= 1_500L || absSlope >= 5_000.0 -> DriftSeverity.SEVERE
            spreadMs >= 450L || absSlope >= 1_200.0 -> DriftSeverity.MILD
            else -> DriftSeverity.NONE
        }

        if (severity == DriftSeverity.NONE) {
            return DriftAssessment(
                severity = DriftSeverity.NONE,
                driftMsPerHour = slopeMsPerHour,
                spreadMs = spreadMs,
                suspectedCause = null,
            )
        }

        val isFpsRatioSlope = absSlope in 2_200.0..5_500.0 || absSlope in 120_000.0..185_000.0
        val timeSpanMs = (xs.max() - xs.min())
        val isGeometricallyLinear = when {
            reliable.size >= 3 -> maxResidualMs <= maxOf(450.0, spreadMs * 0.20) && absSlope >= 1_200.0
            reliable.size == 2 -> reliable.minOf { it.confidence } >= 0.62f &&
                timeSpanMs >= 720_000.0 &&
                isFpsRatioSlope
            else -> false
        }

        val (causeKey, causeText) = classifyDriftCause(absSlope, spreadMs, isGeometricallyLinear, slopeMsPerMs)

        return DriftAssessment(
            severity = severity,
            driftMsPerHour = slopeMsPerHour,
            spreadMs = spreadMs,
            suspectedCause = causeText,
            causeKey = causeKey,
        )
    }

    private fun classifyDriftCause(
        absSlopeMsPerHour: Double,
        spreadMs: Long,
        isLinear: Boolean,
        slopeMsPerMs: Double,
    ): Pair<DriftCauseKey, String> {
        if (!isLinear && spreadMs >= 1_500L) {
            return DriftCauseKey.DIFFERENT_CUT to
                "Kemungkinan beda versi rilis (Theatrical vs Extended / potongan adegan berbeda)"
        }
        return when {
            absSlopeMsPerHour in 120_000.0..185_000.0 -> {
                val label = if (slopeMsPerMs > 0) "25 → 23.976 fps" else "23.976 → 25 fps"
                DriftCauseKey.FPS_23976_VS_25 to "Kemungkinan besar beda FPS ($label)"
            }
            absSlopeMsPerHour in 2_200.0..5_500.0 -> {
                val label = if (slopeMsPerMs > 0) "24 → 23.976 fps" else "23.976 → 24 fps"
                DriftCauseKey.FPS_23976_VS_24 to "Kemungkinan beda FPS tipis ($label)"
            }
            spreadMs >= 2_000L -> DriftCauseKey.DIFFERENT_CUT to
                "Kemungkinan beda versi rilis (misal Theatrical vs Extended / beda potongan iklan)"
            else -> DriftCauseKey.GRADUAL_SHIFT to
                "Offset bergeser bertahap — cek beberapa titik di Test Dengar"
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
