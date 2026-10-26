package com.example.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.example.data.local.ChapterEntity
import com.example.viewmodel.ChapterStructure.Numbering

/** Which numbering each novel shows (site list position or the novel's own), remembered per novel. */
object ChapterNumbering {
    private val cache = mutableStateMapOf<String, Numbering>() // observed by the UI

    fun get(context: Context, bookId: String): Numbering = cache.getOrPut(bookId) {
        val v = context.getSharedPreferences("chapter_numbering", Context.MODE_PRIVATE).getString(bookId, null)
        if (v == Numbering.SITE.name) Numbering.SITE else Numbering.NOVEL
    }

    fun set(context: Context, bookId: String, numbering: Numbering) {
        context.getSharedPreferences("chapter_numbering", Context.MODE_PRIVATE).edit().putString(bookId, numbering.name).apply()
        cache[bookId] = numbering
    }

    fun label(context: Context, chapter: ChapterEntity) = ChapterStructure.label(chapter, get(context, chapter.bookId))
}
