package com.example.util

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Shared detection of Cloudflare / anti-bot interstitials for every scraper.
 *
 * Previously only TomatoScraper recognised a challenge page; plugin and generic sources would
 * poll it for 20s, decide the chapter was "too short" and give up without ever offering the
 * manual-solve dialog. Every source now runs [ChallengeWatch.check] inside its polling loop.
 */
object CloudflareDetector {

    private val TITLE_MARKERS = listOf(
        "just a moment",
        "attention required!",
        "please wait...",
        "ddos-guard",
        "security check",
        "one more step"
    )

    private val BODY_MARKERS = listOf(
        "verify you are human",
        "checking your browser",
        "checking if the site connection is secure",
        "needs to review the security of your connection",
        "enable javascript and cookies to continue",
        "ddos protection by cloudflare",
        "performance & security by cloudflare",
        "complete the security check to access"
    )

    /**
     * Returns `{title, body, el}` describing the page. Only the first few KB of body text are
     * read: challenge pages are tiny, and a long chapter mentioning "are you human" must not
     * trip the detector.
     */
    const val PROBE_JS = """
        (() => {
            const t = (document.title || '').toLowerCase();
            const text = (document.body && (document.body.innerText || document.body.textContent) || '');
            const el = !!document.querySelector(
                '#challenge-form, #challenge-running, #challenge-stage, #cf-challenge-running, ' +
                '#cf-please-wait, .cf-browser-verification, #cf-wrapper #cf-error-details, ' +
                'iframe[src*="challenges.cloudflare.com"]'
            );
            return { title: t, body: text.slice(0, 4000).toLowerCase(), len: text.length, el: el };
        })()
    """

    /** Pure decision so it can be unit tested without a WebView. */
    fun looksLikeChallenge(title: String, body: String, bodyLength: Int, hasChallengeElement: Boolean): Boolean {
        val t = title.lowercase()
        // startsWith, not contains: a chapter can legitimately be titled "...Just a Moment".
        if (TITLE_MARKERS.any { t.trim().startsWith(it) } && bodyLength < 6000) return true
        if (hasChallengeElement) return true
        // A real chapter is long; an interstitial is a couple of sentences.
        if (bodyLength < 3000) {
            val b = body.lowercase()
            if (BODY_MARKERS.any { b.contains(it) }) return true
        }
        return false
    }

    suspend fun isChallenge(webView: WebView): Boolean {
        val raw = withTimeoutOrNull(4000) {
            suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(PROBE_JS) { if (cont.isActive) cont.resume(it) }
            }
        }
        val obj = JsResultParser.toJsonObject(raw) ?: return false
        return looksLikeChallenge(
            title = obj.optString("title"),
            body = obj.optString("body"),
            bodyLength = obj.optInt("len", 0),
            hasChallengeElement = obj.optBoolean("el", false)
        )
    }

    /**
     * Cloudflare's non-interactive challenge often clears itself within a few seconds, so a
     * single sighting is not enough to interrupt the user. Create one per page load and call
     * [check] on every poll; it throws [CloudflareException] once the challenge has persisted
     * for [graceMs].
     */
    class ChallengeWatch(private val sourceName: String, private val graceMs: Long = 7000L) {
        private var firstSeenAt = 0L

        suspend fun check(webView: WebView) {
            if (!isChallenge(webView)) {
                firstSeenAt = 0L
                return
            }
            val now = System.currentTimeMillis()
            if (firstSeenAt == 0L) firstSeenAt = now
            if (now - firstSeenAt >= graceMs) {
                throw CloudflareException("$sourceName: Cloudflare/anti-bot check is blocking the page")
            }
        }
    }
}
