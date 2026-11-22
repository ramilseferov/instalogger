package com.instalogger.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.instalogger.app.service.BatteryHelper
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.ui.screens.*
import com.instalogger.app.ui.theme.InstaLoggerTheme

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Panel", Icons.Default.Dashboard)
    object Notifications : Screen("notifications", "Bildirişlər", Icons.Default.Notifications)
    object Session : Screen("session", "Sessiya", Icons.Default.AccountCircle)
    object Settings : Screen("settings", "Tənzimləmələr", Icons.Default.Settings)
    object TargetDetail : Screen("target/{username}", "Təfərrüat", Icons.Default.Dashboard) {
        fun createRoute(username: String) = "target/$username"
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as InstaLoggerApp

        // Start background monitoring service
        startLoggerService()

        setContent {
            InstaLoggerTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                val currentRoute = currentDestination?.route

                val unreadCount by app.notificationRepository.unreadCount.collectAsStateWithLifecycle(initialValue = 0)

                // Request notification permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                        onResult = { isGranted ->
                            if (isGranted) {
                                startLoggerService()
                            }
                        }
                    )

                    LaunchedEffect(Unit) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

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

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = NavigationBarDefaults.Elevation
                            ) {
                                bottomNavItems.forEach { screen ->
                                    val isSelected = currentRoute == screen.route
                                    NavigationBarItem(
                                        icon = {
                                            if (screen == Screen.Notifications && unreadCount > 0) {
                                                BadgedBox(
                                                    badge = {
                                                        Badge {
                                                            Text(text = if (unreadCount > 99) "99+" else unreadCount.toString())
                                                        }
                                                    }
                                                ) {
                                                    Icon(screen.icon, contentDescription = screen.title)
                                                }
                                            } else {
                                                Icon(screen.icon, contentDescription = screen.title)
                                            }
                                        },
                                        label = { Text(screen.title) },
                                        selected = isSelected,
                                        onClick = {
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
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        modifier = Modifier.padding(innerPadding)
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
