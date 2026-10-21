package com.example.data.scraper

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-site download limits and per-novel auto-download choices.
 *
 * Some sites (TomatoMTL) only serve ~100 chapters an hour and ~600 a day per account; going past
 * that gets login/limit pages instead of text. Every chapter download, manual or automatic, is
 * counted here, so the scraper can wait for the window to reopen instead of burning the quota on
 * failures, and the background auto-download never spends the day's allowance on one novel.
 */
class DownloadQuota private constructor(context: Context) {

    data class Limits(
        /** Whether new chapters of this site's novels download in the background by default. */
        val autoDownload: Boolean,
        /** 0 means unlimited. */
        val perHour: Int,
        val perDay: Int
    )

    private val prefs = context.applicationContext.getSharedPreferences("download_quota", Context.MODE_PRIVATE)

    /** Bumped on every settings change so Compose screens can recompose. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    // ---------- site limits ----------

    fun limitsFor(sourceKey: String): Limits {
        val default = DEFAULTS[sourceKey] ?: Limits(autoDownload = true, perHour = 0, perDay = 0)
        return Limits(
            autoDownload = prefs.getBoolean("auto_$sourceKey", default.autoDownload),
            perHour = prefs.getInt("hour_$sourceKey", default.perHour),
            perDay = prefs.getInt("day_$sourceKey", default.perDay)
        )
    }

    fun setLimits(sourceKey: String, limits: Limits) {
        prefs.edit()
            .putBoolean("auto_$sourceKey", limits.autoDownload)
            .putInt("hour_$sourceKey", limits.perHour.coerceAtLeast(0))
            .putInt("day_$sourceKey", limits.perDay.coerceAtLeast(0))
            .apply()
        _version.value++
    }

    // ---------- per-novel override ----------

    /** null = follow the site's default. */
    fun bookOverride(bookId: String): Boolean? =
        if (prefs.contains("book_$bookId")) prefs.getBoolean("book_$bookId", false) else null

    fun setBookOverride(bookId: String, enabled: Boolean?) {
        if (enabled == null) prefs.edit().remove("book_$bookId").apply()
        else prefs.edit().putBoolean("book_$bookId", enabled).apply()
        _version.value++
    }

    fun isAutoDownloadEnabled(bookId: String, bookUrl: String): Boolean =
        bookOverride(bookId) ?: limitsFor(SourceKeys.keyFor(bookUrl)).autoDownload

    // ---------- usage accounting ----------

    @Synchronized
    private fun stamps(sourceKey: String, now: Long): MutableList<Long> {
        val raw = prefs.getString("used_$sourceKey", "").orEmpty()
        return raw.split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < DAY_MS }
            .toMutableList()
    }

    /** Records one downloaded chapter for [sourceKey]. */
    @Synchronized
    fun record(sourceKey: String, now: Long = System.currentTimeMillis()) {
        val list = stamps(sourceKey, now)
        list += now
        prefs.edit().putString("used_$sourceKey", list.joinToString(",")).apply()
        _version.value++
    }

    data class Usage(val lastHour: Int, val lastDay: Int)

    fun usage(sourceKey: String, now: Long = System.currentTimeMillis()): Usage {
        val list = stamps(sourceKey, now)
        return Usage(list.count { now - it < HOUR_MS }, list.size)
    }

    /** Chapters that may still be downloaded right now; [Int.MAX_VALUE] when unlimited. */
    fun remaining(sourceKey: String, now: Long = System.currentTimeMillis()): Int =
        remainingFor(limitsFor(sourceKey), stamps(sourceKey, now), now)

    /** Milliseconds until at least one more chapter is allowed (0 if allowed now). */
    fun waitMs(sourceKey: String, now: Long = System.currentTimeMillis()): Long =
        waitFor(limitsFor(sourceKey), stamps(sourceKey, now), now)

    companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS

        /**
         * Built-in defaults. Tomato is metered per account, so it stays manual unless the user
         * opts a novel in, and both windows are enforced. Everything else defaults to unlimited.
         */
        val DEFAULTS: Map<String, Limits> = mapOf(
            SourceKeys.TOMATO to Limits(autoDownload = false, perHour = 100, perDay = 600)
        )

        fun remainingFor(limits: Limits, stamps: List<Long>, now: Long): Int {
            var left = Int.MAX_VALUE
            if (limits.perHour > 0) left = minOf(left, limits.perHour - stamps.count { now - it < HOUR_MS })
            if (limits.perDay > 0) left = minOf(left, limits.perDay - stamps.count { now - it < DAY_MS })
            return left.coerceAtLeast(0)
        }

        fun waitFor(limits: Limits, stamps: List<Long>, now: Long): Long {
            if (remainingFor(limits, stamps, now) > 0) return 0L
            val sorted = stamps.sorted()
            var wait = 0L
            // The window reopens when the oldest counted download in it ages out.
            if (limits.perHour > 0) {
                val inHour = sorted.filter { now - it < HOUR_MS }
                if (inHour.size >= limits.perHour) {
                    wait = maxOf(wait, inHour[inHour.size - limits.perHour] + HOUR_MS - now)
                }
            }
            if (limits.perDay > 0) {
                val inDay = sorted.filter { now - it < DAY_MS }
                if (inDay.size >= limits.perDay) {
                    wait = maxOf(wait, inDay[inDay.size - limits.perDay] + DAY_MS - now)
                }
            }
            return wait.coerceAtLeast(1_000L)
        }

        @Volatile private var instance: DownloadQuota? = null
        fun get(context: Context): DownloadQuota =
            instance ?: synchronized(this) { instance ?: DownloadQuota(context).also { instance = it } }
    }
}

/** Stable identifiers for sources, used as keys for limits and usage. */
object SourceKeys {
    const val TOMATO = "tomatomtl"

    fun keyFor(url: String): String {
        val source = SourceManager.getSourceForUrl(url)
        return when (source) {
            is com.example.util.TomatoScraper -> TOMATO
            is com.example.data.plugin.PluginExecutionEngine -> source.config.id
            else -> try {
                java.net.URI(url).host?.lowercase()?.removePrefix("www.") ?: "generic"
            } catch (e: Exception) { "generic" }
        }
    }
}
