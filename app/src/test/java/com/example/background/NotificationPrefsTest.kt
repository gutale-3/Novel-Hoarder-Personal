package com.example.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = NotificationPrefs.get(context)
    private val hour = 3_600_000L

    @Before
    fun reset() {
        context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun sameBacklogIsNotAnnouncedTwice() {
        val found = listOf(Triple("a", "Big Novel", 1000))
        assertEquals(1, prefs.chapterAlertsDue(found, now = 100 * hour).size)
        prefs.recordChapterAlert(found, now = 100 * hour)
        prefs.minHoursBetween = 0
        // Five minutes later, and even a day later, the same 1,000 chapters stay quiet.
        assertTrue(prefs.chapterAlertsDue(found, now = 100 * hour + 300_000).isEmpty())
        assertTrue(prefs.chapterAlertsDue(found, now = 124 * hour).isEmpty())
        // More chapters do get announced.
        assertEquals(1, prefs.chapterAlertsDue(listOf(Triple("a", "Big Novel", 1010)), now = 125 * hour).size)
    }

    @Test
    fun spacingAndMutingAreRespected() {
        prefs.minHoursBetween = 24
        prefs.recordChapterAlert(emptyList(), now = 0)
        val found = listOf(Triple("a", "A", 3), Triple("b", "B", 5))
        assertTrue(prefs.chapterAlertsDue(found, now = 5 * hour).isEmpty())
        prefs.setMuted("b", true)
        assertEquals(listOf("a"), prefs.chapterAlertsDue(found, now = 25 * hour).map { it.first })
        prefs.newChapters = false
        assertTrue(prefs.chapterAlertsDue(found, now = 50 * hour).isEmpty())
    }
}
