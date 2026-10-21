package com.example.data.scraper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HumanPacingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun gapsAreUnevenAndWithinRange() {
        val r = Random(7)
        val gaps = (1..11).map { HumanPacing.nextDelayMs(context, "site_a", 5000, it, r) }
        assertTrue(gaps.all { it in 3500L..9000L })
        assertTrue(gaps.toSet().size > 5)
    }

    @Test
    fun aBotCheckSlowsThatSiteOnly() {
        HumanPacing.onChallenge(context, "wtrlab")
        assertEquals(2f, HumanPacing.slowdownFactor(context, "wtrlab"))
        assertEquals(1f, HumanPacing.slowdownFactor(context, "novelbin"))
        HumanPacing.onChallenge(context, "wtrlab")
        assertEquals(4f, HumanPacing.slowdownFactor(context, "wtrlab"))
    }

    @Test
    fun challengePauseBlocksBackgroundUntilCleared() {
        val quota = DownloadQuota.get(context)
        quota.markChallenged("wtrlab")
        assertEquals(0, quota.remaining("wtrlab"))
        quota.clearChallenge("wtrlab")
        assertTrue(quota.remaining("wtrlab") > 0)
    }
}
