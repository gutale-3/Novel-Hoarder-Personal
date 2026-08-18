package com.example.data.plugin

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TocScriptTest {

    @Test
    fun testPaginatedTocScriptGeneration() {
        val config = PluginConfig(
            id = "test_paginated",
            name = "Test Paginated",
            baseUrl = "test.com",
            tocPaginationMode = "query",
            tocPageParam = "page",
            tocFirstPage = 1,
            tocMaxPages = 150
        )

        val engine = PluginExecutionEngine(config)
        val method = PluginExecutionEngine::class.java.getDeclaredMethod("buildPaginatedTocScript")
        method.isAccessible = true
        val script = method.invoke(engine) as String

        // Verify the generated script contains required features:
        // 1. same-origin credentials
        assertTrue("Script should use same-origin fetch", script.contains("credentials: 'same-origin'"))

        // 2. group pause
        assertTrue("Script should have a group pause timeout", script.contains("setTimeout"))

        // 3. tocMaxPages
        assertTrue("Script should respect MAX_PAGES", script.contains("const MAX_PAGES = 150"))

        // 4. origin check
        assertTrue("Script should check origin", script.contains("u.origin !== location.origin"))
    }

    @Test
    fun testPaginatedTocScriptWithNoneModeDoesNotFetchExtraPages() {
        val config = PluginConfig(
            id = "test_none",
            name = "Test None Pagination",
            baseUrl = "test.com",
            tocPaginationMode = "none",
            tocFirstPage = 1,
            tocMaxPages = 200
        )

        val engine = PluginExecutionEngine(config)
        val method = PluginExecutionEngine::class.java.getDeclaredMethod("buildPaginatedTocScript")
        method.isAccessible = true
        val script = method.invoke(engine) as String

        // When tocPaginationMode is "none", MAX_PAGES should be FIRST_PAGE (which is 1)
        assertTrue("Script should limit MAX_PAGES to FIRST_PAGE when mode is none", script.contains("const MAX_PAGES = 1"))
    }
}
