package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        GlossaryEntity::class,
        PolishedChapterEntity::class,
        ChapterRecapEntity::class,
        BookmarkEntity::class,
        ReadingSessionEntity::class,
        TextReplacementRuleEntity::class
    ],
    version = 9,
    exportSchema = true
)
/**
 * Room Database represents the high-performance local persistence layer on Android,
 * serving as the robust counterpart to IndexedDB on the Web. It stores downloaded novels,
 * chapters, custom glossaries, bookmarks, and reading progress locally to enable a fully-featured,
 * seamless offline reading experience.
 */
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `polished_chapters` (" +
                    "`chapterId` TEXT NOT NULL, " +
                    "`bookId` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`chapterId`)" +
                    ")"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapter_recaps` (" +
                    "`chapterId` TEXT NOT NULL, " +
                    "`bookId` TEXT NOT NULL, " +
                    "`summary` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`chapterId`)" +
                    ")"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                    "`id` TEXT NOT NULL, " +
                    "`bookId` TEXT NOT NULL, " +
                    "`chapterId` TEXT NOT NULL, " +
                    "`paragraphIndex` INTEGER NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`note` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`)" +
                    ")"
                )
            }
        }

        /** ALTER TABLE ADD COLUMN throws if the column already exists, so probe first. */
        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase, table: String, column: String, type: String
        ) {
            var exists = false
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex >= 0) {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == column) { exists = true; break }
                    }
                }
            }
            if (!exists) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $type")
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "books", "autoArchiveHours", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "chapters", "isArchived", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "chapters", "readAt", "INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId` ON `chapters` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_glossaries_bookId` ON `glossaries` (`bookId`)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "books", "isDeleted", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "chapters", "isDeleted", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_sessions` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`bookId` TEXT NOT NULL, " +
                    "`chapterId` TEXT NOT NULL, " +
                    "`date` TEXT NOT NULL, " +
                    "`durationSeconds` INTEGER NOT NULL, " +
                    "`wordsRead` INTEGER NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL" +
                    ")"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `replacement_rules` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`bookId` TEXT, " +
                    "`pattern` TEXT NOT NULL, " +
                    "`replacement` TEXT NOT NULL, " +
                    "`isRegex` INTEGER NOT NULL DEFAULT 0, " +
                    "`isCaseSensitive` INTEGER NOT NULL DEFAULT 0, " +
                    "`isEnabled` INTEGER NOT NULL DEFAULT 1, " +
                    "`createdAt` INTEGER NOT NULL" +
                    ")"
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId_isArchived_isDeleted_chapterNumber` ON `chapters` (`bookId`, `isArchived`, `isDeleted`, `chapterNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId_chapterNumber` ON `chapters` (`bookId`, `chapterNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_hash` ON `chapters` (`hash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId_chapterId` ON `bookmarks` (`bookId`, `chapterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_sessions_bookId` ON `reading_sessions` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_sessions_date` ON `reading_sessions` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_replacement_rules_bookId` ON `replacement_rules` (`bookId`)")
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "books", "category", "TEXT NOT NULL DEFAULT 'Reading'")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_category` ON `books` (`category`)")
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure index_books_category exists for any database transitioning from 8 to 9
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_category` ON `books` (`category`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_hoarder_db"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9
                )
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
