package com.example.util

import com.example.data.local.ChapterEntity

enum class TocSubdivisionMode(val code: String, val label: String, val description: String) {
    AS_IS(
        code = "as_is",
        label = "Leave it as it is",
        description = "Continuous flat list of all chapters from beginning to end"
    ),
    BATCH_CHAPTERS(
        code = "chapters",
        label = "Subdivide into chapters",
        description = "Group chapters into numerical sets (e.g. 1-50, 51-100, etc.)"
    ),
    VOLUMES(
        code = "volumes",
        label = "Subdivide by chapter volumes",
        description = "Group chapters by story volumes or arcs (Volume 1, Volume 2, etc.)"
    );

    companion object {
        fun fromCode(code: String): TocSubdivisionMode {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AS_IS
        }
    }
}

data class ChapterGroup(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val chapters: List<ChapterEntity>,
    val volumeIndex: Int? = null,
    val rangeText: String = ""
) {
    val totalCount: Int get() = chapters.size
    val readCount: Int get() = chapters.count { it.isRead }
    val unreadCount: Int get() = totalCount - readCount
    val isAllRead: Boolean get() = totalCount > 0 && readCount == totalCount
}

object ChapterSubdivisionHelper {

    private val VOLUME_KEYWORD_REGEX = Regex(
        "(?i)(?:^|[\\[(]|\\b)(?:volume|vol\\.?|book|arc)\\s*(\\d+|[ivxlcdm]+)(?:[:\\s\\-\\].)]*|$)"
    )

    private val CHAPTER_NUM_REGEX = Regex(
        "(?i)(?:^|[\\[(]|\\b)(?:chapter|ch\\.?|ep\\.?)\\s*(\\d+)(?:[:\\s\\-\\].)]*|$)"
    )

    private val ROMAN_NUMERALS = mapOf(
        "I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5,
        "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10,
        "XI" to 11, "XII" to 12, "XIII" to 13, "XIV" to 14, "XV" to 15,
        "XVI" to 16, "XVII" to 17, "XVIII" to 18, "XIX" to 19, "XX" to 20
    )

    fun parseRomanOrInt(value: String): Int? {
        val trimmed = value.trim()
        trimmed.toIntOrNull()?.let { return it }
        return ROMAN_NUMERALS[trimmed.uppercase()]
    }

    /**
     * Subdivides a list of chapters according to the specified subdivision mode.
     */
    fun subdivideChapters(
        chapters: List<ChapterEntity>,
        mode: TocSubdivisionMode,
        batchSize: Int = 50,
        customVolumeSplits: List<Int> = emptyList()
    ): List<ChapterGroup> {
        if (chapters.isEmpty()) return emptyList()

        return when (mode) {
            TocSubdivisionMode.AS_IS -> {
                listOf(
                    ChapterGroup(
                        id = "all_chapters",
                        title = "All Chapters",
                        subtitle = "${chapters.size} chapters",
                        chapters = chapters,
                        rangeText = "1 – ${chapters.size}"
                    )
                )
            }

            TocSubdivisionMode.BATCH_CHAPTERS -> {
                groupByChapterBatches(chapters, batchSize)
            }

            TocSubdivisionMode.VOLUMES -> {
                groupByVolumes(chapters, customVolumeSplits, defaultVolumeSize = batchSize)
            }
        }
    }

    /**
     * Subdivides chapters into groups of [batchSize] (e.g. 1 to 50, 51 to 100, ..., 951 to 970).
     */
    private fun groupByChapterBatches(
        chapters: List<ChapterEntity>,
        batchSize: Int
    ): List<ChapterGroup> {
        val size = if (batchSize <= 0) 50 else batchSize
        val groups = mutableListOf<ChapterGroup>()
        val chunks = chapters.chunked(size)

        chunks.forEachIndexed { index, chunk ->
            if (chunk.isNotEmpty()) {
                val first = chunk.first()
                val last = chunk.last()

                val firstNum = if (first.chapterNumber > 0) first.chapterNumber else (index * size + 1)
                val lastNum = if (last.chapterNumber >= firstNum) last.chapterNumber else (index * size + chunk.size)

                val title = "Chapters $firstNum – $lastNum"
                val subtitle = "${chunk.size} chapters"

                groups.add(
                    ChapterGroup(
                        id = "batch_${firstNum}_${lastNum}_$index",
                        title = title,
                        subtitle = subtitle,
                        chapters = chunk,
                        rangeText = "Ch. $firstNum – $lastNum"
                    )
                )
            }
        }

        return groups
    }

