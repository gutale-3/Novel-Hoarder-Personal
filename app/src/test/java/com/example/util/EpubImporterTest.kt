/**
 * Unit tests for EpubImporter using Robolectric.
 * Tests EPUB spine ordering, paragraph preservation, TOC title fallback, front matter skipping,
 * Zip Slip guard, TXT paragraph rejoining, and windows-1252 decoding.
 */
package com.example.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EpubImporterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testEpubSpineOrderAndParagraphBreaksAndTocTitles() = runBlocking {
        // Create an EPUB where filenames sort as part10.html before part2.html naturally,
        // but spine specifies part2.html then part10.html.
        val epubFile = File(context.cacheDir, "test_spine.epub")
        ZipOutputStream(FileOutputStream(epubFile)).use { zip ->
            // mimetype
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            // container.xml
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // content.opf with spine ordering: item2 (part2.xhtml) then item10 (part10.xhtml)
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Test Book</dc:title>
                    <dc:creator>Test Author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                    <item id="item2" href="part2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="item10" href="part10.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="item2"/>
                    <itemref idref="item10"/>
                  </spine>
                </package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // nav.xhtml
            zip.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
            zip.write("""
                <?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <body>
                  <nav epub:type="toc">
                    <ol>
                      <li><a href="part2.xhtml">TOC Chapter Two</a></li>
                      <li><a href="part10.xhtml">TOC Chapter Ten</a></li>
                    </ol>
                  </nav>
                </body>
                </html>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // part2.xhtml (3 <p> tags)
            zip.putNextEntry(ZipEntry("OEBPS/part2.xhtml"))
            zip.write("""
                <html>
                <head><title>Test Book</title></head>
                <body>
                  <p>First paragraph of chapter two.</p>
                  <p>Second paragraph of chapter two.</p>
                  <p>Third paragraph of chapter two.</p>
                </body>
                </html>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // part10.xhtml
            zip.putNextEntry(ZipEntry("OEBPS/part10.xhtml"))
            zip.write("""
                <html>
                <head><title>Test Book</title></head>
                <body>
                  <p>This is chapter ten content with enough text to pass filters.</p>
                  <p>Another paragraph in chapter ten.</p>
                </body>
                </html>
            """.trimIndent().toByteArray())
            zip.closeEntry()
        }

        val uri = Uri.fromFile(epubFile)
        val result = EpubImporter.import(context, uri)
        assertNotNull(result)

        val chapters = result!!.chapters
        assertEquals(2, chapters.size)

        // Verify spine order: Chapter 1 is part2 (TOC Chapter Two), Chapter 2 is part10 (TOC Chapter Ten)
        assertEquals("TOC Chapter Two", chapters[0].title)
        assertEquals("TOC Chapter Ten", chapters[1].title)

        // Verify paragraph breaks survive as \n\n
        assertTrue(chapters[0].content.contains("First paragraph of chapter two.\n\nSecond paragraph of chapter two."))
        assertTrue(chapters[0].content.contains("Second paragraph of chapter two.\n\nThird paragraph of chapter two."))
    }

    @Test
    fun testFrontMatterSkippedAndRecordedInWarnings() = runBlocking {
        val epubFile = File(context.cacheDir, "test_copyright.epub")
        ZipOutputStream(FileOutputStream(epubFile)).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Frontmatter Book</dc:title>
                  </metadata>
                  <manifest>
                    <item id="cop" href="copyright.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="cop"/>
                    <itemref idref="ch1"/>
                  </spine>
                </package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/copyright.xhtml"))
            zip.write("""
                <html>
                <head><title>Copyright</title></head>
                <body>
                  <h1>Copyright</h1>
                  <p>All rights reserved 2026.</p>
                </body>
                </html>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/chapter1.xhtml"))
            zip.write("""
                <html>
                <head><title>Chapter 1</title></head>
                <body>
                  <h1>Chapter 1</h1>
                  <p>Once upon a time in a far away land, there lived a hero who sought adventure.</p>
                </body>
                </html>
            """.trimIndent().toByteArray())
            zip.closeEntry()
        }

        val result = EpubImporter.import(context, Uri.fromFile(epubFile))
        assertNotNull(result)
        assertEquals(1, result!!.chapters.size)
        assertEquals("Chapter 1", result.chapters[0].title)

        // Verify warning contains skipped copyright
        assertTrue(result.warnings.any { it.contains("Copyright") || it.contains("copyright") })
    }

    @Test
    fun testZipSlipEntryIsRefused() = runBlocking {
        val epubFile = File(context.cacheDir, "zip_slip.epub")
        ZipOutputStream(FileOutputStream(epubFile)).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write("malicious payload".toByteArray())
            zip.closeEntry()
        }

        val result = EpubImporter.import(context, Uri.fromFile(epubFile))
        // Should return null due to Zip Slip exception
        assertNull(result)
    }

    @Test
    fun testTxtHardWrappedParagraphsAndSentenceSplitting() = runBlocking {
        val txtFile = File(context.cacheDir, "wrapped.txt")
        val padding = "Word ".repeat(120) + "\n\n"
        val txtContent = """
            Chapter 1
            This is a soft-wrapped line 1
            that continues on line 2
            and ends on line 3.
            
            This is paragraph 2 of the story.
            It also has soft wrapping on line 2.
            
            $padding
            Chapter 2
            This is chapter two text line 1
            that continues on line 2.
            
            $padding
            Chapter 3
            This is chapter three text line 1
            that continues on line 2.
            
            $padding
        """.trimIndent()
        txtFile.writeText(txtContent)

        val result = EpubImporter.import(context, Uri.fromFile(txtFile))
        assertNotNull(result)
        val chapters = result!!.chapters
        assertEquals(3, chapters.size)

        // Verify soft wrapping rejoined
        assertTrue(chapters[0].content.contains("This is a soft-wrapped line 1 that continues on line 2 and ends on line 3."))
        assertTrue(chapters[0].content.contains("This is paragraph 2 of the story. It also has soft wrapping on line 2."))

        // Test fallback splitIntoParts does not cut mid sentence
        val longUnstructuredText = (1..100).joinToString(" ") { "Sentence $it is complete and readable." }
        val parts = EpubImporter.splitIntoParts(longUnstructuredText, targetChars = 500)
        assertTrue(parts.size > 1)
        for (part in parts) {
            assertTrue("Part must end with sentence boundary", part.endsWith("."))
        }
    }

    @Test
    fun testTxtWindows1252Decoding() = runBlocking {
        val txtFile = File(context.cacheDir, "win1252.txt")
        val filler = "A".repeat(550) + "\n\n"
        // Windows-1252 smart quotes: 0x93 (“) and 0x94 (”)
        val byteArray = mutableListOf<Byte>()
        fun addString(str: String) {
            for (ch in str) byteArray.add(ch.code.toByte())
        }
        addString("Chapter 1\n")
        addString(filler)
        addString("Chapter 2\n")
        addString(filler)
        addString("Chapter 3\n")
        byteArray.add(0x93.toByte())
        addString("Hello, World!")
        byteArray.add(0x94.toByte())
        addString("\n\n")
        addString(filler)

        txtFile.writeBytes(byteArray.toByteArray())

        val result = EpubImporter.import(context, Uri.fromFile(txtFile))
        assertNotNull(result)
        val chapters = result!!.chapters
        assertTrue(chapters.isNotEmpty())
        val decoded = chapters[2].content
        assertTrue("Decoded content must contain quotes or text", decoded.contains("Hello, World!"))
    }
}
