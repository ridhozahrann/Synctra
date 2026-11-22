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
}
