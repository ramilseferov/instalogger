package com.instalogger.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.instalogger.app.service.BatteryHelper
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.ui.animation.MotionSpecs
import com.instalogger.app.ui.components.AnimatedBottomBar
import com.instalogger.app.ui.components.LanguageBlurOverlay
import com.instalogger.app.ui.components.languageTransitionBlur
import com.instalogger.app.ui.screens.*
import com.instalogger.app.ui.theme.InstaLoggerTheme
import com.instalogger.app.util.LanguageManager

sealed class Screen(val route: String, @StringRes val titleRes: Int, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", R.string.nav_dashboard, Icons.Default.Dashboard)
    object Notifications : Screen("notifications", R.string.nav_notifications, Icons.Default.Notifications)
    object Session : Screen("session", R.string.nav_session, Icons.Default.AccountCircle)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
    object TargetDetail : Screen("target/{username}", R.string.target_detail_title, Icons.Default.Dashboard) {
        fun createRoute(username: String) = "target/$username"
    }
}

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startLoggerService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LanguageManager.init(this)
        val app = application as InstaLoggerApp

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

        setContent {
            val currentLanguage by LanguageManager.currentLanguage.collectAsState()
            val themeMode by app.settingsManager.themeMode.collectAsState()
            val localizedContext = remember(currentLanguage) {
                LanguageManager.getLocalizedContext(this@MainActivity, currentLanguage)
            }
            val localizedConfiguration = remember(currentLanguage) {
                LanguageManager.getLocalizedConfiguration(resources.configuration, currentLanguage)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration
            ) {
                InstaLoggerTheme(themeMode = themeMode) {
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

                    // Check battery optimization
                    LaunchedEffect(Unit) {
                        if (!BatteryHelper.isBatteryOptimizationIgnored(this@MainActivity)) {
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
                                enterTransition = { MotionSpecs.NavEnterTransition },
                                exitTransition = { MotionSpecs.NavExitTransition },
                                popEnterTransition = { MotionSpecs.NavPopEnterTransition },
                                popExitTransition = { MotionSpecs.NavPopExitTransition }
                            ) {
                                composable(Screen.Dashboard.route) {
                                    DashboardScreen(
                                        onNavigateToDetail = { username ->
                                            navController.navigate(Screen.TargetDetail.createRoute(username))
                                        }
                                    )
                                }
                                composable(Screen.Notifications.route) {
                                    NotificationCenterScreen()
                                }
                                composable(Screen.Session.route) {
                                    InstagramSessionScreen()
                                }
                                composable(Screen.Settings.route) {
                                    SettingsScreen()
                                }
                                composable(
                                    route = Screen.TargetDetail.route,
                                    arguments = listOf(navArgument("username") { type = NavType.StringType })
                                ) { backStackEntry ->
                                    val username = backStackEntry.arguments?.getString("username") ?: ""
                                    ProfileDetailScreen(
                                        username = username,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }
                        }

                        LanguageBlurOverlay(isBlurActive = isSettingsTransitionActive)
                    }
            }
        }
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
