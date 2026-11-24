package com.synctra.app.domain.subtitle

import com.synctra.app.domain.model.DriftAssessment
import com.synctra.app.domain.model.SubtitleCue
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Exports synchronized subtitles as clean UTF-8 `.srt`, supporting:
 * - Constant offset shift (`newTime = origTime + offsetMs`)
 * - Exact linear FPS stretch correction regressed on subtitle time:
 *   `newTime = origTime + anchorOffset + (origTime - anchorTime) * (speedRatio - 1) + manualNudgeMs`
 * - Selective exclusion of user-confirmed ad/credit/SDH cues
 */
data class SrtExportResult(
    val content: String,
    val writtenCount: Int,
    val excludedCount: Int,
    val droppedByShiftCount: Int,
)

object SrtExporter {

    private const val MIN_CUE_DURATION_MS = 300L
    private const val MIN_CLAMPED_VISIBLE_DURATION_MS = 200L

    fun exportSrt(
        allCues: List<SubtitleCue>,
        offsetMs: Long,
        excludedCueIndices: Set<Int> = emptySet(),
        applyDriftCorrection: Boolean = false,
        drift: DriftAssessment? = null,
        manualNudgeMs: Long = 0L,
    ): String = exportSrtWithStats(
        allCues = allCues,
        offsetMs = offsetMs,
        excludedCueIndices = excludedCueIndices,
        applyDriftCorrection = applyDriftCorrection,
        drift = drift,
        manualNudgeMs = manualNudgeMs,
    ).content

    fun exportSrtWithStats(
        allCues: List<SubtitleCue>,
        offsetMs: Long,
        excludedCueIndices: Set<Int> = emptySet(),
        applyDriftCorrection: Boolean = false,
        drift: DriftAssessment? = null,
        manualNudgeMs: Long = 0L,
    ): SrtExportResult {
        val sb = StringBuilder(allCues.size * 64)
        var outIndex = 1
        var writtenCount = 0
        var excludedCount = 0
        var droppedByShiftCount = 0

        val useDrift = applyDriftCorrection &&
            drift != null &&
            (drift.speedRatio != 1.0 || drift.driftMsPerHour != 0.0)

        for (cue in allCues) {
            if (cue.index in excludedCueIndices) {
                excludedCount++
                continue
            }

            val newStart = shiftTimestamp(
                origMs = cue.startMs,
                constantOffsetMs = offsetMs,
                useDrift = useDrift,
                drift = drift,
                manualNudgeMs = manualNudgeMs,
            )
            val rawEnd = shiftTimestamp(
                origMs = cue.endMs,
                constantOffsetMs = offsetMs,
                useDrift = useDrift,
                drift = drift,
                manualNudgeMs = manualNudgeMs,
            )

            // Drop cues shifted completely before 00:00:00,000, or clipped down to an unusable stub (<200 ms)
            if (rawEnd <= 0L || (newStart < 0L && rawEnd < MIN_CLAMPED_VISIBLE_DURATION_MS)) {
                droppedByShiftCount++
                continue
            }

            val clampedStart = newStart.coerceAtLeast(0L)
            val minDuration = if (newStart < 0L) MIN_CLAMPED_VISIBLE_DURATION_MS else MIN_CUE_DURATION_MS
            val clampedEnd = rawEnd.coerceAtLeast(clampedStart + minDuration)

            sb.append(outIndex++)
                .append("\r\n")
                .append(formatSrtTimestamp(clampedStart))
                .append(" --> ")
                .append(formatSrtTimestamp(clampedEnd))
                .append("\r\n")
                .append(cue.cleanText.replace("\n", "\r\n"))
                .append("\r\n\r\n")
            writtenCount++
        }

        return SrtExportResult(
            content = sb.toString(),
            writtenCount = writtenCount,
            excludedCount = excludedCount,
            droppedByShiftCount = droppedByShiftCount,
        )
    }

    fun suggestedOutputFileName(originalSubtitleName: String?): String {
        val base = originalSubtitleName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "subtitle"
        return "$base.synced.srt"
    }

    private fun shiftTimestamp(
        origMs: Long,
        constantOffsetMs: Long,
        useDrift: Boolean,
        drift: DriftAssessment?,
        manualNudgeMs: Long,
    ): Long {
        if (!useDrift || drift == null) {
            return origMs + constantOffsetMs
        }
        // Works identically for both:
        // - Exact cinema FPS match (anchorTimeMs = 0, anchorOffsetMs = baseOffset, slope = speedRatio - 1)
        //   => origMs + baseOffset + origMs * (speedRatio - 1) = origMs * speedRatio + baseOffset
        // - Weighted subtitle-time regression (anchorTimeMs = mean(t_sub), anchorOffsetMs = mean(offset))
        val deltaFromAnchor = origMs - drift.anchorTimeMs
        val driftAtTime = drift.anchorOffsetMs + (deltaFromAnchor * drift.slopeMsPerMs).roundToLong()
        return origMs + driftAtTime + manualNudgeMs
    }

    internal fun formatSrtTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
