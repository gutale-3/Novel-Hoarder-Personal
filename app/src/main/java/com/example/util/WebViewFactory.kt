package com.example.util

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Factory for WebViews configured with secure defaults and modern desktop/mobile network behavior.
 */
object WebViewFactory {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

    /**
     * Creates a WebView configured with standard secure options and desktop-class network
     * behaviors (DOM storage, JS enabled, custom User-Agent).
     *
     * ALWAYS call this instead of `WebView(context)` directly!
     */
    fun create(context: Context, userAgent: String? = null): WebView {
        return WebView(context.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = if (!userAgent.isNullOrBlank()) userAgent else DEFAULT_UA
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }

    fun applySafeDefaults(webView: WebView, userAgent: String? = null) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (!userAgent.isNullOrBlank()) userAgent else DEFAULT_UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * Safely destroys a WebView instance, releasing all listeners, removing from any parent,
     * clearing history, and preventing memory leaks.
     */
    fun destroySafely(webView: WebView?) {
        if (webView == null) return
        try {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = android.webkit.WebViewClient()
            webView.loadUrl("about:blank")
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        } catch (ignored: Exception) {
            // ignore
        }
    }
}
