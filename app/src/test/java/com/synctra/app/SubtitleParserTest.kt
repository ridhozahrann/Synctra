package com.synctra.app

import com.synctra.app.domain.model.SubtitleFormat
import com.synctra.app.domain.subtitle.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun parsesSrtWithBomAndHtmlTags() {
        val srt = "\uFEFF1\r\n" +
            "00:00:10,500 --> 00:00:13,250\r\n" +
            "<i>Halo dunia!</i>\r\n" +
            "<font color=\"#ffff00\">Apa kabar?</font>\r\n\r\n" +
            "2\r\n" +
            "00:01:02,000 --> 00:01:05,120\r\n" +
            "Baik-baik saja.\r\n"

        val parsed = SubtitleParser.parse(srt.toByteArray(Charsets.UTF_8), "movie.srt")
        assertEquals(SubtitleFormat.SRT, parsed.format)
        assertEquals(2, parsed.allCues.size)
        assertEquals(10_500L, parsed.allCues[0].startMs)
        assertEquals(13_250L, parsed.allCues[0].endMs)
        assertEquals("Halo dunia!\nApa kabar?", parsed.allCues[0].cleanText)
        assertEquals(62_000L, parsed.allCues[1].startMs)
        assertEquals(65_120L, parsed.allCues[1].endMs)
    }

    @Test
    fun parsesAssWithOverrideTagsAndNewlines() {
        val ass = """
            [Script Info]
            Title: Test Anime
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:02:15.40,0:02:18.90,Default,,0,0,0,,{\an8\i1}Kita harus pergi!\N{\r}Sekarang juga, kawan!
            Dialogue: 0,0:02:20.00,0:02:22.50,Default,,0,0,0,,Tunggu sebentar.
        """.trimIndent()

        val parsed = SubtitleParser.parse(ass.toByteArray(Charsets.UTF_8), "episode01.ass")
        assertEquals(SubtitleFormat.ASS, parsed.format)
        assertEquals(2, parsed.allCues.size)
        assertEquals(135_400L, parsed.allCues[0].startMs)
        assertEquals(138_900L, parsed.allCues[0].endMs)
        assertEquals("Kita harus pergi!\nSekarang juga, kawan!", parsed.allCues[0].cleanText)
    }

    @Test
    fun parsesWebVttWithShortAndLongTimestamps() {
        val vtt = """
            WEBVTT

            00:05.200 --> 00:08.400
            Selamat pagi.

            00:01:10.000 --> 00:01:12.500 align:center
            Mari kita mulai.
        """.trimIndent()

        val parsed = SubtitleParser.parse(vtt.toByteArray(Charsets.UTF_8), "clip.vtt")
        assertEquals(SubtitleFormat.VTT, parsed.format)
        assertEquals(2, parsed.allCues.size)
        assertEquals(5_200L, parsed.allCues[0].startMs)
        assertEquals(70_000L, parsed.allCues[1].startMs)
        assertTrue(parsed.allCues[1].cleanText.contains("Mari kita mulai"))
    }

    @Test
    fun parsesMalformedSrtWithoutBlankLinesBetweenCues() {
        val malformed = """
            1
            00:00:05,000 --> 00:00:07,500
            Baris pertama.
            2
            00:00:08,000 --> 00:00:10,000
            Baris kedua.
        """.trimIndent()

        val parsed = SubtitleParser.parse(malformed.toByteArray(Charsets.UTF_8), "malformed.srt")
        assertEquals(2, parsed.allCues.size)
        assertEquals("Baris pertama.", parsed.allCues[0].cleanText)
        assertEquals("Baris kedua.", parsed.allCues[1].cleanText)
        assertEquals(8_000L, parsed.allCues[1].startMs)
    }

    @Test
    fun stripsAssVectorDrawingsAndKeepsKaraokeDialogue() {
        val ass = """
            [Script Info]
            Title: Typeset Anime
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:10.00,0:00:14.00,Signs,,0,0,0,,{\an7\pos(120,80)\p1}m 0 0 l 120 0 120 40 0 40{\p0}
            Dialogue: 0,0:00:15.00,0:00:18.00,Signs,,0,0,0,,{\p2}m 10 10 b 20 30 40 50 60 70
            Dialogue: 0,0:00:20.00,0:00:24.00,OP,,0,0,0,,{\k40}Ha{\k30}lo {\kf50}du{\k20}nia!
            Dialogue: 0,0:00:25.00,0:00:28.00,Default,,0,0,0,,{\p1}m 0 0 l 10 10{\p0}Teks setelah gambar\hmuncul.
        """.trimIndent()

        val parsed = SubtitleParser.parse(ass.toByteArray(Charsets.UTF_8), "typeset.ass")
        assertEquals(SubtitleFormat.ASS, parsed.format)
        assertEquals(2, parsed.allCues.size)
        assertEquals("Halo dunia!", parsed.allCues[0].cleanText)
        assertEquals("Teks setelah gambar muncul.", parsed.allCues[1].cleanText)
    }

    @Test
    fun skipsWebVttNoteBlocksAndCueSettings() {
        val vtt = """
            WEBVTT

            NOTE
            00:00:01.000 --> 00:00:04.000
            Comment line that must not become a cue.

            00:00:10.000 --> 00:00:13.500 align:start position:10% line:85%
            Baris dialog valid.
        """.trimIndent()

        val parsed = SubtitleParser.parse(vtt.toByteArray(Charsets.UTF_8), "notes.vtt")
        assertEquals(SubtitleFormat.VTT, parsed.format)
        assertEquals(1, parsed.allCues.size)
        assertEquals("Baris dialog valid.", parsed.allCues[0].cleanText)
        assertEquals(10_000L, parsed.allCues[0].startMs)
    }
}
