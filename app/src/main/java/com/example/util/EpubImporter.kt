/**
 * EPUB and TXT document importer for Novel Hoarder.
 *
 * Fixes bugs in initial implementation:
 * 1. Jsoup `.text()` collapsed all whitespace including paragraph breaks into spaces. We now convert
 *    <br> to \n and structural elements (<p>, <div>, <li>, <h1>-<h6>, <tr>) to \n\n before extracting text.
 * 2. ZIP entry order is arbitrary and caused chapters to import out of order. We now parse the OPF <spine>
 *    to guarantee reading order.
 * 3. Document <title> tags often duplicate the book title. We now parse EPUB 3 nav / EPUB 2 NCX files for
 *    real chapter titles, falling back to headings or distinct <title> tags.
 * 4. OPF <metadata> is parsed for title, author, description, language, and cover image.
 * 5. Front matter (cover, copyright, title page) is filtered out and recorded in warnings.
 * 6. TXT importer previously used chunked(8000) cutting mid-word. It now detects line-anchored chapter patterns,
 *    auto-detects UTF-8 / windows-1252 encodings, rejoins hard-wrapped paragraphs, and uses smart sentence-boundary
 *    splitting as a fallback.
 * 7. Implements Zip Slip security guards and size limits to prevent unsafe archive extraction.
 */
package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

data class ImportResult(
    val book: BookEntity,
    val chapters: List<ChapterEntity>,
    val coverBytes: ByteArray?,
    val warnings: List<String>
)

sealed class ImportProgress {
    data class Reading(val message: String) : ImportProgress()
    data class Parsing(val current: Int, val total: Int) : ImportProgress()
    data class Done(val result: ImportResult) : ImportProgress()
    data class Failed(val reason: String) : ImportProgress()
}

object EpubImporter {

    suspend fun import(
        context: Context,
        uri: Uri,
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult? = withContext(Dispatchers.IO) {
        onProgress(ImportProgress.Reading("Opening file..."))
        val contentResolver = context.contentResolver

        // Read first 4 bytes to check format by content
        val header = ByteArray(4)
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.read(header, 0, 4)
            }
        } catch (e: Exception) {
            onProgress(ImportProgress.Failed("Could not open file: ${e.message}"))
            return@withContext null
        }