    /**
     * Subdivides chapters into volumes.
     * Supports:
     * 1. Explicit user volume splits
     * 2. Volume labels in chapter titles ("Volume 1", "Vol. 2", "Book 1")
     * 3. Chapter numbering restarts (e.g. Vol 1 is Ch 1..40, Vol 2 is Ch 1..end)
     * 4. Smart fallback grouping
     */
    private fun groupByVolumes(
        chapters: List<ChapterEntity>,
        customVolumeSplits: List<Int>,
        defaultVolumeSize: Int
    ): List<ChapterGroup> {
        // 1. If user defined custom volume splits, use them
        if (customVolumeSplits.isNotEmpty()) {
            return applyCustomSplits(chapters, customVolumeSplits)
        }

        // 2. Try explicit volume keyword grouping
        val keywordGroups = tryExtractVolumeKeywordGroups(chapters)
        if (keywordGroups.size > 1) {
            return keywordGroups
        }

        // 3. Try chapter number restart detection (e.g. Ch 1..35, then Ch 1..40)
        val restartGroups = tryExtractRestartGroups(chapters)
        if (restartGroups.size > 1) {
            return restartGroups
        }

        // 4. If single volume found from title (e.g. all Volume 1), return it
        if (keywordGroups.size == 1) {
            return keywordGroups
        }

        // 5. Fallback: Group into standard volume sizes (e.g. 50 chapters per volume)
        return fallbackVolumeGroups(chapters, defaultVolumeSize)
    }

    private fun applyCustomSplits(
        chapters: List<ChapterEntity>,
        splits: List<Int>
    ): List<ChapterGroup> {
        val sortedSplits = splits.filter { it > 0 }.distinct().sorted()
        if (sortedSplits.isEmpty()) return fallbackVolumeGroups(chapters, 50)

        val groups = mutableListOf<ChapterGroup>()
        val startThresholds = (if (sortedSplits.first() != 1) listOf(1) + sortedSplits else sortedSplits)

        for (i in startThresholds.indices) {
            val fromNum = startThresholds[i]
            val toNum = if (i + 1 < startThresholds.size) startThresholds[i + 1] - 1 else Int.MAX_VALUE

            val chunk = chapters.filterIndexed { idx, ch ->
                val effectiveNum = if (ch.chapterNumber > 0) ch.chapterNumber else (idx + 1)
                effectiveNum in fromNum..toNum
            }

            if (chunk.isNotEmpty()) {
                val volIdx = i + 1
                val firstNum = chunk.first().chapterNumber.let { if (it > 0) it else fromNum }
                val lastNum = chunk.last().chapterNumber.let { if (it > 0) it else (fromNum + chunk.size - 1) }

                groups.add(
                    ChapterGroup(
                        id = "volume_custom_$volIdx",
                        title = "Volume $volIdx",
                        subtitle = "Chapters $firstNum – $lastNum · ${chunk.size} chapters",
                        chapters = chunk,
                        volumeIndex = volIdx,
                        rangeText = "Ch. $firstNum – $lastNum"
                    )
                )
            }
        }

        return if (groups.isNotEmpty()) groups else fallbackVolumeGroups(chapters, 50)
    }

    private fun tryExtractVolumeKeywordGroups(chapters: List<ChapterEntity>): List<ChapterGroup> {
        val groups = mutableListOf<ChapterGroup>()
        var currentVolNum = -1
        var currentVolName = ""
        var currentList = mutableListOf<ChapterEntity>()

        for (ch in chapters) {
            val match = VOLUME_KEYWORD_REGEX.find(ch.title)
            val parsedVolNum = match?.groupValues?.get(1)?.let { parseRomanOrInt(it) }

            if (parsedVolNum != null && parsedVolNum != currentVolNum) {
                if (currentList.isNotEmpty()) {
                    val volIdx = if (currentVolNum > 0) currentVolNum else (groups.size + 1)
                    val volTitle = if (currentVolName.isNotBlank()) currentVolName else "Volume $volIdx"
                    groups.add(buildVolumeGroup(volIdx, volTitle, currentList))
                    currentList = mutableListOf()
                }
                currentVolNum = parsedVolNum
                currentVolName = extractVolumeNameFromTitle(ch.title, parsedVolNum)
            }
            currentList.add(ch)
        }

        if (currentList.isNotEmpty()) {
            val volIdx = if (currentVolNum > 0) currentVolNum else (groups.size + 1)
            val volTitle = if (currentVolName.isNotBlank()) currentVolName else "Volume $volIdx"
            groups.add(buildVolumeGroup(volIdx, volTitle, currentList))
        }

        return groups
    }

