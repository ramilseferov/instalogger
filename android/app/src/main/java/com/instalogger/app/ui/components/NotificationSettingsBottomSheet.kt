package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.engine.pool.FollowerPoolMode
import com.instalogger.app.settings.AppSettingsManager
import com.instalogger.app.settings.NotificationCategory
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusAmber
import com.instalogger.app.ui.theme.StatusGreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationSettingsBottomSheet(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val settingsManager = app.settingsManager
    val haptic = LocalHapticFeedback.current

    var selectedSection by remember { mutableIntStateOf(0) }

    val statusBarEnabled by settingsManager.statusBarNotifications.collectAsStateWithLifecycle()
    val inAppEnabled by settingsManager.inAppNotifications.collectAsStateWithLifecycle()
    val soundEnabled by settingsManager.soundEnabled.collectAsStateWithLifecycle()
    val vibrationEnabled by settingsManager.vibrationEnabled.collectAsStateWithLifecycle()

    val poolMode by settingsManager.poolMode.collectAsStateWithLifecycle()
    val poolFixed by settingsManager.poolFixedThreshold.collectAsStateWithLifecycle()
    val poolPercent by settingsManager.poolPercentThreshold.collectAsStateWithLifecycle()
    val poolApplyFollowing by settingsManager.poolApplyToFollowing.collectAsStateWithLifecycle()
    val poolDigest by settingsManager.poolDigestWindowMinutes.collectAsStateWithLifecycle()
    val poolCooldown by settingsManager.poolCooldownMinutes.collectAsStateWithLifecycle()
    val poolAntiFlicker by settingsManager.poolAntiFlickerEnabled.collectAsStateWithLifecycle()
    val poolSpikeEnabled by settingsManager.poolSpikeAlertEnabled.collectAsStateWithLifecycle()
    val poolSuppressLogs by settingsManager.poolSuppressMinorAuditLogs.collectAsStateWithLifecycle()
    val listCompletenessGuard by settingsManager.listCompletenessGuardEnabled.collectAsStateWithLifecycle()

    var fixedInputText by remember(poolFixed) { mutableStateOf(poolFixed.toString()) }
    var percentInputText by remember(poolPercent) { mutableStateOf(poolPercent.toString()) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(top = 12.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(ShapeTokens.SmallShape)
                            .background(AccentBlue.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.notif_settings_sheet_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.section_notifications_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dialog_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Live Compact Status Summary Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusColor = if (statusBarEnabled) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(
                    shape = ShapeTokens.PillShape,
                    color = statusColor.copy(alpha = 0.12f),
                    border = BorderStroke(0.75.dp, statusColor.copy(alpha = 0.28f))
                ) {
                    Text(
                        text = if (statusBarEnabled) stringResource(R.string.notif_summary_push_on) else stringResource(R.string.notif_summary_push_off),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                val poolLabel = when (poolMode) {
                    FollowerPoolMode.OFF -> stringResource(R.string.pool_mode_off)
                    FollowerPoolMode.FIXED_COUNT -> "±$poolFixed"
                    FollowerPoolMode.PERCENTAGE -> "$poolPercent%"
                    FollowerPoolMode.AUTO_ADAPTIVE -> stringResource(R.string.pool_mode_auto)
                }
                val poolColor = if (poolMode == FollowerPoolMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else AccentBlue
                Surface(
                    shape = ShapeTokens.PillShape,
                    color = poolColor.copy(alpha = 0.12f),
                    border = BorderStroke(0.75.dp, poolColor.copy(alpha = 0.28f))
                ) {
                    Text(
                        text = "${stringResource(R.string.notif_tab_pool)}: $poolLabel",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = poolColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // 3-Tab Segmented Navigation (Eliminates endless chaotic wall of settings)
            SlidingPillTabRow(
                tabs = listOf(
                    TabItem(title = stringResource(R.string.notif_tab_general), icon = Icons.Default.Tune),
                    TabItem(title = stringResource(R.string.notif_tab_categories), icon = Icons.Default.Category),
                    TabItem(title = stringResource(R.string.notif_tab_pool), icon = Icons.Default.Layers)
                ),
                selectedIndex = selectedSection,
                onTabSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedSection = it
                }
            )

            // Tab Body (Scrollable, smooth AnimatedContent crossfade)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(tween(220)),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AnimatedContent(
                    targetState = selectedSection,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "notif_settings_section"
                ) { section ->
                    when (section) {
                        0 -> {
                            // TAB 0: General Delivery Channels & System Settings
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                SettingsSectionBox {
                                    SettingSwitchRow(
                                        title = stringResource(R.string.notif_status_bar_title),
                                        description = stringResource(R.string.notif_status_bar_desc),
                                        checked = statusBarEnabled,
                                        onCheckedChange = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            settingsManager.setStatusBarNotifications(it)
                                        }
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )

                                    SettingSwitchRow(
                                        title = stringResource(R.string.notif_in_app_title),
                                        description = stringResource(R.string.notif_in_app_desc),
                                        checked = inAppEnabled,
                                        onCheckedChange = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            settingsManager.setInAppNotifications(it)
                                        }
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )

                                    Box(modifier = Modifier.alpha(if (statusBarEnabled) 1f else 0.45f)) {
                                        SettingSwitchRow(
                                            title = stringResource(R.string.notif_sound_title),
                                            description = stringResource(R.string.notif_sound_desc),
                                            checked = soundEnabled,
                                            enabled = statusBarEnabled,
                                            onCheckedChange = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                settingsManager.setSoundEnabled(it)
                                            }
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )

                                    Box(modifier = Modifier.alpha(if (statusBarEnabled) 1f else 0.45f)) {
                                        SettingSwitchRow(
                                            title = stringResource(R.string.notif_vibration_title),
                                            description = stringResource(R.string.notif_vibration_desc),
                                            checked = vibrationEnabled,
                                            enabled = statusBarEnabled,
                                            onCheckedChange = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                settingsManager.setVibrationEnabled(it)
                                            }
                                        )
                                    }
                                }

                                // Android System Notification Settings Shortcut
                                SettingsSectionBox {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(ShapeTokens.SmallShape)
                                                .background(AccentBlue.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tune,
                                                contentDescription = null,
                                                tint = AccentBlue,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.btn_open_system_notifs),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.btn_open_system_notifs_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Surface(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            AppSettingsManager.openSystemNotificationSettings(context)
                                        },
                                        shape = ShapeTokens.MediumShape,
                                        color = AccentBlue.copy(alpha = 0.12f),
                                        border = BorderStroke(ShapeTokens.BorderStandard, AccentBlue.copy(alpha = 0.35f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.btn_open_system_notifs),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = AccentBlue,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                                            )
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                tint = AccentBlue,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        1 -> {
                            // TAB 1: Event Categories (Color-coded & Clean)
                            SettingsSectionBox {
                                Text(
                                    text = stringResource(R.string.notif_categories_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.notif_categories_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                NotificationCategory.entries.forEachIndexed { index, cat ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 10.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                        )
                                    }

                                    val isCatChecked by settingsManager.getCategoryFlow(cat).collectAsStateWithLifecycle()
                                    val catColor = when (cat) {
                                        NotificationCategory.PROFILE_CHANGES -> Color(0xFF8B5CF6)
                                        NotificationCategory.PRIVACY_ALERTS -> StatusAmber
                                        NotificationCategory.ACTIVITY_UPDATES -> StatusGreen
                                        NotificationCategory.SYSTEM_ALERTS -> AccentBlue
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(ShapeTokens.SmallShape)
                                                    .background(catColor.copy(alpha = if (isCatChecked) 0.16f else 0.07f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = cat.icon,
                                                    contentDescription = null,
                                                    tint = if (isCatChecked) catColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = stringResource(cat.titleRes),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = stringResource(cat.descRes),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Switch(
                                            checked = isCatChecked,
                                            onCheckedChange = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                settingsManager.setCategoryEnabled(cat, it)
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.White,
                                                checkedTrackColor = catColor
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            // TAB 2: Follower Change Pool & High-Velocity Account Protection
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                SettingsSectionBox {
                                    Text(
                                        text = stringResource(R.string.pool_global_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.pool_global_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // 4-Column Uniform Mode Selector Row (Zero wrapping jumps!)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        val modes = listOf(
                                            FollowerPoolMode.OFF to stringResource(R.string.pool_mode_off),
                                            FollowerPoolMode.FIXED_COUNT to stringResource(R.string.pool_mode_fixed),
                                            FollowerPoolMode.PERCENTAGE to stringResource(R.string.pool_mode_percent),
                                            FollowerPoolMode.AUTO_ADAPTIVE to stringResource(R.string.pool_mode_auto)
                                        )
                                        modes.forEach { (mode, label) ->
                                            SelectableOptionChip(
                                                label = label,
                                                selected = poolMode == mode,
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    settingsManager.setPoolMode(mode)
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    AnimatedVisibility(visible = poolMode == FollowerPoolMode.FIXED_COUNT) {
                                        Column(
                                            modifier = Modifier.padding(top = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.pool_threshold_label),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(10L, 50L, 100L, 250L, 500L, 1000L).forEach { preset ->
                                                    SelectableOptionChip(
                                                        label = "±$preset",
                                                        selected = poolFixed == preset,
                                                        onClick = {
                                                            fixedInputText = preset.toString()
                                                            settingsManager.setPoolFixedThreshold(preset)
                                                        }
                                                    )
                                                }
                                            }
                                            OutlinedTextField(
                                                value = fixedInputText,
                                                onValueChange = { input ->
                                                    val digits = input.filter { it.isDigit() }.take(7)
                                                    fixedInputText = digits
                                                    digits.toLongOrNull()?.takeIf { it >= 1L }?.let {
                                                        settingsManager.setPoolFixedThreshold(it)
                                                    }
                                                },
                                                label = { Text(stringResource(R.string.pool_custom_value_hint)) },
                                                singleLine = true,
                                                shape = ShapeTokens.MediumShape,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }

                                    AnimatedVisibility(visible = poolMode == FollowerPoolMode.PERCENTAGE) {
                                        Column(
                                            modifier = Modifier.padding(top = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.pool_percent_label),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(0.1, 0.5, 1.0, 2.0, 5.0).forEach { pct ->
                                                    SelectableOptionChip(
                                                        label = "$pct%",
                                                        selected = poolPercent == pct,
                                                        onClick = {
                                                            percentInputText = pct.toString()
                                                            settingsManager.setPoolPercentThreshold(pct)
                                                        }
                                                    )
                                                }
                                            }
                                            OutlinedTextField(
                                                value = percentInputText,
                                                onValueChange = { input ->
                                                    val filtered = input.filter { it.isDigit() || it == '.' || it == ',' }.take(6)
                                                    percentInputText = filtered
                                                    filtered.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.01..100.0 }?.let {
                                                        settingsManager.setPoolPercentThreshold(it)
                                                    }
                                                },
                                                label = { Text(stringResource(R.string.pool_custom_percent_hint)) },
                                                singleLine = true,
                                                shape = ShapeTokens.MediumShape,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )

                                    Text(
                                        text = stringResource(R.string.pool_digest_title),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            0 to stringResource(R.string.pool_option_off),
                                            15 to "15m",
                                            60 to "1h",
                                            360 to "6h",
                                            1440 to "24h"
                                        ).forEach { (mins, label) ->
                                            SelectableOptionChip(
                                                label = label,
                                                selected = poolDigest == mins,
                                                onClick = { settingsManager.setPoolDigestWindowMinutes(mins) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text(
                                        text = stringResource(R.string.pool_cooldown_title),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            0 to stringResource(R.string.pool_option_off),
                                            1 to "1m",
                                            5 to "5m",
                                            15 to "15m",
                                            60 to "1h"
                                        ).forEach { (mins, label) ->
                                            SelectableOptionChip(
                                                label = label,
                                                selected = poolCooldown == mins,
                                                onClick = { settingsManager.setPoolCooldownMinutes(mins) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }

                                SettingsSectionBox {
                                    SettingSwitchRow(
                                        title = stringResource(R.string.pool_anti_flicker_title),
                                        description = stringResource(R.string.pool_anti_flicker_desc),
                                        checked = poolAntiFlicker,
                                        onCheckedChange = { settingsManager.setPoolAntiFlickerEnabled(it) }
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                                    SettingSwitchRow(
                                        title = stringResource(R.string.pool_spike_title),
                                        description = stringResource(R.string.pool_spike_desc),
                                        checked = poolSpikeEnabled,
                                        onCheckedChange = { settingsManager.setPoolSpikeAlertEnabled(it) }
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                                    SettingSwitchRow(
                                        title = stringResource(R.string.pool_apply_following_title),
                                        description = stringResource(R.string.pool_apply_following_desc),
                                        checked = poolApplyFollowing,
                                        onCheckedChange = { settingsManager.setPoolApplyToFollowing(it) }
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                                    SettingSwitchRow(
                                        title = stringResource(R.string.pool_suppress_logs_title),
                                        description = stringResource(R.string.pool_suppress_logs_desc),
                                        checked = poolSuppressLogs,
                                        onCheckedChange = { settingsManager.setPoolSuppressMinorAuditLogs(it) }
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                                    SettingSwitchRow(
                                        title = stringResource(R.string.pool_completeness_guard_title),
                                        description = stringResource(R.string.pool_completeness_guard_desc),
                                        checked = listCompletenessGuard,
                                        onCheckedChange = { settingsManager.setListCompletenessGuardEnabled(it) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionBox(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.LargeShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(ShapeTokens.BorderStandard, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun SelectableOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(
        targetValue = if (selected) AccentBlue.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(180),
        label = "chip_bg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(180),
        label = "chip_border"
    )
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(bg)
            .border(if (selected) 1.25.dp else 0.75.dp, border, shape)
            .bouncyClickable(pressScale = 0.94f, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
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

@Composable
fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentBlue
            )
        )
    }
}
