package com.synctra.app

import com.synctra.app.domain.audio.SpeechActivityDetector
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.subtitle.SubtitleCleaner
import com.synctra.app.domain.subtitle.SubtitleParser
import com.synctra.app.domain.sync.FftCrossCorrelator
import com.synctra.app.domain.sync.SyncAnalyzer
import com.synctra.app.domain.sync.WindowPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * End-to-end test synthesizing raw 16 kHz PCM audio (human voice formants + low-bass background music)
 * and a shifted SRT subtitle file polluted with Indonesian ads/credits/SDH, verifying that the full
 * pipeline (SubtitleParser -> SubtitleCleaner -> WindowPlanner -> SpeechActivityDetector ->
 * FftCrossCorrelator -> SyncAnalyzer) recovers the exact offset within +/- 80 ms.
 */
class EndToEndSyncPipelineTest {

    @Test
    fun recoversExactOffsetFromSynthesizedPcmAudioAndPollutedSrt() {
        val trueOffsetMs = 3_250L // Subtitle is 3.25s too early -> player needs +3.25s delay
        val sampleRate = 16_000
        val winStartMs = 900_000L // 15:00 in a 90-minute movie
        val winDurationMs = 60_000L

        // Ground-truth spoken dialogue intervals inside [15:00, 16:00]
        val spokenIntervalsMs = listOf(
            903_000L to 905_800L,
            908_200L to 911_400L,
            914_500L to 917_900L,
            922_000L to 926_200L,
            930_400L to 933_100L,
            937_000L to 941_800L,
            946_200L to 949_500L,
            952_800L to 956_400L,
        )

        // Build polluted SRT with intro ads, SDH cues, and shifted dialogue
        val sb = StringBuilder()
        var idx = 1
        fun appendCue(startMs: Long, endMs: Long, text: String) {
            sb.append(idx++).append("\n")
            sb.append(formatSrtTime(startMs)).append(" --> ").append(formatSrtTime(endMs)).append("\n")
            sb.append(text).append("\n\n")
        }

        // Ads & credits at intro & mid-film
        appendCue(2_000L, 9_000L, "Terjemahan oleh Lebah Ganteng - www.subfilm.xyz")
        appendCue(12_000L, 18_000L, "Synced and corrected by OpenSubtitles.org")
        appendCue(901_000L, 902_500L, "[Musik tegang]")
        appendCue(920_000L, 921_500L, "♪ La la la ♪")

        // Shifted real dialogue (originalSubTime = audioTime - trueOffsetMs)
        for ((i, pair) in spokenIntervalsMs.withIndex()) {
            appendCue(pair.first - trueOffsetMs, pair.second - trueOffsetMs, "Ini adalah dialog asli nomor ${i + 1}.")
        }

        val parsed = SubtitleParser.parse(sb.toString().toByteArray(Charsets.UTF_8), "film.srt")
        val cleaned = SubtitleCleaner.clean(parsed, mediaDurationMs = 5_400_000L)
        assertEquals(spokenIntervalsMs.size, cleaned.dialogueCues.size)
        assertEquals(4, cleaned.totalFilteredCount)

        // Verify WindowPlanner generates 3 interior windows for a 90-minute movie
        val planned = WindowPlanner.planWindows(5_400_000L, cleaned.dialogueCues)
        assertEquals(3, planned.size)
        assertTrue(planned.all { it.startMs >= 60_000L })

        // Synthesize raw 16 kHz PCM with 65 Hz background bass + voice-band bursts at spokenIntervalsMs
        val totalSamples = (winDurationMs * sampleRate / 1000L).toInt()
        val pcm = FloatArray(totalSamples) { i ->
            (0.08 * sin(2.0 * PI * 65.0 * i / sampleRate)).toFloat()
        }
        for ((sMs, eMs) in spokenIntervalsMs) {
            val sIdx = ((sMs - winStartMs) * sampleRate / 1000L).toInt().coerceIn(0, totalSamples)
            val eIdx = ((eMs - winStartMs) * sampleRate / 1000L).toInt().coerceIn(sIdx, totalSamples)
            for (i in sIdx until eIdx) {
                val t = i.toDouble() / sampleRate
                val syllabic = 0.55 + 0.45 * sin(2.0 * PI * 4.8 * t)
                val voice = 0.32 * sin(2.0 * PI * 520.0 * t) +
                    0.24 * sin(2.0 * PI * 1280.0 * t) +
                    0.14 * sin(2.0 * PI * 2350.0 * t)
                pcm[i] += (voice * syllabic).toFloat()
            }
        }

        val vad = SpeechActivityDetector(sampleRate = sampleRate)
        val envelope = vad.detectEnvelope(pcm)

        val window = SampleWindow(0, "Awal (~20%)", winStartMs, winDurationMs)
        val wr = FftCrossCorrelator.correlateWindow(
            window = window,
            audioEnvelope = envelope,
            dialogueCues = cleaned.dialogueCues,
            maxLagMs = 30_000L,
        )

        val result = SyncAnalyzer.aggregate(
            windowResults = listOf(wr),
            cleanedSubtitles = cleaned,
            audioCodecUsed = "AC-3 (Dolby Digital)",
            usedFfmpegDecoder = true,
            analysisDurationMs = 1_500L,
        )

        val errorMs = abs(result.offsetMs - trueOffsetMs)
        assertTrue(
            "Expected offset near $trueOffsetMs ms, got ${result.offsetMs} ms (error=$errorMs ms, conf=${result.confidence})",
            errorMs <= 80L,
        )
        assertEquals(DriftSeverity.NONE, result.drift.severity)
        assertTrue("Expected high confidence, got ${result.confidence}", result.confidence >= 0.65f)
    }

