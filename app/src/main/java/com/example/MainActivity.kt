package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.example.ui.screens.*
import com.example.ui.theme.AppTheme
import com.example.ui.theme.NovelHoarderTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    // Device sync: pick up other devices' progress when the app comes forward, publish ours
    // when it goes away.
    override fun onStart() {
        super.onStart()
        val container = NovelHoarderApp.getContainer(application)
        container.appScope.launch { container.cloudSync.syncIfDue() }
    }

    override fun onStop() {
        super.onStop()
        val container = NovelHoarderApp.getContainer(application)
        container.appScope.launch { if (container.cloudSync.isEnabled) container.cloudSync.syncNow() }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || code == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            val container = com.example.NovelHoarderApp.getContainer(application)
            // While TTS is speaking the volume keys must still change the volume.
            val ttsSpeaking = container.tts.ttsIsPlaying
            if (com.example.ui.components.ReaderPaging.readerVisible &&
                container.settings.enableVolumeKeysNavigation && !ttsSpeaking
            ) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    com.example.ui.components.ReaderPaging.requestTurn(
                        if (code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
                    )
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("open_route")?.takeIf { it.isNotBlank() }?.let { pendingRoute.value = it }
        val openBookId = intent?.getStringExtra("open_reader_book")
        if (!openBookId.isNullOrEmpty()) {
            val openChapterId = intent.getStringExtra("open_reader_chapter")
            val targetRoute = if (!openChapterId.isNullOrEmpty()) {
                "reader/$openBookId?chapterId=$openChapterId"
            } else {
                "reader/$openBookId"
            }
            pendingRoute.value = targetRoute
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // On rotation/theme change the back stack is restored; handling the same launch intent
        // again would stack a second copy of the screen.
        if (savedInstanceState == null) handleIntent(intent)
        val launchedForRoute = pendingRoute.value != null

        setContent {
            val viewModel: MainViewModel = viewModel()
            val navController = rememberNavController()

            /**
             * Switch bottom tab. Always clears back to Home first (so a tab never reopens on top of
             * a stale Settings/Reader page) and never stacks a second copy of the tab — the two
             * ways tabs used to get "stuck" until Back was pressed.
             */
            fun goToTab(route: String) {
                if (route != "updates") viewModel.progress.setLastActiveTab(route)
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id)
                    launchSingleTop = true
                }
            }

            fun openReader(route: String) {
                // Replace an open reader instead of piling readers on top of each other.
                navController.navigate(route) {
                    popUpTo(READER_ROUTE) { inclusive = true }
                    launchSingleTop = true
                }
            }

            // Handle incoming intents (e.g. from notifications and the widget)
            val currentPendingRoute by pendingRoute
            LaunchedEffect(currentPendingRoute) {
                currentPendingRoute?.let { route ->
                    when {
                        route in TAB_ROUTES -> goToTab(route)
                        route.startsWith("reader/") -> openReader(route)
                        else -> navController.navigate(route) { launchSingleTop = true }
                    }
                    pendingRoute.value = null
                }
            }

            // Resume reading session or last screen automatically upon launch
            var hasRestoredSession by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (!hasRestoredSession) {
                    hasRestoredSession = true
                    // Opened from a notification or the widget: go there, not to the last session.
                    if (!launchedForRoute && pendingRoute.value == null) {
                        val activeBookId = viewModel.progress.getActiveReaderBookId()
                        if (!activeBookId.isNullOrEmpty()) {
                            val activeChapterId = viewModel.progress.getActiveReaderChapterId()
                            val activeParaIndex = viewModel.progress.getActiveReaderParaIndex()
                            val route = if (!activeChapterId.isNullOrEmpty()) {
                                "reader/$activeBookId?chapterId=$activeChapterId&paraIndex=$activeParaIndex"
                            } else {
                                "reader/$activeBookId"
                            }
                            navController.navigate(route)
                        } else {
                            val lastTab = viewModel.progress.getLastActiveTab()
                            if (lastTab != "home" && (lastTab == "library" || lastTab == "scrape")) {
                                goToTab(lastTab)
                            }
                        }
                    }
                }
            }

            NovelHoarderTheme(appTheme = viewModel.currentTheme) {
                val updatesStore = remember { com.example.background.ChapterUpdatesStore.get(applicationContext) }
                val updateEntries by updatesStore.entries.collectAsState()
                val pendingUpdateBooks = updateEntries.values.count { it.newCount > 0 }
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "home"

                // The security-check window and the browser are drawn over the whole app (below),
                // so a paused download no longer needs to jump to the Scrape tab.

                val isFullScreen = currentRoute.startsWith("reader") || currentRoute == "settings" ||
                    currentRoute == "plugins" || currentRoute == "search" // search has its own top bar

                Box(Modifier.fillMaxSize()) {
                Scaffold(
                    // Full-screen pages (reader, settings, sites) handle the system bars themselves;
                    // padding them here painted white bands above and below the reader.
                    contentWindowInsets = if (isFullScreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (!isFullScreen && currentRoute != "library") {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = when (currentRoute) {
                                            "home" -> "Inkvault"
                                            "scrape" -> "Scraper Terminal"
                                            else -> "Inkvault"
                                        },
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                },
                                actions = {
                                    IconButton(onClick = { navController.navigate("search") { launchSingleTop = true } }) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Search & discover",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { navController.navigate("plugins") { launchSingleTop = true } }) {
                                        Icon(
                                            imageVector = Icons.Default.Extension,
                                            contentDescription = "Sites & Add-ons Studio",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    IconButton(onClick = { navController.navigate("settings") { launchSingleTop = true } }) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    var showThemeMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { showThemeMenu = true }) {
                                            Icon(
                                                imageVector = Icons.Default.Palette,
                                                contentDescription = "Toggle Themes",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showThemeMenu,
                                            onDismissRequest = { showThemeMenu = false }
                                        ) {
                                            AppTheme.values().forEach { theme ->
                                                DropdownMenuItem(
                                                    text = { Text(theme.displayName) },
                                                    onClick = {
                                                        viewModel.settings.updateTheme(theme)
                                                        showThemeMenu = false
                                                    },
                                                    leadingIcon = {
                                                        if (viewModel.currentTheme == theme) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = "Selected",
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    },
                    bottomBar = {
                        if (!isFullScreen) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                NavigationBarItem(
                                    selected = currentRoute == "home",
                                    onClick = { goToTab("home") },
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                    label = { Text("Home") },
                                    modifier = Modifier.testTag("nav_home_tab")
                                )

                                NavigationBarItem(
                                    selected = currentRoute == "scrape",
                                    onClick = { goToTab("scrape") },
                                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = "Scrape") },
                                    label = { Text("Scrape") },
                                    modifier = Modifier.testTag("nav_scrape_tab")
                                )

                                NavigationBarItem(
                                    selected = currentRoute == "updates",
                                    onClick = { goToTab("updates") },
                                    icon = {
                                        BadgedBox(badge = {
                                            if (pendingUpdateBooks > 0) Badge { Text(pendingUpdateBooks.toString()) }
                                        }) {
                                            Icon(Icons.Default.NewReleases, contentDescription = "Updates")
                                        }
                                    },
                                    label = { Text("Updates") },
                                    modifier = Modifier.testTag("nav_updates_tab")
                                )

                                NavigationBarItem(
                                    selected = currentRoute == "library",
                                    onClick = { goToTab("library") },
                                    icon = { Icon(Icons.Default.LibraryBooks, contentDescription = "Library") },
                                    label = { Text("Library") },
                                    modifier = Modifier.testTag("nav_library_tab")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        val isTtsActive = viewModel.ttsPlayingBook != null && viewModel.ttsPlayingChapter != null
                        val hasResumeSession = viewModel.hasResumableSession && viewModel.ttsPlayingBook == null

                        val ttsBottomPadding = when {
                            isTtsActive && !viewModel.isTtsPlayerBarMinimized -> 165.dp
                            isTtsActive && viewModel.isTtsPlayerBarMinimized -> 72.dp
                            hasResumeSession -> 72.dp
                            else -> 0.dp
                        }

                        NavHost(
                            navController = navController,
                            startDestination = "home",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = ttsBottomPadding)
                        ) {
                            composable("home") {
                                HomeScreen(
                                    viewModel = viewModel,
                                    onNavigateToScrape = { goToTab("scrape") },
                                    onNavigateToLibrary = { goToTab("library") },
                                    onOpenBook = { bookId ->
                                        viewModel.progress.setActiveReaderSession(bookId)
                                        navController.navigate("reader/$bookId")
                                    }
                                )
                            }

                            composable("scrape") {
                                ScrapeScreen(viewModel = viewModel)
                            }

                            composable("search") {
                                SearchScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onPickResult = { goToTab("scrape") }
                                )
                            }

                            composable("updates") {
                                UpdatesScreen(
                                    viewModel = viewModel,
                                    onOpenBook = { bookId ->
                                        viewModel.progress.setActiveReaderSession(bookId)
                                        navController.navigate("reader/$bookId")
                                    }
                                )
                            }

                            composable("library") {
                                LibraryScreen(
                                    viewModel = viewModel,
                                    onOpenBook = { bookId ->
                                        viewModel.progress.setActiveReaderSession(bookId)
                                        navController.navigate("reader/$bookId")
                                    },
                                    onNavigateToScrape = { goToTab("scrape") },
                                    onNavigateToSettings = {
                                        navController.navigate("settings")
                                    }
                                )
                            }

                            composable("settings") {
                                SettingsScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onNavigateToPlugins = { navController.navigate("plugins") }
                                )
                            }

                            composable("plugins") {
                                PluginManagementScreen(
                                    pluginManager = viewModel.pluginManager,
                                    onBack = { navController.popBackStack() },
                                    libraryUrls = { viewModel.repository.getAllBooks().map { it.url } }
                                )
                            }

                            composable(
                                route = READER_ROUTE,
                                arguments = listOf(
                                    navArgument("bookId") { type = NavType.StringType },
                                    navArgument("chapterId") {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    },
                                    navArgument("paraIndex") {
                                        type = NavType.IntType
                                        defaultValue = -1
                                    }
                                )
                            ) { backStackEntry ->
                                val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
                                val chapterId = backStackEntry.arguments?.getString("chapterId")
                                val paraIndex = backStackEntry.arguments?.getInt("paraIndex") ?: -1
                                ReaderScreen(
                                    bookId = bookId,
                                    viewModel = viewModel,
                                    initialChapterId = chapterId,
                                    initialParaIndex = paraIndex,
                                    onBack = {
                                        viewModel.progress.clearActiveReaderSession()
                                        navController.popBackStack()
                                    }
                                )
                            }
                        }

                        TtsPlayerBar(
                            viewModel = viewModel,
                            onNavigateToReader = { bookId, chapterId, paraIndex ->
                                viewModel.progress.setActiveReaderSession(bookId, chapterId, paraIndex)
                                openReader("reader/$bookId?chapterId=$chapterId&paraIndex=$paraIndex")
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                        )
                    }
                }

                // Full-screen pages drawn over everything in the activity window (not Dialogs,
                // whose windows were mis-sized and cut off the bottom of the page).
                if (viewModel.showManualBrowser) {
                    ManualBrowserScreen(viewModel)
                }
                if (viewModel.showCaptchaDialog) {
                    com.example.ui.components.SecurityCheckDialog(
                        url = viewModel.captchaUrl,
                        userAgent = viewModel.scraping.captchaUserAgent.ifBlank { viewModel.defaultUserAgent },
                        onDone = { viewModel.scraping.resumeAfterCaptcha() },
                        onCancel = {
                            viewModel.showCaptchaDialog = false
                            viewModel.scraping.stopScraping()
                        },
                        doneLabel = "Resume",
                        cancelLabel = "Stop"
                    )
                }
                com.example.ui.components.SecurityCheckRequests.current?.let { req ->
                    com.example.ui.components.SecurityCheckDialog(
                        url = req.url,
                        userAgent = req.userAgent,
                        onDone = {
                            com.example.ui.components.SecurityCheckRequests.current = null
                            req.onDone()
                        },
                        onCancel = { com.example.ui.components.SecurityCheckRequests.current = null },
                        doneLabel = "Done",
                        cancelLabel = "Cancel"
                    )
                }
                }
            }
        }
    }
}

private const val READER_ROUTE = "reader/{bookId}?chapterId={chapterId}&paraIndex={paraIndex}"
private val TAB_ROUTES = setOf("home", "scrape", "updates", "library")
