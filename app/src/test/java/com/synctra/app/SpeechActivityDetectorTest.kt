package com.synctra.app

import com.synctra.app.domain.audio.SpeechActivityDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechActivityDetectorTest {

    @Test
    fun detectsVoiceBandBurstsAndIgnoresLowBassRumble() {
        val sr = 16_000
        val durationSec = 10
        val totalSamples = sr * durationSec
        val pcm = FloatArray(totalSamples) { i ->
            (0.12 * sin(2.0 * PI * 60.0 * i / sr)).toFloat()
        }

        val speechWindows = listOf(2.0 to 4.0, 6.0 to 8.0)
        for ((startSec, endSec) in speechWindows) {
            val s = (startSec * sr).toInt()
            val e = (endSec * sr).toInt()
            for (i in s until e) {
                val t = i.toDouble() / sr
                val syllabicMod = 0.55 + 0.45 * sin(2.0 * PI * 5.0 * t)
                val voice = 0.35 * sin(2.0 * PI * 450.0 * t) +
                    0.25 * sin(2.0 * PI * 1100.0 * t) +
                    0.15 * sin(2.0 * PI * 2100.0 * t)
                pcm[i] += (voice * syllabicMod).toFloat()
            }
        }

        val vad = SpeechActivityDetector(sampleRate = sr)
        val env = vad.detectEnvelope(pcm)

        val fps = SpeechActivityDetector.FRAMES_PER_SECOND
        val speechScore = (2 * fps until 4 * fps).map { env[it] }.average()
        val quietScore = (0 until (1.5 * fps).toInt()).map { env[it] }.average()

        assertTrue("Expected high speech score ($speechScore) vs quiet ($quietScore)", speechScore > 0.55)
        assertTrue("Expected low score during bass-only segment ($quietScore)", quietScore < 0.25)
    }

    @Test
    fun rejectsHighFrequencyMusicAt48kHzWithoutAliasingIntoVoiceBand() {
        val sr = 48_000
        val durationSec = 8
        val totalSamples = sr * durationSec

        // Loud 14 kHz high-frequency shimmer/cymbal throughout clip (would alias into 2 kHz if naively decimated to 16 kHz!)
        val pcm = FloatArray(totalSamples) { i ->
            (0.25 * sin(2.0 * PI * 14_000.0 * i / sr)).toFloat()
        }

        // Speech burst from 3.0s to 5.5s
        for (i in (3.0 * sr).toInt() until (5.5 * sr).toInt()) {
            val t = i.toDouble() / sr
            val mod = 0.55 + 0.45 * sin(2.0 * PI * 5.0 * t)
            val voice = 0.35 * sin(2.0 * PI * 500.0 * t) + 0.25 * sin(2.0 * PI * 1200.0 * t)
            pcm[i] += (voice * mod).toFloat()
        }

        val vad = SpeechActivityDetector(sampleRate = sr)
        val env = vad.detectEnvelope(pcm)

        val fps = SpeechActivityDetector.FRAMES_PER_SECOND
        val speechScore = (3 * fps until 5 * fps).map { env[it] }.average()
        val hfOnlyScore = (0 until 2 * fps).map { env[it] }.average()

        assertTrue("Expected strong speech detection at 48kHz ($speechScore)", speechScore > 0.55)
        assertTrue("Expected 14kHz high-frequency music rejected without aliasing ($hfOnlyScore)", hfOnlyScore < 0.25)
    }

    @Test
    fun prioritizesCenterChannelDialogueIn5Point1SurroundAudio() {
        val sr = 48_000
        val channels = 6
        val durationSec = 6
        val frames = sr * durationSec
        val pcm16 = ShortArray(frames * channels)

        for (f in 0 until frames) {
            val t = f.toDouble() / sr
            val base = f * channels
            // Loud surround & LFE BGM throughout the entire clip (channels 3, 4, 5)
            val bgm = (18_000.0 * sin(2.0 * PI * 650.0 * t)).toInt().toShort()
            pcm16[base + 3] = bgm // LFE
            pcm16[base + 4] = bgm // BL
            pcm16[base + 5] = bgm // BR

            // Dialogue ONLY in Center channel (ch 2) between 2.0s and 4.0s
            if (t in 2.0..4.0) {
                val mod = 0.55 + 0.45 * sin(2.0 * PI * 5.0 * t)
                val voice = (14_000.0 * mod * (0.6 * sin(2.0 * PI * 520.0 * t) + 0.4 * sin(2.0 * PI * 1300.0 * t)))
                    .toInt().toShort()
                pcm16[base + 2] = voice
            }
        }

        val vad = SpeechActivityDetector(sampleRate = sr)
        val collector = vad.StreamingCollector()
        collector.pushPcm16(pcm16, pcm16.size, channels)
        val env = collector.buildEnvelope()

        val fps = SpeechActivityDetector.FRAMES_PER_SECOND
        val centerDialogueScore = (2 * fps until 4 * fps).map { env[it] }.average()
        val surroundOnlyScore = (0 until (1.5 * fps).toInt()).map { env[it] }.average()

        assertTrue("Expected clear Center-channel dialogue ($centerDialogueScore)", centerDialogueScore > 0.55)
        assertTrue("Expected surround-only BGM ignored ($surroundOnlyScore)", surroundOnlyScore < 0.15)
    }
}
