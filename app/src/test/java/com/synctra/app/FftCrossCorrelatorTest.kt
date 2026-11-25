package com.synctra.app

import com.synctra.app.domain.audio.SpeechActivityDetector
import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.sync.FftCrossCorrelator
import com.synctra.app.domain.sync.WindowAudioInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun fastPearsonMatchesNaiveExactPearsonWithinFloatingPointPrecision() {
        val window = SampleWindow(0, "Test", 600_000L, 20_000L)
        val n = 1000
        val maxLag = 200
        val audio = FloatArray(n) { i -> if ((i / 25) % 3 == 0) 0.85f else 0.05f }
        val subPadded = FloatArray(n + 2 * maxLag) { i -> if (((i + 17) / 25) % 3 == 0) 1.0f else 0.0f }

        val prepared = FftCrossCorrelator.prepareAudioWindow(window, audio)
        val prefix = FftCrossCorrelator.buildSubPrefix(subPadded)

        for (lag in listOf(-200, -73, -1, 0, 17, 88, 200)) {
            val expected = FftCrossCorrelator.exactPearsonAtLag(audio, subPadded, maxLag, lag)
            val actual = FftCrossCorrelator.fastPearsonAtLag(prepared, subPadded, prefix, maxLag, lag)
            assertEquals("Mismatch at lag=$lag", expected, actual, 1e-6)
        }
    }

    @Test
    fun correlateJointlyHandlesSilenceSingleWindowAndWideLagGracefully() {
        val w0 = SampleWindow(0, "Awal", 1_200_000L, 60_000L)
        val w1 = SampleWindow(1, "Akhir", 3_600_000L, 60_000L)
        val frameMs = SpeechActivityDetector.FRAME_DURATION_MS
        val count = (w0.durationMs / frameMs).toInt()
        val cues = listOf(
            SubtitleCue(1, 1_205_000L, 1_208_000L, "A", "A"),
            SubtitleCue(2, 1_220_000L, 1_224_000L, "B", "B"),
            SubtitleCue(3, 3_605_000L, 3_608_000L, "C", "C"),
            SubtitleCue(4, 3_620_000L, 3_624_000L, "D", "D"),
        )

        // < 2 windows returns null
        val activeEnv = FloatArray(count) { if (it in 250..400) 1f else 0f }
        assertNull(FftCrossCorrelator.correlateJointly(listOf(WindowAudioInput(w0, activeEnv)), cues))

        // All-silence windows return null
        val silent = FloatArray(count)
        assertNull(
            FftCrossCorrelator.correlateJointly(
                listOf(WindowAudioInput(w0, silent), WindowAudioInput(w1, silent)),
                cues,
            )
        )

        // Wide lag (+85s) recovered when maxLagMs = 120_000L
        val wideOffsetMs = 85_000L
        val patterns = listOf(
            listOf(4_000L to 7_500L, 16_000L to 19_500L, 32_000L to 36_000L, 48_000L to 52_000L),
            listOf(6_000L to 9_500L, 21_000L to 25_000L, 37_000L to 41_500L, 50_000L to 54_000L),
        )
        val wideCues = mutableListOf<SubtitleCue>()
        var idx = 1
        val inputs = listOf(w0, w1).mapIndexed { wIdx, w ->
            val env = FloatArray(count)
            for ((s, e) in patterns[wIdx]) {
                wideCues.add(SubtitleCue(idx++, w.startMs + s - wideOffsetMs, w.startMs + e - wideOffsetMs, "L$idx", "L$idx"))
                val i0 = (s / frameMs).toInt().coerceIn(0, count - 1)
                val i1 = (e / frameMs).toInt().coerceIn(i0, count - 1)
                for (i in i0..i1) env[i] = 1.0f
            }
            WindowAudioInput(w, env)
        }

        val joint = FftCrossCorrelator.correlateJointly(inputs, wideCues, maxLagMs = 120_000L)
        assertNotNull("Expected joint correlation to recover +85s lag within +/-120s", joint)
        assertTrue(
            "Expected window 0 offset near +85000ms, got ${joint!![0].offsetMs}ms",
            abs(joint[0].offsetMs - wideOffsetMs) <= 80L,
        )
    }

    @Test
    fun correlateJointlyHandlesDuplicateWindowIndices() {
        val w0 = SampleWindow(0, "Awal", 1_200_000L, 60_000L)
        val w1 = SampleWindow(0, "Akhir", 3_600_000L, 60_000L)
        val frameMs = SpeechActivityDetector.FRAME_DURATION_MS
        val count = (w0.durationMs / frameMs).toInt()
        val expectedOffsetMs = 3_200L

        val patterns = listOf(
            listOf(4_000L to 7_500L, 16_000L to 19_500L, 32_000L to 36_000L, 48_000L to 52_000L),
            listOf(6_000L to 9_500L, 21_000L to 25_000L, 37_000L to 41_500L, 50_000L to 54_000L),
        )
        val cues = mutableListOf<SubtitleCue>()
        var idx = 1
        val inputs = listOf(w0, w1).mapIndexed { wIdx, w ->
            val env = FloatArray(count)
            for ((s, e) in patterns[wIdx]) {
                cues.add(SubtitleCue(idx++, w.startMs + s - expectedOffsetMs, w.startMs + e - expectedOffsetMs, "L$idx", "L$idx"))
                val i0 = (s / frameMs).toInt().coerceIn(0, count - 1)
                val i1 = (e / frameMs).toInt().coerceIn(i0, count - 1)
                for (i in i0..i1) env[i] = 1.0f
            }
            WindowAudioInput(w, env)
        }

        val joint = FftCrossCorrelator.correlateJointly(inputs, cues, maxLagMs = 60_000L)
        assertNotNull(joint)
        assertEquals(2, joint!!.size)
        assertEquals("Awal", joint[0].window.label)
        assertEquals("Akhir", joint[1].window.label)
        assertTrue(abs(joint[0].offsetMs - expectedOffsetMs) <= 80L)
        assertTrue(abs(joint[1].offsetMs - expectedOffsetMs) <= 80L)
    }
}
