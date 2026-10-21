package com.example.util

/**
 * How long one chapter page may take before the user is asked to look at it. Read by every
 * scraper; set from Settings (SettingsManager keeps it in sync with the stored preference).
 */
object ScrapeTimeouts {
    const val DEFAULT_CHAPTER_SECONDS = 10

    @Volatile
    var chapterSeconds: Int = DEFAULT_CHAPTER_SECONDS

    val chapterMs: Long get() = chapterSeconds * 1000L

    /** A bot check that is still there after this long is handed to the user. */
    const val CHALLENGE_GRACE_MS = 3_000L
}
