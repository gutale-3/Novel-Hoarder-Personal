package com.example.data.ai

import android.content.Context
import com.example.data.local.ChapterEntity
import com.example.data.local.PolishedChapterEntity
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rewrites machine-translated chapters into natural English with Gemini, keeping one output
 * paragraph per input paragraph (so bookmarks, reading position and read-aloud stay aligned)
 * and keeping recurring names spelled the same way across the whole novel.
 *
 * The original text is never changed; the polished copy is stored beside it.
 */
class ChapterPolisher(
    private val context: Context,
    private val repository: NovelRepository,
    private val gemini: GeminiProvider
) {
    data class Name(val name: String, val aliases: List<String>)

    private val prefs = context.getSharedPreferences("chapter_polish", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val inFlight = HashSet<String>()

    // ---------------- settings ----------------

    /** Per novel: show polished text and polish chapters as they are read. */
    fun isEnabled(bookId: String) = prefs.getBoolean("on_$bookId", false)
    fun setEnabled(bookId: String, on: Boolean) = prefs.edit().putBoolean("on_$bookId", on).apply()

    /** Chapters per day, across all novels, so the free Gemini allowance is never exhausted. */
    var dailyLimit: Int
        get() = prefs.getInt("daily_limit", DEFAULT_DAILY_LIMIT)
        set(v) = prefs.edit().putInt("daily_limit", v.coerceIn(10, 1000)).apply()

    fun usedToday(): Int = if (prefs.getString("day", "") == today()) prefs.getInt("count", 0) else 0

    private fun countOne() {
        val d = today()
        val n = if (prefs.getString("day", "") == d) prefs.getInt("count", 0) else 0
        prefs.edit().putString("day", d).putInt("count", n + 1).apply()
    }

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    suspend fun isAvailable() = gemini.isAvailable()

    // ---------------- names ----------------

    fun names(bookId: String): List<Name> = runCatching {
        val arr = JSONArray(prefs.getString("names_$bookId", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val al = o.optJSONArray("aliases") ?: JSONArray()
            Name(o.getString("name"), (0 until al.length()).map { al.getString(it) })
        }
    }.getOrDefault(emptyList())

    fun removeName(bookId: String, name: String) = saveNames(bookId, names(bookId).filterNot { it.name == name })

    private fun saveNames(bookId: String, list: List<Name>) {
        val arr = JSONArray()
        list.take(MAX_NAMES).forEach { n ->
            arr.put(JSONObject().put("name", n.name).put("aliases", JSONArray(n.aliases.take(6))))
        }
        prefs.edit().putString("names_$bookId", arr.toString()).apply()
    }

    /** Adds new names and new spellings of known ones; the first spelling seen stays canonical. */
    internal fun mergeNames(bookId: String, found: List<Name>) {
        val list = names(bookId).toMutableList()
        for (f in found) {
            val name = f.name.trim()
            if (name.length !in 2..40) continue
            val idx = list.indexOfFirst { it.name.equals(name, true) || it.aliases.any { a -> a.equals(name, true) } }
            if (idx < 0) {
                list += Name(name, f.aliases.map { it.trim() }.filter { it.isNotBlank() && !it.equals(name, true) }.distinct())
            } else {
                val cur = list[idx]
                val extra = (f.aliases + name).map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals(cur.name, true) && cur.aliases.none { a -> a.equals(it, true) } }
                if (extra.isNotEmpty()) list[idx] = cur.copy(aliases = (cur.aliases + extra).distinct())
            }
        }
        saveNames(bookId, list)
    }

    // ---------------- polishing ----------------

    /**
     * Polishes [chapter] unless a polished copy exists (or [force]). Returns null on success,
     * otherwise a short reason.
     */
    suspend fun polish(chapter: ChapterEntity, force: Boolean = false): String? {
        if (chapter.content.isBlank()) return "Chapter isn't downloaded yet"
        if (!force && repository.getPolishedChapter(chapter.id)?.let { isCurrent(it, chapter) } == true) return null
        if (!gemini.isAvailable()) return "Add a Gemini key in Settings → AI first"
        synchronized(inFlight) { if (!inFlight.add(chapter.id)) return null }
        try {
            // One chapter at a time keeps well inside Gemini's free per-minute limit.
            return mutex.withLock {
                if (usedToday() >= dailyLimit) return@withLock "Today's polish limit ($dailyLimit chapters) is used up"
                val paragraphs = chapter.content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (paragraphs.isEmpty()) return@withLock "Chapter is empty"
                val out = ArrayList<String>(paragraphs.size)
                for (chunk in chunks(paragraphs)) {
                    val polished = polishChunk(chapter, chunk) ?: return@withLock "Gemini couldn't polish this chapter right now"
                    out += polished
                }
                repository.insertPolishedChapter(
                    PolishedChapterEntity(chapterId = chapter.id, bookId = chapter.bookId, content = out.joinToString("\n\n"))
                )
                countOne()
                null
            }
        } finally {
            synchronized(inFlight) { inFlight.remove(chapter.id) }
        }
    }

    private fun chunks(paragraphs: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        var size = 0
        for (p in paragraphs) {
            if (size + p.length > CHUNK_CHARS && cur.isNotEmpty()) {
                result += cur; cur = mutableListOf(); size = 0
            }
            cur += p; size += p.length
        }
        if (cur.isNotEmpty()) result += cur
        return result
    }

    /** Two tries; a reply with a different paragraph count is rejected so positions stay aligned. */
    private suspend fun polishChunk(chapter: ChapterEntity, paragraphs: List<String>): List<String>? {
        repeat(2) {
            val raw = gemini.generate(buildPrompt(chapter.bookId, paragraphs), jsonMode = true)
            parseReply(raw, paragraphs.size)?.let { (text, names) ->
                mergeNames(chapter.bookId, names)
                return text
            }
        }
        return null
    }

    internal fun buildPrompt(bookId: String, paragraphs: List<String>): String {
        val known = names(bookId)
        val nameLines = if (known.isEmpty()) "(none yet)" else known.joinToString("\n") { n ->
            "- ${n.name}" + if (n.aliases.isNotEmpty()) " (also rendered as: ${n.aliases.joinToString(", ")})" else ""
        }
        val input = JSONArray(paragraphs).toString()
        return """
            You are editing a machine-translated web novel into natural, fluent English.
            Rules:
            - Keep the story exactly the same: every event, line of dialogue, detail and the tone. Do not summarise, skip, add or censor anything.
            - Fix grammar, word order and awkward literal phrasing. Keep cultivation and genre terms consistent.
            - Return exactly ${paragraphs.size} paragraphs, one for each input paragraph, in the same order.
            - Use these spellings for recurring names, and write any listed alternative as its main name:
            $nameLines
            - In "names", list the people, places, sects and techniques in this text, each with the spelling you used and any other spellings found in the input.

            Reply with JSON only: {"paragraphs": ["..."], "names": [{"name": "...", "aliases": ["..."]}]}

            Input paragraphs (JSON array):
            $input
        """.trimIndent()
    }

    internal fun parseReply(raw: String, expected: Int): Pair<List<String>, List<Name>>? {
        if (raw.startsWith("Error")) return null
        val json = runCatching {
            val s = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
            JSONObject(s)
        }.getOrNull() ?: return null
        val arr = json.optJSONArray("paragraphs") ?: return null
        val paragraphs = (0 until arr.length()).map { arr.optString(it).trim() }
        if (paragraphs.size != expected || paragraphs.any { it.isEmpty() }) return null
        val namesArr = json.optJSONArray("names") ?: JSONArray()
        val names = (0 until namesArr.length()).mapNotNull { i ->
            val o = namesArr.optJSONObject(i) ?: return@mapNotNull null
            val al = o.optJSONArray("aliases") ?: JSONArray()
            Name(o.optString("name"), (0 until al.length()).map { al.optString(it) })
        }.filter { it.name.isNotBlank() }
        return paragraphs to names
    }

    /** The text to show or read aloud: the polished copy when the novel has polish on. */
    suspend fun contentFor(chapter: ChapterEntity): String {
        if (!isEnabled(chapter.bookId)) return chapter.content
        return repository.getPolishedChapter(chapter.id)?.takeIf { isCurrent(it, chapter) }?.content ?: chapter.content
    }

    /** A copy made before the chapter was downloaded again belongs to the old text. */
    fun isCurrent(polished: PolishedChapterEntity, chapter: ChapterEntity) = polished.createdAt >= chapter.downloadedAt

    companion object {
        const val DEFAULT_DAILY_LIMIT = 300
        private const val CHUNK_CHARS = 9_000
        private const val MAX_NAMES = 120
    }
}
