package com.example.util

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebViewFactoryTest {
    @Test
    fun rendererCrashIsHandledSoTheAppSurvives() {
        // onRenderProcessGone must return true or Android kills the host app with the renderer.
        assertTrue(WebViewFactory.SafeWebViewClient().onRenderProcessGone(null, null))
    }
}
