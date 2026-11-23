package com.synctra.app.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight, streaming, speech-aware Voice Activity Detector (VAD) in pure Kotlin.
 *
 * Automatically decimates high-rate movie audio (e.g., 48 kHz -> 16 kHz, 44.1 kHz -> 22.05 kHz)
 * with a boxcar prefilter before running the 2nd-order Butterworth bandpass filter (300 - 3400 Hz),
 * cutting VAD CPU work by ~3x on standard 48 kHz tracks with zero loss in voice-band accuracy.
 *
 * Outputs a smooth [0f, 1f] speech activity envelope at 50 Hz (20 ms per frame).
 */
class SpeechActivityDetector(
    private val sampleRate: Int = TARGET_SAMPLE_RATE,
    val frameDurationMs: Int = FRAME_DURATION_MS,
) {
    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val FRAME_DURATION_MS = 20
        const val FRAMES_PER_SECOND = 1000 / FRAME_DURATION_MS // 50 Hz
    }

    // Integer decimation factor: 48kHz -> 3 (16kHz), 44.1kHz -> 2 (22.05kHz), 32kHz -> 2 (16kHz), <=24kHz -> 1
    internal val decimationFactor: Int = (sampleRate / TARGET_SAMPLE_RATE).coerceAtLeast(1)
    internal val effectiveSampleRate: Double = sampleRate.toDouble() / decimationFactor
    private val frameSizeSamples: Int = (effectiveSampleRate * frameDurationMs / 1000.0).toInt().coerceAtLeast(1)

    /**
     * Streaming collector that consumes decoded PCM chunks and keeps only compact per-frame
     * feature values (~12 bytes per 20 ms) instead of raw PCM.
     */
    inner class StreamingCollector {
        private val bandpass = BiquadBandpass(effectiveSampleRate, lowHz = 300.0, highHz = 3400.0)

        private var decimAccum = 0.0
        private var decimCount = 0

        private var sampleCountInFrame = 0
        private var sumSqFull = 0.0
        private var sumSqBand = 0.0
        private var zeroCrossings = 0
        private var prevBandSample = 0.0

        private val bandRmsList = ArrayList<Float>(4096)
        private val bandRatioList = ArrayList<Float>(4096)
        private val zcrList = ArrayList<Float>(4096)

        fun pushSamples(monoSamples: FloatArray, count: Int = monoSamples.size) {
            for (i in 0 until count) {
                feedMonoSample(monoSamples[i].toDouble())
            }
        }

        fun pushPcm16(pcm16: ShortArray, sampleCount: Int, channels: Int) {
            if (channels <= 0 || sampleCount <= 0) return
            val frames = sampleCount / channels
            val invScale = 1.0 / (32768.0 * channels)
            for (f in 0 until frames) {
                var sum = 0.0
                val base = f * channels
                for (c in 0 until channels) {
                    sum += pcm16[base + c]
                }
                feedMonoSample(sum * invScale)
            }
        }

        private fun feedMonoSample(sample: Double) {
            if (decimationFactor > 1) {
                decimAccum += sample
                decimCount++
                if (decimCount < decimationFactor) return
                val downsampled = decimAccum / decimationFactor
                decimAccum = 0.0
                decimCount = 0
                processDecimatedSample(downsampled)
            } else {
                processDecimatedSample(sample)
            }
        }

        private fun processDecimatedSample(x: Double) {
            val y = bandpass.process(x)
            sumSqFull += x * x
            sumSqBand += y * y
            if (sampleCountInFrame > 0 && ((y >= 0.0 && prevBandSample < 0.0) || (y < 0.0 && prevBandSample >= 0.0))) {
                zeroCrossings++
            }
            prevBandSample = y
            sampleCountInFrame++

            if (sampleCountInFrame >= frameSizeSamples) {
                finalizeFrame()
            }
        }

        private fun finalizeFrame() {
            val n = sampleCountInFrame.coerceAtLeast(1)
            val fullRms = sqrt(sumSqFull / n).toFloat()
            val bandRms = sqrt(sumSqBand / n).toFloat()
            val ratio = if (fullRms > 1e-6f) (bandRms / (fullRms + 1e-6f)).coerceIn(0f, 1.5f) else 0f
            val zcr = zeroCrossings.toFloat() / n

            bandRmsList.add(bandRms)
            bandRatioList.add(ratio)
            zcrList.add(zcr)

            sampleCountInFrame = 0
            sumSqFull = 0.0
            sumSqBand = 0.0
            zeroCrossings = 0
        }

        fun buildEnvelope(): FloatArray {
            if (sampleCountInFrame > frameSizeSamples / 2) {
                finalizeFrame()
            }
            return computeSpeechEnvelope(bandRmsList, bandRatioList, zcrList)
        }
    }

    fun detectEnvelope(monoSamples: FloatArray): FloatArray {
        val collector = StreamingCollector()
        collector.pushSamples(monoSamples)
        return collector.buildEnvelope()
    }

    private fun computeSpeechEnvelope(
        bandRms: List<Float>,
        bandRatio: List<Float>,
        zcr: List<Float>,
    ): FloatArray {
        val n = bandRms.size
        if (n == 0) return FloatArray(0)

        val logEnergy = FloatArray(n) { i ->
            ln(bandRms[i].toDouble() + 1e-5).toFloat()
        }

        val sorted = logEnergy.sortedArray()
        val noiseFloor = sorted[(n * 0.22).toInt().coerceIn(0, n - 1)]
        val highLevel = sorted[(n * 0.88).toInt().coerceIn(0, n - 1)]
        val dynamicRange = (highLevel - noiseFloor).coerceAtLeast(0.5f)

        if (highLevel < -9.5f) {
            return FloatArray(n)
        }

        val modWindow = 10
        val rawScore = FloatArray(n)
        val effSr = effectiveSampleRate.toFloat()

        for (i in 0 until n) {
            val energyNorm = ((logEnergy[i] - (noiseFloor + 0.22f * dynamicRange)) / (0.58f * dynamicRange))
                .coerceIn(0f, 1f)

            val ratioScore = ((bandRatio[i] - 0.18f) / 0.37f).coerceIn(0f, 1f)

            val zcrHz = zcr[i] * effSr * 0.5f
            val zcrScore = when {
                zcrHz < 120f -> 0.25f
                zcrHz in 120f..3200f -> 1.0f
                zcrHz in 3200f..4500f -> 0.65f
                else -> 0.2f
            }

            val lo = (i - modWindow).coerceAtLeast(0)
            val hi = (i + modWindow).coerceAtMost(n - 1)
            var mean = 0f
            for (k in lo..hi) mean += logEnergy[k]
            mean /= (hi - lo + 1)
            var variance = 0f
            for (k in lo..hi) {
                val d = logEnergy[k] - mean
                variance += d * d
            }
            val std = sqrt((variance / (hi - lo + 1)).toDouble()).toFloat()
            val modScore = (0.55f + (std / 1.4f).coerceIn(0f, 0.45f))

            rawScore[i] = (energyNorm * (0.35f + 0.65f * ratioScore) * zcrScore * modScore)
                .coerceIn(0f, 1f)
        }

        val smoothed = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            var cnt = 0
            for (k in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(n - 1)) {
                acc += rawScore[k]
                cnt++
            }
            smoothed[i] = acc / cnt
        }

        val threshold = 0.28f
        val active = BooleanArray(n)
        var hangover = 0
        for (i in 0 until n) {
            if (smoothed[i] >= threshold) {
                active[i] = true
                hangover = 7
            } else if (hangover > 0) {
                active[i] = true
                hangover--
            }
        }

        var runStart = -1
        for (i in 0..n) {
            val isOn = i < n && active[i]
            if (isOn && runStart == -1) {
                runStart = i
            } else if (!isOn && runStart != -1) {
                if (i - runStart < 3) {
                    for (k in runStart until i) active[k] = false
                }
                runStart = -1
            }
        }

        return FloatArray(n) { i ->
            if (active[i]) (0.45f + 0.55f * smoothed[i]).coerceIn(0f, 1f) else smoothed[i] * 0.15f
        }
    }

    private class BiquadBandpass(sampleRate: Double, lowHz: Double, highHz: Double) {
        private val hp = Biquad.highPass(sampleRate, lowHz.coerceAtMost(sampleRate * 0.45))
        private val lp = Biquad.lowPass(sampleRate, highHz.coerceAtMost(sampleRate * 0.45))

        fun process(x: Double): Double = lp.process(hp.process(x))
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            fun lowPass(fs: Double, fc: Double, q: Double = 0.70710678): Biquad {
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = ((1.0 - cosW0) / 2.0) / a0,
                    b1 = (1.0 - cosW0) / a0,
                    b2 = ((1.0 - cosW0) / 2.0) / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }

            fun highPass(fs: Double, fc: Double, q: Double = 0.70710678): Biquad {
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = ((1.0 + cosW0) / 2.0) / a0,
                    b1 = -(1.0 + cosW0) / a0,
                    b2 = ((1.0 + cosW0) / 2.0) / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }
        }
    }
}
