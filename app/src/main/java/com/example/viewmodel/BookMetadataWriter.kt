package com.example.viewmodel

import com.example.data.local.BookEntity
import java.io.File

/** Saves a novel's cover image and info.json next to its chapters (split out of ScrapingManager). */
internal object BookMetadataWriter {
    fun downloadCoverAndSaveMetadata(
        application: android.app.Application,
        book: BookEntity,
        cookies: String,
        userAgent: String,
        addLog: (String) -> Unit
    ): BookEntity {
        val context = application.applicationContext
        val outputFolder = File(context.filesDir, book.id)
        if (!outputFolder.exists()) outputFolder.mkdirs()

        // 1. Download Cover Image
        var updatedBook = book
        val coverUrl = book.coverUrl
        if (!coverUrl.isNullOrEmpty()) {
            try {
                addLog("Downloading book cover from $coverUrl...")
                val coverFile = File(outputFolder, "cover.jpg")
                val url = java.net.URL(coverUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", userAgent)
                if (cookies.isNotEmpty()) {
                    conn.setRequestProperty("Cookie", cookies)
                }
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                val responseCode = conn.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(coverFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    updatedBook = book.copy(coverLocalPath = coverFile.absolutePath)
                    addLog("Book cover downloaded successfully to: ${coverFile.name}")
                } else {
                    addLog("Failed to download book cover (HTTP $responseCode)")
                }
            } catch (e: Exception) {
                addLog("Error downloading book cover: ${e.message}")
                e.printStackTrace()
            }
        }

        // 2. Save info.json metadata
        try {
            val infoFile = File(outputFolder, "info.json")
            val json = org.json.JSONObject().apply {
                put("id", updatedBook.id)
                put("url", updatedBook.url)
                put("title", updatedBook.title)
                put("author", updatedBook.author)
                put("synopsis", updatedBook.synopsis)
                put("coverUrl", updatedBook.coverUrl ?: "")
                put("coverLocalPath", updatedBook.coverLocalPath ?: "")
                put("totalChapters", updatedBook.totalChapters)
                put("updatedAt", updatedBook.updatedAt)
            }
            infoFile.writeText(json.toString(4))
            addLog("Saved metadata info.json for book: ${updatedBook.title}")
        } catch (e: Exception) {
            addLog("Error saving info.json: ${e.message}")
            e.printStackTrace()
        }

        return updatedBook
    }
}
