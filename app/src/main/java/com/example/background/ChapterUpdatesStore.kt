package com.example.background

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Remembers what the background update check found, so the Updates tab can list every book with
 * new chapters instead of the result living only in a notification that is easy to swipe away.
 * Kept in SharedPreferences rather than Room: it is a small, disposable cache.
 */
class ChapterUpdatesStore private constructor(context: Context) {

    data class Entry(
        val bookId: String,
        val newCount: Int,
        val fromChapter: Int,
        val checkedAt: Long,
        val error: String? = null
    )

    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("chapter_updates", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    private fun load(): Map<String, Entry> = prefs.all.mapNotNull { (id, raw) ->
        try {
            val o = JSONObject(raw as String)
            id to Entry(
                bookId = id,
                newCount = o.optInt("n"),
                fromChapter = o.optInt("from"),
                checkedAt = o.optLong("at"),
                error = o.optString("err").ifBlank { null }
            )
        } catch (_: Exception) { null }
    }.toMap()

    fun record(entry: Entry) {
        val o = JSONObject()
            .put("n", entry.newCount)
            .put("from", entry.fromChapter)
            .put("at", entry.checkedAt)
            .put("err", entry.error ?: "")
        prefs.edit().putString(entry.bookId, o.toString()).apply()
        _entries.value = _entries.value + (entry.bookId to entry)
        ContinueReadingWidget.refresh(appContext)
    }

    /** Called once the new chapters have been queued for download. */
    fun clear(bookId: String) {
        prefs.edit().remove(bookId).apply()
        _entries.value = _entries.value - bookId
    }

    companion object {
        @Volatile private var instance: ChapterUpdatesStore? = null
        fun get(context: Context): ChapterUpdatesStore =
            instance ?: synchronized(this) { instance ?: ChapterUpdatesStore(context).also { instance = it } }
    }
}
