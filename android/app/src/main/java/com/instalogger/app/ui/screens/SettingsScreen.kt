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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.ChangeLogEntity
import com.instalogger.app.engine.media.ProviderHealthTracker
import com.instalogger.app.engine.media.YtDlpEngineState
import com.instalogger.app.engine.media.YtDlpQualityPreset
import com.instalogger.app.engine.media.YtDlpUpdateChannel
import com.instalogger.app.service.BatteryHelper
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.settings.AppSettingsManager
import com.instalogger.app.settings.ArchiverSpeedPreset
import com.instalogger.app.settings.ThemeMode
import com.instalogger.app.ui.animation.MotionSpecs
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.NotificationSettingsBottomSheet
import com.instalogger.app.ui.components.ReachabilityHeader
import com.instalogger.app.ui.components.SettingSwitchRow
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.theme.*
import com.instalogger.app.util.AppLockManager
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.ArchiveDirectoryManager
import com.instalogger.app.util.LanguageManager
import com.instalogger.app.util.LogLevel
import com.instalogger.app.util.VaultStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsScreen(
    onOpenDeveloperLogs: () -> Unit = {}
) {
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
    val daemonSyncEnabled by app.settingsManager.daemonSyncEnabled.collectAsStateWithLifecycle()
    val daemonHostUrl by app.settingsManager.daemonHostUrl.collectAsStateWithLifecycle()
    val ytDlpEngineState by app.ytDlpProvider.engineState.collectAsStateWithLifecycle()
    val ytDlpAutoDownloadVideos by app.settingsManager.ytDlpAutoDownloadVideos.collectAsStateWithLifecycle()
    val ytDlpWifiOnly by app.settingsManager.ytDlpWifiOnly.collectAsStateWithLifecycle()
    val ytDlpQualityPreset by app.settingsManager.ytDlpQualityPreset.collectAsStateWithLifecycle()
    val ytDlpUpdateChannel by app.settingsManager.ytDlpUpdateChannel.collectAsStateWithLifecycle()
    val archiverSpeedPreset by app.settingsManager.archiverSpeedPreset.collectAsStateWithLifecycle()
    val keepAwakeEnabled by app.settingsManager.keepAwakeEnabled.collectAsStateWithLifecycle()
    val autoArchivePendingEnabled by app.settingsManager.autoArchivePendingEnabled.collectAsStateWithLifecycle()
    val maxPostsSyncLimit by app.settingsManager.maxPostsSyncLimit.collectAsStateWithLifecycle()
    val activeDownloadCount by app.mediaDownloadOrchestrator.activeDownloadCount.collectAsStateWithLifecycle()
    val queuedDownloadCount by app.mediaDownloadOrchestrator.queuedDownloadCount.collectAsStateWithLifecycle()
    val providerHealthList by app.providerHealthTracker.healthFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var isUpdatingYtDlp by remember { mutableStateOf(false) }
    var isTestingDaemon by remember { mutableStateOf(false) }
    var daemonPingStatus by remember { mutableStateOf<Boolean?>(null) }

    var showNotifSettingsSheet by remember { mutableStateOf(false) }
    var isBatteryIgnored by remember { mutableStateOf(BatteryHelper.isBatteryOptimizationIgnored(context)) }

    var currentDisplayPath by remember { mutableStateOf(ArchiveDirectoryManager.getDisplayPath(context)) }
    val allLogs by app.database.changeLogDao().getAllLogs().collectAsStateWithLifecycle(initialValue = emptyList())

    var showClearAllDialog by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    var showLogsExpanded by remember { mutableStateOf(false) }

    val appLockManager = app.appLockManager
    val isBiometricEnabled by appLockManager.isBiometricEnabled.collectAsStateWithLifecycle()
    val isSecureScreenEnabled by appLockManager.isSecureScreenEnabled.collectAsStateWithLifecycle()
    val autoLockTimeout by appLockManager.autoLockTimeoutSeconds.collectAsStateWithLifecycle()
    var isLockConfigured by remember { mutableStateOf(appLockManager.isLockConfigured()) }

    var showSetPinDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showDisableLockDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }

    val pinSetSuccessMsg = stringResource(R.string.lock_pin_set_success)
    val pinDisabledSuccessMsg = stringResource(R.string.lock_disabled_success)
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
                            LanguageManager.setLanguage(context, "az")
                        },
                        modifier = Modifier.weight(1f)
                    )
                    LanguageFlagButton(
                        code = "en",
                        label = "EN",
                        flagResId = R.drawable.ic_flag_en,
                        isSelected = currentLanguage == "en",
                        onClick = {
                            LanguageManager.setLanguage(context, "en")
                        },
                        modifier = Modifier.weight(1f)
                    )
                    LanguageFlagButton(
                        code = "ru",
                        label = "RU",
                        flagResId = R.drawable.ic_flag_ru,
                        isSelected = currentLanguage == "ru",
                        onClick = {
                            LanguageManager.setLanguage(context, "ru")
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

            // 3. Security & App Lock Card
            SettingsGroupCard(
                icon = Icons.Default.Security,
                title = stringResource(R.string.sec_card_title),
                description = stringResource(R.string.sec_card_desc)
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.sec_app_lock),
                    description = stringResource(R.string.sec_app_lock_desc),
                    checked = isLockConfigured,
                    onCheckedChange = { enable ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (enable) {
                            showSetPinDialog = true
                        } else {
                            showDisableLockDialog = true
                        }
                    }
                )

                if (isLockConfigured) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // Change PIN button row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .bouncyClickable(pressScale = 0.96f) { showChangePinDialog = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.sec_change_pin),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    SettingSwitchRow(
                        title = stringResource(R.string.sec_biometric),
                        description = stringResource(R.string.sec_biometric_desc),
                        checked = isBiometricEnabled,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            appLockManager.setBiometricEnabled(it)
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    SettingSwitchRow(
                        title = stringResource(R.string.sec_secure_screen),
                        description = stringResource(R.string.sec_secure_screen_desc),
                        checked = isSecureScreenEnabled,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            appLockManager.setSecureScreenEnabled(it)
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    val timeoutLabel = when (autoLockTimeout) {
                        0 -> stringResource(R.string.autolock_immediately)
                        15 -> stringResource(R.string.autolock_15s)
                        30 -> stringResource(R.string.autolock_30s)
                        60 -> stringResource(R.string.autolock_1m)
                        300 -> stringResource(R.string.autolock_5m)
                        else -> "${autoLockTimeout}s"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .bouncyClickable(pressScale = 0.96f) { showTimeoutDialog = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.sec_autolock),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.sec_autolock_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = timeoutLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 4. Notification Settings Card
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
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier.fillMaxWidth()
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
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier.fillMaxWidth()
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
                val pathShape = ShapeTokens.MediumShape
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(pathShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, pathShape)
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
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier.weight(1f)
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
                        shape = ShapeTokens.MediumShape
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
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier.weight(1f)
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
                            shape = ShapeTokens.MediumShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
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
                        val logContainerShape = ShapeTokens.MediumShape
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(logContainerShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, logContainerShape)
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
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier.fillMaxWidth()
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
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_request_battery_opt))
                    }
                }
            }

            // 5. Archiver Speed & Parallel Background Engine Card
            SettingsGroupCard(
                icon = Icons.Default.Speed,
                title = stringResource(R.string.section_speed_title),
                description = stringResource(R.string.section_speed_desc),
                headerTrailing = {
                    StatusBadge(
                        text = if (activeDownloadCount > 0 || queuedDownloadCount > 0) {
                            "${archiverSpeedPreset.downloadWorkers}x · $activeDownloadCount/$queuedDownloadCount"
                        } else {
                            "${archiverSpeedPreset.downloadWorkers}x · ${archiverSpeedPreset.targetWorkers}T"
                        },
                        icon = Icons.Default.Bolt,
                        containerColor = AccentBlue.copy(alpha = 0.14f),
                        contentColor = AccentBlue
                    )
                }
            ) {
                Text(
                    text = stringResource(R.string.speed_preset_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val speedOptions = listOf(
                        ArchiverSpeedPreset.ECO to stringResource(R.string.speed_preset_eco),
                        ArchiverSpeedPreset.BALANCED to stringResource(R.string.speed_preset_balanced),
                        ArchiverSpeedPreset.FAST to stringResource(R.string.speed_preset_fast),
                        ArchiverSpeedPreset.TURBO to stringResource(R.string.speed_preset_turbo)
                    )
                    speedOptions.forEach { (preset, label) ->
                        val selected = archiverSpeedPreset == preset
                        val chipShape = RoundedCornerShape(12.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 42.dp)
                                .clip(chipShape)
                                .background(
                                    if (selected) AccentBlue.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .border(
                                    width = if (selected) 1.25.dp else 0.75.dp,
                                    color = if (selected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
                                    shape = chipShape
                                )
                                .bouncyClickable(pressScale = 0.94f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    app.settingsManager.setArchiverSpeedPreset(preset)
                                }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${preset.downloadWorkers}x",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )

                Text(
                    text = stringResource(R.string.max_posts_limit_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val limitOptions = listOf(
                        100 to "100",
                        300 to "300",
                        500 to "500",
                        1000 to "1000",
                        0 to stringResource(R.string.max_posts_limit_all)
                    )
                    limitOptions.forEach { (limitVal, label) ->
                        val selected = maxPostsSyncLimit == limitVal
                        val chipShape = RoundedCornerShape(10.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp)
                                .clip(chipShape)
                                .background(
                                    if (selected) AccentBlue.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .border(
                                    width = if (selected) 1.25.dp else 0.75.dp,
                                    color = if (selected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
                                    shape = chipShape
                                )
                                .bouncyClickable(pressScale = 0.94f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    app.settingsManager.setMaxPostsSyncLimit(limitVal)
                                }
                                .padding(horizontal = 2.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )

                SettingSwitchRow(
                    title = stringResource(R.string.keep_awake_title),
                    description = stringResource(R.string.keep_awake_desc),
                    checked = keepAwakeEnabled,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.settingsManager.setKeepAwakeEnabled(it)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )

                SettingSwitchRow(
                    title = stringResource(R.string.auto_archive_pending_title),
                    description = stringResource(R.string.auto_archive_pending_desc),
                    checked = autoArchivePendingEnabled,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.settingsManager.setAutoArchivePendingEnabled(it)
                    }
                )
            }

            // 5.2 Foreground Service Card
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
                    shape = ShapeTokens.MediumShape
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_restart_service))
                }
            }

            // 5.5 Media Engines & yt-dlp Provider Hub Card
            SettingsGroupCard(
                icon = Icons.Default.Movie,
                title = stringResource(R.string.section_media_engine_title),
                description = stringResource(R.string.section_media_engine_desc),
                headerTrailing = {
                    when (val st = ytDlpEngineState) {
                        is YtDlpEngineState.Ready -> StatusBadge(
                            text = stringResource(R.string.ytdlp_status_ready, st.version),
                            icon = Icons.Default.CheckCircle,
                            containerColor = StatusGreenLight,
                            contentColor = StatusGreen
                        )
                        is YtDlpEngineState.Updating -> StatusBadge(
                            text = stringResource(R.string.ytdlp_status_updating),
                            icon = Icons.Default.Sync,
                            containerColor = AccentBlue.copy(alpha = 0.14f),
                            contentColor = AccentBlue
                        )
                        is YtDlpEngineState.Initializing, is YtDlpEngineState.Uninitialized -> StatusBadge(
                            text = stringResource(R.string.ytdlp_status_init),
                            icon = Icons.Default.Sync,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        is YtDlpEngineState.Failed -> StatusBadge(
                            text = stringResource(R.string.ytdlp_status_error),
                            icon = Icons.Default.Warning,
                            containerColor = StatusRed.copy(alpha = 0.14f),
                            contentColor = StatusRed
                        )
                    }
                }
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.ytdlp_auto_download_title),
                    description = stringResource(R.string.ytdlp_auto_download_desc),
                    checked = ytDlpAutoDownloadVideos,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.settingsManager.setYtDlpAutoDownloadVideos(it)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )

                SettingSwitchRow(
                    title = stringResource(R.string.ytdlp_wifi_only_title),
                    description = stringResource(R.string.ytdlp_wifi_only_desc),
                    checked = ytDlpWifiOnly,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.settingsManager.setYtDlpWifiOnly(it)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )

                // Quality Preset Selector
                Text(
                    text = stringResource(R.string.ytdlp_quality_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val qualityOptions = listOf(
                        YtDlpQualityPreset.BEST to stringResource(R.string.ytdlp_quality_best),
                        YtDlpQualityPreset.HD_1080 to stringResource(R.string.ytdlp_quality_1080),
                        YtDlpQualityPreset.HD_720 to stringResource(R.string.ytdlp_quality_720),
                        YtDlpQualityPreset.DATA_SAVER_480 to stringResource(R.string.ytdlp_quality_480)
                    )
                    qualityOptions.forEach { (preset, label) ->
                        val selected = ytDlpQualityPreset == preset
                        val chipShape = RoundedCornerShape(12.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .clip(chipShape)
                                .background(
                                    if (selected) AccentBlue.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .border(
                                    width = if (selected) 1.25.dp else 0.75.dp,
                                    color = if (selected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
                                    shape = chipShape
                                )
                                .bouncyClickable(pressScale = 0.94f) {
                                    app.settingsManager.setYtDlpQualityPreset(preset)
                                }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Update Channel + Runtime Self-Updater
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        YtDlpUpdateChannel.entries.forEach { ch ->
                            val selected = ytDlpUpdateChannel == ch
                            FilterChip(
                                selected = selected,
                                onClick = { app.settingsManager.setYtDlpUpdateChannel(ch) },
                                label = {
                                    Text(
                                        text = ch.key,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (!isUpdatingYtDlp) {
                                scope.launch {
                                    isUpdatingYtDlp = true
                                    val result = app.ytDlpProvider.updateBinary(ytDlpUpdateChannel)
                                    isUpdatingYtDlp = false
                                    val msg = result.fold(
                                        onSuccess = { context.getString(R.string.msg_ytdlp_updated, it) },
                                        onFailure = { context.getString(R.string.msg_ytdlp_update_failed, it.message ?: "") }
                                    )
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        },
                        enabled = !isUpdatingYtDlp && ytDlpEngineState !is YtDlpEngineState.Updating,
                        shape = ShapeTokens.MediumShape
                    ) {
                        if (isUpdatingYtDlp || ytDlpEngineState is YtDlpEngineState.Updating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        } else {
                            Icon(Icons.Default.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(stringResource(R.string.btn_update_ytdlp))
                    }
                }

                // Provider Health Pills (Circuit Breaker Telemetry)
                if (providerHealthList.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                    )
                    Text(
                        text = stringResource(R.string.provider_health_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        providerHealthList.forEach { health ->
                            val isHealthy = health.status == ProviderHealthTracker.STATUS_HEALTHY
                            val isCooldown = health.status == ProviderHealthTracker.STATUS_RATE_LIMITED
                            val badgeColor = when {
                                isHealthy -> StatusGreen
                                isCooldown -> Color(0xFFF59E0B)
                                else -> StatusRed
                            }
                            val statusText = when {
                                isHealthy -> stringResource(R.string.provider_status_healthy)
                                isCooldown -> stringResource(R.string.provider_status_rate_limited)
                                else -> stringResource(R.string.provider_status_degraded)
                            }
                            Surface(
                                shape = ShapeTokens.PillShape,
                                color = badgeColor.copy(alpha = 0.14f),
                                modifier = Modifier.bouncyClickable(pressScale = 0.94f) {
                                    if (!isHealthy) {
                                        scope.launch {
                                            app.providerHealthTracker.resetProvider(health.providerId)
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    text = "${health.providerId}: $statusText",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 6. Termux & Daemon Sync Card (Optional integration)
            SettingsGroupCard(
                icon = Icons.Default.Dns,
                title = stringResource(R.string.section_daemon_title),
                description = stringResource(R.string.section_daemon_desc),
                headerTrailing = {
                    if (daemonSyncEnabled) {
                        StatusBadge(
                            text = if (daemonPingStatus == true) stringResource(R.string.daemon_status_connected) else stringResource(R.string.badge_active),
                            icon = if (daemonPingStatus == true) Icons.Default.CheckCircle else Icons.Default.Sync,
                            containerColor = StatusGreenLight,
                            contentColor = StatusGreen
                        )
                    } else {
                        StatusBadge(
                            text = stringResource(R.string.daemon_status_disconnected),
                            icon = Icons.Default.CloudOff,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.daemon_sync_enabled),
                    description = stringResource(R.string.daemon_sync_desc),
                    checked = daemonSyncEnabled,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.settingsManager.setDaemonSyncEnabled(it)
                    }
                )

                if (daemonSyncEnabled) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    OutlinedTextField(
                        value = daemonHostUrl,
                        onValueChange = { app.settingsManager.setDaemonHostUrl(it) },
                        label = { Text(stringResource(R.string.daemon_host_title)) },
                        singleLine = true,
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isTestingDaemon = true
                                    val ok = withContext(Dispatchers.IO) {
                                        try {
                                            val client = okhttp3.OkHttpClient.Builder()
                                                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                                .build()
                                            val req = okhttp3.Request.Builder().url("$daemonHostUrl/api/targets").get().build()
                                            client.newCall(req).execute().use { it.isSuccessful }
                                        } catch (_: Exception) {
                                            false
                                        }
                                    }
                                    daemonPingStatus = ok
                                    isTestingDaemon = false
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.msg_daemon_connected) else context.getString(R.string.msg_daemon_unreachable)
                                    )
                                }
                            },
                            shape = ShapeTokens.MediumShape,
                            enabled = !isTestingDaemon
                        ) {
                            if (isTestingDaemon) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(stringResource(R.string.btn_ping_daemon))
                        }
                    }
                }
            }

            // 6.5. Developer Logs (Encrypted Terminal Console) Card
            val devLogs by AppLogger.logs.collectAsStateWithLifecycle()
            val devErrCount = remember(devLogs) { devLogs.count { it.level == LogLevel.ERROR } }
            val devWarnCount = remember(devLogs) { devLogs.count { it.level == LogLevel.WARN } }

            SettingsGroupCard(
                icon = Icons.Default.Terminal,
                title = stringResource(R.string.dev_logs_title),
                description = stringResource(R.string.dev_logs_desc),
                headerTrailing = {
                    if (devErrCount > 0) {
                        StatusBadge(
                            text = "ERR: $devErrCount",
                            containerColor = StatusRedLight,
                            contentColor = StatusRed
                        )
                    } else if (devWarnCount > 0) {
                        StatusBadge(
                            text = "WARN: $devWarnCount",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        StatusBadge(
                            text = "${devLogs.size}",
                            containerColor = AccentBlue.copy(alpha = 0.14f),
                            contentColor = AccentBlue
                        )
                    }
                }
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenDeveloperLogs()
                    },
                    shape = ShapeTokens.MediumShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dev_logs_open_btn))
                }
            }

            // 7. Danger Zone Card (Clear All Data)
            SettingsGroupCard(
                icon = Icons.Default.Warning,
                title = stringResource(R.string.danger_zone_title),
                description = stringResource(R.string.danger_zone_desc)
            ) {
                OutlinedButton(
                    onClick = { showClearAllDialog = true },
                    shape = ShapeTokens.MediumShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed)
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
                                app.database.postDao().clearAll()
                                app.database.highlightDao().clearAll()
                                app.database.highlightDao().clearAllMedia()
                                app.database.followerAuditDao().clearAll()
                                app.database.followerHistoryDao().clearAll()
                                app.database.mediaAssetDao().clearAll()
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

    if (showSetPinDialog) {
        PinSetupDialog(
            onDismiss = { showSetPinDialog = false },
            onPinConfirmed = { newPin ->
                appLockManager.setPin(newPin)
                isLockConfigured = true
                showSetPinDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar(pinSetSuccessMsg)
                }
            }
        )
    }

    if (showChangePinDialog) {
        PinVerifyDialog(
            title = stringResource(R.string.sec_change_pin),
            appLockManager = appLockManager,
            onDismiss = { showChangePinDialog = false },
            onVerified = {
                showChangePinDialog = false
                showSetPinDialog = true
            }
        )
    }

    if (showDisableLockDialog) {
        PinVerifyDialog(
            title = stringResource(R.string.sec_app_lock),
            appLockManager = appLockManager,
            onDismiss = { showDisableLockDialog = false },
            onVerified = {
                appLockManager.removeLock()
                isLockConfigured = false
                showDisableLockDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar(pinDisabledSuccessMsg)
                }
            }
        )
    }

    if (showTimeoutDialog) {
        AutoLockTimeoutDialog(
            currentTimeout = autoLockTimeout,
            onSelect = { seconds ->
                appLockManager.setAutoLockTimeoutSeconds(seconds)
                showTimeoutDialog = false
            },
            onDismiss = { showTimeoutDialog = false }
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
            .heightIn(min = 48.dp)
            .testTag("language_button_$code")
            .semantics { selected = isSelected }
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

@Composable
private fun PinSetupDialog(
    onDismiss: () -> Unit,
    onPinConfirmed: (String) -> Unit
) {
    var step by remember { mutableStateOf(0) } // 0 = enter pin, 1 = confirm pin
    var firstPin by remember { mutableStateOf("") }
    var secondPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    val currentPin = if (step == 0) firstPin else secondPin
    val title = if (step == 0) stringResource(R.string.lock_enter_pin_title) else stringResource(R.string.lock_confirm_pin_title)
    val subtitle = if (step == 0) stringResource(R.string.lock_enter_pin_desc) else stringResource(R.string.lock_confirm_pin_title)
    val mismatchError = stringResource(R.string.lock_pin_mismatch)

    fun onNumber(digit: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        errorMessage = null
        if (step == 0) {
            if (firstPin.length < 4) {
                val next = firstPin + digit
                firstPin = next
                if (next.length == 4) {
                    step = 1
                }
            }
        } else {
            if (secondPin.length < 4) {
                val next = secondPin + digit
                secondPin = next
                if (next.length == 4) {
                    if (next == firstPin) {
                        onPinConfirmed(next)
                    } else {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        errorMessage = mismatchError
                        firstPin = ""
                        secondPin = ""
                        step = 0
                    }
                }
            }
        }
    }

    fun onBackspace() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        errorMessage = null
        if (step == 0) {
            if (firstPin.isNotEmpty()) firstPin = firstPin.dropLast(1)
        } else {
            if (secondPin.isNotEmpty()) secondPin = secondPin.dropLast(1) else step = 0
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = errorMessage ?: subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // 4 PIN Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    for (i in 0 until 4) {
                        val isFilled = i < currentPin.length
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (isFilled) AccentBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                        )
                    }
                }

                // Numeric Keypad
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("", "0", "DEL")
                    )
                    for (row in rows) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (cell in row) {
                                when (cell) {
                                    "" -> Spacer(modifier = Modifier.weight(1f))
                                    "DEL" -> {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(50.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .bouncyClickable(pressScale = 0.92f) { onBackspace() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Backspace,
                                                contentDescription = "Backspace",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    else -> {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(50.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                .bouncyClickable(pressScale = 0.92f) { onNumber(cell) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = cell,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
private fun PinVerifyDialog(
    title: String,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    appLockManager: AppLockManager
) {
    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    fun onDigit(d: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        isError = false
        if (enteredPin.length < 4) {
            val next = enteredPin + d
            enteredPin = next
            if (next.length == 4) {
                if (appLockManager.verifyPin(next)) {
                    onVerified()
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isError = true
                    enteredPin = ""
                }
            }
        }
    }

    fun onBack() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isError) stringResource(R.string.lock_pin_incorrect) else stringResource(R.string.lock_enter_pin_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // 4 PIN Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    for (i in 0 until 4) {
                        val isFilled = i < enteredPin.length
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (isFilled) AccentBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                        )
                    }
                }

                // Numeric Keypad
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("", "0", "DEL")
                    )
                    for (row in rows) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (cell in row) {
                                when (cell) {
                                    "" -> Spacer(modifier = Modifier.weight(1f))
                                    "DEL" -> {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(50.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .bouncyClickable(pressScale = 0.92f) { onBack() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Backspace,
                                                contentDescription = "Backspace",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    else -> {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(50.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                .bouncyClickable(pressScale = 0.92f) { onDigit(cell) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = cell,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
private fun AutoLockTimeoutDialog(
    currentTimeout: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        0 to stringResource(R.string.autolock_immediately),
        15 to stringResource(R.string.autolock_15s),
        30 to stringResource(R.string.autolock_30s),
        60 to stringResource(R.string.autolock_1m),
        300 to stringResource(R.string.autolock_5m)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.sec_autolock), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (seconds, label) ->
                    val isSelected = currentTimeout == seconds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) AccentBlue.copy(alpha = 0.12f) else Color.Transparent)
                            .bouncyClickable(pressScale = 0.96f) { onSelect(seconds) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface
                        )
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        }
    )
}