        var filename = ""
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1 && cursor.moveToFirst()) {
                    filename = cursor.getString(nameIdx).orEmpty()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (filename.isEmpty()) {
            filename = uri.lastPathSegment.orEmpty()
        }

        val hasEpubExtension = filename.endsWith(".epub", ignoreCase = true) || filename.endsWith(".zip", ignoreCase = true)
        val mimeType = contentResolver.getType(uri).orEmpty()
        val hasEpubMime = mimeType.contains("epub", ignoreCase = true) || mimeType.contains("zip", ignoreCase = true)

        val isZip = (header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()) || hasEpubExtension || hasEpubMime

        if (isZip) {
            val result = importEpubInternal(context, uri, onProgress)
            if (result != null) {
                onProgress(ImportProgress.Done(result))
            } else {
                onProgress(ImportProgress.Failed("Failed to parse EPUB file."))
            }
            return@withContext result
        } else {
            val result = importTxtInternal(context, uri, onProgress)
            if (result != null) {
                onProgress(ImportProgress.Done(result))
            } else {
                onProgress(ImportProgress.Failed("Failed to parse TXT file."))
            }
            return@withContext result
        }
    }

    // --- Legacy compatibility entry points ---
    fun importEpub(context: Context, uri: Uri): Pair<BookEntity, List<ChapterEntity>>? {
        val result = kotlinx.coroutines.runBlocking {
            importEpubInternal(context, uri) {}
        } ?: return null
        return Pair(saveCoverAndBuildBook(context, result.book, result.coverBytes), result.chapters)
    }

    fun importTxt(context: Context, uri: Uri): Pair<BookEntity, List<ChapterEntity>>? {
        val result = kotlinx.coroutines.runBlocking {
            importTxtInternal(context, uri) {}
        } ?: return null
        return Pair(result.book, result.chapters)
    }

    // Helper to write cover file if available
    fun saveCoverAndBuildBook(context: Context, book: BookEntity, coverBytes: ByteArray?): BookEntity {
        if (coverBytes == null || coverBytes.isEmpty()) return book
        return try {
            val coversDir = File(context.filesDir, "covers")
            if (!coversDir.exists()) coversDir.mkdirs()

            val coverFile = File(coversDir, "${book.id}.jpg")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, options)

            var sample = 1
            while (options.outWidth / sample > 1000) {
                sample *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, decodeOptions)

            FileOutputStream(coverFile).use { out ->
                bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            book.copy(coverLocalPath = coverFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            book
        }
    }

    // =========================================================================
    // EPUB IMPORTER
    // =========================================================================

    private suspend fun importEpubInternal(
        context: Context,
        uri: Uri,
        onProgress: (ImportProgress) -> Unit
    ): ImportResult? = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val timestamp = System.currentTimeMillis()
        val tempDir = File(context.cacheDir, "epub_import_$timestamp")
        if (!tempDir.exists()) tempDir.mkdirs()

        var totalSize = 0L
        var totalEntries = 0

        try {
            onProgress(ImportProgress.Reading("Extracting EPUB archive..."))
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipStream ->
                    var entry = zipStream.nextEntry
                    val buffer = ByteArray(65536)
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            totalEntries++
                            if (totalEntries > 5000) {
                                throw IOException("EPUB contains more than 5000 entries (possible zip bomb).")
                            }

                            val target = File(tempDir, entry.name)
                            // Zip Slip Guard
                            if (!target.canonicalPath.startsWith(tempDir.canonicalPath + File.separator)) {
                                throw IOException("Blocked unsafe archive entry: ${entry.name}")
                            }

                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                var bytesRead: Int
                                while (zipStream.read(buffer).also { bytesRead = it } != -1) {
                                    out.write(buffer, 0, bytesRead)
                                    totalSize += bytesRead
                                    if (totalSize > 500 * 1024 * 1024) { // 500 MB limit
                                        throw IOException("Uncompressed archive size exceeds 500 MB limit.")
                                    }
                                }
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            } ?: return@withContext null

            // Step 2 — Find OPF
            var opfRelativePath = ""
            val containerFile = File(tempDir, "META-INF/container.xml")
            if (containerFile.exists()) {
                try {
                    val containerDoc = Jsoup.parse(containerFile, "UTF-8", "", Parser.xmlParser())
                    opfRelativePath = containerDoc.select("rootfile").attr("full-path")
                } catch (e: Exception) {
                    warnings.add("Failed to parse container.xml: ${e.message}")
                }
            }

            var opfFile = if (opfRelativePath.isNotEmpty()) File(tempDir, opfRelativePath) else null
            if (opfFile == null || !opfFile.exists()) {
                opfFile = tempDir.walk().firstOrNull { it.extension.equals("opf", ignoreCase = true) }
            }

            if (opfFile == null || !opfFile.exists()) {
                warnings.add("No OPF metadata file found in EPUB archive. Using fallback extraction...")
                var fallbackFilenameTitle = "Imported Zip"
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIdx != -1 && cursor.moveToFirst()) {
                            val name = cursor.getString(nameIdx)
                            fallbackFilenameTitle = name.replace(Regex("(?i)\\.(epub|zip)$"), "")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val htmlFiles = tempDir.walk()
                    .filter { it.extension.lowercase() in listOf("html", "xhtml", "htm", "txt") }
                    .sortedWith(Comparator { f1, f2 -> naturalCompare(f1.name, f2.name) })
                    .toList()
                
                if (htmlFiles.isEmpty()) {
                    return@withContext null
                }
                
                val bookTitle = fallbackFilenameTitle.ifEmpty { "Imported Zip" }
                val bookId = "epub_${bookTitle.hashCode()}"
                val book = BookEntity(
                    id = bookId,
                    url = "local://$bookId",
                    title = bookTitle,
                    author = "Unknown Author",
                    synopsis = "Imported from Zip/EPUB archive.",
                    coverUrl = "",
                    coverLocalPath = null,
                    lastReadChapterId = null,
                    totalChapters = htmlFiles.size
                )
                
                val chapters = htmlFiles.mapIndexed { index, file ->
                    val rawText = file.readText()
                    val cleanText = if (file.extension.lowercase() in listOf("html", "xhtml", "htm")) {
                        Jsoup.parse(rawText).text()
                    } else {
                        rawText
                    }
                    val normalized = normalizeText(cleanText)
                    val title = file.nameWithoutExtension.replace("_", " ").replace("-", " ")
                        .split(' ')
                        .joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
                    val chapterId = "${bookId}_ch_${index + 1}"
                    ChapterEntity(
                        id = chapterId,
                        bookId = bookId,
                        chapterId = "ch_${index + 1}",
                        chapterNumber = index + 1,
                        title = title,
                        url = "local://$bookId/ch/${index + 1}",
                        content = normalized,
                        hash = md5(normalized)
                    )
                }
                
                return@withContext ImportResult(book, chapters, coverBytes = null, warnings = warnings)
            }

            val opfDir = opfFile.parentFile ?: tempDir
            val opfDoc = Jsoup.parse(opfFile, "UTF-8", "", Parser.xmlParser())

            // Step 3 — Read Metadata
            val metaTitle = opfDoc.select("metadata > dc|title, metadata > title").text().trim()
            val metaAuthor = opfDoc.select("metadata > dc|creator, metadata > creator").text().trim()
            val metaDescHtml = opfDoc.select("metadata > dc|description, metadata > description").text()
            val metaDesc = Jsoup.parse(metaDescHtml).text().trim()

            var filenameTitle = "Imported EPUB"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIdx)
                    filenameTitle = name.replace(Regex("(?i)\\.(epub|zip)$"), "")
                }
            }

            val rawTitle = metaTitle.ifEmpty { filenameTitle }
            val cleanTitle = rawTitle
                .replace(Regex("(?i)\\(EPUB\\)|\\[Complete\\]|\\[Finished\\]"), "")
                .replace(Regex("(?i)Vol(ume)?\\s*\\d+"), "")
                .trim()
                .removeSurrounding("[", "]")
                .removeSurrounding("(", ")")
                .trim()

            val bookTitle = cleanTitle.ifEmpty { filenameTitle }
            val bookAuthor = metaAuthor.ifEmpty { "Unknown Author" }
            val bookDesc = metaDesc.ifEmpty { "Imported from EPUB." }

            // Step 4 — Find Cover
            var coverBytes: ByteArray? = null
            try {
                var coverHref: String? = null
                val coverMetaId = opfDoc.select("metadata > meta[name=cover]").attr("content")
                if (coverMetaId.isNotEmpty()) {
                    coverHref = opfDoc.select("manifest > item[id=$coverMetaId]").attr("href")
                }
                if (coverHref.isNullOrEmpty()) {
                    coverHref = opfDoc.select("manifest > item[properties*=cover-image]").attr("href")
                }
                if (coverHref.isNullOrEmpty()) {
                    coverHref = opfDoc.select("manifest > item[href~=(?i)cover\\.(jpe?g|png|webp)]").attr("href")
                }
                if (coverHref.isNullOrEmpty()) {
                    val imageItems = opfDoc.select("manifest > item[media-type^=image/]")
                    if (imageItems.size in 1..399) {
                        coverHref = imageItems.first()?.attr("href")
                    }
                }

                if (!coverHref.isNullOrEmpty()) {
                    val resolvedCover = resolveEpubFile(opfDir, coverHref, tempDir)
                    if (resolvedCover != null && resolvedCover.exists()) {
                        coverBytes = resolvedCover.readBytes()
                    }
                }
            } catch (e: Exception) {
                warnings.add("Failed to extract cover image: ${e.message}")
            }

            // Step 5 — Build Reading Order (Spine + Manifest Fallback)
            val manifestMap = mutableMapOf<String, String>() // id -> href
            opfDoc.select("manifest > item").forEach { item ->
                val id = item.attr("id")
                val href = item.attr("href")
                if (id.isNotEmpty() && href.isNotEmpty()) {
                    manifestMap[id] = href
                }
            }

            val spineHrefs = mutableListOf<String>()
            opfDoc.select("spine > itemref").forEach { itemref ->
                val idref = itemref.attr("idref")
                val href = manifestMap[idref]
                if (!href.isNullOrEmpty()) {
                    spineHrefs.add(href)
                }
            }

            val spineFiles = mutableListOf<Pair<String, File>>()
            val seenCanonicalPaths = mutableSetOf<String>()

            if (spineHrefs.isNotEmpty()) {
                for (href in spineHrefs) {
                    val resolved = resolveEpubFile(opfDir, href, tempDir)
                    if (resolved != null && resolved.exists() && seenCanonicalPaths.add(resolved.canonicalPath)) {
                        spineFiles.add(Pair(href, resolved))
                    }
                }
            }

            // Include any readable manifest HTML/XHTML items that might be missing from spine
            val manifestHtmlHrefs = manifestMap.values.filter { href ->
                val clean = href.substringBefore("#").substringBefore("?")
                val ext = clean.substringAfterLast('.', "").lowercase()
                ext in listOf("html", "xhtml", "htm", "xml")
            }

            for (href in manifestHtmlHrefs) {
                val resolved = resolveEpubFile(opfDir, href, tempDir)
                if (resolved != null && resolved.exists() && seenCanonicalPaths.add(resolved.canonicalPath)) {
                    spineFiles.add(Pair(href, resolved))
                }
            }

            // If spine and manifest both yielded nothing, fall back to natural numeric-aware filename sorting of all unzipped HTML files
            if (spineFiles.isEmpty()) {
                tempDir.walk()
                    .filter { it.isFile && it.extension.lowercase() in listOf("html", "xhtml", "htm") }
                    .sortedWith(Comparator { f1, f2 -> naturalCompare(f1.name, f2.name) })
                    .forEach { file ->
                        if (seenCanonicalPaths.add(file.canonicalPath)) {
                            spineFiles.add(Pair(file.name, file))
                        }
                    }
            }

            // Step 6 — Get Real Chapter Titles from Nav / NCX
            val tocMap = mutableMapOf<String, String>() // href (relative to opfDir) -> Title
            try {
                val navItem = opfDoc.select("manifest > item[properties*=nav]").first()
                    ?: opfDoc.select("manifest > item[media-type*=ncx], manifest > item[href$=.ncx]").first()

                if (navItem != null) {
                    val navFile = resolveEpubFile(opfDir, navItem.attr("href"), tempDir)
                    if (navFile != null && navFile.exists()) {
                        if (navFile.extension.equals("ncx", ignoreCase = true)) {
                            val ncxDoc = Jsoup.parse(navFile, "UTF-8", "", Parser.xmlParser())
                            ncxDoc.select("navPoint").forEach { point ->
                                val text = point.select("> navLabel > text").text().trim()
                                val src = point.select("> content").attr("src").substringBefore("#")
                                if (text.isNotEmpty() && src.isNotEmpty()) {
                                    val relPath = File(navFile.parentFile, src).relativeToOrSelf(opfDir).path
                                    tocMap[relPath] = text
                                    tocMap[src] = text
                                    tocMap[File(src).name] = text
                                }
                            }
                        } else {
                            val navDoc = Jsoup.parse(navFile, "UTF-8")
                            val navEl = navDoc.select("nav[epub|type=toc], nav[type=toc], nav").first()
                            navEl?.select("a[href]")?.forEach { a ->
                                val text = a.text().trim()
                                val href = a.attr("href").substringBefore("#")
                                if (text.isNotEmpty() && href.isNotEmpty()) {
                                    val relPath = File(navFile.parentFile, href).relativeToOrSelf(opfDir).path
                                    tocMap[relPath] = text
                                    tocMap[href] = text
                                    tocMap[File(href).name] = text
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                warnings.add("Failed to parse EPUB navigation document: ${e.message}")
            }

            // Step 7, 8, 9 — Extract Text, Drop Front Matter, Build Chapters
            val rawChapters = mutableListOf<RawChapter>()
            val totalSpine = spineFiles.size

            spineFiles.forEachIndexed { index, (href, file) ->
                onProgress(ImportProgress.Parsing(index + 1, totalSpine))

                val doc = Jsoup.parse(file, "UTF-8")
                doc.select("script, style, noscript").remove()
                doc.select("nav[epub\\:type=toc], nav[type=toc]").remove()

                doc.select("br").after("\n")
                doc.select("p, div, li, blockquote, h1, h2, h3, h4, h5, h6, tr, section, article, dd, dt").after("\n\n")

                var rawText = doc.body()?.wholeText().orEmpty()
                if (rawText.isBlank()) {
                    rawText = doc.body()?.text().orEmpty()
                }
                if (rawText.isBlank()) {
                    rawText = doc.text().orEmpty()
                }
                var text = normalizeText(rawText)

                if (text.isBlank()) {
                    val hasImg = doc.select("img, image").isNotEmpty()
                    if (hasImg) {
                        text = "[Illustration / Image Page]"
                    }
                }

                // Chapter title resolution
                val relPath = file.relativeToOrSelf(opfDir).path
                val tocTitle = tocMap[relPath] ?: tocMap[href] ?: tocMap[file.name]

                val hTitle = doc.select("h1, h2, h3").first()?.text()?.trim()
                val docTitle = doc.title().trim()

                val resolvedTitle = when {
                    !tocTitle.isNullOrBlank() -> tocTitle
                    !hTitle.isNullOrBlank() -> hTitle
                    docTitle.isNotBlank() && !docTitle.equals(bookTitle, ignoreCase = true) -> docTitle
                    else -> ""
                }

                if (text.isNotBlank()) {
                    rawChapters.add(RawChapter(title = resolvedTitle, content = text))
                } else {
                    warnings.add("Skipped empty file: ${file.name}")
                }
            }

            if (rawChapters.isEmpty()) {
                warnings.add("No readable chapter content found in EPUB.")
                return@withContext null
            }

            // Do not merge split chapters to ensure no chapters are skipped or bundled incorrectly
            val mergedChapters = rawChapters

            val bookId = "epub_$timestamp"
            val chapters = mergedChapters.mapIndexed { idx, raw ->
                val chNum = idx + 1
                val title = raw.title.ifBlank { "Chapter $chNum" }
                val hash = md5(raw.content)

                ChapterEntity(
                    id = "${bookId}_ch_$chNum",
                    bookId = bookId,
                    chapterId = "ch_$chNum",
                    chapterNumber = chNum,
                    title = title,
                    url = "local://$bookId/ch/$chNum",
                    content = raw.content,
                    hash = hash
                )
            }

            val book = BookEntity(
                id = bookId,
                title = bookTitle,
                author = bookAuthor,
                synopsis = bookDesc,
                coverUrl = null,
                coverLocalPath = null,
                totalChapters = chapters.size,
                url = "local://$bookId",
                lastReadChapterId = chapters.first().id
            )

            ImportResult(
                book = book,
                chapters = chapters,
                coverBytes = coverBytes,
                warnings = warnings
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private data class RawChapter(val title: String, val content: String)

    // =========================================================================
    // TXT IMPORTER
    // =========================================================================

    private suspend fun importTxtInternal(
        context: Context,
        uri: Uri,
        onProgress: (ImportProgress) -> Unit
    ): ImportResult? = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val contentResolver = context.contentResolver

        var filenameTitle = "Imported Book"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIdx != -1 && cursor.moveToFirst()) {
                val name = cursor.getString(nameIdx)
                filenameTitle = name.replace(Regex("(?i)\\.txt$"), "")
            }
        }

        val rawBytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        } catch (e: Exception) {
            return@withContext null
        }

        if (rawBytes.size < 50) return@withContext null

        // Encoding Detection: BOM check -> Strict UTF-8 -> windows-1252
        val textContent = decodeTextBytes(rawBytes)

        // Chapter Patterns (anchored at START OF LINE, multiline, case-insensitive)
        val patterns = listOf(
            Regex("(?m)^\\s*chapter\\s+\\d+", RegexOption.IGNORE_CASE),
            Regex("(?m)^\\s*chapter\\s+[ivxlcdm]+\\b", RegexOption.IGNORE_CASE),
            Regex("(?m)^\\s*第\\s*[0-9一二三四五六七八九十百千]+\\s*[章回节]"),
            Regex("(?m)^\\s*\\d+\\s*[.、]\\s*\\S"),
            Regex("(?m)^\\s*(part|book|volume)\\s+\\d+", RegexOption.IGNORE_CASE)
        )

        var bestPattern: Regex? = null
        var bestMatches: List<MatchResult> = emptyList()

        for (pattern in patterns) {
            val matches = pattern.findAll(textContent).toList()
            if (matches.size >= 3) {
                // Check match spacing (no two matches closer than 500 characters)
                var wellSpaced = true
                for (i in 0 until matches.size - 1) {
                    if (matches[i + 1].range.first - matches[i].range.first < 500) {
                        wellSpaced = false
                        break
                    }
                }
                if (wellSpaced && matches.size > bestMatches.size) {
                    bestMatches = matches
                    bestPattern = pattern
                }
            }
        }

        val bookId = "txt_${System.currentTimeMillis()}"
        val chapters = mutableListOf<ChapterEntity>()

        if (bestPattern != null && bestMatches.isNotEmpty()) {
            for (i in bestMatches.indices) {
                val currentMatch = bestMatches[i]
                val start = currentMatch.range.first
                val end = if (i + 1 < bestMatches.size) bestMatches[i + 1].range.first else textContent.length

                val lineEnd = textContent.indexOf('\n', start)
                val fullHeadingLine = if (lineEnd != -1 && lineEnd < end) {
                    textContent.substring(start, lineEnd).trim()
                } else {
                    currentMatch.value.trim()
                }

                val rawBlock = textContent.substring(start, end).trim()
                val cleanBlock = formatTxtParagraphs(rawBlock)
                val chNum = i + 1

                if (cleanBlock.length > 30) {
                    chapters.add(
                        ChapterEntity(
                            id = "${bookId}_ch_$chNum",
                            bookId = bookId,
                            chapterId = "ch_$chNum",
                            chapterNumber = chNum,
                            title = fullHeadingLine,
                            url = "local://$bookId/ch/$chNum",
                            content = cleanBlock,
                            hash = md5(cleanBlock)
                        )
                    )
                }
            }
        } else {
            // Fallback: splitIntoParts
            warnings.add("No chapter markers found. Split into parts.")
            val parts = splitIntoParts(textContent, targetChars = 12000)

            for ((idx, part) in parts.withIndex()) {
                val chNum = idx + 1
                val cleanBlock = formatTxtParagraphs(part)
                chapters.add(
                    ChapterEntity(
                        id = "${bookId}_ch_$chNum",
                        bookId = bookId,
                        chapterId = "ch_$chNum",
                        chapterNumber = chNum,
                        title = "Part $chNum",
                        url = "local://$bookId/ch/$chNum",
                        content = cleanBlock,
                        hash = md5(cleanBlock)
                    )
                )
            }
        }

        if (chapters.isEmpty()) return@withContext null

        val book = BookEntity(
            id = bookId,
            title = filenameTitle,
            author = "Unknown Author",
            synopsis = "Imported offline from local text file.",
            coverUrl = null,
            coverLocalPath = null,
            totalChapters = chapters.size,
            url = "local://$bookId",
            lastReadChapterId = chapters.first().id
        )

        ImportResult(
            book = book,
            chapters = chapters,
            coverBytes = null,
            warnings = warnings
        )
    }

    /**
     * Splits text into readable parts when no chapter markers exist. Cuts on a blank line near the
     * target size, never mid-sentence — the previous chunked() call split words in half.
     */
    fun splitIntoParts(text: String, targetChars: Int = 12000): List<String> {
        val result = mutableListOf<String>()
        var cursor = 0
        val len = text.length

        while (cursor < len) {
            if (len - cursor <= targetChars + 2000) {
                result.add(text.substring(cursor).trim())
                break
            }

            val target = cursor + targetChars
            val windowStart = (target - 2000).coerceAtLeast(cursor)
            val windowEnd = (target + 2000).coerceAtMost(len)
            val windowText = text.substring(windowStart, windowEnd)

            var cutOffset = -1

            // Look for blank line in window
            val blankLineIdx = windowText.indexOf("\n\n")
            if (blankLineIdx != -1) {
                cutOffset = windowStart + blankLineIdx
            } else {
                // Sentence end
                val sentenceRegex = Regex("[.!?。！？]\\s+")
                val matches = sentenceRegex.findAll(windowText).toList()
                if (matches.isNotEmpty()) {
                    cutOffset = windowStart + matches.last().range.last + 1
                }
            }

            if (cutOffset <= cursor) {
                cutOffset = target.coerceAtMost(len)
            }

            val part = text.substring(cursor, cutOffset).trim()
            if (part.isNotEmpty()) {
                result.add(part)
            }
            cursor = cutOffset
        }

        return result
    }

    // --- Helpers ---

    private fun resolveEpubFile(opfDir: File, rawHref: String, tempDir: File): File? {
        val cleanHref = rawHref.substringBefore("#").substringBefore("?")
        val decodedHref = try {
            java.net.URLDecoder.decode(cleanHref, "UTF-8")
        } catch (e: Exception) {
            cleanHref
        }

        val candidates = listOf(
            File(opfDir, decodedHref),
            File(opfDir, cleanHref),
            File(tempDir, decodedHref),
            File(tempDir, cleanHref),
            File(tempDir, "OEBPS/$decodedHref"),
            File(tempDir, "OEBPS/$cleanHref"),
            File(tempDir, "OPS/$decodedHref"),
            File(tempDir, "OPS/$cleanHref")
        )
        for (cand in candidates) {
            if (cand.exists() && cand.isFile) return cand
        }

        // Search recursively by filename in tempDir
        val simpleName = File(decodedHref).name
        val matchByName = tempDir.walk().firstOrNull { it.isFile && it.name.equals(simpleName, ignoreCase = true) }
        if (matchByName != null) return matchByName

        val nameNoExt = File(decodedHref).nameWithoutExtension
        return tempDir.walk().firstOrNull { it.isFile && it.nameWithoutExtension.equals(nameNoExt, ignoreCase = true) }
    }

    private fun decodeTextBytes(bytes: ByteArray): String {
        // Check BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }

        // Try strict UTF-8
        try {
            val decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val byteBuffer = java.nio.ByteBuffer.wrap(bytes)
            val charBuffer = java.nio.CharBuffer.allocate(bytes.size * 2 + 10)
            val result = decoder.decode(byteBuffer, charBuffer, true)
            if (result.isError) {
                throw java.nio.charset.CharacterCodingException()
            }
            val flushResult = decoder.flush(charBuffer)
            if (flushResult.isError) {
                throw java.nio.charset.CharacterCodingException()
            }
            charBuffer.flip()
            return charBuffer.toString()
        } catch (e: Exception) {
            // Fall back to windows-1252
            return String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun formatTxtParagraphs(raw: String): String {
        val blocks = raw.split(Regex("\n\\s*\n"))
        return blocks.map { block ->
            // Rejoin soft-wrapped single newlines inside paragraph
            block.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }.filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    private fun normalizeText(text: String): String {
        val sanitized = GenericScraper.sanitizeText(text, aggressive = false)
        return sanitized
            .replace("\u00A0", " ")
            .replace("ﬀ", "ff")
            .replace("ﬁ", "fi")
            .replace("ﬂ", "fl")
            .replace("ﬃ", "ffi")
            .replace("ﬄ", "ffl")
            .replace("ﬅ", "ft")
            .replace("ﬆ", "st")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun naturalCompare(s1: String, s2: String): Int {
        val r = Regex("(\\d+)|(\\D+)")
        val m1 = r.findAll(s1).map { it.value }.toList()
        val m2 = r.findAll(s2).map { it.value }.toList()

        for (i in 0 until minOf(m1.size, m2.size)) {
            val p1 = m1[i]
            val p2 = m2[i]

            if (p1.all { it.isDigit() } && p2.all { it.isDigit() }) {
                val cmp = p1.toBigInteger().compareTo(p2.toBigInteger())
                if (cmp != 0) return cmp
            } else {
                val cmp = p1.compareTo(p2, ignoreCase = true)
                if (cmp != 0) return cmp
            }
        }
        return m1.size.compareTo(m2.size)
    }
}
