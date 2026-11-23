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
        val pcm = FloatArray(totalSamples)

        // Background low-frequency rumble (60 Hz) throughout the entire clip
        for (i in 0 until totalSamples) {
            pcm[i] = (0.12 * sin(2.0 * PI * 60.0 * i / sr)).toFloat()
        }

        // Inject synthetic vocal-band bursts (multi-harmonic 450Hz + 1100Hz + 2100Hz modulated at 5Hz)
        // Burst 1: 2.0s - 4.0s, Burst 2: 6.0s - 8.0s
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

        // Average score during speech windows vs non-speech windows
        val fps = SpeechActivityDetector.FRAMES_PER_SECOND
        val speechScore = (2 * fps until 4 * fps).map { env[it] }.average()
        val quietScore = (0 until (1.5 * fps).toInt()).map { env[it] }.average()

        assertTrue("Expected high speech score ($speechScore) vs quiet ($quietScore)", speechScore > 0.55)
        assertTrue("Expected low score during bass-only segment ($quietScore)", quietScore < 0.25)
    }

    @Test
    fun decimates48kHzAudioByThreeAndStillDetectsSpeechAccurately() {
        val sr = 48_000
        val durationSec = 8
        val totalSamples = sr * durationSec
        val pcm = FloatArray(totalSamples) { i ->
            (0.10 * sin(2.0 * PI * 60.0 * i / sr)).toFloat()
        }

        // Speech burst from 3.0s to 5.5s
        for (i in (3.0 * sr).toInt() until (5.5 * sr).toInt()) {
            val t = i.toDouble() / sr
            val mod = 0.55 + 0.45 * sin(2.0 * PI * 5.0 * t)
            val voice = 0.35 * sin(2.0 * PI * 500.0 * t) + 0.25 * sin(2.0 * PI * 1200.0 * t)
            pcm[i] += (voice * mod).toFloat()
        }

        val vad = SpeechActivityDetector(sampleRate = sr)
        org.junit.Assert.assertEquals(3, vad.decimationFactor)
        val env = vad.detectEnvelope(pcm)

        val fps = SpeechActivityDetector.FRAMES_PER_SECOND
        val speechScore = (3 * fps until 5 * fps).map { env[it] }.average()
        val quietScore = (0 until 2 * fps).map { env[it] }.average()

        assertTrue("Expected high speech score at 48kHz ($speechScore)", speechScore > 0.55)
        assertTrue("Expected low quiet score at 48kHz ($quietScore)", quietScore < 0.25)
    }
}
