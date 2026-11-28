package com.instalogger.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.instalogger.app.settings.ArchiverSpeedPreset
import com.instalogger.app.settings.ThemeMode
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.AnimatedSegmentedSelector
import com.instalogger.app.ui.components.BackupExportImportBottomSheet
import com.instalogger.app.ui.components.DataManagementBottomSheet
import com.instalogger.app.ui.components.NotificationSettingsBottomSheet
import com.instalogger.app.ui.components.ReachabilityHeader
import com.instalogger.app.ui.components.SegmentedOption
import com.instalogger.app.ui.components.SettingSwitchRow
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.components.ThemeAppearanceBottomSheet
import com.instalogger.app.ui.theme.*
import com.instalogger.app.update.AppUpdateChannel
import com.instalogger.app.update.UpdateUiState
import com.instalogger.app.util.AppLockManager
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.ArchiveDirectoryManager
import com.instalogger.app.util.DeviceStorageSnapshot
import com.instalogger.app.util.LanguageManager
import com.instalogger.app.util.LogLevel
import com.instalogger.app.util.PinHapticHelper
import com.instalogger.app.util.StorageCacheManager
import com.instalogger.app.util.StorageCategoryMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsScreen(
    isActiveTab: Boolean = true,
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
    val sessionWebViewAutoPreview by app.settingsManager.sessionWebViewAutoPreview.collectAsStateWithLifecycle()
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
    val autoCheckUpdates by app.settingsManager.autoCheckUpdates.collectAsStateWithLifecycle()
    val updateWifiOnly by app.settingsManager.updateWifiOnly.collectAsStateWithLifecycle()
    val updateAutoDownloadWifi by app.settingsManager.updateAutoDownloadWifi.collectAsStateWithLifecycle()
    val updateChannel by app.settingsManager.updateChannel.collectAsStateWithLifecycle()
    val updateState by app.updateManager.state.collectAsStateWithLifecycle()
    val availableUpdate by app.updateManager.availableRelease.collectAsStateWithLifecycle()
    val lastUpdateCheckMs by app.updateManager.lastCheckedAtMs.collectAsStateWithLifecycle()
    val providerHealthList by app.providerHealthTracker.healthFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allLogs by app.database.changeLogDao().getAllLogs().collectAsStateWithLifecycle(initialValue = emptyList())
    val devLogs by AppLogger.logs.collectAsStateWithLifecycle()

    val appLockManager = app.appLockManager
    val isBiometricEnabled by appLockManager.isBiometricEnabled.collectAsStateWithLifecycle()
    val isSecureScreenEnabled by appLockManager.isSecureScreenEnabled.collectAsStateWithLifecycle()
    val autoLockTimeout by appLockManager.autoLockTimeoutSeconds.collectAsStateWithLifecycle()
    var isLockConfigured by remember { mutableStateOf(appLockManager.isLockConfigured()) }

    var showThemeSheet by remember { mutableStateOf(false) }
    var showNotifSettingsSheet by remember { mutableStateOf(false) }
    var showDataManagementSheet by remember { mutableStateOf(false) }
    var showBackupExportImportSheet by remember { mutableStateOf(false) }
    var showSecurityExpanded by remember { mutableStateOf(false) }
    var showEngineExpanded by remember { mutableStateOf(false) }
    var showUpdateExpanded by remember { mutableStateOf(false) }
    var showArchiveExpanded by remember { mutableStateOf(false) }
    var showLogsExpanded by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }

    var showSetPinDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showDisableLockDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }

    var isUpdatingYtDlp by remember { mutableStateOf(false) }
    var isTestingDaemon by remember { mutableStateOf(false) }
    var daemonPingStatus by remember { mutableStateOf<Boolean?>(null) }

    var storageSnapshot by remember { mutableStateOf(CachedStorageSnapshotHolder.lastSnapshot) }
    LaunchedEffect(Unit) {
        val fresh = runCatching {
            withContext(Dispatchers.IO) { StorageCacheManager.analyzeStorage(context) }
        }.getOrNull()
        if (fresh != null) {
            CachedStorageSnapshotHolder.lastSnapshot = fresh
            storageSnapshot = fresh
        }
    }

    var isBatteryIgnored by remember { mutableStateOf(BatteryHelper.isBatteryOptimizationIgnored(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isBatteryIgnored = BatteryHelper.isBatteryOptimizationIgnored(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var currentDisplayPath by remember { mutableStateOf(ArchiveDirectoryManager.getDisplayPath(context)) }

    val pinSetSuccessMsg = stringResource(R.string.lock_pin_set_success)
    val pinDisabledSuccessMsg = stringResource(R.string.lock_disabled_success)
    val serviceRestartedMsg = stringResource(R.string.msg_service_restarted)
    val pathSavedMsg = stringResource(R.string.msg_archive_dir_saved)
    val pathCopiedMsg = stringResource(R.string.archive_path_copied)

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val ok = ArchiveDirectoryManager.setArchiveUri(context, uri)
            if (ok) {
                currentDisplayPath = ArchiveDirectoryManager.getDisplayPath(context)
                scope.launch { snackbarHostState.showSnackbar(pathSavedMsg) }
            }
        }
    }

    val themeLabel = when (currentThemeMode) {
        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
        ThemeMode.DARK -> stringResource(R.string.theme_dark)
        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    }
    val devErrCount = remember(devLogs) { devLogs.count { it.level == LogLevel.ERROR } }

    Scaffold(
        topBar = {
            ReachabilityHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
                logoResId = R.drawable.ic_header_settings
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "sec_appearance") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader(text = stringResource(R.string.settings_hub_sec_appearance))

                    SettingsHubCard {
                        SettingsHubRow(
                            icon = Icons.Default.Palette,
                            iconTint = Color(0xFF818CF8),
                            title = stringResource(R.string.theme_section_title),
                            subtitle = stringResource(R.string.settings_hub_theme_row_sub),
                            badgeText = themeLabel,
                            badgeColor = AccentBlue,
                            onClick = { showThemeSheet = true }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 16.dp),
                            thickness = 0.7.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SettingsIconPod(icon = Icons.Default.Language, tint = AccentBlue)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.section_language),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.section_language_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    }
                }
            }

            item(key = "sec_security") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader(text = stringResource(R.string.settings_hub_sec_security))

                    SettingsHubCard {
                        SettingsHubRow(
                            icon = Icons.Default.NotificationsActive,
                            iconTint = StatusAmber,
                            title = stringResource(R.string.section_notifications),
                            subtitle = stringResource(R.string.section_notifications_desc),
                            badgeText = if (statusBarNotifs) stringResource(R.string.settings_hub_security_badge_on) else stringResource(R.string.settings_hub_security_badge_off),
                            badgeColor = if (statusBarNotifs) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { showNotifSettingsSheet = true }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 16.dp),
                            thickness = 0.7.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )

                        // App Lock & Security Expandable Hub Row
                        SettingsHubExpandableRow(
                            icon = Icons.Default.Security,
                            iconTint = StatusGreen,
                            title = stringResource(R.string.sec_card_title),
                            subtitle = stringResource(R.string.sec_card_desc),
                            badgeText = if (isLockConfigured) stringResource(R.string.settings_hub_security_badge_on) else stringResource(R.string.settings_hub_security_badge_off),
                            badgeColor = if (isLockConfigured) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            expanded = showSecurityExpanded,
                            onToggle = { showSecurityExpanded = !showSecurityExpanded }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SettingSwitchRow(
                                    title = stringResource(R.string.sec_app_lock),
                                    description = stringResource(R.string.sec_app_lock_desc),
                                    checked = isLockConfigured,
                                    onCheckedChange = { enable ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (enable) showSetPinDialog = true else showDisableLockDialog = true
                                    }
                                )

                                AnimatedVisibility(
                                    visible = isLockConfigured,
                                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                                    exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

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
                                                Icon(Icons.Default.Pin, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(19.dp))
                                                Text(
                                                    text = stringResource(R.string.sec_change_pin),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }

                                        SettingSwitchRow(
                                            title = stringResource(R.string.sec_biometric),
                                            description = stringResource(R.string.sec_biometric_desc),
                                            checked = isBiometricEnabled,
                                            onCheckedChange = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                appLockManager.setBiometricEnabled(it)
                                            }
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
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = timeoutLabel,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = AccentBlue
                                                )
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 16.dp),
                            thickness = 0.7.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    app.settingsManager.setSessionWebViewAutoPreview(!sessionWebViewAutoPreview)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingsIconPod(icon = Icons.Default.WebAsset, tint = AccentBlue)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.session_webview_preview_setting_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.session_webview_preview_setting_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = sessionWebViewAutoPreview,
                                onCheckedChange = { enabled ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    app.settingsManager.setSessionWebViewAutoPreview(enabled)
                                },
                                modifier = Modifier.testTag("settings_session_webview_preview_switch")
                            )
                        }
                    }
                }
            }

            item(key = "sec_storage") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader(text = stringResource(R.string.settings_hub_sec_storage))

                    SettingsHubCard {
                        // Data & Storage Management Row (TourJet style with live MB + mini segment bar)
                        val storageBadge = storageSnapshot?.let { StorageCacheManager.formatBytes(it.totalAppCacheBytes) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showDataManagementSheet = true
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .testTag("settings_open_data_management_btn"),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SettingsIconPod(icon = Icons.Default.Storage, tint = Color(0xFF32ADE6))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.storage_mgmt_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = stringResource(R.string.storage_mgmt_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                AnimatedVisibility(
                                    visible = storageBadge != null,
                                    enter = fadeIn(tween(340, easing = FastOutSlowInEasing)) +
                                        scaleIn(
                                            initialScale = 0.88f,
                                            animationSpec = tween(340, easing = FastOutSlowInEasing)
                                        )
                                ) {
                                    Surface(
                                        shape = ShapeTokens.PillShape,
                                        color = Color(0xFF32ADE6).copy(alpha = 0.14f)
                                    ) {
                                        Text(
                                            text = storageBadge.orEmpty(),
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF32ADE6),
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            val activeSegments = remember(storageSnapshot) {
                                storageSnapshot?.categories?.filter { it.sizeBytes > 0L }.orEmpty()
                            }
                            AnimatedStorageDistributionBar(
                                segments = activeSegments,
                                isLoaded = storageSnapshot != null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 50.dp)
                                    .height(6.dp)
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 16.dp),
                            thickness = 0.7.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )

                        SettingsHubRow(
                            icon = Icons.Default.ImportExport,
                            iconTint = Color(0xFF10B981),
                            title = stringResource(R.string.backup_sheet_title),
                            subtitle = stringResource(R.string.backup_sheet_subtitle),
                            badgeText = "JSON / CSV",
                            badgeColor = Color(0xFF10B981),
                            onClick = { showBackupExportImportSheet = true }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 16.dp),
                            thickness = 0.7.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )

                        SettingsHubExpandableRow(
                            icon = Icons.Default.Folder,
                            iconTint = Color(0xFFF59E0B),
                            title = stringResource(R.string.settings_hub_archive_title),
                            subtitle = stringResource(R.string.settings_hub_archive_sub),
                            badgeText = "${allLogs.size}",
                            badgeColor = AccentBlue,
                            expanded = showArchiveExpanded,
                            onToggle = { showArchiveExpanded = !showArchiveExpanded }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                ArchivePathBreadcrumbCard(
                                    rawPath = currentDisplayPath,
                                    onCopyPath = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Archive Path", currentDisplayPath))
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        scope.launch { snackbarHostState.showSnackbar(pathCopiedMsg) }
                                    }
                                )

                                // Symmetrical 50/50 Buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = { folderPicker.launch(null) },
                                        shape = ShapeTokens.MediumShape,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.btn_select_folder),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            ArchiveDirectoryManager.resetToDefault(context)
                                            currentDisplayPath = ArchiveDirectoryManager.getDisplayPath(context)
                                            scope.launch { snackbarHostState.showSnackbar(pathSavedMsg) }
                                        },
                                        shape = ShapeTokens.MediumShape,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                    ) {
                                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.btn_reset_default),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.section_audit_logs),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.logs_count_format, allLogs.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentBlue
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = { showLogsExpanded = !showLogsExpanded },
                                        shape = ShapeTokens.MediumShape,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showLogsExpanded) Icons.Default.ExpandLess else Icons.Default.Visibility,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (showLogsExpanded) stringResource(R.string.btn_hide_logs) else stringResource(R.string.btn_view_logs),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { showClearLogsDialog = true },
                                        enabled = allLogs.isNotEmpty(),
                                        shape = ShapeTokens.MediumShape,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.action_delete),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = showLogsExpanded,
                                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                                    exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
                                ) {
                                    val logContainerShape = ShapeTokens.MediumShape
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(logContainerShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, logContainerShape)
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (allLogs.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.empty_logs_text),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(6.dp)
                                            )
                                        } else {
                                            allLogs.take(25).forEach { log ->
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

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                                SettingSwitchRow(
                                    title = stringResource(R.string.daemon_sync_enabled),
                                    description = stringResource(R.string.daemon_sync_desc),
                                    checked = daemonSyncEnabled,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        app.settingsManager.setDaemonSyncEnabled(it)
                                    }
                                )

                                AnimatedVisibility(
                                    visible = daemonSyncEnabled,
                                    enter = expandVertically(tween(200)) + fadeIn(tween(160)),
                                    exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedTextField(
                                            value = daemonHostUrl,
                                            onValueChange = { app.settingsManager.setDaemonHostUrl(it) },
                                            label = { Text(stringResource(R.string.daemon_host_title)) },
                                            singleLine = true,
                                            shape = ShapeTokens.MediumShape,
                                            modifier = Modifier.fillMaxWidth()
                                        )

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
                                            enabled = !isTestingDaemon,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                        ) {
                                            if (isTestingDaemon) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            } else {
                                                Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            Text(stringResource(R.string.btn_ping_daemon))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "sec_engine") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader(text = stringResource(R.string.settings_hub_sec_engine))

                    SettingsHubCard {
                        val engineBadgeText = when (val st = ytDlpEngineState) {
                            is YtDlpEngineState.Ready -> stringResource(R.string.ytdlp_status_ready, st.version)
                            is YtDlpEngineState.Updating -> stringResource(R.string.ytdlp_status_updating)
                            is YtDlpEngineState.Initializing, is YtDlpEngineState.Uninitialized -> stringResource(R.string.ytdlp_status_init)
                            is YtDlpEngineState.Failed -> stringResource(R.string.ytdlp_status_error)
                        }
                        val engineBadgeColor = when (ytDlpEngineState) {
                            is YtDlpEngineState.Ready -> StatusGreen
                            is YtDlpEngineState.Updating -> AccentBlue
                            is YtDlpEngineState.Failed -> StatusRed
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        SettingsHubExpandableRow(
                            icon = Icons.Default.Movie,
                            iconTint = Color(0xFF9B59B6),
                            title = stringResource(R.string.settings_hub_engine_title),
                            subtitle = stringResource(R.string.settings_hub_engine_sub),
                            badgeText = null,
                            badgeColor = engineBadgeColor,
                            expanded = showEngineExpanded,
                            onToggle = { showEngineExpanded = !showEngineExpanded }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    color = engineBadgeColor.copy(alpha = 0.10f),
                                    border = BorderStroke(0.75.dp, engineBadgeColor.copy(alpha = 0.28f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (ytDlpEngineState is YtDlpEngineState.Ready) Icons.Default.CheckCircle else Icons.Default.Sync,
                                                contentDescription = null,
                                                tint = engineBadgeColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.section_media_engine_title),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = engineBadgeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = engineBadgeColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Text(
                                    text = stringResource(R.string.ytdlp_quality_label),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val qualityPresets = remember {
                                    listOf(
                                        YtDlpQualityPreset.BEST,
                                        YtDlpQualityPreset.HD_1080,
                                        YtDlpQualityPreset.HD_720,
                                        YtDlpQualityPreset.DATA_SAVER_480
                                    )
                                }
                                val qualityOptions = listOf(
                                    SegmentedOption(stringResource(R.string.ytdlp_quality_best)),
                                    SegmentedOption(stringResource(R.string.ytdlp_quality_1080)),
                                    SegmentedOption(stringResource(R.string.ytdlp_quality_720)),
                                    SegmentedOption(stringResource(R.string.ytdlp_quality_480))
                                )
                                AnimatedSegmentedSelector(
                                    options = qualityOptions,
                                    selectedIndex = qualityPresets.indexOf(ytDlpQualityPreset).coerceAtLeast(0),
                                    onSelected = { idx -> app.settingsManager.setYtDlpQualityPreset(qualityPresets[idx]) }
                                )

                                val channels = remember { YtDlpUpdateChannel.entries.toList() }
                                AnimatedSegmentedSelector(
                                    options = channels.map { SegmentedOption(it.key) },
                                    selectedIndex = channels.indexOf(ytDlpUpdateChannel).coerceAtLeast(0),
                                    onSelected = { idx -> app.settingsManager.setYtDlpUpdateChannel(channels[idx]) },
                                    height = 38.dp
                                )

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
                                    shape = ShapeTokens.MediumShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                ) {
                                    if (isUpdatingYtDlp || ytDlpEngineState is YtDlpEngineState.Updating) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    } else {
                                        Icon(Icons.Default.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.btn_update_ytdlp))
                                }

                                SettingSwitchRow(
                                    title = stringResource(R.string.ytdlp_auto_download_title),
                                    description = stringResource(R.string.ytdlp_auto_download_desc),
                                    checked = ytDlpAutoDownloadVideos,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        app.settingsManager.setYtDlpAutoDownloadVideos(it)
                                    }
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

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                                Text(
                                    text = stringResource(R.string.speed_preset_label),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val speedPresets = remember {
                                    listOf(
                                        ArchiverSpeedPreset.ECO,
                                        ArchiverSpeedPreset.BALANCED,
                                        ArchiverSpeedPreset.FAST,
                                        ArchiverSpeedPreset.TURBO
                                    )
                                }
                                val speedOptions = listOf(
                                    SegmentedOption(stringResource(R.string.speed_preset_eco), "${ArchiverSpeedPreset.ECO.downloadWorkers}x"),
                                    SegmentedOption(stringResource(R.string.speed_preset_balanced), "${ArchiverSpeedPreset.BALANCED.downloadWorkers}x"),
                                    SegmentedOption(stringResource(R.string.speed_preset_fast), "${ArchiverSpeedPreset.FAST.downloadWorkers}x"),
                                    SegmentedOption(stringResource(R.string.speed_preset_turbo), "${ArchiverSpeedPreset.TURBO.downloadWorkers}x")
                                )
                                AnimatedSegmentedSelector(
                                    options = speedOptions,
                                    selectedIndex = speedPresets.indexOf(archiverSpeedPreset).coerceAtLeast(0),
                                    onSelected = { idx -> app.settingsManager.setArchiverSpeedPreset(speedPresets[idx]) },
                                    height = 46.dp
                                )

                                Text(
                                    text = stringResource(R.string.max_posts_limit_label),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val limitValues = remember { listOf(100, 300, 500, 1000, 0) }
                                val limitOptions = listOf(
                                    SegmentedOption("100"),
                                    SegmentedOption("300"),
                                    SegmentedOption("500"),
                                    SegmentedOption("1000"),
                                    SegmentedOption(stringResource(R.string.max_posts_limit_all))
                                )
                                AnimatedSegmentedSelector(
                                    options = limitOptions,
                                    selectedIndex = limitValues.indexOf(maxPostsSyncLimit).coerceAtLeast(0),
                                    onSelected = { idx -> app.settingsManager.setMaxPostsSyncLimit(limitValues[idx]) },
                                    height = 38.dp
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

                                SettingSwitchRow(
                                    title = stringResource(R.string.auto_archive_pending_title),
                                    description = stringResource(R.string.auto_archive_pending_desc),
                                    checked = autoArchivePendingEnabled,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        app.settingsManager.setAutoArchivePendingEnabled(it)
                                    }
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                                // Symmetrical 50/50 System Actions: Battery Optimization & Restart Service
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            if (isBatteryIgnored) {
                                                BatteryHelper.openBatteryOptimizationSettings(context)
                                            } else {
                                                BatteryHelper.requestIgnoreBatteryOptimization(context)
                                            }
                                            isBatteryIgnored = BatteryHelper.isBatteryOptimizationIgnored(context)
                                        },
                                        shape = ShapeTokens.MediumShape,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isBatteryIgnored) Icons.Default.CheckCircle else Icons.Default.BatteryChargingFull,
                                            contentDescription = null,
                                            tint = if (isBatteryIgnored) StatusGreen else AccentBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBatteryIgnored) stringResource(R.string.badge_unrestricted) else stringResource(R.string.battery_opt_title),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            InstaLoggerForegroundService.start(context)
                                            scope.launch { snackbarHostState.showSnackbar(serviceRestartedMsg) }
                                        },
                                        shape = ShapeTokens.MediumShape,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.btn_restart_service),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (providerHealthList.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        providerHealthList.forEach { health ->
                                            val isHealthy = health.status == ProviderHealthTracker.STATUS_HEALTHY
                                            val isCooldown = health.status == ProviderHealthTracker.STATUS_RATE_LIMITED
                                            val badgeColor = when {
                                                isHealthy -> StatusGreen
                                                isCooldown -> StatusAmber
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
                                                        scope.launch { app.providerHealthTracker.resetProvider(health.providerId) }
                                                    }
                                                }
                                            ) {
                                                Text(
                                                    text = "${health.providerId}: $statusText",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = badgeColor,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 16.dp),
                            thickness = 0.7.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )

                        // App Update Center Hub Row (GitHub Releases)
                        val isCheckingUpdate = updateState is UpdateUiState.Checking
                        val updateBadgeText = availableUpdate?.let {
                            stringResource(R.string.update_badge_new, it.versionName)
                        }
                        SettingsHubExpandableRow(
                            icon = Icons.Default.SystemUpdate,
                            iconTint = if (availableUpdate != null) StatusGreen else AccentBlue,
                            title = stringResource(R.string.update_section_title),
                            subtitle = stringResource(
                                R.string.update_section_sub,
                                app.updateManager.installedVersionName(),
                                app.updateManager.installedVersionCode()
                            ),
                            badgeText = updateBadgeText,
                            badgeColor = StatusGreen,
                            expanded = showUpdateExpanded,
                            onToggle = { showUpdateExpanded = !showUpdateExpanded }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (lastUpdateCheckMs > 0L) {
                                    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
                                    Text(
                                        text = stringResource(R.string.update_last_checked, fmt.format(Date(lastUpdateCheckMs))),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            if (!isCheckingUpdate) {
                                                scope.launch {
                                                    val res = app.updateManager.checkForUpdates(
                                                        manual = true,
                                                        openSheetIfAvailable = true
                                                    )
                                                    when (res) {
                                                        is UpdateUiState.UpToDate -> {
                                                            snackbarHostState.showSnackbar(
                                                                context.getString(R.string.update_status_up_to_date)
                                                            )
                                                        }
                                                        is UpdateUiState.Error -> {
                                                            snackbarHostState.showSnackbar(res.message)
                                                        }
                                                        else -> Unit
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isCheckingUpdate,
                                        shape = ShapeTokens.MediumShape,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                    ) {
                                        if (isCheckingUpdate) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        } else {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(
                                            text = stringResource(R.string.update_btn_check_now),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (availableUpdate != null) {
                                        FilledTonalButton(
                                            onClick = { app.updateManager.openSheet() },
                                            shape = ShapeTokens.MediumShape,
                                            modifier = Modifier.height(44.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("v${availableUpdate!!.versionName}")
                                        }
                                    }
                                }

                                Text(
                                    text = stringResource(R.string.update_channel_label),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val appUpdateChannels = remember { listOf(AppUpdateChannel.STABLE, AppUpdateChannel.BETA) }
                                val appUpdateChannelLabels = listOf(
                                    SegmentedOption(stringResource(R.string.update_channel_stable)),
                                    SegmentedOption(stringResource(R.string.update_channel_beta))
                                )
                                AnimatedSegmentedSelector(
                                    options = appUpdateChannelLabels,
                                    selectedIndex = appUpdateChannels.indexOf(updateChannel).coerceAtLeast(0),
                                    onSelected = { idx ->
                                        app.settingsManager.setUpdateChannel(appUpdateChannels[idx])
                                    },
                                    height = 38.dp
                                )

                                SettingSwitchRow(
                                    title = stringResource(R.string.update_auto_check_title),
                                    description = stringResource(R.string.update_auto_check_desc),
                                    checked = autoCheckUpdates,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        app.settingsManager.setAutoCheckUpdates(it)
                                    }
                                )

                                SettingSwitchRow(
                                    title = stringResource(R.string.update_wifi_only_title),
                                    description = stringResource(R.string.update_wifi_only_desc),
                                    checked = updateWifiOnly,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        app.settingsManager.setUpdateWifiOnly(it)
                                    }
                                )

                                SettingSwitchRow(
                                    title = stringResource(R.string.update_auto_dl_wifi_title),
                                    description = stringResource(R.string.update_auto_dl_wifi_desc),
                                    checked = updateAutoDownloadWifi,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        app.settingsManager.setUpdateAutoDownloadWifi(it)
                                    }
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 16.dp),
                            thickness = 0.7.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )

                        // Developer Logs Hub Row
                        SettingsHubRow(
                            icon = Icons.Default.Terminal,
                            iconTint = if (devErrCount > 0) StatusRed else AccentBlue,
                            title = stringResource(R.string.dev_logs_title),
                            subtitle = stringResource(R.string.dev_logs_desc),
                            badgeText = if (devErrCount > 0) "ERR $devErrCount" else "${devLogs.size}",
                            badgeColor = if (devErrCount > 0) StatusRed else AccentBlue,
                            onClick = onOpenDeveloperLogs
                        )
                    }
                }
            }
        }
    }

    // Confirmation dialog: Clear audit change logs
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
            shape = RoundedCornerShape(24.dp),
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
                scope.launch { snackbarHostState.showSnackbar(pinSetSuccessMsg) }
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
                scope.launch { snackbarHostState.showSnackbar(pinDisabledSuccessMsg) }
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

    if (showThemeSheet) {
        ThemeAppearanceBottomSheet(
            currentThemeMode = currentThemeMode,
            onSelectTheme = { mode ->
                scope.launch { app.settingsManager.changeThemeWithAnimation(mode) }
            },
            onDismissRequest = { showThemeSheet = false }
        )
    }

    if (showNotifSettingsSheet) {
        NotificationSettingsBottomSheet(onDismissRequest = { showNotifSettingsSheet = false })
    }

    if (showDataManagementSheet) {
        DataManagementBottomSheet(
            onDismissRequest = { showDataManagementSheet = false },
            onStorageChanged = { updated ->
                CachedStorageSnapshotHolder.lastSnapshot = updated
                storageSnapshot = updated
            }
        )
    }

    if (showBackupExportImportSheet) {
        BackupExportImportBottomSheet(
            onDismissRequest = { showBackupExportImportSheet = false },
            onCompletedMessage = { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
            }
        )
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

@Composable
private fun SettingsHubCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsIconPod(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsHubRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeColor: Color = AccentBlue,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIconPod(icon = icon, tint = iconTint)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!badgeText.isNullOrBlank()) {
            Surface(
                shape = ShapeTokens.PillShape,
                color = badgeColor.copy(alpha = 0.14f)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsHubExpandableRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeColor: Color = AccentBlue,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val chevronRot by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "hub_expand_chevron"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggle()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsIconPod(icon = icon, tint = iconTint)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!badgeText.isNullOrBlank()) {
                Surface(
                    shape = ShapeTokens.PillShape,
                    color = badgeColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = chevronRot }
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(140))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.7.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                expandedContent()
            }
        }
    }
}

