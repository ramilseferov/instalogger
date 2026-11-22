package com.instalogger.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.ChangeLogEntity
import com.instalogger.app.service.BatteryHelper
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.settings.AppSettingsManager
import com.instalogger.app.settings.ThemeMode
import com.instalogger.app.ui.animation.MotionSpecs
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.NotificationSettingsBottomSheet
import com.instalogger.app.ui.components.ReachabilityHeader
import com.instalogger.app.ui.components.SettingSwitchRow
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.theme.*
import com.instalogger.app.util.ArchiveDirectoryManager
import com.instalogger.app.util.LanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    val currentLanguage by LanguageManager.currentLanguage.collectAsState()
    val currentThemeMode by app.settingsManager.themeMode.collectAsStateWithLifecycle()
    val statusBarNotifs by app.settingsManager.statusBarNotifications.collectAsStateWithLifecycle()
    val inAppNotifs by app.settingsManager.inAppNotifications.collectAsStateWithLifecycle()
    val soundEnabled by app.settingsManager.soundEnabled.collectAsStateWithLifecycle()
    val vibrationEnabled by app.settingsManager.vibrationEnabled.collectAsStateWithLifecycle()

    var showNotifSettingsSheet by remember { mutableStateOf(false) }
    var isBatteryIgnored by remember { mutableStateOf(BatteryHelper.isBatteryOptimizationIgnored(context)) }

    var currentDisplayPath by remember { mutableStateOf(ArchiveDirectoryManager.getDisplayPath(context)) }
    val allLogs by app.database.changeLogDao().getAllLogs().collectAsStateWithLifecycle(initialValue = emptyList())

    var showClearAllDialog by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    var showLogsExpanded by remember { mutableStateOf(false) }

    val serviceRestartedMsg = stringResource(R.string.msg_service_restarted)
    val dataClearedMsg = stringResource(R.string.msg_data_cleared)
    val pathSavedMsg = stringResource(R.string.msg_archive_dir_saved)

    // Native Android Folder Picker launcher (Storage Access Framework)
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val ok = ArchiveDirectoryManager.setArchiveUri(context, uri)
            if (ok) {
                currentDisplayPath = ArchiveDirectoryManager.getDisplayPath(context)
                scope.launch {
                    snackbarHostState.showSnackbar(pathSavedMsg)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ReachabilityHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Language Selection Card
            SettingsGroupCard(
                icon = Icons.Default.Language,
                title = stringResource(R.string.section_language),
                description = stringResource(R.string.section_language_desc)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LanguageFlagButton(
                        code = "az",
                        label = "AZ",
                        flagResId = R.drawable.ic_flag_az,
                        isSelected = currentLanguage == "az",
                        onClick = {
                            scope.launch { LanguageManager.changeLanguageWithAnimation(context, "az") }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    LanguageFlagButton(
                        code = "en",
                        label = "EN",
                        flagResId = R.drawable.ic_flag_en,
                        isSelected = currentLanguage == "en",
                        onClick = {
                            scope.launch { LanguageManager.changeLanguageWithAnimation(context, "en") }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    LanguageFlagButton(
                        code = "ru",
                        label = "RU",
                        flagResId = R.drawable.ic_flag_ru,
                        isSelected = currentLanguage == "ru",
                        onClick = {
                            scope.launch { LanguageManager.changeLanguageWithAnimation(context, "ru") }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 2. Theme & Appearance Card
            SettingsGroupCard(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.theme_section_title),
                description = stringResource(R.string.theme_section_desc)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeOptionCard(
                        title = stringResource(R.string.theme_light),
                        description = stringResource(R.string.theme_light_desc),
                        icon = Icons.Default.LightMode,
                        isSelected = currentThemeMode == ThemeMode.LIGHT,
                        iconTint = Color(0xFFF59E0B),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                app.settingsManager.changeThemeWithAnimation(ThemeMode.LIGHT)
                            }
                        }
                    )
                    ThemeOptionCard(
                        title = stringResource(R.string.theme_dark),
                        description = stringResource(R.string.theme_dark_desc),
                        icon = Icons.Default.DarkMode,
                        isSelected = currentThemeMode == ThemeMode.DARK,
                        iconTint = Color(0xFF818CF8),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                app.settingsManager.changeThemeWithAnimation(ThemeMode.DARK)
                            }
                        }
                    )
                    ThemeOptionCard(
                        title = stringResource(R.string.theme_system),
                        description = stringResource(R.string.theme_system_desc),
                        icon = Icons.Default.BrightnessAuto,
                        isSelected = currentThemeMode == ThemeMode.SYSTEM,
                        iconTint = AccentBlue,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                app.settingsManager.changeThemeWithAnimation(ThemeMode.SYSTEM)
                            }
                        }
                    )
                }
            }

            // 3. Notification Settings Card
            SettingsGroupCard(
                icon = Icons.Default.NotificationsActive,
                title = stringResource(R.string.section_notifications),
                description = stringResource(R.string.section_notifications_desc),
                headerTrailing = {
                    IconButton(
                        onClick = { showNotifSettingsSheet = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.notif_status_bar_title),
                    description = stringResource(R.string.notif_status_bar_desc),
                    checked = statusBarNotifs,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.settingsManager.setStatusBarNotifications(it)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                SettingSwitchRow(
                    title = stringResource(R.string.notif_in_app_title),
                    description = stringResource(R.string.notif_in_app_desc),
                    checked = inAppNotifs,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.settingsManager.setInAppNotifications(it)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                SettingSwitchRow(
                    title = stringResource(R.string.notif_sound_title),
                    description = stringResource(R.string.notif_sound_desc),
                    checked = soundEnabled,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.settingsManager.setSoundEnabled(it)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                SettingSwitchRow(
                    title = stringResource(R.string.notif_vibration_title),
                    description = stringResource(R.string.notif_vibration_desc),
                    checked = vibrationEnabled,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.settingsManager.setVibrationEnabled(it)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showNotifSettingsSheet = true },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .bouncyClickable(pressScale = 0.95f) { showNotifSettingsSheet = true }
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.notif_categories_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            AppSettingsManager.openSystemNotificationSettings(context)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .bouncyClickable(pressScale = 0.95f) {
                                AppSettingsManager.openSystemNotificationSettings(context)
                            }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_open_system_notifs),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 2. Native Android Archive Folder Picker Card
            SettingsGroupCard(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.archive_dir_title),
                description = stringResource(R.string.archive_dir_desc)
            ) {
                // Path display pill
                val pathShape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(pathShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, pathShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = currentDisplayPath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { folderPicker.launch(null) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .bouncyClickable(pressScale = 0.94f) { folderPicker.launch(null) }
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_select_folder))
                    }

                    OutlinedButton(
                        onClick = {
                            ArchiveDirectoryManager.resetToDefault(context)
                            currentDisplayPath = ArchiveDirectoryManager.getDisplayPath(context)
                            scope.launch {
                                snackbarHostState.showSnackbar(pathSavedMsg)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.bouncyClickable(pressScale = 0.94f) {
                            ArchiveDirectoryManager.resetToDefault(context)
                            currentDisplayPath = ArchiveDirectoryManager.getDisplayPath(context)
                            scope.launch {
                                snackbarHostState.showSnackbar(pathSavedMsg)
                            }
                        }
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_reset_default))
                    }
                }
            }

            // 3. Audit & Inspection Logs Card
            SettingsGroupCard(
                icon = Icons.Default.History,
                title = stringResource(R.string.section_audit_logs),
                description = stringResource(R.string.section_audit_logs_desc),
                headerTrailing = {
                    Text(
                        text = stringResource(R.string.logs_count_format, allLogs.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showLogsExpanded = !showLogsExpanded },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .bouncyClickable(pressScale = 0.94f) { showLogsExpanded = !showLogsExpanded }
                    ) {
                        Icon(
                            imageVector = if (showLogsExpanded) Icons.Default.ExpandLess else Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (showLogsExpanded) stringResource(R.string.btn_hide_logs) else stringResource(R.string.btn_view_logs))
                    }

                    if (allLogs.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showClearLogsDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.bouncyClickable(pressScale = 0.94f) { showClearLogsDialog = true }
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }

                // Smooth Expandable Log Viewer Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
                ) {
                    if (showLogsExpanded) {
                        val logContainerShape = RoundedCornerShape(16.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(logContainerShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, logContainerShape)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (allLogs.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.empty_logs_text),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp)
                                )
                            } else {
                                allLogs.take(30).forEach { log ->
                                    SettingsLogItem(
                                        log = log,
                                        onDelete = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    app.database.changeLogDao().deleteLogById(log.id)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Battery Optimization Card
            SettingsGroupCard(
                icon = Icons.Default.BatteryChargingFull,
                title = stringResource(R.string.battery_opt_title),
                description = stringResource(R.string.battery_opt_desc),
                headerTrailing = {
                    if (isBatteryIgnored) {
                        StatusBadge(
                            text = stringResource(R.string.badge_unrestricted),
                            icon = Icons.Default.CheckCircle,
                            containerColor = StatusGreenLight,
                            contentColor = StatusGreen
                        )
                    } else {
                        StatusBadge(
                            text = stringResource(R.string.badge_restricted),
                            icon = Icons.Default.Warning,
                            containerColor = StatusAmberLight,
                            contentColor = StatusAmber
                        )
                    }
                }
            ) {
                if (isBatteryIgnored) {
                    OutlinedButton(
                        onClick = {
                            BatteryHelper.openBatteryOptimizationSettings(context)
                            isBatteryIgnored = BatteryHelper.isBatteryOptimizationIgnored(context)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .bouncyClickable(pressScale = 0.94f) {
                                BatteryHelper.openBatteryOptimizationSettings(context)
                                isBatteryIgnored = BatteryHelper.isBatteryOptimizationIgnored(context)
                            }
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = StatusGreen)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_battery_opt_active))
                    }
                } else {
                    Button(
                        onClick = {
                            BatteryHelper.requestIgnoreBatteryOptimization(context)
                            isBatteryIgnored = BatteryHelper.isBatteryOptimizationIgnored(context)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .bouncyClickable(pressScale = 0.94f) {
                                BatteryHelper.requestIgnoreBatteryOptimization(context)
                                isBatteryIgnored = BatteryHelper.isBatteryOptimizationIgnored(context)
                            }
                    ) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_request_battery_opt))
                    }
                }
            }

            // 5. Foreground Service Card
            SettingsGroupCard(
                icon = Icons.Default.Sync,
                title = stringResource(R.string.service_card_title),
                description = stringResource(R.string.service_card_desc)
            ) {
                OutlinedButton(
                    onClick = {
                        InstaLoggerForegroundService.start(context)
                        scope.launch {
                            snackbarHostState.showSnackbar(serviceRestartedMsg)
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.bouncyClickable(pressScale = 0.94f) {
                        InstaLoggerForegroundService.start(context)
                        scope.launch {
                            snackbarHostState.showSnackbar(serviceRestartedMsg)
                        }
                    }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_restart_service))
                }
            }

            // 6. Danger Zone Card (Clear All Data)
            SettingsGroupCard(
                icon = Icons.Default.Warning,
                title = stringResource(R.string.danger_zone_title),
                description = stringResource(R.string.danger_zone_desc)
            ) {
                OutlinedButton(
                    onClick = { showClearAllDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                    modifier = Modifier.bouncyClickable(pressScale = 0.94f) { showClearAllDialog = true }
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_clear_all_data))
                }
            }
        }
    }

    // Confirmation dialog: Clear all logs
    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogsDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_clear_logs_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_clear_logs_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearLogsDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                app.database.changeLogDao().clearAll()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearLogsDialog = false },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = RoundedCornerShape(26.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    // Confirmation dialog: Clear all local data
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.danger_zone_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.danger_zone_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                app.database.targetDao().clearAll()
                                app.database.changeLogDao().clearAll()
                                app.database.avatarHistoryDao().clearAll()
                                app.database.notificationDao().clearAll()
                            }
                            snackbarHostState.showSnackbar(dataClearedMsg)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearAllDialog = false },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = RoundedCornerShape(26.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    if (showNotifSettingsSheet) {
        NotificationSettingsBottomSheet(onDismissRequest = { showNotifSettingsSheet = false })
    }
}

