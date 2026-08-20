package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "replacement_rules")
data class TextReplacementRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String? = null, // null indicates global rule across all books
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val isCaseSensitive: Boolean = false,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
