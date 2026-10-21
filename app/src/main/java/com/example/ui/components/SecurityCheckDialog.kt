package com.example.ui.components

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.util.CloudflareDetector
import com.example.util.WebViewFactory
import kotlinx.coroutines.delay

/**
 * Full-screen page where the user passes a site's bot check or fixes a chapter that did not load.
 * No instructions — just the page and a big Resume button at the bottom, clear of the gesture bar.
 * When a bot check that was showing disappears, it resumes by itself after a moment.
 *
 * Uses the scraper's User-Agent and cookie store, so the pass carries over to the next download.
 */
@Composable
fun SecurityCheckDialog(
    url: String,
    userAgent: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    @Suppress("UNUSED_PARAMETER") reason: String = "",
    doneLabel: String = "Resume",
    cancelLabel: String = "Stop"
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var sawChallenge by remember { mutableStateOf(false) }
    var passed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun finish() {
        if (finished) return
        finished = true
        CookieManager.getInstance().flush()
        onDone()
    }

    // Watch the page: once a check that was showing has gone for ~2 s, carry on automatically.
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        var clearSince = 0L
        while (!finished) {
            delay(700)
            val challenge = runCatching { CloudflareDetector.isChallenge(wv) }.getOrDefault(true)
            if (challenge) {
                sawChallenge = true
                passed = false
                clearSince = 0L
            } else if (sawChallenge && wv.progress == 100) {
                passed = true
                if (clearSince == 0L) clearSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - clearSince > 2_000) finish()
            }
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            WebViewFactory.applySafeDefaults(this, userAgent)
                            webViewClient = WebViewFactory.SafeWebViewClient(
                                onPageFinishedCallback = { _, _ -> CookieManager.getInstance().flush() }
                            )
                            webChromeClient = WebViewFactory.SafeWebChromeClient()
                            loadUrl(url)
                            webView = this
                        }
                    },
                    onRelease = { WebViewFactory.destroySafely(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(12.dp)) {
                            Text(cancelLabel, maxLines = 1, softWrap = false)
                        }
                        Button(
                            onClick = { finish() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Icon(if (passed) Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(doneLabel, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
