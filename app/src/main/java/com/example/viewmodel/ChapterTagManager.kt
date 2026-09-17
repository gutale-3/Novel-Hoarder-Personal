package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class MilestoneTag(
    val id: String,
    val name: String,
    val icon: String,
    val colorHex: Long,
    val description: String = ""
)

class ChapterTagManager(private val application: Application) {
    private val prefs = application.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)

    companion object {
        val PRESET_TAGS = listOf(
            MilestoneTag("favorite", "Favorite", "⭐", 0xFFF59E0B, "Favorite chapter to re-read"),
            MilestoneTag("action", "Fight / Action", "⚔️", 0xFFE53935, "Major battle or tournament action"),
            MilestoneTag("lore", "Lore / World", "📜", 0xFF8E24AA, "Key lore or world-building revelation"),
            MilestoneTag("cliffhanger", "Cliffhanger", "⚠️", 0xFFFB8C00, "High-tension cliffhanger or twist"),
            MilestoneTag("climax", "Climax / Peak", "💡", 0xFFD81B60, "Major arc climax or turning point"),
            MilestoneTag("breakthrough", "Breakthrough", "🌀", 0xFF0288D1, "Power-up, promotion, or realm upgrade"),
            MilestoneTag("comedy", "Dialogue / Fun", "💬", 0xFF00897B, "Wholesome banter or comedy highlight")
        )
    }

    // chapterId -> Set of tag IDs
    val chapterTags = mutableStateMapOf<String, Set<String>>()

    // Optional active filter in TOC (null = show all)
    var activeTagFilter by mutableStateOf<String?>(null)

    init {
        loadTags()
    }

    private fun loadTags() {
        try {
            val jsonStr = prefs.getString("chapter_milestone_tags", null) ?: return
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val chId = keys.next()
                val arr = json.optJSONArray(chId)
                if (arr != null) {
                    val tagSet = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        tagSet.add(arr.getString(i))
                    }
                    if (tagSet.isNotEmpty()) {
                        chapterTags[chId] = tagSet
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persistTags() {
        try {
            val json = JSONObject()
            for ((chId, tags) in chapterTags) {
                if (tags.isNotEmpty()) {
                    val arr = JSONArray()
                    tags.forEach { arr.put(it) }
                    json.put(chId, arr)
                }
            }
            prefs.edit().putString("chapter_milestone_tags", json.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAllAvailableTags(): List<MilestoneTag> {
        return PRESET_TAGS
    }

    fun getTagById(tagId: String): MilestoneTag? {
        return PRESET_TAGS.find { it.id == tagId }
    }

    fun getTagsForChapter(chapterId: String): List<MilestoneTag> {
        val tagIds = chapterTags[chapterId] ?: return emptyList()
        return tagIds.mapNotNull { getTagById(it) }
    }

    fun hasTag(chapterId: String, tagId: String): Boolean {
        return chapterTags[chapterId]?.contains(tagId) == true
    }

    fun toggleTag(chapterId: String, tagId: String) {
        val current = chapterTags[chapterId] ?: emptySet()
        if (current.contains(tagId)) {
            val updated = current - tagId
            if (updated.isEmpty()) {
                chapterTags.remove(chapterId)
            } else {
                chapterTags[chapterId] = updated
            }
        } else {
            chapterTags[chapterId] = current + tagId
        }
        persistTags()
    }

    fun clearTagsForChapter(chapterId: String) {
        chapterTags.remove(chapterId)
        persistTags()
    }

    fun getChapterCountForTag(bookChapterIds: Collection<String>, tagId: String): Int {
        return bookChapterIds.count { hasTag(it, tagId) }
    }
}
