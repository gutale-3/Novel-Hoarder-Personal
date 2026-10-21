package com.example.data.scraper

import com.example.data.scraper.DownloadQuota.Companion.HOUR_MS
import com.example.data.scraper.DownloadQuota.Companion.remainingFor
import com.example.data.scraper.DownloadQuota.Companion.waitFor
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQuotaTest {
    private val tomato = DownloadQuota.Limits(autoDownload = false, perHour = 100, perDay = 600)
    private val now = 10 * 24 * HOUR_MS

    @Test
    fun unlimitedNeverWaits() {
        val open = DownloadQuota.Limits(true, 0, 0)
        assertEquals(Int.MAX_VALUE, remainingFor(open, List(5000) { now }, now))
        assertEquals(0L, waitFor(open, List(5000) { now }, now))
    }

    @Test
    fun hourlyCapCountsOnlyTheLastHour() {
        val stamps = List(100) { now - 30 * 60_000L } + List(50) { now - 2 * HOUR_MS }
        assertEquals(0, remainingFor(tomato, stamps, now))
        // The 100 recent ones age out 30 minutes from now.
        assertEquals(30 * 60_000L, waitFor(tomato, stamps, now))
    }

    @Test
    fun dailyCapWinsWhenHourIsFree() {
        val stamps = List(600) { i -> now - 2 * HOUR_MS - i * 60_000L }
        assertEquals(0, remainingFor(tomato, stamps, now))
        val oldest = stamps.min()
        assertEquals(oldest + 24 * HOUR_MS - now, waitFor(tomato, stamps, now))
    }

    @Test
    fun partialUseLeavesTheSmallerRemainder() {
        val stamps = List(40) { now - 10_000L } + List(520) { now - 3 * HOUR_MS }
        assertEquals(40, remainingFor(tomato, stamps, now)) // day: 600-560=40, hour: 100-40=60
    }
}
