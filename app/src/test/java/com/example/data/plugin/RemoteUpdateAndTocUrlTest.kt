package com.example.data.plugin

import com.example.util.CloudflareDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUpdateAndTocUrlTest {

    @Test
    fun compareVersionsIsNumeric() {
        assertTrue(PluginManager.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(PluginManager.compareVersions("1.0", "1.0.1") < 0)
        assertEquals(0, PluginManager.compareVersions("v1.2.0", "1.2"))
    }

    @Test
    fun tocUrlTemplateExpandsPlaceholders() {
        assertEquals(
            "https://novelfire.net/book/abc/chapters",
            PluginExecutionEngine.resolveTocUrl("{url}/chapters", "https://novelfire.net/book/abc/?x=1#top", null)
        )
        assertEquals(
            "https://readnovelfull.com/ajax/chapter-archive?novelId=2454",
            PluginExecutionEngine.resolveTocUrl(
                "{origin}/ajax/chapter-archive?novelId={novelId}",
                "https://readnovelfull.com/advent-of-the-three-calamities.html", "2454"
            )
        )
        // Without a data-novel-id the slug stands in.
        assertEquals(
            "https://novelbin.me/ajax/chapter-archive?novelId=my-novel",
            PluginExecutionEngine.resolveTocUrl(
                "{origin}/ajax/chapter-archive?novelId={novelId}", "https://novelbin.me/novel-book/my-novel", null
            )
        )
    }

    @Test
    fun cloudflareInterstitialIsDetected() {
        assertTrue(CloudflareDetector.looksLikeChallenge("Just a moment...", "", 120, false))
        assertTrue(CloudflareDetector.looksLikeChallenge("novelbin", "verify you are human by completing the action below", 300, false))
        assertTrue(CloudflareDetector.looksLikeChallenge("", "", 0, true))
    }

    @Test
    fun longChapterMentioningTheMarkersIsNotAChallenge() {
        val chapter = "\"Verify you are human,\" the golem said. ".repeat(200)
        assertFalse(CloudflareDetector.looksLikeChallenge("Chapter 12: Just a moment", chapter, chapter.length, false))
        assertFalse(CloudflareDetector.looksLikeChallenge("Chapter 3: Just a Moment - NovelBin", "short", 4000, false))
    }
}
