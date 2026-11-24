package com.synctra.app.domain.subtitle

import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SubtitleFormat
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Lightweight, zero-dependency parser for SRT, ASS/SSA, and WebVTT subtitle files.
 * Handles UTF-8 (with or without BOM), UTF-16, and Windows-1252/ISO-8859-1 fallback.
 */
object SubtitleParser {

    private val srtTimestampRegex = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val vttShortTimestampRegex = Regex(
        """(\d{2}):(\d{2})\.(\d{1,3})\s*-->\s*(\d{2}):(\d{2})\.(\d{1,3})"""
    )
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val assOverrideRegex = Regex("""\{[^}]*\}""")
    private val whitespaceRegex = Regex("""[ \t]+""")

    fun parse(bytes: ByteArray, fileName: String? = null): ParsedSubtitleFile {
        val (text, encoding) = decodeBytes(bytes)
        val normalized = text
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val format = detectFormat(normalized, fileName)
        val cues = when (format) {
            SubtitleFormat.ASS -> parseAss(normalized)
            SubtitleFormat.VTT -> parseVtt(normalized)
            SubtitleFormat.SRT -> parseSrt(normalized)
            SubtitleFormat.UNKNOWN -> {
                val srtAttempt = parseSrt(normalized)
                if (srtAttempt.isNotEmpty()) srtAttempt else parseAss(normalized)
            }
        }

        val sortedCues = cues
            .filter { it.endMs > it.startMs && it.cleanText.isNotBlank() }
            .sortedBy { it.startMs }
            .mapIndexed { idx, cue -> cue.copy(index = idx + 1) }

        return ParsedSubtitleFile(
            format = format,
            allCues = sortedCues,
            encodingName = encoding,
        )
    }

