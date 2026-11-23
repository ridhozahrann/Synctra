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
object SrtExporter {

    private const val MIN_CUE_DURATION_MS = 300L

    fun exportSrt(
        allCues: List<SubtitleCue>,
        offsetMs: Long,
        excludedCueIndices: Set<Int> = emptySet(),
        applyDriftCorrection: Boolean = false,
        drift: DriftAssessment? = null,
        manualNudgeMs: Long = 0L,
    ): String {
        val sb = StringBuilder(allCues.size * 64)
        var outIndex = 1

        val useDrift = applyDriftCorrection &&
            drift != null &&
            (drift.speedRatio != 1.0 || drift.driftMsPerHour != 0.0)

        for (cue in allCues) {
            if (cue.index in excludedCueIndices) continue

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

            if (rawEnd <= 0L) continue

            val clampedStart = newStart.coerceAtLeast(0L)
            val clampedEnd = rawEnd.coerceAtLeast(clampedStart + MIN_CUE_DURATION_MS)

            sb.append(outIndex++)
                .append("\r\n")
                .append(formatSrtTimestamp(clampedStart))
                .append(" --> ")
                .append(formatSrtTimestamp(clampedEnd))
                .append("\r\n")
                .append(cue.cleanText.replace("\n", "\r\n"))
                .append("\r\n\r\n")
        }

        return sb.toString()
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
