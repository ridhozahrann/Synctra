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
     * Reusable precomputed audio window state shared across 1.0x correlation, FPS candidates,
     * and joint pooled correlation so audio centering, downsampling, and coarse FFT are never
     * recomputed for the same extracted window.
     */
    internal class PreparedAudioWindow(
        val window: SampleWindow,
        val audioEnvelope: FloatArray,
        val speechRatio: Float,
        val aCentered: DoubleArray,
        val normA: Double,
        val coarseAudio: FloatArray,
        val coarseACentered: DoubleArray,
        val coarseNormA: Double,
    ) {
        private var cachedFftSize: Int = -1
        private var cachedCoarseFftRe: DoubleArray? = null
        private var cachedCoarseFftIm: DoubleArray? = null

        fun getCoarseFft(fftSize: Int): Pair<DoubleArray, DoubleArray> {
            val reCached = cachedCoarseFftRe
            val imCached = cachedCoarseFftIm
            if (cachedFftSize == fftSize && reCached != null && imCached != null) {
                return reCached to imCached
            }
            val aRe = DoubleArray(fftSize)
            val aIm = DoubleArray(fftSize)
            for (i in coarseACentered.indices) aRe[i] = coarseACentered[i]
            fft(aRe, aIm, inverse = false)
            cachedFftSize = fftSize
            cachedCoarseFftRe = aRe
            cachedCoarseFftIm = aIm
            return aRe to aIm
        }
    }

    internal class SubEnvelopePrefix(
        val prefixS: DoubleArray,
        val prefixS2: DoubleArray,
    )

    internal fun prepareAudioWindow(
        window: SampleWindow,
        audioEnvelope: FloatArray,
    ): PreparedAudioWindow {
        val n = audioEnvelope.size
        val speechRatio = if (n > 0) {
            audioEnvelope.count { it >= 0.45f }.toFloat() / n
        } else {
            0f
        }

        val (aCentered, normA) = centerAndNorm(audioEnvelope)
        val coarseAudio = downsample(audioEnvelope, COARSE_DECIMATION)
        val (coarseACentered, coarseNormA) = centerAndNorm(coarseAudio)

        return PreparedAudioWindow(
            window = window,
            audioEnvelope = audioEnvelope,
            speechRatio = speechRatio,
            aCentered = aCentered,
            normA = normA,
            coarseAudio = coarseAudio,
            coarseACentered = coarseACentered,
            coarseNormA = coarseNormA,
        )
    }

    private fun centerAndNorm(signal: FloatArray): Pair<DoubleArray, Double> {
        val n = signal.size
        if (n == 0) return DoubleArray(0) to 0.0
        var sum = 0.0
        for (v in signal) sum += v
        val mean = sum / n
        var energy = 0.0
        val centered = DoubleArray(n) { i ->
            val d = signal[i] - mean
            energy += d * d
            d
        }
        return centered to sqrt(energy)
    }

    internal fun buildSubPrefix(subPadded: FloatArray): SubEnvelopePrefix {
        val m = subPadded.size
        val prefixS = DoubleArray(m + 1)
        val prefixS2 = DoubleArray(m + 1)
        for (i in 0 until m) {
            val v = subPadded[i].toDouble()
            prefixS[i + 1] = prefixS[i] + v
            prefixS2[i + 1] = prefixS2[i] + v * v
        }
        return SubEnvelopePrefix(prefixS, prefixS2)
    }

    /**
     * Correlates an extracted audio speech-activity envelope (starting at [window.startMs])
     * against cleaned subtitle cues within `[-maxLagMs, +maxLagMs]`.
     */
    fun correlateWindow(
        window: SampleWindow,
        audioEnvelope: FloatArray,
        dialogueCues: List<SubtitleCue>,
        maxLagMs: Long = 60_000L,
    ): WindowSyncResult = correlateWindow(
        prepared = prepareAudioWindow(window, audioEnvelope),
        dialogueCues = dialogueCues,
        maxLagMs = maxLagMs,
    )

    internal fun correlateWindow(
        prepared: PreparedAudioWindow,
        dialogueCues: List<SubtitleCue>,
        maxLagMs: Long = 60_000L,
    ): WindowSyncResult {
        val window = prepared.window
        val audioEnvelope = prepared.audioEnvelope
        if (audioEnvelope.size < 25 || dialogueCues.isEmpty()) {
            return WindowSyncResult(
                window = window,
                offsetMs = 0L,
                confidence = 0f,
                peakProminence = 0f,
                speechRatio = 0f,
            )
        }

        val speechRatio = prepared.speechRatio

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

        // Stage 1: Coarse FFT search at 100ms bins (reusing cached coarse audio FFT)
        val coarseSub = downsample(subEnvelope, COARSE_DECIMATION)
        val coarseMaxLag = (maxLagFrames / COARSE_DECIMATION).coerceAtLeast(1)

        val coarseCorr = computeNormalizedFftCorrelation(
            prepared = prepared,
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
        val subPrefix = buildSubPrefix(subEnvelope)
        var bestFineLagFrames = 0
        var bestFineScore = -2.0
        var bestYMinus = Double.NaN
        var bestYPlus = Double.NaN
        val expandedRadius = 50 // +/- 1.0s around each coarse peak
        val peakScores = DoubleArray(2 * expandedRadius + 1)

        for (coarseLag in candidateCoarseLags) {
            val centerFine = coarseLag * COARSE_DECIMATION
            val lo = (centerFine - expandedRadius).coerceAtLeast(-maxLagFrames)
            val hi = (centerFine + expandedRadius).coerceAtMost(maxLagFrames)
            var peakBestLag = lo
            var peakBestScore = -2.0

            for (lag in lo..hi) {
                val score = fastPearsonAtLag(
                    preparedAudio = prepared,
                    subPadded = subEnvelope,
                    subPrefix = subPrefix,
                    maxLagFrames = maxLagFrames,
                    lagFrames = lag,
                )
                peakScores[lag - lo] = score
                if (score > peakBestScore) {
                    peakBestScore = score
                    peakBestLag = lag
                }
            }

            if (peakBestScore > bestFineScore) {
                bestFineScore = peakBestScore
                bestFineLagFrames = peakBestLag
                bestYMinus = if (peakBestLag > lo) peakScores[peakBestLag - 1 - lo] else Double.NaN
                bestYPlus = if (peakBestLag < hi) peakScores[peakBestLag + 1 - lo] else Double.NaN
            }
        }

        // Parabolic sub-frame interpolation around the best fine peak for ~10ms precision
        val yMinus = if (!bestYMinus.isNaN()) {
            bestYMinus
        } else {
            fastPearsonAtLag(prepared, subEnvelope, subPrefix, maxLagFrames, (bestFineLagFrames - 1).coerceAtLeast(-maxLagFrames))
        }
        val yZero = bestFineScore
        val yPlus = if (!bestYPlus.isNaN()) {
            bestYPlus
        } else {
            fastPearsonAtLag(prepared, subEnvelope, subPrefix, maxLagFrames, (bestFineLagFrames + 1).coerceAtMost(maxLagFrames))
        }

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

    /**
     * Joint multi-window pooled correlation for constant-offset movies where individual windows
     * have heavy BGM/SFX noise. Sums normalized coarse correlation curves across all windows so
     * the true shared offset adds constructively while random local noise peaks cancel out.
     *
     * Runs purely on the already-extracted 50 Hz envelopes (~2-5 ms CPU, zero extra decoding).
     * Returns null if windows do not share a prominent common peak.
     */
    fun correlateJointly(
        windows: List<WindowAudioInput>,
        dialogueCues: List<SubtitleCue>,
        maxLagMs: Long = 60_000L,
    ): List<WindowSyncResult>? = correlateJointlyPrepared(
        preparedWindows = windows.map { prepareAudioWindow(it.window, it.speechEnvelope) },
        dialogueCues = dialogueCues,
        maxLagMs = maxLagMs,
    )

    internal fun correlateJointlyPrepared(
        preparedWindows: List<PreparedAudioWindow>,
        dialogueCues: List<SubtitleCue>,
        maxLagMs: Long = 60_000L,
    ): List<WindowSyncResult>? {
        if (preparedWindows.size < 2 || dialogueCues.isEmpty()) return null

        val maxLagFrames = (maxLagMs / FRAME_MS).toInt().coerceAtLeast(1)
        val coarseMaxLag = (maxLagFrames / COARSE_DECIMATION).coerceAtLeast(1)
        val coarseLen = 2 * coarseMaxLag + 1

        data class JointWindowState(
            val audio: PreparedAudioWindow,
            val weight: Double,
            val subEnvelope: FloatArray,
            val subPrefix: SubEnvelopePrefix,
            val coarseCorr: DoubleArray,
        )

        val prepared = ArrayList<JointWindowState>(preparedWindows.size)
        for (audio in preparedWindows) {
            val env = audio.audioEnvelope
            if (env.size < 25 || audio.normA <= 1e-9) continue
            val subOriginMs = audio.window.startMs - maxLagFrames.toLong() * FRAME_MS
            val subTotalFrames = env.size + 2 * maxLagFrames
            val subEnv = buildSubtitleEnvelope(dialogueCues, subOriginMs, subTotalFrames, FRAME_MS)

            var subSum = 0.0
            for (v in subEnv) subSum += v
            if (subSum < 5.0) continue

            val coarseSub = downsample(subEnv, COARSE_DECIMATION)
            val coarseCorr = computeNormalizedFftCorrelation(audio, coarseSub, coarseMaxLag)
            val subPrefix = buildSubPrefix(subEnv)
            val weight = audio.speechRatio.coerceIn(0.08f, 0.65f).toDouble()
            prepared.add(JointWindowState(audio, weight, subEnv, subPrefix, coarseCorr))
        }

        if (prepared.size < 2) return null

        val totalWeight = prepared.sumOf { it.weight }.coerceAtLeast(1e-6)
        val pooledCoarse = DoubleArray(coarseLen)
        for (pw in prepared) {
            val w = pw.weight / totalWeight
            for (i in 0 until coarseLen) {
                pooledCoarse[i] += pw.coarseCorr[i] * w
            }
        }

        val topPooledLags = selectTopPeaks(
            scores = pooledCoarse,
            maxLagBins = coarseMaxLag,
            topK = 3,
            minSeparationBins = 15,
        )

        var bestJointLag = 0
        var bestJointScore = -2.0
        val fineRadius = 25 // +/- 0.5s around each 100ms coarse peak

        for (coarseLag in topPooledLags) {
            val center = coarseLag * COARSE_DECIMATION
            val lo = (center - fineRadius).coerceAtLeast(-maxLagFrames)
            val hi = (center + fineRadius).coerceAtMost(maxLagFrames)
            for (lag in lo..hi) {
                var sum = 0.0
                for (pw in prepared) {
                    val s = fastPearsonAtLag(pw.audio, pw.subEnvelope, pw.subPrefix, maxLagFrames, lag)
                    sum += s * pw.weight
                }
                val score = sum / totalWeight
                if (score > bestJointScore) {
                    bestJointScore = score
                    bestJointLag = lag
                }
            }
        }

        // Measure prominence of the pooled peak against pooled background (> 3s away)
        val bestCoarseIdx = ((bestJointLag / COARSE_DECIMATION) + coarseMaxLag).coerceIn(0, coarseLen - 1)
        var bgSum = 0.0
        var bgSqSum = 0.0
        var bgCount = 0
        for (i in 0 until coarseLen) {
            if (abs(i - bestCoarseIdx) > 30) {
                val v = pooledCoarse[i]
                bgSum += v
                bgSqSum += v * v
                bgCount++
            }
        }
        val bgMean = if (bgCount > 0) bgSum / bgCount else 0.0
        val bgStd = if (bgCount > 1) {
            sqrt(((bgSqSum / bgCount) - bgMean * bgMean).coerceAtLeast(1e-6))
        } else {
            0.12
        }
        val jointProminence = (((bestJointScore - bgMean) / bgStd).toFloat()).coerceAtLeast(0f)

        if (bestJointScore < 0.12 || jointProminence < 3.0f) {
            return null
        }

        // Locally refine each window within +/- 300ms around the pooled lag using a reusable flat array
        val localRadius = 15 // +/- 300ms
        val localScores = DoubleArray(2 * localRadius + 1)
        var supportingWindows = 0
        val resultsByIndex = HashMap<Int, WindowSyncResult>(prepared.size)

        for (pw in prepared) {
            val lo = (bestJointLag - localRadius).coerceAtLeast(-maxLagFrames)
            val hi = (bestJointLag + localRadius).coerceAtMost(maxLagFrames)
            var bestLocalLag = bestJointLag
            var bestLocalScore = -2.0

            for (lag in lo..hi) {
                val sc = fastPearsonAtLag(pw.audio, pw.subEnvelope, pw.subPrefix, maxLagFrames, lag)
                localScores[lag - lo] = sc
                if (sc > bestLocalScore) {
                    bestLocalScore = sc
                    bestLocalLag = lag
                }
            }

            val yMinus = if (bestLocalLag > lo) {
                localScores[bestLocalLag - 1 - lo]
            } else {
                fastPearsonAtLag(pw.audio, pw.subEnvelope, pw.subPrefix, maxLagFrames, (bestLocalLag - 1).coerceAtLeast(-maxLagFrames))
            }
            val yZero = bestLocalScore
            val yPlus = if (bestLocalLag < hi) {
                localScores[bestLocalLag + 1 - lo]
            } else {
                fastPearsonAtLag(pw.audio, pw.subEnvelope, pw.subPrefix, maxLagFrames, (bestLocalLag + 1).coerceAtMost(maxLagFrames))
            }
            val denom = yMinus - 2.0 * yZero + yPlus
            val delta = if (abs(denom) > 1e-9 && yZero >= yMinus && yZero >= yPlus) {
                (0.5 * (yMinus - yPlus) / denom).coerceIn(-0.5, 0.5)
            } else {
                0.0
            }
            val refinedMs = ((bestLocalLag + delta) * FRAME_MS).toLong()

            // Local prominence against this window's own background
            val localCoarseIdx = ((bestLocalLag / COARSE_DECIMATION) + coarseMaxLag).coerceIn(0, coarseLen - 1)
            var wBgSum = 0.0
            var wBgSq = 0.0
            var wBgCnt = 0
            for (i in 0 until coarseLen) {
                if (abs(i - localCoarseIdx) > 30) {
                    val v = pw.coarseCorr[i]
                    wBgSum += v
                    wBgSq += v * v
                    wBgCnt++
                }
            }
            val wMean = if (wBgCnt > 0) wBgSum / wBgCnt else 0.0
            val wStd = if (wBgCnt > 1) sqrt(((wBgSq / wBgCnt) - wMean * wMean).coerceAtLeast(1e-6)) else 0.15
            val localProm = (((bestLocalScore - wMean) / wStd).toFloat()).coerceAtLeast(0f)

            if (bestLocalScore >= 0.09 && localProm >= 1.6f) {
                supportingWindows++
            }

            val effectiveProm = maxOf(localProm, jointProminence * 0.75f)
            val corrTerm = ((bestLocalScore - 0.06) / 0.42).coerceIn(0.0, 1.0)
            val promTerm = ((effectiveProm - 1.8) / 4.2).coerceIn(0.0, 1.0)
            val conf = (0.45 * corrTerm + 0.55 * promTerm).toFloat().coerceIn(0f, 0.92f)

            resultsByIndex[pw.audio.window.index] = WindowSyncResult(
                window = pw.audio.window,
                offsetMs = refinedMs,
                confidence = conf,
                peakProminence = effectiveProm,
                speechRatio = pw.audio.speechRatio,
            )
        }

        if (supportingWindows < 2) return null

        return preparedWindows.map { audio ->
            resultsByIndex[audio.window.index] ?: WindowSyncResult(
                window = audio.window,
                offsetMs = bestJointLag.toLong() * FRAME_MS,
                confidence = 0f,
                peakProminence = 0f,
                speechRatio = audio.speechRatio,
            )
        }
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
     * Fast exact Pearson correlation using pre-centered audio and O(1) prefix sums of [subPadded].
     * Algebraically identical to [exactPearsonAtLag] because zero-mean `aCentered` satisfies
     * `dot(aCentered, sub - meanS) == dot(aCentered, sub)`.
     */
    internal fun fastPearsonAtLag(
        preparedAudio: PreparedAudioWindow,
        subPadded: FloatArray,
        subPrefix: SubEnvelopePrefix,
        maxLagFrames: Int,
        lagFrames: Int,
    ): Double {
        val aCentered = preparedAudio.aCentered
        val n = aCentered.size
        val subStart = maxLagFrames - lagFrames
        if (subStart < 0 || subStart + n > subPadded.size) return -1.0
        if (preparedAudio.normA <= 1e-9) return 0.0

        var num = 0.0
        for (i in 0 until n) {
            num += aCentered[i] * subPadded[subStart + i]
        }

        val sumS = subPrefix.prefixS[subStart + n] - subPrefix.prefixS[subStart]
        val sumS2 = subPrefix.prefixS2[subStart + n] - subPrefix.prefixS2[subStart]
        val varS = (sumS2 - (sumS * sumS) / n).coerceAtLeast(0.0)
        val denom = preparedAudio.normA * sqrt(varS)
        return if (denom > 1e-9) (num / denom).coerceIn(-1.0, 1.0) else 0.0
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
     * in O(N log N) via Radix-2 FFT, reusing the cached coarse audio FFT from [prepared].
     */
    private fun computeNormalizedFftCorrelation(
        prepared: PreparedAudioWindow,
        subPadded: FloatArray,
        maxLagBins: Int,
    ): DoubleArray {
        val n = prepared.coarseAudio.size
        val m = subPadded.size
        val outLen = 2 * maxLagBins + 1
        if (n == 0 || m < n || prepared.coarseNormA <= 1e-9) return DoubleArray(outLen)

        val prefix = buildSubPrefix(subPadded)
        val fftSize = nextPowerOfTwo(m + n)
        val (aRe, aIm) = prepared.getCoarseFft(fftSize)

        val sRe = DoubleArray(fftSize)
        val sIm = DoubleArray(fftSize)
        for (i in 0 until m) sRe[i] = subPadded[i].toDouble()
        fft(sRe, sIm, inverse = false)

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
        val normA = prepared.coarseNormA
        for (lag in -maxLagBins..maxLagBins) {
            val s = maxLagBins - lag
            if (s < 0 || s + n > m) continue

            val dot = sRe[s]
            val sumS = prefix.prefixS[s + n] - prefix.prefixS[s]
            val sumS2 = prefix.prefixS2[s + n] - prefix.prefixS2[s]
            val varS = (sumS2 - (sumS * sumS) / n).coerceAtLeast(0.0)
            val denom = normA * sqrt(varS)
            result[lag + maxLagBins] = if (denom > 1e-9) (dot / denom).coerceIn(-1.0, 1.0) else 0.0
        }
        return result
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
