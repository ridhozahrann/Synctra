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
 * performs outlier-resistant subtitle-time drift regression (or consumes an exact matched
 * cinema FPS ratio from [FpsStretchMatcher]), and chooses the best scene for Test Dengar.
 */
object SyncAnalyzer {

    private const val JOINT_CONFIDENCE_CAP = 0.75f

    /**
     * Decides whether adaptive extra sampling windows should be extracted on a movie (>= 15 min)
     * because the initial checkpoints had weak confidence or lacked cluster agreement.
     */
    fun shouldSampleExtraWindows(
        matchResult: SpeedMatchResult,
        mediaDurationMs: Long,
    ): Boolean {
        if (mediaDurationMs < 15L * 60L * 1000L) return false
        if (matchResult.matchedFpsCandidate != null) return false
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
        matchedFpsCandidate: CinemaFpsCandidate? = null,
        matchedBaseOffsetMs: Long = 0L,
        usedJointPooling: Boolean = false,
    ): SyncResult {
        require(windowResults.isNotEmpty()) { "Minimal satu jendela sampel dibutuhkan." }

        val maxConfAll = windowResults.maxOf { it.confidence }
        val minValidConf = maxOf(0.25f, maxConfAll * 0.45f)
        val validWindows = windowResults.filter { it.confidence >= minValidConf }
            .ifEmpty { listOf(windowResults.maxBy { it.confidence }) }

        val consensusOffsetMs = computeConsensusOffset(validWindows)
        val drift = if (matchedFpsCandidate != null) {
            buildMatchedFpsDrift(windowResults, matchedFpsCandidate, matchedBaseOffsetMs)
        } else {
            assessDrift(windowResults)
        }

        val avgConf = validWindows.map { it.confidence }.average().toFloat()
        val agreementBoost = when {
            usedJointPooling -> 0f
            matchedFpsCandidate != null && validWindows.size >= 2 -> 0.12f
            validWindows.size >= 2 && drift.severity == DriftSeverity.NONE -> 0.10f
            else -> 0f
        }
        val maxCap = if (usedJointPooling) JOINT_CONFIDENCE_CAP else 0.99f
        val finalConfidence = (avgConf + agreementBoost).coerceIn(0f, maxCap)

        val confidenceLevel = when {
            finalConfidence >= 0.68f && (drift.severity != DriftSeverity.SEVERE || drift.isLinearStretch) -> ConfidenceLevel.HIGH
            finalConfidence >= 0.38f -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        val source = when {
            matchedFpsCandidate != null -> SyncResultSource.FPS_STRETCH
            usedJointPooling -> SyncResultSource.JOINT_POOLED
            else -> SyncResultSource.STANDARD
        }

        val bestWindow = validWindows.maxBy { it.confidence * (1f + it.peakProminence * 0.2f) }
        val previewAudioMs = pickPreviewTimestamp(
            window = bestWindow,
            offsetMs = bestWindow.offsetMs,
            cues = cleanedSubtitles.dialogueCues,
            drift = drift,
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

    private fun buildMatchedFpsDrift(
        windows: List<WindowSyncResult>,
        candidate: CinemaFpsCandidate,
        baseOffsetMs: Long,
    ): DriftAssessment {
        val slopeMsPerMs = candidate.speedRatio - 1.0
        val driftMsPerHour = slopeMsPerMs * 3_600_000.0
        val spreadMs = if (windows.size >= 2) {
            windows.maxOf { it.offsetMs } - windows.minOf { it.offsetMs }
        } else {
            0L
        }
        val severity = if (abs(driftMsPerHour) >= 5_000.0 || spreadMs >= 1_500L) {
            DriftSeverity.SEVERE
        } else {
            DriftSeverity.MILD
        }
        val suspectedCause = when (candidate.causeKey) {
            DriftCauseKey.FPS_23976_VS_25 -> "Terdeteksi beda FPS (${candidate.label})"
            DriftCauseKey.FPS_23976_VS_24 -> "Terdeteksi beda FPS tipis (${candidate.label})"
            else -> "Terdeteksi beda kecepatan (${candidate.label})"
        }
        return DriftAssessment(
            severity = severity,
            driftMsPerHour = driftMsPerHour,
            spreadMs = spreadMs,
            suspectedCause = suspectedCause,
            causeKey = candidate.causeKey,
            // Exact linear mapping from subtitle t=0: t_audio = t_sub * speedRatio + baseOffsetMs
            anchorTimeMs = 0L,
            anchorOffsetMs = baseOffsetMs,
            speedRatio = candidate.speedRatio,
            isLinearStretch = true,
            detectedFpsLabel = candidate.label,
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
     * Outlier-resistant weighted least-squares drift assessment regressed directly on
     * SUBTITLE time (`t_sub = window.centerMs - offsetMs`) so exported timestamps match exactly.
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

        // Only trust reliable windows so a single noisy low-confidence action scene cannot trigger false drift!
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

        // Weighted least-squares on SUBTITLE timeline:
        // x_i = t_sub_i = audioCenter_i - offset_i (in ms)
        // y_i = offset_i (in ms)
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

        // Check linearity when 3+ reliable checkpoints exist:
        // If 2 windows agree closely and 1 jumps abruptly, it's a scene cut (not linear FPS stretch).
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
                anchorTimeMs = meanX.roundToLong(),
                anchorOffsetMs = meanY.roundToLong(),
                speedRatio = 1.0,
                isLinearStretch = false,
            )
        }

        // Only consider a linear fit valid when:
        // - 3+ checkpoints follow a straight line tightly, OR
        // - 2 high-confidence checkpoints (>= 62%) separated by >= 12 min match a known cinema FPS slope.
        val isFpsRatioSlope = absSlope in 2_200.0..5_500.0 || absSlope in 120_000.0..185_000.0
        val timeSpanMs = (xs.max() - xs.min())
        val isGeometricallyLinear = when {
            reliable.size >= 3 -> maxResidualMs <= maxOf(450.0, spreadMs * 0.20) && absSlope >= 1_200.0
            reliable.size == 2 -> reliable.minOf { it.confidence } >= 0.62f &&
                timeSpanMs >= 720_000.0 &&
                isFpsRatioSlope
            else -> false
        }

        val (causeKey, causeText, fpsLabel) = classifyDriftCause(absSlope, spreadMs, isGeometricallyLinear, slopeMsPerMs)

        // Never auto-enable linear FPS stretch for scene cuts (DIFFERENT_CUT) or unexplained gradual shifts!
        val isVerifiedFpsStretch = isGeometricallyLinear &&
            (causeKey == DriftCauseKey.FPS_23976_VS_25 || causeKey == DriftCauseKey.FPS_23976_VS_24)

        return DriftAssessment(
            severity = severity,
            driftMsPerHour = slopeMsPerHour,
            spreadMs = spreadMs,
            suspectedCause = causeText,
            causeKey = causeKey,
            anchorTimeMs = meanX.roundToLong(),
            anchorOffsetMs = meanY.roundToLong(),
            speedRatio = 1.0 + slopeMsPerMs,
            isLinearStretch = isVerifiedFpsStretch,
            detectedFpsLabel = fpsLabel,
        )
    }

    private fun classifyDriftCause(
        absSlopeMsPerHour: Double,
        spreadMs: Long,
        isLinear: Boolean,
        slopeMsPerMs: Double,
    ): Triple<DriftCauseKey, String, String?> {
        if (!isLinear && spreadMs >= 1_500L) {
            return Triple(
                DriftCauseKey.DIFFERENT_CUT,
                "Kemungkinan beda versi rilis (Theatrical vs Extended / potongan adegan berbeda)",
                null,
            )
        }
        return when {
            absSlopeMsPerHour in 120_000.0..185_000.0 -> {
                val label = if (slopeMsPerMs > 0) "25 → 23.976 fps" else "23.976 → 25 fps"
                Triple(
                    DriftCauseKey.FPS_23976_VS_25,
                    "Kemungkinan besar beda FPS ($label)",
                    label,
                )
            }
            absSlopeMsPerHour in 2_200.0..5_500.0 -> {
                val label = if (slopeMsPerMs > 0) "24 → 23.976 fps" else "23.976 → 24 fps"
                Triple(
                    DriftCauseKey.FPS_23976_VS_24,
                    "Kemungkinan beda FPS tipis ($label)",
                    label,
                )
            }
            spreadMs >= 2_000L -> Triple(
                DriftCauseKey.DIFFERENT_CUT,
                "Kemungkinan beda versi rilis (misal Theatrical vs Extended / beda potongan iklan)",
                null,
            )
            else -> Triple(
                DriftCauseKey.GRADUAL_SHIFT,
                "Offset bergeser bertahap — cek beberapa titik di Test Dengar",
                null,
            )
        }
    }

    /**
     * Finds a dialogue-rich 10-second spot inside [window] so Test Dengar immediately plays spoken lines.
     */
    internal fun pickPreviewTimestamp(
        window: WindowSyncResult,
        offsetMs: Long,
        cues: List<SubtitleCue>,
        drift: DriftAssessment? = null,
    ): Long {
        val winStart = window.window.startMs
        val winEnd = (window.window.endMs - 10_000L).coerceAtLeast(winStart)
        if (cues.isEmpty()) return window.window.centerMs

        var bestPos = window.window.centerMs
        var bestCueCount = -1

        var pos = winStart
        while (pos <= winEnd) {
            val subStart = if (drift != null && drift.isLinearStretch && drift.severity != DriftSeverity.NONE) {
                audioTimeToOriginalSubtitleTime(pos, drift, manualNudgeMs = 0L)
            } else {
                pos - offsetMs
            }
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

    /**
     * Maps an audio playback position back to the original subtitle timeline when linear FPS stretch applies.
     * Inverse of: t_audio = t_sub + anchorOffset + (speedRatio - 1) * (t_sub - anchorTime) + manualNudgeMs
     */
    fun audioTimeToOriginalSubtitleTime(
        audioPosMs: Long,
        drift: DriftAssessment,
        manualNudgeMs: Long,
    ): Long {
        val s = drift.speedRatio
        if (abs(s - 1.0) < 1e-7) {
            return audioPosMs - (drift.anchorOffsetMs + manualNudgeMs)
        }
        val anchorAudioMs = drift.anchorTimeMs + drift.anchorOffsetMs + manualNudgeMs
        return drift.anchorTimeMs + ((audioPosMs - anchorAudioMs) / s).roundToLong()
    }
}
