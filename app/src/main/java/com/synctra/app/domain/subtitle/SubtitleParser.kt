package com.synctra.app.domain.subtitle

import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SubtitleFormat
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class SubtitleEncodingOption(val label: String, val charsetName: String?) {
    AUTO("Auto", null),
    UTF_8("UTF-8", "UTF-8"),
    SHIFT_JIS("Shift-JIS", "Shift_JIS"),
    EUC_KR("EUC-KR", "EUC-KR"),
    GBK("GBK", "GBK"),
    BIG5("Big5", "Big5"),
    WINDOWS_1252("Win-1252", "windows-1252"),
    WINDOWS_1251("Win-1251", "windows-1251"),
    WINDOWS_1256("Win-1256", "windows-1256"),
}

/**
 * Lightweight, zero-dependency parser for SRT, ASS/SSA, and WebVTT subtitle files.
 * Handles UTF-8 (with or without BOM), UTF-16, and auto-detects legacy CJK/Cyrillic/Arabic/Latin encodings.
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

    private val fallbackCandidateCharsets = listOf(
        "Shift_JIS",
        "EUC-KR",
        "GBK",
        "Big5",
        "windows-1251",
        "windows-1256",
        "windows-1252",
    )

    fun parse(
        bytes: ByteArray,
        fileName: String? = null,
        forcedCharsetName: String? = null,
    ): ParsedSubtitleFile {
        val (text, encoding) = decodeBytes(bytes, forcedCharsetName)
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

    internal fun decodeBytes(
        bytes: ByteArray,
        forcedCharsetName: String? = null,
    ): Pair<String, String> {
        if (!forcedCharsetName.isNullOrBlank()) {
            val charset = runCatching { Charset.forName(forcedCharsetName) }
                .getOrDefault(Charsets.UTF_8)
            val offset = when {
                charset == Charsets.UTF_8 && bytes.size >= 3 &&
                    bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3
                else -> 0
            }
            return String(bytes, offset, bytes.size - offset, charset) to charset.name()
        }

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

        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString() to "UTF-8"
        } catch (_: CharacterCodingException) {
            // Fall through to legacy charset scoring
        }

        return detectLegacyCharset(bytes)
    }

    private fun detectLegacyCharset(bytes: ByteArray): Pair<String, String> {
        // Score on a bounded prefix (up to 64 KB) for speed on huge subtitle files
        val sampleLen = minOf(bytes.size, 65_536)
        var bestCharset: Charset? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (name in fallbackCandidateCharsets) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val isMultiByte = name == "Shift_JIS" || name == "EUC-KR" || name == "GBK" || name == "Big5"

            val strictDecoded = runCatching {
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, sampleLen))
                    .toString()
            }.getOrNull()

            val decodedSample = strictDecoded ?: runCatching {
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes, 0, sampleLen))
                    .toString()
            }.getOrNull() ?: continue

            val score = scoreDecodedText(
                text = decodedSample,
                charsetName = name,
                isMultiByte = isMultiByte,
                strictValid = strictDecoded != null,
            )
            if (score > bestScore) {
                bestScore = score
                bestCharset = charset
            }
        }

        val chosen = bestCharset ?: runCatching { Charset.forName("windows-1252") }
            .getOrDefault(Charsets.ISO_8859_1)
        val fullText = chosen.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        return fullText to chosen.name()
    }

    private fun scoreDecodedText(
        text: String,
        charsetName: String,
        isMultiByte: Boolean,
        strictValid: Boolean,
    ): Double {
        var asciiLetters = 0
        var nonAsciiChars = 0
        var replacementCount = 0
        var controlCount = 0
        var hiraganaKatakana = 0
        var hangul = 0
        var cjkIdeographs = 0
        var cjkPunctuation = 0
        var halfwidthKatakana = 0
        var cyrillicLower = 0
        var cyrillicUpper = 0
        var cyrillicMidWordUpper = 0
        var arabicLetters = 0
        var arabicDiacritics = 0
        var latinExtended = 0
        var smartPunctuation = 0
        var otherNonAscii = 0

        var prevWasCyrillicLower = false

        for (ch in text) {
            val code = ch.code
            if (code < 0x80) {
                if (ch in 'a'..'z' || ch in 'A'..'Z') asciiLetters++
                if (code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') controlCount++
                prevWasCyrillicLower = false
                continue
            }

            nonAsciiChars++
            when {
                ch == '\uFFFD' -> {
                    replacementCount++
                    prevWasCyrillicLower = false
                }
                code in 0x007F..0x009F -> {
                    controlCount++
                    prevWasCyrillicLower = false
                }
                code in 0x3040..0x30FF -> {
                    hiraganaKatakana++
                    prevWasCyrillicLower = false
                }
                code in 0xAC00..0xD7A3 -> {
                    hangul++
                    prevWasCyrillicLower = false
                }
                code in 0x4E00..0x9FFF -> {
                    cjkIdeographs++
                    prevWasCyrillicLower = false
                }
                code in 0x3000..0x303F || code in 0xFF01..0xFF60 -> {
                    cjkPunctuation++
                    prevWasCyrillicLower = false
                }
                code in 0xFF61..0xFF9F -> {
                    halfwidthKatakana++
                    prevWasCyrillicLower = false
                }
                code in 0x0430..0x044F || ch == 'ё' -> {
                    cyrillicLower++
                    prevWasCyrillicLower = true
                }
                code in 0x0410..0x042F || ch == 'Ё' -> {
                    cyrillicUpper++
                    if (prevWasCyrillicLower) cyrillicMidWordUpper++
                    prevWasCyrillicLower = false
                }
                code in 0x0621..0x064A || code in 0x0671..0x06D3 -> {
                    arabicLetters++
                    prevWasCyrillicLower = false
                }
                code in 0x064B..0x065F -> {
                    arabicDiacritics++
                    prevWasCyrillicLower = false
                }
                code in 0x00C0..0x024F -> {
                    latinExtended++
                    prevWasCyrillicLower = false
                }
                ch in "\u2018\u2019\u201C\u201D\u2013\u2014\u2026\u00A0\u00AB\u00BB\u00BF\u00A1" -> {
                    smartPunctuation++
                    prevWasCyrillicLower = false
                }
                else -> {
                    otherNonAscii++
                    prevWasCyrillicLower = false
                }
            }
        }

        if (nonAsciiChars == 0) return if (charsetName == "windows-1252") 1.0 else 0.0

        val totalLetters = (asciiLetters + nonAsciiChars).coerceAtLeast(1)
        val nonAsciiShare = nonAsciiChars.toDouble() / totalLetters

        var score = 0.0
        score -= replacementCount * 10.0
        score -= controlCount * 8.0
        score -= otherNonAscii * 2.5

        if (isMultiByte) {
            if (strictValid) {
                score += nonAsciiChars * 0.9 + 6.0
            } else {
                score -= 12.0
            }
            score += hiraganaKatakana * 5.0
            score += hangul * 5.0
            score += cjkIdeographs * 2.4
            score += cjkPunctuation * 1.8
            // Halfwidth katakana appears frequently in mojibake when non-SJIS bytes are decoded as Shift_JIS
            score -= halfwidthKatakana * 2.2
            // Small preference for GBK over Big5 only when tied on pure Hanzi without malformed bytes
            if (charsetName == "GBK" && strictValid) score += 0.2
            // Penalize CJK candidates if text is overwhelmingly ASCII Latin with only a few accented chars
            if (nonAsciiShare < 0.12 && hiraganaKatakana == 0 && hangul == 0) {
                score -= 15.0
            }
        } else {
            when (charsetName) {
                "windows-1252" -> {
                    score += smartPunctuation * 2.5
                    // Western Latin files mostly contain ASCII letters with occasional accents (< 35% non-ASCII)
                    if (nonAsciiShare < 0.35) {
                        score += latinExtended * 3.0 + smartPunctuation * 1.5
                    } else {
                        // Dense non-ASCII (> 45%) in windows-1252 is almost always Cyrillic/Arabic/CJK mojibake
                        score += latinExtended * 0.4 - nonAsciiChars * 1.2
                    }
                }
                "windows-1251" -> {
                    val cyrTotal = cyrillicLower + cyrillicUpper
                    score += cyrTotal * 2.6
                    // Mixed uppercase inside lowercase words strongly indicates non-Cyrillic bytes (e.g. Arabic)
                    score -= cyrillicMidWordUpper * 5.0
                    if (cyrTotal > 0 && cyrillicUpper > cyrillicLower * 1.5) {
                        score -= cyrillicUpper * 1.5
                    }
                    if (nonAsciiShare < 0.25) {
                        score -= 10.0
                    }
                }
                "windows-1256" -> {
                    score += arabicLetters * 2.8
                    // Genuine Arabic subtitles have few Latin-extended leftovers and moderate diacritics
                    score -= latinExtended * 3.0
                    score -= arabicDiacritics * 0.6
                    if (nonAsciiShare < 0.25) {
                        score -= 10.0
                    }
                }
            }
        }

        // Normalize gently so multi-byte (fewer output chars per byte) and single-byte remain directly comparable
        return score / nonAsciiChars.coerceAtLeast(1)
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
