package com.example.ui.screens

import android.content.Context
import org.json.JSONArray

/** Library choices that should survive leaving the screen: sort order, quick filter, own shelves. */
object LibraryPrefs {
    val DEFAULT_SHELVES = listOf("Reading", "Plan to Read", "Waiting", "Completed", "Favorites")
    val SORTS = listOf("Recently read", "Title A-Z", "Title Z-A", "Most unread", "Total Chapters", "Last Updated")
    /** Quick filters shown as chips; "" = none. */
    val FILTERS = listOf("Unread", "New chapters", "Not started", "Finished")

    private fun prefs(c: Context) = c.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    fun sort(c: Context) = prefs(c).getString("sort", "Recently read")!!.let { if (it == "Unread Chapters") "Most unread" else it }
    fun setSort(c: Context, v: String) = prefs(c).edit().putString("sort", v).apply()

    fun filter(c: Context) = prefs(c).getString("filter", "")!!
    fun setFilter(c: Context, v: String) = prefs(c).edit().putString("filter", v).apply()

    /** Leave novels you've caught up on out of the list unless the Finished filter is chosen. */
    fun hideFinished(c: Context) = prefs(c).getBoolean("hide_finished", false)
    fun setHideFinished(c: Context, v: Boolean) = prefs(c).edit().putBoolean("hide_finished", v).apply()

    fun customShelves(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("shelves", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun shelves(c: Context) = DEFAULT_SHELVES + customShelves(c)

    fun addShelf(c: Context, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.equals("All", true) || shelves(c).any { it.equals(n, true) }) return false
        prefs(c).edit().putString("shelves", JSONArray(customShelves(c) + n).toString()).apply()
        return true
    }

    fun removeShelf(c: Context, name: String) {
        prefs(c).edit().putString("shelves", JSONArray(customShelves(c).filterNot { it == name }).toString()).apply()
    }

    /** When each novel was last read (set whenever reading progress is saved). */
    fun lastReadAt(c: Context, bookId: String) =
        c.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE).getLong("progress_at_$bookId", 0L)
}
