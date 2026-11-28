package com.instalogger.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.instalogger.app.service.BatteryHelper
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.ui.animation.MotionSpecs
import com.instalogger.app.ui.components.AnimatedBottomBar
import com.instalogger.app.ui.components.LanguageBlurOverlay
import com.instalogger.app.ui.components.SplashOverlay
import com.instalogger.app.ui.components.UpdateBottomSheet
import com.instalogger.app.ui.components.languageTransitionBlur
import com.instalogger.app.ui.screens.*
import com.instalogger.app.ui.theme.InstaLoggerTheme
import com.instalogger.app.util.LanguageManager
import kotlinx.coroutines.launch

@Immutable
sealed class Screen(val route: String, @StringRes val titleRes: Int, val icon: ImageVector) {
    object MainTabs : Screen("main_tabs", R.string.nav_dashboard, Icons.Default.Dashboard)
    object Dashboard : Screen("dashboard", R.string.nav_dashboard, Icons.Default.Dashboard)
    object Notifications : Screen("notifications", R.string.nav_notifications, Icons.Default.Notifications)
    object Session : Screen("session", R.string.nav_session, Icons.Default.AccountCircle)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
    object DeveloperLogs : Screen("developer_logs", R.string.dev_logs_title, Icons.Default.Terminal)
    object TargetDetail : Screen("target/{username}", R.string.target_detail_title, Icons.Default.Dashboard) {
        fun createRoute(username: String) = "target/$username"
    }
}

