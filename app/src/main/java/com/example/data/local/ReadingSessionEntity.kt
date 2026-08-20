package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_sessions")
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterId: String,
    val date: String, // format "yyyy-MM-dd"
    val durationSeconds: Long,
    val wordsRead: Int,
    val timestamp: Long = System.currentTimeMillis()
)
