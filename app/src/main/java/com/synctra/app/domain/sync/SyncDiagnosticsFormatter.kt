package com.synctra.app.domain.sync

import com.synctra.app.domain.model.ExportVerificationResult
import com.synctra.app.domain.model.SyncResult
import java.util.Locale

/**
 * Formats an offline, plain-text diagnostics summary of a sync run for bug reports and
 * troubleshooting (codec, decoder, timings, drift, per-window offset/confidence/prominence).
 */
object SyncDiagnosticsFormatter {

    fun format(
        result: SyncResult,
        videoName: String?,
        subtitleName: String?,
        encodingName: String?,
        manualNudgeMs: Long = 0L,
        verification: ExportVerificationResult? = null,
    ): String {
        val sb = StringBuilder(512)
        sb.appendLine("Synctra Diagnostics")
        if (!videoName.isNullOrBlank()) sb.appendLine("Video: $videoName")
        if (!subtitleName.isNullOrBlank()) {
            val enc = encodingName?.let { " ($it)" } ?: ""
            sb.appendLine("Subtitle: $subtitleName$enc")
        }
        val track = result.audioTrackTitle.ifBlank { "default" }
        val decoder = if (result.usedFfmpegDecoder) "FFmpeg" else "MediaCodec"
        sb.appendLine("Audio: $track | ${result.audioCodecUsed} | $decoder")
        sb.appendLine(
            String.format(
                Locale.US,
                "Result: %+d ms (nudge %+d ms) | conf=%.0f%% (%s) | source=%s | %.2fs",
                result.offsetMs,
                manualNudgeMs,
                result.confidence * 100f,
                result.confidenceLevel.name,
                result.source.name,
                result.analysisDurationMs / 1000.0,
            )
        )
        sb.appendLine(
            String.format(
                Locale.US,
                "Cues: %d dialogue, %d filtered | Drift: %s (spread=%dms, slope=%.0fms/h)",
                result.totalDialogueCues,
                result.filteredJunkCues,
                result.drift.severity.name,
                result.drift.spreadMs,
                result.drift.driftMsPerHour,
            )
        )
        sb.appendLine("Windows (${result.windowResults.size}):")
        for (wr in result.windowResults) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "  #%d @ %s (%ds): %+dms | conf=%.0f%% | prom=%.2f | speech=%.0f%%",
                    wr.window.index + 1,
                    formatClock(wr.window.startMs),
                    wr.window.durationMs / 1000L,
                    wr.offsetMs,
                    wr.confidence * 100f,
                    wr.peakProminence,
                    wr.speechRatio * 100f,
                )
            )
        }
        if (verification != null) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "Export verify: residual=%+dms | conf=%.0f%% | drift=%b | cues=%d",
                    verification.residualOffsetMs,
                    verification.confidence * 100f,
                    verification.hasDrift,
                    verification.verifiedCuesCount,
                )
            )
        }
        return sb.toString().trimEnd()
    }

    private fun formatClock(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
