package com.synctra.app

import com.synctra.app.domain.model.AudioTrackOption
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackOptionTest {

    @Test
    fun formatsIsoLanguageCodesAndChannelLayoutsCleanly() {
        val english = AudioTrackOption(
            audioIndex = 0,
            extractorTrackId = 2,
            mimeType = "audio/ac3",
            codecLabel = "AC-3",
            languageCode = "eng",
            trackLabel = null,
            channelCount = 6,
            sampleRate = 48_000,
            isDefault = true,
        )
        val hindi = AudioTrackOption(
            audioIndex = 1,
            extractorTrackId = 3,
            mimeType = "audio/mp4a-latm",
            codecLabel = "AAC",
            languageCode = "hin",
            trackLabel = "Hindi Dub",
            channelCount = 2,
            sampleRate = 48_000,
            isDefault = false,
        )
        val unknown = AudioTrackOption(
            audioIndex = 2,
            extractorTrackId = 4,
            mimeType = "audio/opus",
            codecLabel = "Opus",
            languageCode = "und",
            trackLabel = null,
            channelCount = 2,
            sampleRate = 48_000,
            isDefault = false,
        )

        assertEquals("English", english.displayTitle)
        assertEquals("AC-3 · 5.1", english.technicalSummary)

        assertEquals("Hindi Dub", hindi.displayTitle)
        assertEquals("AAC · 2.0", hindi.technicalSummary)

        assertEquals("Audio 3", unknown.displayTitle)
        assertEquals("Opus · 2.0", unknown.technicalSummary)
    }
}
