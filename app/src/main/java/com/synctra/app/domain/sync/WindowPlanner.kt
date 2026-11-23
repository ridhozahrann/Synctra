package com.synctra.app.domain.sync

import com.synctra.app.domain.model.SampleWindow
import com.synctra.app.domain.model.SubtitleCue

/**
 * Selects 1 to 3 dialogue-rich sampling windows inside the interior of the movie,
 * intentionally skipping intro credits/ads and end credits.
 */
object WindowPlanner {

    private const val DEFAULT_WINDOW_MS = 90_000L // 90 seconds per sample

    fun planWindows(
        mediaDurationMs: Long,
        dialogueCues: List<SubtitleCue>,
    ): List<SampleWindow> {
        val effectiveDuration = if (mediaDurationMs > 0L) {
            mediaDurationMs
        } else {
            (dialogueCues.maxOfOrNull { it.endMs } ?: 0L) + 30_000L
        }

        if (effectiveDuration <= 0L) return emptyList()

        // Short clip (< 4 minutes): sample a single interior window (or whole clip if < 90s)
        if (effectiveDuration < 4L * 60L * 1000L) {
            val winLen = effectiveDuration.coerceAtMost(DEFAULT_WINDOW_MS)
            val start = ((effectiveDuration - winLen) / 2L).coerceAtLeast(0L)
            return listOf(
                SampleWindow(
                    index = 0,
                    label = "Bagian Tengah",
                    startMs = start,
                    durationMs = winLen,
                )
            )
        }

        // Medium video (4 - 15 minutes): 2 windows around 25% and 70%
        if (effectiveDuration < 15L * 60L * 1000L) {
            val winLen = (effectiveDuration / 4L).coerceIn(60_000L, DEFAULT_WINDOW_MS)
            val safeStart = 15_000L
            val safeEnd = (effectiveDuration - winLen - 15_000L).coerceAtLeast(safeStart)
            val anchors = listOf(
                "Awal-Tengah" to (effectiveDuration * 0.25).toLong(),
                "Akhir-Tengah" to (effectiveDuration * 0.68).toLong(),
            )
            return anchors.mapIndexed { idx, (label, anchor) ->
                val bestStart = findDensestStart(
                    anchorCenterMs = anchor,
                    searchRadiusMs = (effectiveDuration * 0.08).toLong(),
                    windowDurationMs = winLen,
                    minStartMs = safeStart,
                    maxStartMs = safeEnd,
                    cues = dialogueCues,
                )
                SampleWindow(idx, label, bestStart, winLen)
            }
        }

        // Standard movie (>= 15 minutes): 3 well-separated windows at ~20%, ~50%, ~75%
        val winLen = DEFAULT_WINDOW_MS
        val safeStart = 60_000L // Skip first 60s (studio logos, intro ads)
        val safeEnd = (effectiveDuration - winLen - 120_000L).coerceAtLeast(safeStart) // Skip last 2m (end credits)
        val searchRadius = (effectiveDuration * 0.06).toLong().coerceIn(30_000L, 180_000L)

        val anchors = listOf(
            "Awal (~20%)" to (effectiveDuration * 0.20).toLong(),
            "Tengah (~50%)" to (effectiveDuration * 0.50).toLong(),
            "Akhir (~75%)" to (effectiveDuration * 0.75).toLong(),
        )

        return anchors.mapIndexed { idx, (label, anchor) ->
            val bestStart = findDensestStart(
                anchorCenterMs = anchor,
                searchRadiusMs = searchRadius,
                windowDurationMs = winLen,
                minStartMs = safeStart,
                maxStartMs = safeEnd,
                cues = dialogueCues,
            )
            SampleWindow(idx, label, bestStart, winLen)
        }
    }

    /**
     * Picks the window start position near [anchorCenterMs] that maximizes spoken subtitle coverage
     * (sweet spot around 25-65% speech coverage, avoiding long silent/action-only stretches).
     */
    private fun findDensestStart(
        anchorCenterMs: Long,
        searchRadiusMs: Long,
        windowDurationMs: Long,
        minStartMs: Long,
        maxStartMs: Long,
        cues: List<SubtitleCue>,
    ): Long {
        val idealStart = (anchorCenterMs - windowDurationMs / 2L).coerceIn(minStartMs, maxStartMs)
        if (cues.isEmpty()) return idealStart

        val lo = (idealStart - searchRadiusMs).coerceAtLeast(minStartMs)
        val hi = (idealStart + searchRadiusMs).coerceAtMost(maxStartMs)
        val step = 15_000L

        var bestStart = idealStart
        var bestScore = -1.0

        var candidate = lo
        while (candidate <= hi) {
            val winEnd = candidate + windowDurationMs
            var cueCount = 0
            var activeMs = 0L
            for (cue in cues) {
                if (cue.endMs <= candidate) continue
                if (cue.startMs >= winEnd) break
                cueCount++
                val overlapStart = maxOf(cue.startMs, candidate)
                val overlapEnd = minOf(cue.endMs, winEnd)
                if (overlapEnd > overlapStart) activeMs += (overlapEnd - overlapStart)
            }
            val coverage = activeMs.toDouble() / windowDurationMs
            // Prefer healthy dialogue cadence (many distinct cues + 20-65% coverage)
            val coverageScore = if (coverage <= 0.65) coverage else (1.3 - coverage).coerceAtLeast(0.1)
            val distancePenalty = 1.0 - (kotlin.math.abs(candidate - idealStart).toDouble() / (searchRadiusMs * 3.0 + 1.0))
            val score = (cueCount * 0.6 + coverageScore * 30.0) * distancePenalty

            if (score > bestScore) {
                bestScore = score
                bestStart = candidate
            }
            candidate += step
        }

        return bestStart
    }
}