@Composable
private fun ArchivePathBreadcrumbCard(
    rawPath: String,
    onCopyPath: () -> Unit
) {
    val internalLabel = stringResource(R.string.archive_path_internal_storage)
    val segments = remember(rawPath, internalLabel) {
        val cleaned = rawPath
            .replace("/storage/emulated/0", internalLabel)
            .replace("/data/user/0/com.instalogger.app", internalLabel)
            .trim('/')
        val parts = cleaned.split('/').filter { it.isNotBlank() }
        if (parts.size > 4) {
            listOf(parts.first(), "…", parts[parts.size - 2], parts.last())
        } else {
            parts
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(18.dp)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                segments.forEachIndexed { idx, seg ->
                    val isLast = idx == segments.lastIndex
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = if (isLast) AccentBlue.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = seg,
                            fontSize = 11.5.sp,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                            color = if (isLast) AccentBlue else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    if (!isLast) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            IconButton(
                onClick = onCopyPath,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
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
    val shape = RoundedCornerShape(14.dp)
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "lang_border_$code"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) AccentBlue.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "lang_bg_$code"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "lang_txt_$code"
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .testTag("language_button_$code")
            .semantics { selected = isSelected }
            .clip(shape)
            .background(bgColor)
            .border(if (isSelected) 1.5.dp else 0.75.dp, borderColor, shape)
            .bouncyClickable(pressScale = 0.94f, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Image(
                painter = painterResource(flagResId),
                contentDescription = label,
                modifier = Modifier
                    .size(width = 24.dp, height = 18.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                color = textColor
            )
        }
    }
}

@Composable
private fun SettingsLogItem(
    log: ChangeLogEntity,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val currentLang by LanguageManager.currentLanguage.collectAsState()
    val (localizedTitle, localizedOld, localizedNew) = remember(log.id, log.title, log.oldValue, log.newValue, log.eventType, currentLang) {
        LanguageManager.localizeChangeLog(context, log)
    }
    val formattedTime = remember(log.timestamp) {
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
    }

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
                text = if (!localizedOld.isNullOrBlank() || !localizedNew.isNullOrBlank()) {
                    "${localizedOld ?: "-"} -> ${localizedNew ?: "-"}"
                } else {
                    localizedTitle
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun PinSetupDialog(
    onDismiss: () -> Unit,
    onPinConfirmed: (String) -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var firstPin by remember { mutableStateOf("") }
    var secondPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val view = LocalView.current

    val currentPin = if (step == 0) firstPin else secondPin
    val title = if (step == 0) stringResource(R.string.lock_enter_pin_title) else stringResource(R.string.lock_confirm_pin_title)
    val subtitle = if (step == 0) stringResource(R.string.lock_enter_pin_desc) else stringResource(R.string.lock_confirm_pin_title)
    val mismatchError = stringResource(R.string.lock_pin_mismatch)

    fun onNumber(digit: String) {
        PinHapticHelper.performKeyTap(context, view)
        errorMessage = null
        if (step == 0) {
            if (firstPin.length < 4) {
                val next = firstPin + digit
                firstPin = next
                if (next.length == 4) step = 1
            }
        } else {
            if (secondPin.length < 4) {
                val next = secondPin + digit
                secondPin = next
                if (next.length == 4) {
                    if (next == firstPin) {
                        PinHapticHelper.performSuccess(context, view)
                        onPinConfirmed(next)
                    } else {
                        PinHapticHelper.performError(context, view)
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
        PinHapticHelper.performBackspace(context, view)
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
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            PinKeypadBody(
                currentPin = currentPin,
                message = errorMessage ?: subtitle,
                isError = errorMessage != null,
                onNumber = ::onNumber,
                onBackspace = ::onBackspace
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
private fun PinVerifyDialog(
    title: String,
    appLockManager: AppLockManager,
    onDismiss: () -> Unit,
    onVerified: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val view = LocalView.current
    val wrongPinMsg = stringResource(R.string.lock_pin_incorrect)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            PinKeypadBody(
                currentPin = enteredPin,
                message = errorMessage ?: stringResource(R.string.lock_enter_pin_desc),
                isError = errorMessage != null,
                onNumber = { digit ->
                    PinHapticHelper.performKeyTap(context, view)
                    errorMessage = null
                    if (enteredPin.length < 4) {
                        val next = enteredPin + digit
                        enteredPin = next
                        if (next.length == 4) {
                            if (appLockManager.verifyPin(next)) {
                                PinHapticHelper.performSuccess(context, view)
                                onVerified()
                            } else {
                                PinHapticHelper.performError(context, view)
                                errorMessage = wrongPinMsg
                                enteredPin = ""
                            }
                        }
                    }
                },
                onBackspace = {
                    PinHapticHelper.performBackspace(context, view)
                    errorMessage = null
                    if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                }
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
private fun PinKeypadBody(
    currentPin: String,
    message: String,
    isError: Boolean,
    onNumber: (String) -> Unit,
    onBackspace: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(vertical = 6.dp)
        ) {
            for (i in 0 until 4) {
                val isFilled = i < currentPin.length
                val dotColor by animateColorAsState(
                    targetValue = if (isFilled) AccentBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    animationSpec = tween(150),
                    label = "pin_dot_$i"
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }

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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (key in row) {
                        when (key) {
                            "" -> Spacer(modifier = Modifier.size(56.dp))
                            "DEL" -> Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .bouncyClickable(pressScale = 0.88f, onClick = onBackspace),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            else -> Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .bouncyClickable(pressScale = 0.88f) { onNumber(key) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
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
            Text(
                text = stringResource(R.string.sec_autolock),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (seconds, label) ->
                    val selected = seconds == currentTimeout
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) AccentBlue.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { onSelect(seconds) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurface
                        )
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

private object CachedStorageSnapshotHolder {
    @Volatile
    var lastSnapshot: DeviceStorageSnapshot? = null
}

private val SmoothStorageEaseOut = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

@Composable
private fun AnimatedStorageDistributionBar(
    segments: List<StorageCategoryMetric>,
    isLoaded: Boolean,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val shimmerHighlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    // Master reveal animation (0f -> 1f) whenever the bar enters composition or finishes loading
    val revealAnim = remember { Animatable(0f) }
    LaunchedEffect(isLoaded, segments.isNotEmpty()) {
        if (isLoaded && segments.isNotEmpty()) {
            if (revealAnim.value < 0.05f) {
                revealAnim.snapTo(0f)
            }
            revealAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 720,
                    easing = SmoothStorageEaseOut
                )
            )
        }
    }

    // Subtle shimmer ONLY while initial storage analysis is running (0 Choreographer frames once loaded!)
    val showLoadingShimmer = !isLoaded || segments.isEmpty()
    val shimmerPhaseState = if (showLoadingShimmer) {
        val infiniteTransition = rememberInfiniteTransition(label = "storage_bar_shimmer")
        infiniteTransition.animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1350, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "storage_shimmer_phase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val rawWeights = remember(segments) {
        if (segments.isEmpty()) {
            emptyList()
        } else {
            val total = segments.sumOf { it.sizeBytes }.coerceAtLeast(1L).toFloat()
            val minPinned = segments.map { (it.sizeBytes.toFloat() / total).coerceAtLeast(0.05f) }
            val sumWeights = minPinned.sum().coerceAtLeast(0.0001f)
            minPinned.map { it / sumWeights }
        }
    }

    Canvas(
        modifier = modifier.clip(ShapeTokens.PillShape)
    ) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(h / 2f, h / 2f)

        // Base track
        drawRoundRect(
            color = trackColor,
            size = Size(w, h),
            cornerRadius = corner
        )

        if (showLoadingShimmer) {
            // Soft non-fatiguing shimmer sweep while computing (Draw-phase read only)
            val bandWidth = w * 0.42f
            val startX = w * shimmerPhaseState.value
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, shimmerHighlight, Color.Transparent),
                    startX = startX,
                    endX = startX + bandWidth
                ),
                size = Size(w, h),
                cornerRadius = corner
            )
            return@Canvas
        }

        val progress = revealAnim.value.coerceIn(0f, 1f)
        if (progress <= 0.001f) return@Canvas

        val gapPx = 2.dp.toPx()
        val count = segments.size
        val totalGaps = gapPx * (count - 1).coerceAtLeast(0)
        val usableWidth = (w - totalGaps).coerceAtLeast(1f)
        val revealedUsableWidth = usableWidth * progress

        var currentX = 0f
        var remainingReveal = revealedUsableWidth

        for (i in 0 until count) {
            if (remainingReveal <= 0.5f) break
            val targetSegWidth = usableWidth * rawWeights[i]
            val drawnSegWidth = remainingReveal.coerceAtMost(targetSegWidth)
            val segCompletion = (drawnSegWidth / targetSegWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val segColor = Color(segments[i].type.colorHex).copy(
                alpha = (0.35f + 0.65f * segCompletion).coerceIn(0f, 1f)
            )

            drawRoundRect(
                color = segColor,
                topLeft = Offset(currentX, 0f),
                size = Size(drawnSegWidth.coerceAtLeast(h.coerceAtMost(targetSegWidth)), h),
                cornerRadius = corner
            )

            currentX += drawnSegWidth + gapPx
            remainingReveal -= targetSegWidth
        }
    }
}

