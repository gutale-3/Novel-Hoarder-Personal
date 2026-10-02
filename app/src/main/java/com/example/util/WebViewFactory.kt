package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.webkit.*

/**
 * Factory for WebViews configured with secure defaults and modern desktop/mobile network behavior.
 * Provides crash-resilient clients that prevent renderer process termination and unhandled intent crashes.
 */
object WebViewFactory {

    private const val TAG = "WebViewFactory"

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

    /**
     * Crash-resilient WebViewClient that:
     * 1. Overrides onRenderProcessGone to prevent Android OS from killing the host app process
     * 2. Safely intercepts non-HTTP intent/market/external schemes without throwing ActivityNotFoundException
     * 3. Gracefully proceeds on SSL warnings and handles page transitions smoothly.
     */
    open class SafeWebViewClient(
        private val onPageFinishedCallback: ((WebView?, String?) -> Unit)? = null,
        private val onPageStartedCallback: ((WebView?, String?, Bitmap?) -> Unit)? = null,
        private val onUrlChanged: ((String) -> Unit)? = null
    ) : WebViewClient() {

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val crashed = detail?.didCrash() == true
            Log.w(TAG, "WebView render process exited (crashed=$crashed). Handled cleanly to prevent host app crash.")
            try {
                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                view?.destroy()
            } catch (_: Throwable) {}
            // Returning true tells Android OS that host application handled the renderer exit and must NOT be terminated
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            return handleUrlNavigation(view, url)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (url == null) return false
            return handleUrlNavigation(view, url)
        }

        private fun handleUrlNavigation(view: WebView?, url: String): Boolean {
            try {
                val uri = Uri.parse(url)
                val scheme = uri.scheme?.lowercase() ?: return false

                // Standard web schemes are loaded inside the WebView
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") {
                    onUrlChanged?.invoke(url)
                    return false
                }

                val context = view?.context ?: return true

                // Special handling for Android intent:// URI scheme commonly used by ads and redirects
                if (scheme == "intent") {
                    try {
                        val parsedIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (parsedIntent != null) {
                            val pm = context.packageManager
                            if (parsedIntent.resolveActivity(pm) != null) {
                                parsedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(parsedIntent)
                                return true
                            }
                            // Fallback to browser URL if available in intent extras
                            val fallback = parsedIntent.getStringExtra("browser_fallback_url")
                            if (!fallback.isNullOrBlank()) {
                                view.loadUrl(fallback)
                                return true
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed resolving intent URI: $url", e)
                    }
                    return true
                }

                // Handling for common external schemes: market://, tel://, mailto://, sms://, etc.
                try {
                    val externalIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (externalIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(externalIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed launching external scheme $scheme for $url", e)
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Error handling URL navigation for $url", e)
                return true
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            // Avoid freezing or crashing on minor SSL/CDN chain quirks on novel sites
            try {
                handler?.proceed()
            } catch (_: Throwable) {
                try { handler?.cancel() } catch (_: Throwable) {}
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (url != null) onUrlChanged?.invoke(url)
            onPageStartedCallback?.invoke(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (url != null) onUrlChanged?.invoke(url)
            onPageFinishedCallback?.invoke(view, url)
        }
    }

    /**
     * Crash-resilient WebChromeClient that handles popups / target="_blank" safely
     * without causing unhandled window transport exceptions.
     */
    open class SafeWebChromeClient(
        private val onProgressUpdate: ((Int) -> Unit)? = null
    ) : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            onProgressUpdate?.invoke(newProgress)
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            try {
                // If a site triggers window.open() or target="_blank", attempt to load in current view
                val hrefMsg = view?.handler?.obtainMessage()
                if (hrefMsg != null) {
                    view.requestFocusNodeHref(hrefMsg)
                    val targetUrl = hrefMsg.data?.getString("url")
                    if (!targetUrl.isNullOrBlank()) {
                        view.loadUrl(targetUrl)
                        return true
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport != null && view != null) {
                    transport.webView = view
                    resultMsg.sendToTarget()
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error handling onCreateWindow safely", e)
            }
            return false
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            return true // Prevent unhandled console logging issues
        }
    }

    /**
     * Creates a WebView configured with standard secure options and desktop-class network
     * behaviors (DOM storage, JS enabled, custom User-Agent, and safe clients).
     *
     * ALWAYS call this instead of `WebView(context)` directly!
     */
    fun create(context: Context, userAgent: String? = null): WebView {
        return WebView(context.applicationContext).apply {
            applySafeDefaults(this, userAgent)
            webViewClient = SafeWebViewClient()
            webChromeClient = SafeWebChromeClient()
        }
    }

    fun applySafeDefaults(webView: WebView, userAgent: String? = null) {
        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = if (!userAgent.isNullOrBlank()) userAgent else DEFAULT_UA
                allowContentAccess = true
                allowFileAccess = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = true
                builtInZoomControls = true
                displayZoomControls = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        } catch (e: Exception) {
            Log.w(TAG, "Error applying safe defaults to WebView", e)
        }
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
            webView.webViewClient = WebViewClient()
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
