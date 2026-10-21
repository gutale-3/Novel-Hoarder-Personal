package com.example.ui.components

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.util.WebViewFactory

/**
 * Full-screen page where the user passes a site's bot check by hand. Uses the scraper's
 * User-Agent and cookie store, so the pass carries over to the next download.
 */
@Composable
fun SecurityCheckDialog(url: String, userAgent: String, onDone: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pass the security check", fontWeight = FontWeight.Bold)
                        Text(
                            "Complete it below, wait until the chapter text shows, then tap Done.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = {
                        CookieManager.getInstance().flush()
                        onDone()
                    }) { Text("Done") }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            WebViewFactory.applySafeDefaults(this, userAgent)
                            webViewClient = WebViewFactory.SafeWebViewClient()
                            webChromeClient = WebViewFactory.SafeWebChromeClient()
                            loadUrl(url)
                        }
                    },
                    onRelease = { WebViewFactory.destroySafely(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}
