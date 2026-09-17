package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.*
import com.example.ui.theme.AppTheme
import com.example.ui.theme.NovelHoarderTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
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

        handleIntent(intent)

        setContent {
            val viewModel: MainViewModel = viewModel()
            val navController = rememberNavController()

            // Handle incoming intents (e.g. from TTS media notifications)
            val currentPendingRoute by pendingRoute
            LaunchedEffect(currentPendingRoute) {
                currentPendingRoute?.let { route ->
                    navController.navigate(route)
                    pendingRoute.value = null
                }
            }

            // Resume reading session or last screen automatically upon launch
            var hasRestoredSession by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (!hasRestoredSession) {
                    hasRestoredSession = true
                    if (pendingRoute.value == null) {
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
                                navController.navigate(lastTab)
                            }
                        }
                    }
                }
            }

            NovelHoarderTheme(appTheme = viewModel.currentTheme) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "home"

                val isFullScreen = currentRoute.startsWith("reader") || currentRoute == "settings" || currentRoute == "plugins"

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (!isFullScreen && currentRoute != "library") {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = when (currentRoute) {
                                            "home" -> "Novel Hoarder"
                                            "scrape" -> "Scraper Terminal"
                                            else -> "Novel Hoarder"
                                        },
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                },
                                actions = {
                                    IconButton(onClick = { navController.navigate("settings") }) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings",
                                            tint = MaterialTheme.colorScheme.primary
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
                                    onClick = {
                                        if (currentRoute != "home") {
                                            viewModel.progress.setLastActiveTab("home")
                                            navController.navigate("home") {
                                                popUpTo("home") { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                    label = { Text("Home") },
                                    modifier = Modifier.testTag("nav_home_tab")
                                )

                                NavigationBarItem(
                                    selected = currentRoute == "scrape",
                                    onClick = {
                                        if (currentRoute != "scrape") {
                                            viewModel.progress.setLastActiveTab("scrape")
                                            navController.navigate("scrape") {
                                                popUpTo("home") { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = "Scrape") },
                                    label = { Text("Scrape") },
                                    modifier = Modifier.testTag("nav_scrape_tab")
                                )

                                NavigationBarItem(
                                    selected = currentRoute == "library",
                                    onClick = {
                                        if (currentRoute != "library") {
                                            viewModel.progress.setLastActiveTab("library")
                                            navController.navigate("library") {
                                                popUpTo("home") { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
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
                                    onNavigateToScrape = {
                                        viewModel.progress.setLastActiveTab("scrape")
                                        navController.navigate("scrape")
                                    },
                                    onNavigateToLibrary = {
                                        viewModel.progress.setLastActiveTab("library")
                                        navController.navigate("library")
                                    },
                                    onOpenBook = { bookId ->
                                        viewModel.progress.setActiveReaderSession(bookId)
                                        navController.navigate("reader/$bookId")
                                    }
                                )
                            }

                            composable("scrape") {
                                ScrapeScreen(viewModel = viewModel)
                            }

                            composable("library") {
                                LibraryScreen(
                                    viewModel = viewModel,
                                    onOpenBook = { bookId ->
                                        viewModel.progress.setActiveReaderSession(bookId)
                                        navController.navigate("reader/$bookId")
                                    },
                                    onNavigateToScrape = {
                                        viewModel.progress.setLastActiveTab("scrape")
                                        navController.navigate("scrape")
                                    },
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
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(
                                route = "reader/{bookId}?chapterId={chapterId}&paraIndex={paraIndex}",
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
                                navController.navigate("reader/$bookId?chapterId=$chapterId&paraIndex=$paraIndex")
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
