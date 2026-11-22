package com.synctra.app

import com.synctra.app.domain.audio.SpeechActivityDetector
import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.sync.FftCrossCorrelator
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FftCrossCorrelatorTest {

    @Test
    fun recoversPositiveAndNegativeOffsetsAccuratelyEvenWithAdNoise() {
        val trueDialogueTimesMs = listOf(
            602_000L to 604_500L,
            606_800L to 610_100L,
            614_000L to 616_200L,
            619_500L to 624_000L,
            629_200L to 631_800L,
            636_000L to 641_500L,
            646_100L to 648_900L,
            653_000L to 657_400L,
            661_200L to 665_000L,
            670_500L to 675_800L,
            680_000L to 684_200L,
        )

        val window = SampleWindow(
            index = 0,
            label = "Tengah",
            startMs = 600_000L,
            durationMs = 90_000L,
        )

        // Build ground-truth audio speech envelope at 50 Hz (20ms/frame)
        val frameMs = SpeechActivityDetector.FRAME_DURATION_MS
        val frameCount = (window.durationMs / frameMs).toInt()
        val audioEnv = FloatArray(frameCount)
        for ((s, e) in trueDialogueTimesMs) {
            val i0 = ((s - window.startMs) / frameMs).toInt().coerceIn(0, frameCount - 1)
            val i1 = ((e - window.startMs) / frameMs).toInt().coerceIn(i0, frameCount - 1)
            for (i in i0..i1) audioEnv[i] = 1.0f
        }

        // Test multiple ground-truth delays: +2400ms (subtitle too early), -1360ms (too late), +28500ms (large)
        val testOffsets = listOf(2_400L, -1_360L, 0L, 28_500L)

        for (expectedDelayMs in testOffsets) {
            // If delay = audioTime - subTime, then subTime = audioTime - expectedDelayMs
            val cues = trueDialogueTimesMs.mapIndexed { idx, (s, e) ->
                SubtitleCue(idx + 1, s - expectedDelayMs, e - expectedDelayMs, "Dialog $idx", "Dialog $idx")
            }.toMutableList()

            // Inject 2 fake stray cues (simulating uncleaned mid-roll ads) to prove correlation robustness
            cues.add(SubtitleCue(99, 612_000L, 613_500L, "Stray text", "Stray text"))
            cues.add(SubtitleCue(100, 667_000L, 668_800L, "Another stray", "Another stray"))
            cues.sortBy { it.startMs }

            val res = FftCrossCorrelator.correlateWindow(
                window = window,
                audioEnvelope = audioEnv,
                dialogueCues = cues,
                maxLagMs = 60_000L,
            )

            val errorMs = abs(res.offsetMs - expectedDelayMs)
            assertTrue(
                "For expectedDelay=$expectedDelayMs ms, got ${res.offsetMs} ms (error=$errorMs ms, conf=${res.confidence})",
                errorMs <= 40L,
            )
            assertTrue("Expected high confidence for $expectedDelayMs ms, got ${res.confidence}", res.confidence >= 0.65f)
        }
    }
}
