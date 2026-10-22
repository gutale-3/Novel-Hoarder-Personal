package com.example.background

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.NovelHoarderApp
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home-screen widget: the novel being read, its cover and chapter, and how many new chapters
 * are waiting. Tapping it reopens the reader at the saved place. Refreshed when the reading
 * chapter changes and when the update check finds new chapters.
 */
class ContinueReadingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            // A failure here would otherwise crash the whole app from the home screen.
            try { runCatching { render(context, manager, ids) } } finally { pending.finish() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Re-renders every placed widget; cheap no-op when none are placed. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, ContinueReadingWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { runCatching { render(app, manager, ids) } }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext as Application
            val repository = NovelHoarderApp.getContainer(app).repository
            val prefs = app.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)

            val bookId = prefs.getString("active_reading_book_id", null)
                ?: repository.getAllBooks().maxByOrNull { prefs.getLong("progress_at_${it.id}", 0L) }?.id
            val book = bookId?.let { repository.getBook(it) }
            val chapterId = bookId?.let { prefs.getString("progress_chapter_$it", null) } ?: book?.lastReadChapterId
            val chapter = chapterId?.let { repository.getChapter(it) }
            val newTotal = ChapterUpdatesStore.get(app).entries.value.values.sumOf { it.newCount }

            val views = RemoteViews(app.packageName, R.layout.widget_continue)
            if (book == null) {
                views.setTextViewText(R.id.widget_label, "Inkvault")
                views.setTextViewText(R.id.widget_title, "Start reading a novel")
                views.setTextViewText(R.id.widget_chapter, "")
            } else {
                views.setTextViewText(R.id.widget_label, "Continue reading")
                views.setTextViewText(R.id.widget_title, book.title)
                views.setTextViewText(R.id.widget_chapter, chapter?.title ?: "${book.totalChapters} chapters")
                val cover = book.coverLocalPath?.let { File(it) }?.takeIf { it.exists() }
                val bitmap = cover?.let { com.example.util.CoverBitmaps.decodeDownsampled(it, 240) }
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.widget_cover, bitmap)
                } else {
                    views.setImageViewResource(R.id.widget_cover, R.drawable.inkvault_logo)
                }
            }
            if (newTotal > 0) {
                views.setViewVisibility(R.id.widget_updates, View.VISIBLE)
                views.setTextViewText(R.id.widget_updates, "$newTotal new chapters waiting")
            } else {
                views.setViewVisibility(R.id.widget_updates, View.GONE)
            }

            val open = Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (book != null) {
                    putExtra("open_reader_book", book.id)
                    chapterId?.let { putExtra("open_reader_chapter", it) }
                }
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(app, 7001, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            manager.updateAppWidget(ids, views)
        }
    }
}
