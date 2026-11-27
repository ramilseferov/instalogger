package com.instalogger.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
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

sealed class Screen(val route: String, @StringRes val titleRes: Int, val icon: ImageVector) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                            val currentDestination = navBackStackEntry?.destination
                            val currentRoute = currentDestination?.route

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

                        val bottomNavItems = listOf(
                            Screen.Dashboard,
                            Screen.Notifications,
                            Screen.Session,
                            Screen.Settings
                        )

                        val showBottomBar = currentRoute in bottomNavItems.map { it.route }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .languageTransitionBlur(isSettingsTransitionActive)
                        ) {
                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                bottomBar = {
                                    if (showBottomBar) {
                                        AnimatedBottomBar(
                                            items = bottomNavItems,
                                            currentRoute = currentRoute,
                                            unreadCount = unreadCount,
                                            onItemSelected = { screen ->
                                                if (currentRoute != screen.route) {
                                                    navController.navigate(screen.route) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            ) { innerPadding ->
                                NavHost(
                                    navController = navController,
                                    startDestination = Screen.Dashboard.route,
                                    modifier = Modifier.padding(innerPadding),
                                    enterTransition = {
                                        val fromRoute = initialState.destination.route
                                        val toRoute = targetState.destination.route
                                        val fromIdx = getTabRouteIndex(fromRoute)
                                        val toIdx = getTabRouteIndex(toRoute)

                                        when {
                                            toRoute?.startsWith("target/") == true -> MotionSpecs.PushEnter
                                            fromIdx != -1 && toIdx != -1 -> {
                                                if (toIdx > fromIdx) MotionSpecs.TabFlowRightEnter else MotionSpecs.TabFlowLeftEnter
                                            }
                                            else -> MotionSpecs.NavEnterTransition
                                        }
                                    },
                                    exitTransition = {
                                        val fromRoute = initialState.destination.route
                                        val toRoute = targetState.destination.route
                                        val fromIdx = getTabRouteIndex(fromRoute)
                                        val toIdx = getTabRouteIndex(toRoute)

                                        when {
                                            toRoute?.startsWith("target/") == true -> MotionSpecs.PushExit
                                            fromIdx != -1 && toIdx != -1 -> {
                                                if (toIdx > fromIdx) MotionSpecs.TabFlowRightExit else MotionSpecs.TabFlowLeftExit
                                            }
                                            else -> MotionSpecs.NavExitTransition
                                        }
                                    },
                                    popEnterTransition = {
                                        val fromRoute = initialState.destination.route
                                        val toRoute = targetState.destination.route
                                        val fromIdx = getTabRouteIndex(fromRoute)
                                        val toIdx = getTabRouteIndex(toRoute)

                                        when {
                                            fromRoute?.startsWith("target/") == true -> MotionSpecs.PopEnter
                                            fromIdx != -1 && toIdx != -1 -> {
                                                if (toIdx > fromIdx) MotionSpecs.TabFlowRightEnter else MotionSpecs.TabFlowLeftEnter
                                            }
                                            else -> MotionSpecs.NavPopEnterTransition
                                        }
                                    },
                                    popExitTransition = {
                                        val fromRoute = initialState.destination.route
                                        val toRoute = targetState.destination.route
                                        val fromIdx = getTabRouteIndex(fromRoute)
                                        val toIdx = getTabRouteIndex(toRoute)

                                        when {
                                            fromRoute?.startsWith("target/") == true -> MotionSpecs.PopExit
                                            fromIdx != -1 && toIdx != -1 -> {
                                                if (toIdx > fromIdx) MotionSpecs.TabFlowRightExit else MotionSpecs.TabFlowLeftExit
                                            }
                                            else -> MotionSpecs.NavPopExitTransition
                                        }
                                    }
                                ) {
                                    composable(Screen.Dashboard.route) {
                                        DashboardScreen(
                                            onNavigateToDetail = { username ->
                                                navController.navigate(Screen.TargetDetail.createRoute(username))
                                            }
                                        )
                                    }
                                    composable(Screen.Notifications.route) {
                                        NotificationCenterScreen(
                                            onNavigateToDetail = { username ->
                                                navController.navigate(Screen.TargetDetail.createRoute(username))
                                            }
                                        )
                                    }
                                    composable(Screen.Session.route) {
                                        InstagramSessionScreen()
                                    }
                                    composable(Screen.Settings.route) {
                                        SettingsScreen(
                                            onOpenDeveloperLogs = {
                                                navController.navigate(Screen.DeveloperLogs.route)
                                            }
                                        )
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
                                            onNavigateToSession = { navController.navigate(Screen.Session.route) }
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

    private fun getTabRouteIndex(route: String?): Int {
        return when (route) {
            Screen.Dashboard.route -> 0
            Screen.Notifications.route -> 1
            Screen.Session.route -> 2
            Screen.Settings.route -> 3
            else -> -1
        }
    }

    private fun startLoggerService() {
        try {
            InstaLoggerForegroundService.start(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