    @Test
    fun recoversExactOffsetAt48kHzWithHighFrequencyMusicEnergy() {
        val trueOffsetMs = -2_400L
        val sampleRate = 48_000
        val winStartMs = 1_200_000L // 20:00 in a 90-minute movie
        val winDurationMs = 90_000L

        val spokenIntervalsMs = listOf(
            1_204_000L to 1_207_200L,
            1_211_500L to 1_215_000L,
            1_222_000L to 1_226_100L,
            1_235_400L to 1_239_200L,
            1_247_000L to 1_251_400L,
            1_258_800L to 1_262_500L,
            1_271_000L to 1_274_800L,
            1_281_500L to 1_285_500L,
        )

        val sb = StringBuilder()
        var idx = 1
        for ((sMs, eMs) in spokenIntervalsMs) {
            sb.append(idx++).append("\n")
            sb.append(formatSrtTime(sMs - trueOffsetMs))
                .append(" --> ")
                .append(formatSrtTime(eMs - trueOffsetMs))
                .append("\n")
            sb.append("Dialog 48kHz nomor $idx.\n\n")
        }

        val parsed = SubtitleParser.parse(sb.toString().toByteArray(Charsets.UTF_8), "jp_movie.srt")
        val cleaned = SubtitleCleaner.clean(parsed, mediaDurationMs = 5_400_000L)

        // Synthesize 48 kHz PCM with strong high-frequency (>4 kHz) cymbal/synth music throughout
        // plus vocal formants during spokenIntervalsMs. Native-rate VAD must NOT alias >4 kHz into voice band.
        val totalSamples = (winDurationMs * sampleRate / 1000L).toInt()
        val pcm = FloatArray(totalSamples) { i ->
            val t = i.toDouble() / sampleRate
            val music = 0.20 * sin(2.0 * PI * 6_200.0 * t) +
                0.18 * sin(2.0 * PI * 9_400.0 * t) +
                0.15 * sin(2.0 * PI * 14_500.0 * t) +
                0.10 * sin(2.0 * PI * 80.0 * t)
            music.toFloat()
        }
        for ((sMs, eMs) in spokenIntervalsMs) {
            val sIdx = ((sMs - winStartMs) * sampleRate / 1000L).toInt().coerceIn(0, totalSamples)
            val eIdx = ((eMs - winStartMs) * sampleRate / 1000L).toInt().coerceIn(sIdx, totalSamples)
            for (i in sIdx until eIdx) {
                val t = i.toDouble() / sampleRate
                val syllabic = 0.55 + 0.45 * sin(2.0 * PI * 4.5 * t)
                val voice = 0.34 * sin(2.0 * PI * 540.0 * t) +
                    0.26 * sin(2.0 * PI * 1_320.0 * t) +
                    0.16 * sin(2.0 * PI * 2_400.0 * t)
                pcm[i] += (voice * syllabic).toFloat()
            }
        }

        val vad = SpeechActivityDetector(sampleRate = sampleRate)
        val envelope = vad.detectEnvelope(pcm)

        val window = SampleWindow(0, "Awal (~20%)", winStartMs, winDurationMs)
        val wr = FftCrossCorrelator.correlateWindow(
            window = window,
            audioEnvelope = envelope,
            dialogueCues = cleaned.dialogueCues,
            maxLagMs = 30_000L,
        )
        val result = SyncAnalyzer.aggregate(
            windowResults = listOf(wr),
            cleanedSubtitles = cleaned,
            audioCodecUsed = "AAC · 48 kHz",
            usedFfmpegDecoder = false,
            analysisDurationMs = 1_200L,
        )

        val errorMs = abs(result.offsetMs - trueOffsetMs)
        assertTrue(
            "Expected 48 kHz offset near $trueOffsetMs ms, got ${result.offsetMs} ms (error=$errorMs ms, conf=${result.confidence})",
            errorMs <= 80L,
        )
        assertTrue("Expected solid confidence at 48 kHz, got ${result.confidence}", result.confidence >= 0.60f)
    }

    private fun formatSrtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val millis = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", h, m, s, millis)
    }
}
