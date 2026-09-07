package com.example.util

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StorageBreakdown(
    val databaseSizeBytes: Long = 0L,
    val coversSizeBytes: Long = 0L,
    val tempExportsSizeBytes: Long = 0L,
    val otherCacheSizeBytes: Long = 0L,
    val totalSizeBytes: Long = 0L,
    val totalBooksCount: Int = 0,
    val totalChaptersCount: Int = 0
)

object StorageMaintenanceManager {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    suspend fun computeStorageBreakdown(context: Context, repository: NovelRepository): StorageBreakdown = withContext(Dispatchers.IO) {
        var dbSize = 0L
        val dbFile = context.getDatabasePath("novel_hoarder_db")
        if (dbFile.exists()) dbSize += dbFile.length()
        val walFile = File(dbFile.parentFile, "novel_hoarder_db-wal")
        if (walFile.exists()) dbSize += walFile.length()
        val shmFile = File(dbFile.parentFile, "novel_hoarder_db-shm")
        if (shmFile.exists()) dbSize += shmFile.length()

        var coversSize = 0L
        val coversDir = File(context.filesDir, "covers")
        if (coversDir.exists() && coversDir.isDirectory) {
            coversSize += getFolderSize(coversDir)
        }

        var tempExportsSize = 0L
        var otherCacheSize = 0L
        val cacheDir = context.cacheDir
        if (cacheDir.exists() && cacheDir.isDirectory) {
            cacheDir.listFiles()?.forEach { file ->
                val name = file.name.lowercase()
                if (name.endsWith(".epub") || name.endsWith(".pdf") || name.endsWith(".txt") || name.endsWith(".nhbackup") || name.endsWith(".json")) {
                    tempExportsSize += file.length()
                } else if (file.isDirectory && file.name == "image_cache") {
                    coversSize += getFolderSize(file)
                } else {
                    otherCacheSize += if (file.isDirectory) getFolderSize(file) else file.length()
                }
            }
        }

        val totalBooks = repository.getBookCount()
        val totalChapters = repository.getTotalChapterCount()

        StorageBreakdown(
            databaseSizeBytes = dbSize,
            coversSizeBytes = coversSize,
            tempExportsSizeBytes = tempExportsSize,
            otherCacheSizeBytes = otherCacheSize,
            totalSizeBytes = dbSize + coversSize + tempExportsSize + otherCacheSize,
            totalBooksCount = totalBooks,
            totalChaptersCount = totalChapters
        )
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    suspend fun cleanTempExportFiles(context: Context): Long = withContext(Dispatchers.IO) {
        var freedBytes = 0L
        val cacheDir = context.cacheDir
        if (cacheDir.exists() && cacheDir.isDirectory) {
            cacheDir.listFiles()?.forEach { file ->
                val name = file.name.lowercase()
                if (name.endsWith(".epub") || name.endsWith(".pdf") || name.endsWith(".txt") || name.endsWith(".nhbackup") || (name.startsWith("export_") && name.endsWith(".json"))) {
                    freedBytes += file.length()
                    file.delete()
                }
            }
        }
        freedBytes
    }

    suspend fun cleanOrphanedCovers(context: Context, repository: NovelRepository): Int = withContext(Dispatchers.IO) {
        val books = repository.getAllBooks()
        val validLocalPaths = books.mapNotNull { it.coverLocalPath }.toSet()
        var deletedCount = 0

        val coversDir = File(context.filesDir, "covers")
        if (coversDir.exists() && coversDir.isDirectory) {
            coversDir.listFiles()?.forEach { file ->
                if (!validLocalPaths.contains(file.absolutePath)) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }
        }
        deletedCount
    }

    suspend fun vacuumDatabase(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
            db.openHelper.writableDatabase.execSQL("VACUUM")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun purgeReadChaptersContent(repository: NovelRepository): Int = withContext(Dispatchers.IO) {
        repository.clearAllReadChaptersContent()
    }
}
