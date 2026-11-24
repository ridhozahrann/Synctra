package com.synctra.app.data.history

import android.content.Context
import com.synctra.app.domain.model.SyncHistoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Lightweight local store (SharedPreferences + org.json, zero extra dependencies)
 * for recent sync checks and UI language preference.
 */
class SyncHistoryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadHistory(): List<SyncHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        SyncHistoryEntry(
                            videoName = obj.optString("videoName", ""),
                            subtitleName = obj.optString("subtitleName", ""),
                            audioTrackTitle = obj.optString("audioTrackTitle", ""),
                            offsetMs = obj.optLong("offsetMs", 0L),
                            hasDrift = obj.optBoolean("hasDrift", false),
                            checkedAtEpochMs = obj.optLong("checkedAtEpochMs", 0L),
                            audioTrackIndex = obj.optInt("audioTrackIndex", 0),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addEntry(entry: SyncHistoryEntry): List<SyncHistoryEntry> {
        val updated = mergeEntries(loadHistory(), entry, MAX_ENTRIES)
        saveList(updated)
        return updated
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun loadLanguageCode(): String {
        val saved = prefs.getString(KEY_LANG, null)
        if (saved == "id" || saved == "en") return saved
        val sys = Locale.getDefault().language.lowercase(Locale.US)
        return if (sys == "in" || sys == "id") "id" else "en"
    }

    fun saveLanguageCode(code: String) {
        prefs.edit().putString(KEY_LANG, code).apply()
    }

    private fun saveList(entries: List<SyncHistoryEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("videoName", e.videoName)
                    put("subtitleName", e.subtitleName)
                    put("audioTrackTitle", e.audioTrackTitle)
                    put("offsetMs", e.offsetMs)
                    put("hasDrift", e.hasDrift)
                    put("checkedAtEpochMs", e.checkedAtEpochMs)
                    put("audioTrackIndex", e.audioTrackIndex)
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "synctra_prefs"
        private const val KEY_HISTORY = "recent_history_v1"
        private const val KEY_LANG = "ui_language"
        const val MAX_ENTRIES = 20

        internal fun mergeEntries(
            existing: List<SyncHistoryEntry>,
            entry: SyncHistoryEntry,
            maxEntries: Int = MAX_ENTRIES,
        ): List<SyncHistoryEntry> = buildList {
            add(entry)
            existing
                .filterNot {
                    it.videoName == entry.videoName &&
                        it.subtitleName == entry.subtitleName &&
                        it.audioTrackIndex == entry.audioTrackIndex &&
                        it.audioTrackTitle == entry.audioTrackTitle
                }
                .take((maxEntries - 1).coerceAtLeast(0))
                .forEach { add(it) }
        }
    }
}
