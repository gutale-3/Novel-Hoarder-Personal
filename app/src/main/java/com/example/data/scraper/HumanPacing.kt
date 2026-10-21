package com.example.data.scraper

import android.content.Context
import kotlin.random.Random

/**
 * Spaces chapter requests the way a person reading would: uneven gaps instead of a metronome,
 * an occasional longer pause, and a site-specific slowdown after it shows a bot check. Sites
 * such as WTR Lab flag "unusual reading activity" when requests arrive fast and evenly.
 */
object HumanPacing {

    private const val PREFS = "human_pacing"
    /** A slowdown lasts this long after the last bot check, then pacing returns to normal. */
    private const val SLOWDOWN_WINDOW_MS = 6 * 60 * 60 * 1000L
    private const val MAX_FACTOR = 6f

    /**
     * Delay before the next chapter. [baseMs] is the site's (or global) request delay and
     * [index] counts chapters in this session, used for the periodic longer pause.
     */
    fun nextDelayMs(context: Context, sourceKey: String, baseMs: Long, index: Int, random: Random = Random.Default): Long {
        val base = baseMs.coerceAtLeast(800L) * slowdownFactor(context, sourceKey)
        // 0.7x–1.8x, skewed towards the slow side like real reading speed.
        val jitter = 0.7 + random.nextDouble().let { it * it } * 1.1
        var delay = (base * jitter).toLong()
        // Every ~12–25 chapters, a longer "reading" pause.
        if (index > 0 && index % (12 + (sourceKey.hashCode() and 0x7fffffff) % 14) == 0) {
            delay += 20_000L + random.nextLong(40_000L)
        }
        return delay
    }

    fun slowdownFactor(context: Context, sourceKey: String, now: Long = System.currentTimeMillis()): Float {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("at_$sourceKey", 0L)
        if (now - at > SLOWDOWN_WINDOW_MS) return 1f
        return prefs.getFloat("factor_$sourceKey", 1f)
    }

    /** Called whenever [sourceKey] shows a bot check: doubles its pacing for the next hours. */
    fun onChallenge(context: Context, sourceKey: String, now: Long = System.currentTimeMillis()) {
        val next = (slowdownFactor(context, sourceKey, now) * 2f).coerceAtMost(MAX_FACTOR)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("factor_$sourceKey", next)
            .putLong("at_$sourceKey", now)
            .apply()
    }
}
