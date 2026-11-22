package com.synctra.app.domain.sync

import com.synctra.app.domain.audio.SpeechActivityDetector
import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.WindowSyncResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fast two-stage normalized cross-correlation engine using pure-Kotlin Radix-2 FFT.
 *
 * Sign convention:
 *   offsetMs = audioTimeMs - subtitleTimeMs
 *   Applying this delay in VLC / MX Player / Nova: newSubtitleTime = originalSubtitleTime + offsetMs.
 */
object FftCrossCorrelator {

    private const val FRAME_MS = SpeechActivityDetector.FRAME_DURATION_MS // 20 ms
    private const val COARSE_DECIMATION = 5 // 100 ms coarse bins

    /**
     * Correlates an extracted audio speech-activity envelope (starting at [window.startMs])
     * against cleaned subtitle cues within `[-maxLagMs, +maxLagMs]`.
     */
    fun correlateWindow(
        window: SampleWindow,
        audioEnvelope: FloatArray,
        dialogueCues: List<SubtitleCue>,
        maxLagMs: Long = 60_000L,
    ): WindowSyncResult {
        if (audioEnvelope.size < 25 || dialogueCues.isEmpty()) {
            return WindowSyncResult(
                window = window,
                offsetMs = 0L,
                confidence = 0f,
                peakProminence = 0f,
                speechRatio = 0f,
            )
        }

        val speechRatio = audioEnvelope.count { it >= 0.45f }.toFloat() / audioEnvelope.size

        // Subtitle slice covers [window.startMs - maxLagMs, window.endMs + maxLagMs]
        // so that for any lag L in [-maxLagMs, +maxLagMs]:
        // audio[t] corresponds to subtitle at time (window.startMs + t*FRAME_MS - L)
        val maxLagFrames = (maxLagMs / FRAME_MS).toInt().coerceAtLeast(1)
        val subOriginMs = window.startMs - maxLagFrames.toLong() * FRAME_MS
        val subTotalFrames = audioEnvelope.size + 2 * maxLagFrames
        val subEnvelope = buildSubtitleEnvelope(
            cues = dialogueCues,
            originMs = subOriginMs,
            frameCount = subTotalFrames,
            frameMs = FRAME_MS,
        )

        // Check if subtitle slice has any dialogue at all
        var subSum = 0.0
        for (v in subEnvelope) subSum += v
        if (subSum < 5.0) {
            return WindowSyncResult(
                window = window,
                offsetMs = 0L,
                confidence = 0f,
                peakProminence = 0f,
                speechRatio = speechRatio,
            )
        }

        // Stage 1: Coarse FFT search at 100ms bins
        val coarseAudio = downsample(audioEnvelope, COARSE_DECIMATION)
        val coarseSub = downsample(subEnvelope, COARSE_DECIMATION)
        val coarseMaxLag = (maxLagFrames / COARSE_DECIMATION).coerceAtLeast(1)

        val coarseCorr = computeNormalizedFftCorrelation(
            audio = coarseAudio,
            subPadded = coarseSub,
            maxLagBins = coarseMaxLag,
        )

        // Pick top-5 well-separated coarse peaks (at least 1.5s apart)
        val candidateCoarseLags = selectTopPeaks(
            scores = coarseCorr,
            maxLagBins = coarseMaxLag,
            topK = 5,
            minSeparationBins = 15, // 1.5s
        )

        // Stage 2: Fine exact Pearson search at 20ms resolution around each coarse peak
        var bestFineLagFrames = 0
        var bestFineScore = -2.0
        val expandedRadius = 50 // +/- 1.0s around each coarse peak

        val fineScoresAroundBest = HashMap<Int, Double>()

        for (coarseLag in candidateCoarseLags) {
            val centerFine = coarseLag * COARSE_DECIMATION
            val lo = (centerFine - expandedRadius).coerceAtLeast(-maxLagFrames)
            val hi = (centerFine + expandedRadius).coerceAtMost(maxLagFrames)

            for (lag in lo..hi) {
                val score = exactPearsonAtLag(
                    audio = audioEnvelope,
                    subPadded = subEnvelope,
                    maxLagFrames = maxLagFrames,
                    lagFrames = lag,
                )
                fineScoresAroundBest[lag] = score
                if (score > bestFineScore) {
                    bestFineScore = score
                    bestFineLagFrames = lag
                }
            }
        }

        // Parabolic sub-frame interpolation around the best fine peak for ~10ms precision
        val yMinus = fineScoresAroundBest[bestFineLagFrames - 1]
            ?: exactPearsonAtLag(audioEnvelope, subEnvelope, maxLagFrames, (bestFineLagFrames - 1).coerceAtLeast(-maxLagFrames))
        val yZero = bestFineScore
        val yPlus = fineScoresAroundBest[bestFineLagFrames + 1]
            ?: exactPearsonAtLag(audioEnvelope, subEnvelope, maxLagFrames, (bestFineLagFrames + 1).coerceAtMost(maxLagFrames))

        val denom = (yMinus - 2.0 * yZero + yPlus)
        val delta = if (abs(denom) > 1e-9 && yZero >= yMinus && yZero >= yPlus) {
            (0.5 * (yMinus - yPlus) / denom).coerceIn(-0.5, 0.5)
        } else {
            0.0
        }

        val refinedOffsetMs = ((bestFineLagFrames + delta) * FRAME_MS).toLong()

        // Compute peak prominence against background coarse scores outside +/- 3s of the peak
        val bestCoarseIndex = ((bestFineLagFrames / COARSE_DECIMATION) + coarseMaxLag)
            .coerceIn(0, coarseCorr.lastIndex)
        var bgSum = 0.0
        var bgSqSum = 0.0
        var bgCount = 0
        for (i in coarseCorr.indices) {
            if (abs(i - bestCoarseIndex) > 30) { // > 3 seconds away
                val v = coarseCorr[i]
                bgSum += v
                bgSqSum += v * v
                bgCount++
            }
        }
        val bgMean = if (bgCount > 0) bgSum / bgCount else 0.0
        val bgStd = if (bgCount > 1) {
            sqrt(((bgSqSum / bgCount) - bgMean * bgMean).coerceAtLeast(1e-6))
        } else {
            0.15
        }
        val prominence = (((bestFineScore - bgMean) / bgStd).toFloat()).coerceAtLeast(0f)

        // Calibrate confidence [0..1] combining correlation strength and peak prominence
        val corrTerm = ((bestFineScore - 0.06) / 0.42).coerceIn(0.0, 1.0)
        val promTerm = ((prominence - 1.8) / 4.2).coerceIn(0.0, 1.0)
        val confidence = (0.45 * corrTerm + 0.55 * promTerm).toFloat().coerceIn(0f, 1f)

        return WindowSyncResult(
            window = window,
            offsetMs = refinedOffsetMs,
            confidence = confidence,
            peakProminence = prominence,
            speechRatio = speechRatio,
        )
    }

