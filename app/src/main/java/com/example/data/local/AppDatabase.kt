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
        BookmarkEntity::class
    ],
    version = 5,
    exportSchema = false
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

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `isDeleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chapters` ADD COLUMN `isDeleted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_hoarder_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