    private fun extractVolumeNameFromTitle(title: String, volNum: Int): String {
        val colonIdx = title.indexOf(':')
        if (colonIdx != -1 && colonIdx < 30) {
            val prefix = title.substring(0, colonIdx).trim()
            if (prefix.contains("vol", ignoreCase = true) || prefix.contains("volume", ignoreCase = true) || prefix.contains("book", ignoreCase = true)) {
                return prefix
            }
        }
        return "Volume $volNum"
    }

    private fun tryExtractRestartGroups(chapters: List<ChapterEntity>): List<ChapterGroup> {
        val groups = mutableListOf<ChapterGroup>()
        var currentVolIndex = 1
        var currentList = mutableListOf<ChapterEntity>()
        var lastNum = -1

        for (ch in chapters) {
            val chNum = extractChapterNumber(ch)
            val isRestart = (lastNum >= 15 && chNum in 1..3) || (lastNum >= 20 && chNum < lastNum - 10)

            if (isRestart && currentList.isNotEmpty()) {
                groups.add(
                    buildVolumeGroup(
                        volIndex = currentVolIndex,
                        volTitle = "Volume $currentVolIndex",
                        chapters = currentList
                    )
                )
                currentVolIndex++
                currentList = mutableListOf()
            }

            currentList.add(ch)
            if (chNum > 0) {
                lastNum = chNum
            }
        }

        if (currentList.isNotEmpty()) {
            groups.add(
                buildVolumeGroup(
                    volIndex = currentVolIndex,
                    volTitle = "Volume $currentVolIndex",
                    chapters = currentList
                )
            )
        }

        return groups
    }

    private fun extractChapterNumber(ch: ChapterEntity): Int {
        if (ch.chapterNumber > 0) return ch.chapterNumber
        val match = CHAPTER_NUM_REGEX.find(ch.title)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun fallbackVolumeGroups(
        chapters: List<ChapterEntity>,
        volumeSize: Int
    ): List<ChapterGroup> {
        val size = if (volumeSize <= 0) 50 else volumeSize
        val groups = mutableListOf<ChapterGroup>()
        val chunks = chapters.chunked(size)

        chunks.forEachIndexed { idx, chunk ->
            val volIdx = idx + 1
            val first = chunk.first()
            val last = chunk.last()
            val firstNum = if (first.chapterNumber > 0) first.chapterNumber else (idx * size + 1)
            val lastNum = if (last.chapterNumber >= firstNum) last.chapterNumber else (idx * size + chunk.size)

            groups.add(
                ChapterGroup(
                    id = "volume_fallback_$volIdx",
                    title = "Volume $volIdx",
                    subtitle = "Chapters $firstNum – $lastNum · ${chunk.size} chapters",
                    chapters = chunk,
                    volumeIndex = volIdx,
                    rangeText = "Ch. $firstNum – $lastNum"
                )
            )
        }

        return groups
    }

    private fun buildVolumeGroup(
        volIndex: Int,
        volTitle: String,
        chapters: List<ChapterEntity>
    ): ChapterGroup {
        val first = chapters.firstOrNull()
        val last = chapters.lastOrNull()
        val firstNum = first?.chapterNumber ?: 1
        val lastNum = last?.chapterNumber ?: chapters.size

        val rangeDesc = if (firstNum > 0 && lastNum >= firstNum) "Ch. $firstNum – $lastNum" else "${chapters.size} ch"
        val subtitle = "$rangeDesc · ${chapters.size} chapters"

        return ChapterGroup(
            id = "volume_${volIndex}_${first?.id ?: ""}",
            title = volTitle,
            subtitle = subtitle,
            chapters = chapters,
            volumeIndex = volIndex,
            rangeText = rangeDesc
        )
    }

    fun findGroupIdForChapter(groups: List<ChapterGroup>, chapterId: String): String? {
        return groups.firstOrNull { g -> g.chapters.any { it.id == chapterId } }?.id
    }
}
