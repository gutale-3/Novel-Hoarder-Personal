package com.example.ui.screens

import kotlinx.coroutines.launch
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.theme.MinTouchTarget
import com.example.ui.theme.isNarrowScreen
import com.example.util.WebViewFactory
import com.example.viewmodel.MainViewModel


/**
 * The in-app browser, drawn over the whole app (MainActivity hosts it outside the navigation
 * scaffold) so it gets the real screen size and system-bar insets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualBrowserScreen(viewModel: MainViewModel) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    if (viewModel.showManualBrowser) {
        var webViewInstance by remember { mutableStateOf<WebView?>(null) }
        var showNovelPicker by remember { mutableStateOf(false) }
        if (showNovelPicker) {
            val libraryBooks by viewModel.repository.allBooks.collectAsState(initial = emptyList())
            com.example.ui.components.NovelPickerDialog(
                books = libraryBooks,
                onPick = { book ->
                    showNovelPicker = false
                    viewModel.manualCapture.selectExistingBook(book)
                    webViewInstance?.let { webView ->
                        viewModel.scraping.grabChapterFromWebView(webView) { msg ->
                            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    }
                },
                onNewNovel = {
                    showNovelPicker = false
                    webViewInstance?.let { webView ->
                        viewModel.scraping.grabInfoFromWebView(webView) { msg ->
                            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    }
                },
                onDismiss = { showNovelPicker = false }
            )
        }
        var currentWebUrl by remember { mutableStateOf(viewModel.manualBrowserUrl) }
        var isEditingUrl by remember { mutableStateOf(false) }
        // Remembers every page visited (in-page navigation included) so reopening the browser
        // returns to exactly where it was left.
        fun onPageUrl(url: String) {
            if (!isEditingUrl) currentWebUrl = url
            if (url.startsWith("http")) viewModel.scraping.saveBrowserUrl(url)
        }
        var isWebLoading by remember { mutableStateOf(false) }
        var canGoBack by remember { mutableStateOf(false) }
        var canGoForward by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current

        // Intercept back presses globally when the manual browser is open
        BackHandler(enabled = true) {
            if (canGoBack && webViewInstance?.canGoBack() == true) {
                webViewInstance?.goBack()
            } else {
                viewModel.showManualBrowser = false
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                try {
                    WebViewFactory.destroySafely(webViewInstance)
                    webViewInstance = null
                } catch (_: Throwable) {}
            }
        }

        // Drawn in the activity's own full-screen window, not a Dialog: a dialog window was
        // placed below the status bar yet kept full height, so the page's bottom (a site's tab
        // bar and settings) ran off the screen.
        run {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        // The dialog is drawn edge to edge: keep the page clear of the gesture /
                        // navigation bar (which hid the site's bottom panel and its settings) and
                        // the keyboard. The toolbar below pads the status bar itself.
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
                    // Streamlined Navigation & Full Width Address Bar
                    Surface(
                        tonalElevation = 3.dp,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val manualCapture = viewModel.manualCapture

                            // Row 1: close, back, address, reload — like a phone browser.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                IconButton(
                                    onClick = { CookieManager.getInstance().flush(); viewModel.showManualBrowser = false },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close browser", modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = { webViewInstance?.goBack() },
                                    enabled = canGoBack,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                                }
                                Box(Modifier.weight(1f)) {
                                com.example.ui.components.CompactAddressBar(
                                    value = currentWebUrl,
                                    onValueChange = { currentWebUrl = it },
                                    onFocusChange = { isEditingUrl = it },
                                    isLoading = isWebLoading,
                                    onGo = {
                                        focusManager.clearFocus()
                                        var targetUrl = currentWebUrl.trim()
                                        if (targetUrl.isNotEmpty()) {
                                            if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                                                targetUrl = if (targetUrl.contains('.') && !targetUrl.contains(' ')) "https://$targetUrl"
                                                else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(targetUrl, "UTF-8")
                                            }
                                            webViewInstance?.loadUrl(targetUrl)
                                        }
                                    }
                                )
                                }
                                IconButton(
                                    onClick = { webViewInstance?.reload() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(20.dp))
                                }
                            }

                            // Row 2: capture tools and the novel they save into.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Interactive Grab Novel Info Button (In Between)
                                Button(
                                    onClick = {
                                        webViewInstance?.let { webView ->
                                            viewModel.scraping.grabInfoFromWebView(webView) { msg ->
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(msg)
                                                }
                                            }
                                        }
                                    },
                                    enabled = !manualCapture.isCapturing,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("scrape_grab_info_btn"),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (manualCapture.isCapturing) "Saving…" else "Grab Info",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Interactive Save Chapter Button (In Between)
                                Button(
                                    onClick = {
                                        if (manualCapture.captureBookId == null) {
                                            showNovelPicker = true // ask which novel instead of failing
                                        } else {
                                            webViewInstance?.let { webView ->
                                                viewModel.scraping.grabChapterFromWebView(webView) { msg ->
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(msg)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = !manualCapture.isCapturing,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("scrape_save_chapter_btn"),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bookmark,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (manualCapture.isCapturing) "Saving…" else "Save Chap",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    text = manualCapture.captureBookTitle.takeIf { !it.isNullOrBlank() }
                                        ?.let { "→ $it (${manualCapture.captureChapterCount})" } ?: "No novel chosen",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Box {
                                    var sessionMenu by remember { mutableStateOf(false) }
                                    IconButton(onClick = { sessionMenu = true }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Session options", modifier = Modifier.size(18.dp))
                                    }
                                    DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Change novel") },
                                            onClick = { sessionMenu = false; showNovelPicker = true }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("New session") },
                                            onClick = { sessionMenu = false; manualCapture.startNewBrowserSession() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Forward") },
                                            enabled = canGoForward,
                                            onClick = { sessionMenu = false; webViewInstance?.goForward() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Google") },
                                            onClick = {
                                                sessionMenu = false
                                                currentWebUrl = "https://www.google.com"
                                                webViewInstance?.loadUrl("https://www.google.com")
                                            }
                                        )
                                    }
                                }
                            }

                            if (manualCapture.isCapturing) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Full Screen WebView Embed with Crash Prevention
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    WebViewFactory.applySafeDefaults(this, viewModel.defaultUserAgent)
                                    webViewClient = object : WebViewFactory.SafeWebViewClient(
                                        onPageStartedCallback = { _, url, _ ->
                                            isWebLoading = true
                                            if (url != null) onPageUrl(url)
                                        },
                                        onPageFinishedCallback = { view, url ->
                                            isWebLoading = false
                                            canGoBack = view?.canGoBack() == true
                                            canGoForward = view?.canGoForward() == true
                                            if (url != null) {
                                                onPageUrl(url)
                                                CookieManager.getInstance().flush()
                                            }
                                        },
                                        onUrlChanged = { url ->
                                            onPageUrl(url)
                                            canGoBack = this@apply.canGoBack()
                                            canGoForward = this@apply.canGoForward()
                                        }
                                    ) {}
                                    webChromeClient = object : WebViewFactory.SafeWebChromeClient(
                                        onProgressUpdate = { _ ->
                                            canGoBack = this@apply.canGoBack()
                                            canGoForward = this@apply.canGoForward()
                                        }
                                    ) {}
                                    webViewInstance = this
                                    loadUrl(viewModel.manualBrowserUrl)
                                }
                            },
                            update = { view ->
                                webViewInstance = view
                            },
                            onRelease = { view ->
                                WebViewFactory.destroySafely(view)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
        }
    }
}
