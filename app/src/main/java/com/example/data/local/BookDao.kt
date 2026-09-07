package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    // --- Books ---
    @Query("SELECT * FROM books WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun getAllBooksFlow(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    suspend fun getAllBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE isDeleted = 1 ORDER BY updatedAt DESC")
    fun getDeletedBooksFlow(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isDeleted = 1 ORDER BY updatedAt DESC")
    suspend fun getDeletedBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookByIdFlow(id: String): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookInternal(book: BookEntity): Long

    @Update
    suspend fun updateBook(book: BookEntity)

    @Transaction
    suspend fun insertBook(book: BookEntity) {
        val rowId = insertBookInternal(book)
        if (rowId == -1L) {
            updateBook(book)
        }
    }

    @Transaction
    suspend fun insertBookAndChapters(book: BookEntity, chapters: List<ChapterEntity>) {
        insertBook(book)
        insertChapters(chapters)
    }

    // Soft delete / restore books
    @Query("UPDATE books SET isDeleted = 1 WHERE id = :id")
    suspend fun softDeleteBookById(id: String)

    @Query("UPDATE chapters SET isDeleted = 1 WHERE bookId = :bookId")
    suspend fun softDeleteChaptersByBookId(bookId: String)

    @Transaction
    suspend fun softDeleteBookFully(bookId: String) {
        softDeleteBookById(bookId)
        softDeleteChaptersByBookId(bookId)
    }

    @Query("UPDATE books SET isDeleted = 0 WHERE id = :id")
    suspend fun restoreBookById(id: String)

    @Query("UPDATE chapters SET isDeleted = 0 WHERE bookId = :bookId")
    suspend fun restoreChaptersByBookId(bookId: String)

    @Transaction
    suspend fun restoreBookFully(bookId: String) {
        restoreBookById(bookId)
        restoreChaptersByBookId(bookId)
    }

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookById(id: String)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersByBookId(bookId: String)

    @Query("DELETE FROM glossaries WHERE bookId = :bookId")
    suspend fun deleteGlossariesByBookId(bookId: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksByBookId(bookId: String)

    @Query("DELETE FROM polished_chapters WHERE bookId = :bookId")
    suspend fun deletePolishedChaptersByBookId(bookId: String)

    @Query("DELETE FROM chapter_recaps WHERE bookId = :bookId")
    suspend fun deleteRecapsByBookId(bookId: String)

    @Transaction
    suspend fun deleteBookFully(bookId: String) {
        deleteBookById(bookId)
        deleteChaptersByBookId(bookId)
        deleteGlossariesByBookId(bookId)
        deleteBookmarksByBookId(bookId)
        deletePolishedChaptersByBookId(bookId)
        deleteRecapsByBookId(bookId)
    }

    @Query("DELETE FROM books WHERE isDeleted = 1")
    suspend fun permanentlyDeleteAllDeletedBooks()

    @Query("DELETE FROM chapters WHERE isDeleted = 1")
    suspend fun permanentlyDeleteAllDeletedChapters()

    @Query("SELECT COUNT(*) FROM books WHERE isDeleted = 0")
    suspend fun getBookCount(): Int

    // --- Chapters ---
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND isArchived = 0 AND isDeleted = 0 ORDER BY chapterNumber ASC")
    fun getChaptersForBookFlow(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND isArchived = 0 AND isDeleted = 0 ORDER BY chapterNumber ASC")
    suspend fun getChaptersForBook(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE isDeleted = 1 ORDER BY downloadedAt DESC")
    fun getDeletedChaptersFlow(): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE isDeleted = 1 ORDER BY downloadedAt DESC")
    suspend fun getDeletedChapters(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND isArchived = 1 AND isDeleted = 0 ORDER BY chapterNumber ASC")
    fun getArchivedChaptersForBookFlow(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND isArchived = 1 AND isDeleted = 0 ORDER BY chapterNumber ASC")
    suspend fun getArchivedChaptersForBook(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getChapterById(id: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE id = :id")
    fun getChapterByIdFlow(id: String): Flow<ChapterEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: ChapterEntity)

    @Query("UPDATE chapters SET chapterNumber = :chapterNumber WHERE id = :id")
    suspend fun updateChapterNumber(id: String, chapterNumber: Int)

    @Query("UPDATE chapters SET isRead = :isRead, readAt = :readAt WHERE id = :chapterId")
    suspend fun updateChapterReadStatus(chapterId: String, isRead: Boolean, readAt: Long?)

    @Query("UPDATE chapters SET isRead = :isRead, readAt = :readAt WHERE id IN (:chapterIds)")
    suspend fun updateChaptersReadStatus(chapterIds: List<String>, isRead: Boolean, readAt: Long?)

    @Query("UPDATE chapters SET isArchived = :isArchived WHERE id = :chapterId")
    suspend fun updateChapterArchiveStatus(chapterId: String, isArchived: Boolean)

    @Query("UPDATE chapters SET isArchived = :isArchived WHERE id IN (:chapterIds)")
    suspend fun updateChaptersArchiveStatus(chapterIds: List<String>, isArchived: Boolean)

    // Soft Delete / Restore Chapters
    @Query("UPDATE chapters SET isDeleted = 1 WHERE id = :id")
    suspend fun softDeleteChapterById(id: String)

    @Query("UPDATE chapters SET isDeleted = 1 WHERE id IN (:ids)")
    suspend fun softDeleteChaptersByIds(ids: List<String>)

    @Query("UPDATE chapters SET isDeleted = 0 WHERE id = :id")
    suspend fun restoreChapterById(id: String)

    @Query("UPDATE chapters SET isDeleted = 0 WHERE id IN (:ids)")
    suspend fun restoreChaptersByIds(ids: List<String>)

    @Query("DELETE FROM chapters WHERE id = :id")
    suspend fun deleteChapterById(id: String)

    @Query("DELETE FROM chapters WHERE id IN (:ids)")
    suspend fun deleteChaptersByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND isDeleted = 0")
    suspend fun getChapterCountForBook(bookId: String): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND content != '' AND isDeleted = 0")
    suspend fun getDownloadedChapterCount(bookId: String): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND content != '' AND isDeleted = 0")
    fun getDownloadedChapterCountFlow(bookId: String): Flow<Int>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND content = '' AND isDeleted = 0 ORDER BY chapterNumber ASC LIMIT :limit")
    suspend fun getPendingChapters(bookId: String, limit: Int): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND content = '' AND isDeleted = 0 AND chapterNumber > :afterNumber ORDER BY chapterNumber ASC LIMIT 1")
    suspend fun getNextPendingChapter(bookId: String, afterNumber: Int): ChapterEntity?

    @Query("SELECT COUNT(*) FROM chapters WHERE isDeleted = 0")
    suspend fun getTotalChapterCount(): Int

    // --- Glossaries ---
    @Query("SELECT * FROM glossaries WHERE bookId = :bookId")
    fun getGlossaryForBookFlow(bookId: String): Flow<List<GlossaryEntity>>

    @Query("SELECT * FROM glossaries WHERE bookId = :bookId")
    suspend fun getGlossaryForBook(bookId: String): List<GlossaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGlossary(glossary: GlossaryEntity)

    @Delete
    suspend fun deleteGlossary(glossary: GlossaryEntity)

    @Query("DELETE FROM glossaries WHERE bookId = :bookId")
    suspend fun clearGlossaryForBook(bookId: String)

    // --- Polished Chapters ---
    @Query("SELECT * FROM polished_chapters WHERE chapterId = :chapterId")
    suspend fun getPolishedChapter(chapterId: String): PolishedChapterEntity?

    @Query("SELECT * FROM polished_chapters WHERE chapterId = :chapterId")
    fun getPolishedChapterFlow(chapterId: String): Flow<PolishedChapterEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolishedChapter(polished: PolishedChapterEntity)

    @Query("DELETE FROM polished_chapters WHERE chapterId = :chapterId")
    suspend fun deletePolishedChapter(chapterId: String)

    // --- Chapter Recaps ---
    @Query("SELECT * FROM chapter_recaps WHERE chapterId = :chapterId")
    suspend fun getChapterRecap(chapterId: String): ChapterRecapEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapterRecap(recap: ChapterRecapEntity)

    @Query("DELETE FROM chapter_recaps WHERE chapterId = :chapterId")
    suspend fun deleteChapterRecap(chapterId: String)

    // --- Bulk Updates / Find-and-Replace ---
    @Query("UPDATE chapters SET content = :content WHERE id = :id")
    suspend fun updateChapterContent(id: String, content: String)

    // --- Bookmarks ---
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY timestamp DESC")
    fun getBookmarksForBookFlow(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: String)

    // --- Unread Counts ---
    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND isRead = 0")
    suspend fun getUnreadChapterCount(bookId: String): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND isRead = 0")
    fun getUnreadChapterCountFlow(bookId: String): Flow<Int>

    // --- Bulk Backup Queries ---
    @Query("SELECT * FROM bookmarks")
    suspend fun getAllBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM glossaries")
    suspend fun getAllGlossaries(): List<GlossaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<BookEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGlossaries(glossaries: List<GlossaryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>)

    // --- Reading Sessions & Stats ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReadingSession(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions ORDER BY timestamp DESC")
    fun getAllReadingSessionsFlow(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions ORDER BY timestamp DESC")
    suspend fun getAllReadingSessions(): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY timestamp DESC")
    fun getReadingSessionsForBookFlow(bookId: String): Flow<List<ReadingSessionEntity>>

    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM reading_sessions")
    suspend fun getTotalReadingTimeSeconds(): Long

    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM reading_sessions")
    fun getTotalReadingTimeSecondsFlow(): Flow<Long>

    @Query("SELECT COALESCE(SUM(wordsRead), 0) FROM reading_sessions")
    suspend fun getTotalWordsRead(): Long

    @Query("SELECT COALESCE(SUM(wordsRead), 0) FROM reading_sessions")
    fun getTotalWordsReadFlow(): Flow<Long>

    @Query("SELECT DISTINCT date FROM reading_sessions ORDER BY date DESC")
    suspend fun getDistinctReadingDates(): List<String>

    @Query("DELETE FROM reading_sessions WHERE bookId = :bookId")
    suspend fun deleteReadingSessionsForBook(bookId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReadingSessions(sessions: List<ReadingSessionEntity>)

    // --- Text Replacement Rules ---
    @Query("SELECT * FROM replacement_rules ORDER BY createdAt DESC")
    fun getAllReplacementRulesFlow(): Flow<List<TextReplacementRuleEntity>>

    @Query("SELECT * FROM replacement_rules ORDER BY createdAt DESC")
    suspend fun getAllReplacementRules(): List<TextReplacementRuleEntity>

    @Query("SELECT * FROM replacement_rules WHERE bookId IS NULL OR bookId = :bookId ORDER BY createdAt DESC")
    fun getReplacementRulesForBookFlow(bookId: String): Flow<List<TextReplacementRuleEntity>>

    @Query("SELECT * FROM replacement_rules WHERE bookId IS NULL OR bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getReplacementRulesForBook(bookId: String): List<TextReplacementRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplacementRule(rule: TextReplacementRuleEntity): Long

    @Update
    suspend fun updateReplacementRule(rule: TextReplacementRuleEntity)

    @Delete
    suspend fun deleteReplacementRule(rule: TextReplacementRuleEntity)

    @Query("DELETE FROM replacement_rules WHERE id = :id")
    suspend fun deleteReplacementRuleById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplacementRules(rules: List<TextReplacementRuleEntity>)

    // --- Batch Aggregated Stats (Single-Query Elimination of N+1) ---
    @Query("""
        SELECT 
            bookId,
            COUNT(*) AS totalChapters,
            COUNT(CASE WHEN isRead = 0 THEN 1 END) AS unreadCount,
            COUNT(CASE WHEN content != '' THEN 1 END) AS downloadedCount
        FROM chapters
        WHERE isDeleted = 0
        GROUP BY bookId
    """)
    fun getAllBookStatsFlow(): Flow<List<BookStats>>

    @Query("""
        SELECT 
            bookId,
            COUNT(*) AS totalChapters,
            COUNT(CASE WHEN isRead = 0 THEN 1 END) AS unreadCount,
            COUNT(CASE WHEN content != '' THEN 1 END) AS downloadedCount
        FROM chapters
        WHERE isDeleted = 0
        GROUP BY bookId
    """)
    suspend fun getAllBookStats(): List<BookStats>

    // --- Category Management ---
    @Query("UPDATE books SET category = :category WHERE id = :bookId")
    suspend fun updateBookCategory(bookId: String, category: String)

    @Query("UPDATE books SET category = :category WHERE id IN (:bookIds)")
    suspend fun updateBooksCategory(bookIds: List<String>, category: String)

    // --- In-Novel & Global Chapter Full-Text Search ---
    @Query("""
        SELECT * FROM chapters 
        WHERE bookId = :bookId 
          AND isDeleted = 0 
          AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%')
        ORDER BY chapterNumber ASC
    """)
    suspend fun searchChaptersInBook(bookId: String, query: String): List<ChapterEntity>

    @Query("""
        SELECT * FROM chapters 
        WHERE isDeleted = 0 
          AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%')
        ORDER BY bookId, chapterNumber ASC
        LIMIT :limit
    """)
    suspend fun searchAllChapters(query: String, limit: Int = 100): List<ChapterEntity>

    // --- Storage Maintenance / Space Reclamation ---
    @Query("UPDATE chapters SET content = '' WHERE bookId = :bookId AND isRead = 1")
    suspend fun clearReadChaptersContent(bookId: String): Int

    @Query("UPDATE chapters SET content = '' WHERE isRead = 1")
    suspend fun clearAllReadChaptersContent(): Int
}

data class BookStats(
    val bookId: String,
    val totalChapters: Int,
    val unreadCount: Int,
    val downloadedCount: Int
)