    internal fun decodeBytes(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8 (BOM)"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        }

        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString() to "UTF-8"
        } catch (_: CharacterCodingException) {
            val cp1252 = runCatching { Charset.forName("windows-1252") }
                .getOrDefault(Charsets.ISO_8859_1)
            String(bytes, cp1252) to cp1252.name()
        }
    }

    internal fun detectFormat(content: String, fileName: String?): SubtitleFormat {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        val head = content.take(1024)
        if (head.trimStart().startsWith("WEBVTT", ignoreCase = true) || ext == "vtt") {
            return SubtitleFormat.VTT
        }
        if (head.contains("[Script Info]", ignoreCase = true) ||
            head.contains("[Events]", ignoreCase = true) ||
            ext == "ass" || ext == "ssa"
        ) {
            return SubtitleFormat.ASS
        }
        if (ext == "srt" || srtTimestampRegex.containsMatchIn(head)) {
            return SubtitleFormat.SRT
        }
        return SubtitleFormat.UNKNOWN
    }

    private fun parseSrt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.split("\n")
        var i = 0
        var counter = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            val match = srtTimestampRegex.find(line)
            if (match != null) {
                val startMs = parseHmsMs(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3],
                    match.groupValues[4],
                )
                val endMs = parseHmsMs(
                    match.groupValues[5],
                    match.groupValues[6],
                    match.groupValues[7],
                    match.groupValues[8],
                )

                val textLines = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    val trimmedNext = lines[i].trim()
                    if (srtTimestampRegex.containsMatchIn(trimmedNext)) break
                    // Handle missing blank line where the next cue's numeric index sits directly above a timestamp
                    if (trimmedNext.all { it.isDigit() } &&
                        i + 1 < lines.size &&
                        srtTimestampRegex.containsMatchIn(lines[i + 1].trim())
                    ) {
                        i++
                        break
                    }
                    textLines.add(lines[i])
                    i++
                }
                val rawText = textLines.joinToString("\n").trim()
                val cleanText = stripFormatting(rawText)
                if (cleanText.isNotBlank() && endMs > startMs) {
                    counter++
                    cues.add(SubtitleCue(counter, startMs, endMs, rawText, cleanText))
                }
            } else {
                i++
            }
        }
        return cues
    }

    private fun parseVtt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.split("\n")
        var i = 0
        var counter = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("NOTE", ignoreCase = true) && (line.length == 4 || line[4].isWhitespace())) {
                i++
                while (i < lines.size && lines[i].isNotBlank()) i++
                continue
            }
            val fullMatch = srtTimestampRegex.find(line)
            val shortMatch = if (fullMatch == null) vttShortTimestampRegex.find(line) else null

            val times: Pair<Long, Long>? = when {
                fullMatch != null -> {
                    parseHmsMs(
                        fullMatch.groupValues[1],
                        fullMatch.groupValues[2],
                        fullMatch.groupValues[3],
                        fullMatch.groupValues[4],
                    ) to parseHmsMs(
                        fullMatch.groupValues[5],
                        fullMatch.groupValues[6],
                        fullMatch.groupValues[7],
                        fullMatch.groupValues[8],
                    )
                }
                shortMatch != null -> {
                    parseHmsMs(
                        "0",
                        shortMatch.groupValues[1],
                        shortMatch.groupValues[2],
                        shortMatch.groupValues[3],
                    ) to parseHmsMs(
                        "0",
                        shortMatch.groupValues[4],
                        shortMatch.groupValues[5],
                        shortMatch.groupValues[6],
                    )
                }
                else -> null
            }

            if (times != null) {
                val (startMs, endMs) = times
                val textLines = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    val trimmedNext = lines[i].trim()
                    if (srtTimestampRegex.containsMatchIn(trimmedNext) || vttShortTimestampRegex.containsMatchIn(trimmedNext)) {
                        break
                    }
                    textLines.add(lines[i])
                    i++
                }
                val rawText = textLines.joinToString("\n").trim()
                val cleanText = stripFormatting(rawText)
                if (cleanText.isNotBlank() && endMs > startMs) {
                    counter++
                    cues.add(SubtitleCue(counter, startMs, endMs, rawText, cleanText))
                }
            } else {
                i++
            }
        }
        return cues
    }

    private val assDrawingLevelRegex = Regex("""\\p(\d+)""")

    private fun parseAss(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.split("\n")
        var inEvents = false
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var counter = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue

            if (line.startsWith("Format:", ignoreCase = true)) {
                val cols = line.substringAfter(":").split(",").map { it.trim().lowercase() }
                cols.indexOf("start").takeIf { it >= 0 }?.let { startIdx = it }
                cols.indexOf("end").takeIf { it >= 0 }?.let { endIdx = it }
                cols.indexOf("text").takeIf { it >= 0 }?.let { textIdx = it }
                continue
            }

            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val body = line.substringAfter(":")
                val parts = body.split(",", limit = textIdx + 1)
                if (parts.size <= maxOf(startIdx, endIdx, textIdx)) continue

                val startMs = parseAssTimestamp(parts[startIdx].trim()) ?: continue
                val endMs = parseAssTimestamp(parts[endIdx].trim()) ?: continue
                val rawText = parts[textIdx].trim()
                val withoutDrawings = stripAssDrawingCommands(rawText)
                val cleanText = stripFormatting(
                    withoutDrawings
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .replace("\\h", " ")
                )
                if (cleanText.isNotBlank() && endMs > startMs) {
                    counter++
                    cues.add(SubtitleCue(counter, startMs, endMs, rawText, cleanText))
                }
            }
        }
        return cues
    }

    /**
     * Removes ASS/SSA vector drawing segments activated by `\p1`..`\pN` (N > 0) until `\p0` or
     * end-of-line so typesetting coordinates (`m 0 0 l 100 0 ...`) never count as spoken dialogue.
     */
    internal fun stripAssDrawingCommands(rawText: String): String {
        if (!rawText.contains("\\p")) return rawText

        val out = StringBuilder(rawText.length)
        var cursor = 0
        var inDrawingMode = false

        for (match in assOverrideRegex.findAll(rawText)) {
            if (!inDrawingMode && match.range.first > cursor) {
                out.append(rawText, cursor, match.range.first)
            }
            val lastDrawingTag = assDrawingLevelRegex.findAll(match.value).lastOrNull()
            if (lastDrawingTag != null) {
                val level = lastDrawingTag.groupValues[1].toIntOrNull() ?: 0
                inDrawingMode = level > 0
            }
            cursor = match.range.last + 1
        }

        if (!inDrawingMode && cursor < rawText.length) {
            out.append(rawText, cursor, rawText.length)
        }
        return out.toString()
    }

    private fun parseAssTimestamp(ts: String): Long? {
        // Format: H:MM:SS.cc (centiseconds)
        val parts = ts.split(":")
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val secParts = parts[2].split(".")
        val seconds = secParts[0].toLongOrNull() ?: return null
        val fracStr = secParts.getOrElse(1) { "0" }.padEnd(3, '0').take(3)
        val millis = fracStr.toLongOrNull() ?: 0L
        return ((hours * 3600L + minutes * 60L + seconds) * 1000L) + millis
    }

    private fun parseHmsMs(h: String, m: String, s: String, ms: String): Long {
        val hours = h.toLongOrNull() ?: 0L
        val minutes = m.toLongOrNull() ?: 0L
        val seconds = s.toLongOrNull() ?: 0L
        val paddedMs = ms.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hours * 3600L + minutes * 60L + seconds) * 1000L) + paddedMs
    }

    internal fun stripFormatting(text: String): String {
        return text
            .replace(assOverrideRegex, "")
            .replace(htmlTagRegex, "")
            .lines()
            .map { it.replace(whitespaceRegex, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
