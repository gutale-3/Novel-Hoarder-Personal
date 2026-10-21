package com.example.data.plugin

import android.content.Context
import android.webkit.WebView
import com.example.util.CloudflareException
import com.example.util.WebViewFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * One-tap "is this site still working?" check. For each enabled plugin it runs the same three
 * steps a real download does — book page, chapter list, first chapter — against a known book,
 * and records which step failed, so a broken source shows up before a 2,000-chapter download.
 */
class SourceHealthChecker(
    private val context: Context,
    private val pluginManager: PluginManager
) {

    enum class Status { OK, BLOCKED, BROKEN, NO_TEST_URL }

    data class Result(
        val pluginId: String,
        val status: Status,
        val stage: String,
        val message: String,
        val testedUrl: String,
        val checkedAt: Long = System.currentTimeMillis()
    )

    private val prefs = context.getSharedPreferences("source_health", Context.MODE_PRIVATE)

    fun lastResults(): Map<String, Result> = prefs.all.mapNotNull { (id, raw) ->
        try {
            val o = JSONObject(raw as String)
            id to Result(
                pluginId = id,
                status = Status.valueOf(o.getString("status")),
                stage = o.optString("stage"),
                message = o.optString("message"),
                testedUrl = o.optString("url"),
                checkedAt = o.optLong("at")
            )
        } catch (_: Exception) { null }
    }.toMap()

    private fun save(result: Result) {
        val o = JSONObject()
            .put("status", result.status.name)
            .put("stage", result.stage)
            .put("message", result.message)
            .put("url", result.testedUrl)
            .put("at", result.checkedAt)
        prefs.edit().putString(result.pluginId, o.toString()).apply()
    }

    /**
     * Picks the book to test with: the plugin's own [PluginConfig.testUrl] if it has one, otherwise
     * a book from the user's library on that site, which is exactly the book that matters most.
     */
    fun pickTestUrl(plugin: PluginConfig, libraryUrls: List<String>): String? {
        plugin.testUrl.takeIf { it.isNotBlank() }?.let { return it }
        return libraryUrls.firstOrNull { pluginManager.matches(plugin, it) }
    }

    suspend fun check(plugin: PluginConfig, libraryUrls: List<String>): Result {
        val url = pickTestUrl(plugin, libraryUrls)
            ?: return Result(
                plugin.id, Status.NO_TEST_URL, "setup",
                "No test book: add a book from this site to your library, or set testUrl in the plugin.", ""
            ).also(::save)

        val engine = PluginExecutionEngine(plugin)
        var webView: WebView? = null
        var stage = "book page"
        val result = try {
            webView = withContext(Dispatchers.Main) { WebViewFactory.create(context) }
            withTimeout(150_000L) {
                val book = engine.scrapeBookInfo(webView, url)
                if (book.title == "Unknown Book") {
                    throw IllegalStateException("titleSelector matched nothing")
                }
                stage = "chapter list"
                val chapters = engine.scrapeChapterList(webView, url)
                if (chapters.isEmpty()) throw IllegalStateException("chapterListSelector found no chapters")
                stage = "chapter text"
                val (_, body) = engine.scrapeChapterContent(webView, chapters.first()) { false }
                Result(plugin.id, Status.OK, "done", "${book.title}: ${chapters.size} chapters, ${body.length} chars in chapter 1", url)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result(plugin.id, Status.BROKEN, stage, "Timed out", url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: CloudflareException) {
            Result(plugin.id, Status.BLOCKED, stage, "Cloudflare check — open the site once in the manual browser to pass it.", url)
        } catch (e: Exception) {
            Result(plugin.id, Status.BROKEN, stage, e.message ?: e::class.simpleName.orEmpty(), url)
        } finally {
            withContext(Dispatchers.Main) { WebViewFactory.destroySafely(webView) }
        }
        save(result)
        return result
    }
}