    internal fun buildSubtitleEnvelope(
        cues: List<SubtitleCue>,
        originMs: Long,
        frameCount: Int,
        frameMs: Int,
    ): FloatArray {
        val env = FloatArray(frameCount)
        val windowEndMs = originMs + frameCount.toLong() * frameMs

        for (cue in cues) {
            if (cue.endMs <= originMs || cue.startMs >= windowEndMs) continue
            val startIdx = ((cue.startMs - originMs) / frameMs).toInt().coerceIn(0, frameCount - 1)
            val endIdx = ((cue.endMs - originMs) / frameMs).toInt().coerceIn(startIdx, frameCount - 1)
            for (i in startIdx..endIdx) {
                env[i] = 1.0f
            }
            // Soft 1-frame edge taper improves correlation stability
            if (startIdx > 0 && env[startIdx - 1] < 0.5f) env[startIdx - 1] = 0.5f
            if (endIdx + 1 < frameCount && env[endIdx + 1] < 0.5f) env[endIdx + 1] = 0.5f
        }
        return env
    }

    internal fun downsample(input: FloatArray, factor: Int): FloatArray {
        val outLen = input.size / factor
        if (outLen == 0) return FloatArray(0)
        return FloatArray(outLen) { i ->
            var sum = 0f
            val base = i * factor
            for (k in 0 until factor) sum += input[base + k]
            sum / factor
        }
    }

    /**
     * Exact Pearson correlation for a specific [lagFrames] where:
     * audio[t] aligns with subPadded[maxLagFrames - lagFrames + t]
     *
     * Proof of sign:
     *   subOrigin = windowStart - maxLag
     *   audioTime = windowStart + t
     *   subTime   = audioTime - lag = windowStart + t - lag = subOrigin + (maxLag - lag) + t
     */
    internal fun exactPearsonAtLag(
        audio: FloatArray,
        subPadded: FloatArray,
        maxLagFrames: Int,
        lagFrames: Int,
    ): Double {
        val n = audio.size
        val subStart = maxLagFrames - lagFrames
        if (subStart < 0 || subStart + n > subPadded.size) return -1.0

        var sumA = 0.0
        var sumS = 0.0
        for (i in 0 until n) {
            sumA += audio[i]
            sumS += subPadded[subStart + i]
        }
        val meanA = sumA / n
        val meanS = sumS / n

        var num = 0.0
        var denA = 0.0
        var denS = 0.0
        for (i in 0 until n) {
            val da = audio[i] - meanA
            val ds = subPadded[subStart + i] - meanS
            num += da * ds
            denA += da * da
            denS += ds * ds
        }
        val denom = sqrt(denA * denS)
        return if (denom > 1e-9) num / denom else 0.0
    }

