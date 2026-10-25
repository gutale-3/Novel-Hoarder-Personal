package com.example.viewmodel

import android.content.Context
import com.example.data.repository.NovelRepository
import com.example.util.ReadingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps "Reading" and "Waiting" tidy by themselves: a novel you've caught up on moves to
 * Waiting, and goes back to Reading when new chapters arrive. Only novels moved here
 * automatically come back, so a novel you put on Waiting yourself stays there.
 */
object AutoShelves {
    private const val PREFS = "library_prefs"

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_shelves", true)
    fun setEnabled(c: Context, on: Boolean) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_shelves", on).apply()

    fun start(context: Context, repository: NovelRepository, scope: CoroutineScope) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope.launch(Dispatchers.IO) {
            combine(repository.allBooks, repository.allBookStatsFlow) { books, stats ->
                books to stats.associateBy { it.bookId }
            }.collect { (books, stats) ->
                if (!isEnabled(context)) return@collect
                val moved = prefs.getStringSet("auto_waiting", emptySet()).orEmpty().toMutableSet()
                var changed = false
                for (b in books) {
                    val p = ReadingProgress.of(b, stats[b.id])
                    if (p.latest <= 0) continue
                    if (p.caughtUp && b.category == "Reading") {
                        repository.updateBookCategory(b.id, "Waiting"); moved += b.id; changed = true
                    } else if (!p.caughtUp && p.left > 0 && b.category == "Waiting" && b.id in moved) {
                        repository.updateBookCategory(b.id, "Reading"); moved -= b.id; changed = true
                    }
                }
                if (changed) prefs.edit().putStringSet("auto_waiting", moved).apply()
            }
        }
    }
}
