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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.util.CloudflareDetector
import com.example.util.WebViewFactory
import kotlinx.coroutines.delay

/**
 * Full-screen page where the user passes a site's bot check or looks at a chapter that did not
 * load. Drawn edge to edge with the system bars padded, a short header, and the Resume button in
 * a bar at the bottom so it is always on screen whatever the message length.
 *
 * When the page was showing a bot check and the check disappears (the user passed it), Resume
 * happens by itself after a moment — no hunting for the button.
 *
 * Uses the scraper's User-Agent and cookie store, so the pass carries over to the next download.
 */
@Composable
fun SecurityCheckDialog(
    url: String,
    userAgent: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    reason: String = "Complete the check below. It continues by itself once the page passes.",
    doneLabel: String = "Resume",
    cancelLabel: String = "Cancel"
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
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // Header: text only, so it can never push a button off screen.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        if (passed) "Check passed — continuing…" else "Security check",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider()

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

                // Action bar pinned to the bottom, always visible.
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(12.dp)) { Text(cancelLabel) }
                        Button(
                            onClick = { finish() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) {
                            Icon(if (passed) Icons.Default.CheckCircle else Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(doneLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