    /**
     * Computes normalized cross-correlation for all lags in `[-maxLagBins, +maxLagBins]`
     * in O(N log N) via Radix-2 FFT.
     *
     * Returns array of length `2 * maxLagBins + 1`, indexed by `lag + maxLagBins`.
     */
    internal fun computeNormalizedFftCorrelation(
        audio: FloatArray,
        subPadded: FloatArray,
        maxLagBins: Int,
    ): DoubleArray {
        val n = audio.size
        val m = subPadded.size
        val outLen = 2 * maxLagBins + 1
        if (n == 0 || m < n) return DoubleArray(outLen)

        // Zero-mean audio & compute its L2 norm
        var sumA = 0.0
        for (v in audio) sumA += v
        val meanA = sumA / n
        var energyA = 0.0
        val aCentered = DoubleArray(n) { i ->
            val d = audio[i] - meanA
            energyA += d * d
            d
        }
        if (energyA <= 1e-9) return DoubleArray(outLen)
        val normA = sqrt(energyA)

        // Prefix sums of subPadded and subPadded^2 to compute sliding window mean & variance in O(1)
        val prefixS = DoubleArray(m + 1)
        val prefixS2 = DoubleArray(m + 1)
        for (i in 0 until m) {
            val v = subPadded[i].toDouble()
            prefixS[i + 1] = prefixS[i] + v
            prefixS2[i + 1] = prefixS2[i] + v * v
        }

        // Cross-correlation dot[s] = sum_{t=0}^{n-1} aCentered[t] * subPadded[s + t]
        // = IFFT( FFT(subPadded) * conj(FFT(aCentered)) )[s]
        val fftSize = nextPowerOfTwo(m + n)
        val sRe = DoubleArray(fftSize)
        val sIm = DoubleArray(fftSize)
        val aRe = DoubleArray(fftSize)
        val aIm = DoubleArray(fftSize)

        for (i in 0 until m) sRe[i] = subPadded[i].toDouble()
        for (i in 0 until n) aRe[i] = aCentered[i]

        fft(sRe, sIm, inverse = false)
        fft(aRe, aIm, inverse = false)

        // Multiply S * conj(A)
        for (i in 0 until fftSize) {
            val sr = sRe[i]
            val si = sIm[i]
            val ar = aRe[i]
            val ai = aIm[i]
            sRe[i] = sr * ar + si * ai
            sIm[i] = si * ar - sr * ai
        }
        fft(sRe, sIm, inverse = true)

        val result = DoubleArray(outLen)
        for (lag in -maxLagBins..maxLagBins) {
            val s = maxLagBins - lag
            if (s < 0 || s + n > m) continue

            // Because aCentered has zero mean, dot(aCentered, sub[s..s+n]) == dot(aCentered, sub[s..s+n] - meanS)
            val dot = sRe[s]
            val sumS = prefixS[s + n] - prefixS[s]
            val sumS2 = prefixS2[s + n] - prefixS2[s]
            val varS = (sumS2 - (sumS * sumS) / n).coerceAtLeast(0.0)
            val denom = normA * sqrt(varS)
            result[lag + maxLagBins] = if (denom > 1e-9) (dot / denom).coerceIn(-1.0, 1.0) else 0.0
        }
        return result
    }

    private fun selectTopPeaks(
        scores: DoubleArray,
        maxLagBins: Int,
        topK: Int,
        minSeparationBins: Int,
    ): List<Int> {
        val indicesByScore = scores.indices.sortedByDescending { scores[it] }
        val selected = mutableListOf<Int>()
        for (idx in indicesByScore) {
            if (selected.none { abs(it - idx) < minSeparationBins }) {
                selected.add(idx)
                if (selected.size >= topK) break
            }
        }
        return selected.map { it - maxLagBins }
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var p = 1
        while (p < v) p = p shl 1
        return p
    }

    /**
     * In-place iterative Cooley-Tukey Radix-2 FFT.
     */
    internal fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        val sign = if (inverse) 1.0 else -1.0
        while (len <= n) {
            val ang = sign * 2.0 * PI / len
            val wLenRe = cos(ang)
            val wLenIm = sin(ang)
            val half = len shr 1

            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe

                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm

                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}