/**
 * Modern squircle grouped container for settings sections.
 */
@Composable
private fun SettingsGroupCard(
    icon: ImageVector,
    title: String,
    description: String? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardShape = ShapeTokens.LargeShape
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, cardShape),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    val iconPodShape = ShapeTokens.SmallShape
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(iconPodShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, iconPodShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (headerTrailing != null) {
                    headerTrailing()
                }
            }

            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            content()
        }
    }
}

@Composable
private fun LanguageFlagButton(
    code: String,
    label: String,
    flagResId: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (isSelected) AccentBlue else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (isSelected) AccentBlue.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(if (isSelected) 1.5.dp else 0.75.dp, borderColor, shape)
            .bouncyClickable(pressScale = 0.92f, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(flagResId),
                contentDescription = label,
                modifier = Modifier
                    .size(width = 28.dp, height = 21.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SettingsLogItem(
    log: ChangeLogEntity,
    onDelete: () -> Unit
) {
    val timeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(log.timestamp))

    val itemShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(itemShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, itemShape)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "@${log.targetUsername}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${log.oldValue ?: ""} -> ${log.newValue ?: ""}".trim().ifEmpty { log.title },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val closeShape = ShapeTokens.SmallShape
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                .bouncyClickable(pressScale = 0.85f, onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(closeShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    iconTint: Color,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) AccentBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val cardBackground = if (isSelected) {
        AccentBlue.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .bouncyClickable(pressScale = 0.96f, onClick = onClick)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(iconTint.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconTint,
                        modifier = Modifier.size(21.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
