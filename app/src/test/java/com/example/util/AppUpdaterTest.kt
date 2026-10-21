package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdaterTest {
    private val json = """
        [
          {"tag_name":"build-7","name":"Build 7","body":"b7","draft":false,
           "assets":[{"name":"NovelHoarder-test-arm64-build7.apk","browser_download_url":"u7","size":10}]},
          {"tag_name":"build-9","name":"Build 9","body":"b9","draft":true,
           "assets":[{"name":"NovelHoarder-test-arm64-build9.apk","browser_download_url":"u9","size":10}]},
          {"tag_name":"build-8","name":"Build 8","body":"b8","draft":false,
           "assets":[{"name":"NovelHoarder-main-arm64-build8.apk","browser_download_url":"u8","size":10}]}
        ]
    """.trimIndent()

    @Test
    fun picksNewestNonDraftForEdition() {
        assertEquals("u7", AppUpdater.pickRelease(json, "test")?.apkUrl)
        assertEquals(8L, AppUpdater.pickRelease(json, "main")?.versionCode)
        assertNull(AppUpdater.pickRelease("[]", "test"))
    }
}
