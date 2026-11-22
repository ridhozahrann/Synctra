package com.synctra.app.domain.model

import java.util.Locale

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
    UNKNOWN("Subtitle"),
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
 * Represents a selectable audio track inside a multi-audio video container (e.g. MKV with English + Hindi).
 */
data class AudioTrackOption(
    val audioIndex: Int,
    val extractorTrackId: Int,
    val mimeType: String,
    val codecLabel: String,
    val languageCode: String?,
    val trackLabel: String?,
    val channelCount: Int,
    val sampleRate: Int,
    val isDefault: Boolean,
) {
    val channelLayoutLabel: String
        get() = when (channelCount) {
            1 -> "Mono"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${channelCount}ch"
        }

    val languageDisplayName: String?
        get() = resolveLanguageName(languageCode)

    val displayTitle: String
        get() {
            val lang = languageDisplayName
            val rawLabel = trackLabel?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                rawLabel != null && lang != null && !rawLabel.contains(lang, ignoreCase = true) -> "$lang — $rawLabel"
                rawLabel != null -> rawLabel
                lang != null -> lang
                else -> "Audio ${audioIndex + 1}"
            }
        }

    val technicalSummary: String
        get() = "$codecLabel · $channelLayoutLabel"

    companion object {
        private val bibliographicMap = mapOf(
            "eng" to "English",
            "hin" to "Hindi",
            "ind" to "Indonesian",
            "jpn" to "Japanese",
            "kor" to "Korean",
            "chi" to "Chinese",
            "zho" to "Chinese",
            "fre" to "French",
            "fra" to "French",
            "ger" to "German",
            "deu" to "German",
            "spa" to "Spanish",
            "ita" to "Italian",
            "por" to "Portuguese",
            "rus" to "Russian",
            "ara" to "Arabic",
            "tha" to "Thai",
            "vie" to "Vietnamese",
            "may" to "Malay",
            "msa" to "Malay",
            "tam" to "Tamil",
            "tel" to "Telugu",
            "dut" to "Dutch",
            "nld" to "Dutch",
            "tur" to "Turkish",
            "pol" to "Polish",
            "cze" to "Czech",
            "rum" to "Romanian",
        )

        internal fun resolveLanguageName(code: String?): String? {
            val cleaned = code?.trim()?.lowercase(Locale.US)?.substringBefore('-')?.substringBefore('_')
            if (cleaned.isNullOrBlank() || cleaned == "und" || cleaned == "zxx" || cleaned == "mul") {
                return null
            }
            bibliographicMap[cleaned]?.let { return it }
            val locale = Locale.forLanguageTag(cleaned)
            val name = locale.getDisplayLanguage(Locale.ENGLISH)
            return if (name.isNotBlank() && !name.equals(cleaned, ignoreCase = true) && !name.equals("Unknown language", ignoreCase = true)) {
                name.replaceFirstChar { it.uppercaseChar() }
            } else {
                cleaned.uppercase(Locale.US)
            }
        }
    }
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
    LOW("Akurasi Rendah"),
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
    val audioTrackTitle: String = "",
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
    val audioTracks: List<AudioTrackOption>,
    val defaultAudioIndex: Int = 0,
) {
    val primaryAudio: AudioTrackOption?
        get() = audioTracks.getOrNull(defaultAudioIndex) ?: audioTracks.firstOrNull()
}

data class SyncProgress(
    val stepTitle: String,
    val stepDetail: String,
    val fraction: Float,
)
