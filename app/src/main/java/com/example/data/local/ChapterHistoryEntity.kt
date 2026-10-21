package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "chapter_history",
    indices = [
        Index(value = ["chapterId"]),
        Index(value = ["bookId"]),
        Index(value = ["timestamp"])
    ]
)
data class ChapterHistoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val chapterId: String,
    val bookId: String,
    val content: String,
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRedo: Boolean = false
)
