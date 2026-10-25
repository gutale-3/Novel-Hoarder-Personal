package com.example.background

import android.content.Context
import org.json.JSONObject

/**
 * Which notifications the user wants and how often. New-chapter alerts are grouped into one
 * notification, spaced at least [minHoursBetween] apart, and a novel only reappears when it has
 * more new chapters than the last time it was mentioned.
 */
class NotificationPrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)

    var newChapters: Boolean
        get() = prefs.getBoolean("new_chapters", true)
        set(v) = prefs.edit().putBoolean("new_chapters", v).apply()

    /** 0 means "every check". */
    var minHoursBetween: Int
        get() = prefs.getInt("min_hours", 24)
        set(v) = prefs.edit().putInt("min_hours", v).apply()

    /** Hour of day (0–23) for a single daily summary instead of alerts as chapters are found; -1 = off. */
    var digestHour: Int
        get() = prefs.getInt("digest_hour", -1)
        set(v) = prefs.edit().putInt("digest_hour", v).apply()

    /** New-chapter alerts are silent unless this is on. */
    var chapterSound: Boolean
        get() = prefs.getBoolean("chapter_sound", false)
        set(v) = prefs.edit().putBoolean("chapter_sound", v).apply()

    var downloadNeedsYou: Boolean
        get() = prefs.getBoolean("download_needs_you", true)
        set(v) = prefs.edit().putBoolean("download_needs_you", v).apply()

    var siteHealth: Boolean
        get() = prefs.getBoolean("site_health", true)
        set(v) = prefs.edit().putBoolean("site_health", v).apply()

    fun mutedBooks(): Set<String> = prefs.getStringSet("muted_books", emptySet()).orEmpty()

    fun isMuted(bookId: String) = bookId in mutedBooks()

    fun setMuted(bookId: String, muted: Boolean) {
        val set = mutedBooks().toMutableSet().apply { if (muted) add(bookId) else remove(bookId) }
        prefs.edit().putStringSet("muted_books", set).apply()
    }

    private fun notifiedCounts(): JSONObject =
        runCatching { JSONObject(prefs.getString("notified_counts", "{}")!!) }.getOrDefault(JSONObject())

    /**
     * From this run's (bookId, title, newCount) list, the novels worth mentioning now, or empty if
     * new-chapter alerts are off or the last one was too recent.
     */
    fun chapterAlertsDue(found: List<Triple<String, String, Int>>, now: Long = System.currentTimeMillis()): List<Triple<String, String, Int>> {
        if (!newChapters) return emptyList()
        val last = prefs.getLong("last_chapter_alert", 0L)
        if (now - last < minHoursBetween * 3_600_000L) return emptyList()
        val counts = notifiedCounts()
        return found.filter { (id, _, n) -> n > 0 && !isMuted(id) && n > counts.optInt(id, 0) }
    }

    /** Remembers what was just announced (and forgets novels that were caught up). */
    fun recordChapterAlert(found: List<Triple<String, String, Int>>, now: Long = System.currentTimeMillis()) {
        val counts = JSONObject()
        found.forEach { (id, _, n) -> if (n > 0) counts.put(id, n) }
        prefs.edit().putLong("last_chapter_alert", now).putString("notified_counts", counts.toString()).apply()
    }

    /** Novels caught up since the last alert can be announced again later. */
    fun forgetCaughtUp(stillPending: Map<String, Int>) {
        val counts = notifiedCounts()
        val keep = JSONObject()
        for (id in counts.keys()) {
            val pending = stillPending[id] ?: continue
            keep.put(id, minOf(counts.getInt(id), pending))
        }
        prefs.edit().putString("notified_counts", keep.toString()).apply()
    }

    companion object {
        fun get(context: Context) = NotificationPrefs(context.applicationContext)
    }
}