class MainActivity : FragmentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startLoggerService()
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LanguageManager.wrapBaseContext(newBase))
    }

    private fun requestHighestRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display ?: @Suppress("DEPRECATION") windowManager?.defaultDisplay
                } else {
                    @Suppress("DEPRECATION")
                    windowManager?.defaultDisplay
                }
                if (activeDisplay != null) {
                    val currentMode = activeDisplay.mode
                    val bestMode = activeDisplay.supportedModes
                        .filter {
                            it.physicalWidth == currentMode.physicalWidth &&
                                it.physicalHeight == currentMode.physicalHeight
                        }
                        .maxByOrNull { it.refreshRate }
                        ?: activeDisplay.supportedModes.maxByOrNull { it.refreshRate }
                    if (bestMode != null && bestMode.refreshRate >= 60f) {
                        val lp = window.attributes
                        lp.preferredDisplayModeId = bestMode.modeId
                        lp.preferredRefreshRate = bestMode.refreshRate
                        window.attributes = lp
                    }
                }
            }
        } catch (_: Throwable) {
            // Best-effort high refresh rate hint
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestHighestRefreshRate()
        LanguageManager.init(this)
        val app = application as InstaLoggerApp

        // Dynamic FLAG_SECURE management to block screenshots and recents previews
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.appLockManager.isSecureScreenEnabled.collect { isSecure ->
                    if (isSecure) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start background monitoring service
        startLoggerService()

        // Trigger silent GitHub update check (respects 6-hour throttle & autoCheckUpdates setting)
        val openUpdateFromNotif = intent?.getBooleanExtra(EXTRA_OPEN_UPDATE_SHEET, false) == true
        app.updateManager.checkForUpdatesAsync(
            manual = openUpdateFromNotif,
            openSheetIfAvailable = openUpdateFromNotif,
            notifyIfAvailable = false
        )
        if (openUpdateFromNotif) {
            app.updateManager.openSheet()
        }

        setContent {
            val currentLanguage by LanguageManager.currentLanguage.collectAsState()
            val themeMode by app.settingsManager.themeMode.collectAsState()
            val startupState by app.startupProgress.collectAsStateWithLifecycle()
            val isAppLocked by app.appLockManager.isAppLocked.collectAsStateWithLifecycle()
            val isLockConfigured = remember(isAppLocked) { app.appLockManager.isLockConfigured() }

            val baseConfiguration = LocalConfiguration.current
            val localizedConfiguration = remember(currentLanguage, baseConfiguration) {
                LanguageManager.getLocalizedConfiguration(baseConfiguration, currentLanguage)
            }
            val localizedContext = remember(currentLanguage, baseConfiguration) {
                LanguageManager.getLocalizedContext(this@MainActivity, currentLanguage, baseConfiguration)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration
            ) {
                InstaLoggerTheme(themeMode = themeMode) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isAppLocked && isLockConfigured) {
                            AppLockScreen(
                                activity = this@MainActivity,
                                appLockManager = app.appLockManager
                            )
                        } else {
                            val navController = rememberNavController()
                            val navBackStackEntry by navController.currentBackStackEntryAsState()
                            val currentNavRoute = navBackStackEntry?.destination?.route

                            val bottomNavItems = remember {
                                listOf(
                                    Screen.Dashboard,
                                    Screen.Notifications,
                                    Screen.Session,
                                    Screen.Settings
                                )
                            }
                            var selectedTabRoute by rememberSaveable { mutableStateOf(Screen.Dashboard.route) }
                            val selectedMainTab = remember(selectedTabRoute) {
                                bottomNavItems.firstOrNull { it.route == selectedTabRoute } ?: Screen.Dashboard
                            }

                            val isLanguageTransitioning by LanguageManager.isTransitioning.collectAsState()
                            val isThemeTransitioning by app.settingsManager.isTransitioning.collectAsState()
                            val isSettingsTransitionActive = isLanguageTransitioning || isThemeTransitioning

                            // Transition safety watchdog: guaranteed release within 600ms
                            LaunchedEffect(isSettingsTransitionActive) {
                                if (isSettingsTransitionActive) {
                                    kotlinx.coroutines.delay(600)
                                    LanguageManager.resetTransition()
                                }
                            }

                            val unreadCount by app.notificationRepository.unreadCount.collectAsStateWithLifecycle(initialValue = 0)

                            // Check battery optimization only after the splash screen has smoothly revealed the app
                            LaunchedEffect(startupState.isReady) {
                                if (startupState.isReady && !BatteryHelper.isBatteryOptimizationIgnored(this@MainActivity)) {
                                    BatteryHelper.requestIgnoreBatteryOptimization(this@MainActivity)
                                }
                            }

                            val isOnMainTabs = currentNavRoute == null || currentNavRoute == Screen.MainTabs.route

                            // Hardware Back returns from non-home main tabs smoothly to Dashboard
                            BackHandler(enabled = isOnMainTabs && selectedMainTab != Screen.Dashboard) {
                                selectedTabRoute = Screen.Dashboard.route
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .languageTransitionBlur(isSettingsTransitionActive)
                            ) {
                                Scaffold(
                                    modifier = Modifier.fillMaxSize(),
                                    bottomBar = {
                                        if (isOnMainTabs) {
                                            AnimatedBottomBar(
                                                items = bottomNavItems,
                                                currentRoute = selectedMainTab.route,
                                                unreadCount = unreadCount,
                                                onItemSelected = { screen ->
                                                    if (selectedTabRoute != screen.route) {
                                                        selectedTabRoute = screen.route
                                                    }
                                                }
                                            )
                                        }
                                    }
                                ) { innerPadding ->
                                    NavHost(
                                        navController = navController,
                                        startDestination = Screen.MainTabs.route,
                                        modifier = Modifier.padding(innerPadding),
                                        enterTransition = { MotionSpecs.PushEnter },
                                        exitTransition = { MotionSpecs.PushExit },
                                        popEnterTransition = { MotionSpecs.PopEnter },
                                        popExitTransition = { MotionSpecs.PopExit }
                                    ) {
                                        composable(Screen.MainTabs.route) {
                                            SmoothMainTabHost(
                                                tabs = bottomNavItems,
                                                selectedTab = selectedMainTab,
                                                modifier = Modifier.fillMaxSize()
                                            ) { tab, isSelected ->
                                                when (tab) {
                                                    Screen.Dashboard -> {
                                                        DashboardScreen(
                                                            onNavigateToDetail = { username ->
                                                                navController.navigate(Screen.TargetDetail.createRoute(username))
                                                            }
                                                        )
                                                    }
                                                    Screen.Notifications -> {
                                                        NotificationCenterScreen(
                                                            onNavigateToDetail = { username ->
                                                                navController.navigate(Screen.TargetDetail.createRoute(username))
                                                            }
                                                        )
                                                    }
                                                    Screen.Session -> {
                                                        InstagramSessionScreen(
                                                            isActiveTab = isSelected && isOnMainTabs
                                                        )
                                                    }
                                                    Screen.Settings -> {
                                                        SettingsScreen(
                                                            isActiveTab = isSelected && isOnMainTabs,
                                                            onOpenDeveloperLogs = {
                                                                navController.navigate(Screen.DeveloperLogs.route)
                                                            }
                                                        )
                                                    }
                                                    else -> Unit
                                                }
                                            }
                                        }
                                        composable(Screen.DeveloperLogs.route) {
                                            DeveloperLogsScreen(
                                                onBack = { navController.popBackStack() }
                                            )
                                        }
                                        composable(
                                            route = Screen.TargetDetail.route,
                                            arguments = listOf(navArgument("username") { type = NavType.StringType })
                                        ) { backStackEntry ->
                                            val username = backStackEntry.arguments?.getString("username") ?: ""
                                            ProfileDetailScreen(
                                                username = username,
                                                onBack = { navController.popBackStack() },
                                                onNavigateToSession = {
                                                    selectedTabRoute = Screen.Session.route
                                                    navController.popBackStack(Screen.MainTabs.route, inclusive = false)
                                                }
                                            )
                                        }
                                    }
                                }

                                LanguageBlurOverlay(isBlurActive = isSettingsTransitionActive)
                                UpdateBottomSheet(updateManager = app.updateManager)
                            }
                        }

                        SplashOverlay(state = startupState)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE_SHEET, false)) {
            (application as? InstaLoggerApp)?.updateManager?.let { um ->
                um.openSheet()
                um.checkForUpdatesAsync(manual = false, openSheetIfAvailable = true)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (application as? InstaLoggerApp)?.appLockManager?.onAppBackgrounded()
    }

    override fun onResume() {
        super.onResume()
        LanguageManager.applyLocaleToResources(this)
        val app = application as? InstaLoggerApp
        app?.appLockManager?.onAppForegrounded()
        app?.updateManager?.onActivityResumed()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        LanguageManager.applyLocaleToResources(this)
    }

    companion object {
        const val EXTRA_OPEN_UPDATE_SHEET = "extra_open_update_sheet"
    }

    private fun startLoggerService() {
        try {
            InstaLoggerForegroundService.start(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/**
 * Hardware-accelerated (RenderNode / GPU) 120 FPS directional tab host.
 * - Retains visited main tabs in composition so scroll positions and Room subscriptions never reset.
 * - Skips measure/layout/draw/touch completely for hidden tabs (0 background UI cost).
 * - Animates directional horizontal glide + crossfade strictly in the Draw phase via graphicsLayer.
 */
@Composable
private fun SmoothMainTabHost(
    tabs: List<Screen>,
    selectedTab: Screen,
    modifier: Modifier = Modifier,
    content: @Composable (tab: Screen, isSelected: Boolean) -> Unit
) {
    var currentActiveTab by remember { mutableStateOf(selectedTab) }
    var outgoingTab by remember { mutableStateOf<Screen?>(null) }
    var transitionDirection by remember { mutableFloatStateOf(1f) }
    var transitionProgress by remember { mutableFloatStateOf(1f) }

    val retainedTabs = remember { mutableStateListOf<Screen>() }
    if (selectedTab !in retainedTabs) {
        retainedTabs.add(selectedTab)
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != currentActiveTab) {
            val prevTab = currentActiveTab
            val oldIdx = tabs.indexOf(prevTab)
            val newIdx = tabs.indexOf(selectedTab)
            transitionDirection = if (newIdx >= oldIdx) 1f else -1f
            outgoingTab = prevTab
            currentActiveTab = selectedTab
            transitionProgress = 0f

            val durationSec = 0.16f
            var elapsed = 0f
            var lastFrameNanos = 0L

            while (elapsed < durationSec) {
                withFrameNanos { frameNanos ->
                    val rawDt = if (lastFrameNanos == 0L) 0.016f else (frameNanos - lastFrameNanos) / 1_000_000_000f
                    lastFrameNanos = frameNanos
                    elapsed += rawDt.coerceIn(0.001f, 0.055f)
                    val linear = (elapsed / durationSec).coerceIn(0f, 1f)
                    transitionProgress = FastOutSlowInEasing.transform(linear)
                }
            }
            transitionProgress = 1f
            outgoingTab = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        for (tab in tabs) {
            val isCurrent = (tab == currentActiveTab)
            val isOutgoing = (tab == outgoingTab)
            val isVisible = isCurrent || isOutgoing
            val isRetained = tab in retainedTabs

            if (isVisible || isRetained) {
                key(tab.route) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(
                                when {
                                    isCurrent -> 1f
                                    isOutgoing -> 0f
                                    else -> -1f
                                }
                            )
                            .layout { measurable, constraints ->
                                if (isVisible) {
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) {
                                        placeable.placeRelative(0, 0)
                                    }
                                } else {
                                    // Zero measure/layout/draw/touch cost for hidden tabs while retaining state
                                    val w = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
                                    val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
                                    layout(w, h) {}
                                }
                            }
                            .graphicsLayer {
                                val p = transitionProgress
                                val w = size.width
                                when {
                                    isCurrent -> {
                                        alpha = (p * 1.15f).coerceIn(0f, 1f)
                                        translationX = (1f - p) * transitionDirection * w * 0.045f
                                    }
                                    isOutgoing -> {
                                        alpha = (1f - p * 1.15f).coerceIn(0f, 1f)
                                        translationX = -p * transitionDirection * w * 0.03f
                                    }
                                    else -> {
                                        alpha = 0f
                                    }
                                }
                            }
                    ) {
                        content(tab, isCurrent)
                    }
                }
            }
        }
    }
}
