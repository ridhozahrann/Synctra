package com.synctra.app.domain.model

/**
 * Represents a single parsed subtitle dialogue entry.
 */
data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val rawText: String,
    val cleanText: String,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

enum class SubtitleFormat(val displayName: String) {
    SRT("SRT"),
    ASS("ASS / SSA"),
    VTT("WebVTT"),
    UNKNOWN("Tidak Dikenal"),
}

data class ParsedSubtitleFile(
    val format: SubtitleFormat,
    val allCues: List<SubtitleCue>,
    val encodingName: String,
)

data class CleanedSubtitleSet(
    val format: SubtitleFormat,
    val allCues: List<SubtitleCue>,
    val dialogueCues: List<SubtitleCue>,
    val filteredAdOrCreditCount: Int,
    val filteredSdhCount: Int,
    val filteredEdgeCount: Int,
) {
    val totalFilteredCount: Int
        get() = filteredAdOrCreditCount + filteredSdhCount + filteredEdgeCount
}

/**
 * A time slice of the movie to decode and correlate against subtitles.
 */
data class SampleWindow(
    val index: Int,
    val label: String,
    val startMs: Long,
    val durationMs: Long,
) {
    val endMs: Long get() = startMs + durationMs
    val centerMs: Long get() = startMs + durationMs / 2L
}

data class WindowSyncResult(
    val window: SampleWindow,
    /**
     * Delay in milliseconds to apply to subtitles so they match audio:
     * newSubtitleTime = originalSubtitleTime + offsetMs
     * Positive => subtitle currently appears TOO EARLY (delay it by +offsetMs).
     * Negative => subtitle currently appears TOO LATE (advance it by offsetMs).
     */
    val offsetMs: Long,
    /** Normalized correlation confidence in [0.0, 1.0]. */
    val confidence: Float,
    /** Peak prominence above runner-up/noise floor (higher is sharper match). */
    val peakProminence: Float,
    /** Ratio of frames detected as speech inside this audio window. */
    val speechRatio: Float,
)

enum class DriftSeverity {
    NONE,
    MILD,
    SEVERE,
}

data class DriftAssessment(
    val severity: DriftSeverity,
    /** Estimated drift in milliseconds per hour of playback. */
    val driftMsPerHour: Double,
    /** Difference between maximum and minimum window offsets in milliseconds. */
    val spreadMs: Long,
    /** Human-readable suspected cause (e.g., "23.976 fps vs 25 fps"). */
    val suspectedCause: String?,
)

enum class ConfidenceLevel(val labelId: String) {
    HIGH("Akurasi Tinggi"),
    MEDIUM("Akurasi Sedang"),
    LOW("Akurasi Rendah — Cek via Test Dengar"),
}

data class SyncResult(
    /** Consensus delay in milliseconds (newSubTime = originalSubTime + offsetMs). */
    val offsetMs: Long,
    /** Overall confidence in [0.0, 1.0]. */
    val confidence: Float,
    val confidenceLevel: ConfidenceLevel,
    val windowResults: List<WindowSyncResult>,
    val drift: DriftAssessment,
    val audioCodecUsed: String,
    val usedFfmpegDecoder: Boolean,
    val analysisDurationMs: Long,
    val totalDialogueCues: Int,
    val filteredJunkCues: Int,
    /** Suggested dialogue-rich timestamp (audio timeline) for the 10-second Test Dengar preview. */
    val recommendedPreviewAudioMs: Long,
) {
    val offsetSeconds: Double get() = offsetMs / 1000.0
    val isAlreadySynced: Boolean get() = kotlin.math.abs(offsetMs) <= 150L
}

data class MediaTrackInfo(
    val durationMs: Long,
    val audioMimeType: String,
    val sampleRate: Int,
    val channelCount: Int,
    val audioCodecLabel: String,
)

data class SyncProgress(
    val stepTitle: String,
    val stepDetail: String,
    val fraction: Float,
)
