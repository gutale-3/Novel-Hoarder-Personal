package com.example.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.data.local.TextReplacementRuleEntity
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextReplacementManager(
    private val repository: NovelRepository,
    private val coroutineScope: CoroutineScope
) {
    var lastOperationStatus by mutableStateOf<String?>(null)

    fun getAllRulesFlow(): Flow<List<TextReplacementRuleEntity>> = repository.getAllReplacementRulesFlow()

    fun getRulesForBookFlow(bookId: String): Flow<List<TextReplacementRuleEntity>> = repository.getReplacementRulesForBookFlow(bookId)

    fun addRule(
        bookId: String?,
        pattern: String,
        replacement: String,
        isRegex: Boolean,
        isCaseSensitive: Boolean,
        onComplete: (Long) -> Unit = {}
    ) {
        if (pattern.isEmpty()) return
        coroutineScope.launch(Dispatchers.IO) {
            val rule = TextReplacementRuleEntity(
                bookId = bookId,
                pattern = pattern,
                replacement = replacement,
                isRegex = isRegex,
                isCaseSensitive = isCaseSensitive,
                isEnabled = true,
                createdAt = System.currentTimeMillis()
            )
            val id = repository.insertReplacementRule(rule)
            withContext(Dispatchers.Main) {
                onComplete(id)
            }
        }
    }

    fun updateRule(rule: TextReplacementRuleEntity) {
        coroutineScope.launch(Dispatchers.IO) {
            repository.updateReplacementRule(rule)
        }
    }

    fun deleteRule(rule: TextReplacementRuleEntity) {
        coroutineScope.launch(Dispatchers.IO) {
            repository.deleteReplacementRule(rule)
        }
    }

    fun deleteRuleById(id: Long) {
        coroutineScope.launch(Dispatchers.IO) {
            repository.deleteReplacementRuleById(id)
        }
    }

    suspend fun applyRulesToText(text: String, bookId: String?): String = withContext(Dispatchers.Default) {
        val rules = if (bookId != null) {
            repository.getReplacementRulesForBook(bookId)
        } else {
            repository.getAllReplacementRules().filter { it.bookId == null }
        }
        repository.applyReplacementRules(text, rules)
    }

    fun testRulePreview(
        pattern: String,
        replacement: String,
        sampleText: String,
        isRegex: Boolean,
        isCaseSensitive: Boolean
    ): Pair<String, String?> {
        if (pattern.isEmpty()) return Pair(sampleText, null)
        return try {
            val result = if (isRegex) {
                val regexOption = if (isCaseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE)
                sampleText.replace(Regex(pattern, regexOption), replacement)
            } else {
                sampleText.replace(pattern, replacement, ignoreCase = !isCaseSensitive)
            }
            Pair(result, null)
        } catch (e: Exception) {
            Pair(sampleText, e.message ?: "Invalid regex pattern")
        }
    }

    fun batchApplyRulesToAllChapters(
        bookId: String,
        onComplete: (Int, Int) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val rules = repository.getReplacementRulesForBook(bookId).filter { it.isEnabled }
                if (rules.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onComplete(0, 0)
                    }
                    return@launch
                }

                val chapters = repository.getChapters(bookId)
                var modifiedChapters = 0
                var totalReplacements = 0

                for (ch in chapters) {
                    if (ch.content.isBlank()) continue
                    val originalContent = ch.content
                    val newContent = repository.applyReplacementRules(originalContent, rules)
                    if (newContent != originalContent) {
                        repository.updateChapterContent(ch.id, newContent)
                        modifiedChapters++
                        totalReplacements++
                    }
                }

                withContext(Dispatchers.Main) {
                    lastOperationStatus = "Applied rules to $modifiedChapters chapters"
                    onComplete(modifiedChapters, totalReplacements)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    lastOperationStatus = "Error applying rules: ${e.message}"
                    onComplete(0, 0)
                }
            }
        }
    }

    suspend fun bulkFindAndReplace(
        bookId: String?,
        findText: String,
        replaceText: String,
        scopeAllBooks: Boolean
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (findText.isEmpty()) return@withContext Pair(0, 0)

        var chaptersModified = 0
        var totalMatchesReplaced = 0

        val targetChapters = if (scopeAllBooks) {
            val books = repository.getAllBooks()
            books.flatMap { repository.getChapters(it.id) }
        } else {
            if (bookId == null) emptyList() else repository.getChapters(bookId)
        }

        val regex = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE)
        for (chapter in targetChapters) {
            if (chapter.content.contains(findText, ignoreCase = true)) {
                val matches = regex.findAll(chapter.content).count()
                if (matches > 0) {
                    val updatedContent = chapter.content.replace(findText, replaceText, ignoreCase = true)
                    repository.updateChapterContent(chapter.id, updatedContent)
                    chaptersModified++
                    totalMatchesReplaced += matches
                }
            }
        }

        return@withContext Pair(chaptersModified, totalMatchesReplaced)
    }
}
