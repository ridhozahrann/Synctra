package com.synctra.app

import com.synctra.app.data.history.SyncHistoryStore
import com.synctra.app.domain.model.ConfidenceLevel
import com.synctra.app.domain.model.DriftCauseKey
import com.synctra.app.domain.model.FlagReason
import com.synctra.app.domain.model.SyncHistoryEntry
import com.synctra.app.ui.i18n.AppLanguage
import com.synctra.app.ui.i18n.AppStringsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHistoryStoreTest {

    @Test
    fun keepsSeparateEntriesForDifferentAudioTracksAndDedupesSameTrack() {
        val track0 = SyncHistoryEntry(
            videoName = "Anime.mkv",
            subtitleName = "Anime.id.srt",
            audioTrackTitle = "Japanese",
            offsetMs = 1_200L,
            hasDrift = false,
            checkedAtEpochMs = 100L,
            audioTrackIndex = 0,
        )
        val track1 = SyncHistoryEntry(
            videoName = "Anime.mkv",
            subtitleName = "Anime.id.srt",
            audioTrackTitle = "English",
            offsetMs = -450L,
            hasDrift = false,
            checkedAtEpochMs = 200L,
            audioTrackIndex = 1,
        )
        val track0Updated = track0.copy(offsetMs = 1_250L, checkedAtEpochMs = 300L)

        var list = SyncHistoryStore.mergeEntries(emptyList(), track0)
        list = SyncHistoryStore.mergeEntries(list, track1)
        assertEquals("Different audio tracks must NOT overwrite each other", 2, list.size)

        list = SyncHistoryStore.mergeEntries(list, track0Updated)
        assertEquals("Re-checking same video+sub+audio track should dedupe", 2, list.size)
        assertEquals(1_250L, list[0].offsetMs)
        assertEquals(0, list[0].audioTrackIndex)
        assertEquals(1, list[1].audioTrackIndex)
    }

    @Test
    fun capsHistoryAtMaxEntries() {
        var list = emptyList<SyncHistoryEntry>()
        for (i in 1..25) {
            list = SyncHistoryStore.mergeEntries(
                list,
                SyncHistoryEntry(
                    videoName = "Movie_$i.mkv",
                    subtitleName = "Movie_$i.srt",
                    audioTrackTitle = "Track 1",
                    offsetMs = i * 100L,
                    hasDrift = false,
                    checkedAtEpochMs = i.toLong(),
                    audioTrackIndex = 0,
                ),
            )
        }
        assertEquals(SyncHistoryStore.MAX_ENTRIES, list.size)
        assertEquals("Movie_25.mkv", list.first().videoName)
    }

    @Test
    fun appStringsProvideNonBlankCopyInBothIndonesianAndEnglish() {
        for (lang in AppLanguage.entries) {
            val s = AppStringsProvider.get(lang)
            assertTrue(s.subtitleTagline.isNotBlank())
            assertTrue(s.encodingLabel.isNotBlank())
            assertTrue(s.encodingPreviewLabel.isNotBlank())
            assertTrue(s.savedSrtToast(10, 2).isNotBlank())
            assertTrue(s.formatGuidance(0L, "+0.00", "+0").isNotBlank())
            assertTrue(s.formatGuidance(2_000L, "+2.00", "+2000").isNotBlank())
            assertTrue(s.formatGuidance(-2_000L, "-2.00", "-2000").isNotBlank())
            for (level in ConfidenceLevel.entries) {
                assertTrue(s.confidenceLabel(level).isNotBlank())
            }
            for (cause in DriftCauseKey.entries) {
                assertTrue(!s.driftCauseLabel(cause, null).isNullOrBlank())
            }
            for (reason in FlagReason.entries) {
                assertTrue(s.flagReasonLabel(reason).isNotBlank())
            }
        }
    }
}
