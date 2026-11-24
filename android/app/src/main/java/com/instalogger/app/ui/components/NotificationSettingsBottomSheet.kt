package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.engine.pool.FollowerPoolMode
import com.instalogger.app.settings.AppSettingsManager
import com.instalogger.app.settings.NotificationCategory
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationSettingsBottomSheet(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val settingsManager = app.settingsManager
    val haptic = LocalHapticFeedback.current

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

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        },
        shape = ShapeTokens.LargeShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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

            // 1. Master Preferences Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.LargeShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = BorderStroke(ShapeTokens.BorderStandard, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Status Bar Toggle
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
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // In-App Toggle
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
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // Sound Toggle
                    SettingSwitchRow(
                        title = stringResource(R.string.notif_sound_title),
                        description = stringResource(R.string.notif_sound_desc),
                        checked = soundEnabled,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            settingsManager.setSoundEnabled(it)
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // Vibration Toggle
                    SettingSwitchRow(
                        title = stringResource(R.string.notif_vibration_title),
                        description = stringResource(R.string.notif_vibration_desc),
                        checked = vibrationEnabled,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            settingsManager.setVibrationEnabled(it)
                        }
                    )
                }
            }

            // 2. Individual Categories Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.LargeShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = BorderStroke(ShapeTokens.BorderStandard, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                                        .size(34.dp)
                                        .clip(ShapeTokens.SmallShape)
                                        .background(AccentBlue.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = cat.icon,
                                        contentDescription = null,
                                        tint = AccentBlue,
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
                                    checkedTrackColor = AccentBlue
                                )
                            )
                        }
                    }
                }
            }

            // 2.5. Follower Change Pool & High-Velocity Account Protection Card
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pool_global_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.pool_global_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf(
                            FollowerPoolMode.OFF to stringResource(R.string.pool_mode_off),
                            FollowerPoolMode.FIXED_COUNT to stringResource(R.string.pool_mode_fixed),
                            FollowerPoolMode.PERCENTAGE to stringResource(R.string.pool_mode_percent),
                            FollowerPoolMode.AUTO_ADAPTIVE to stringResource(R.string.pool_mode_auto)
                        )
                        modes.forEach { (mode, label) ->
                            FilterChip(
                                selected = poolMode == mode,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    settingsManager.setPoolMode(mode)
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    AnimatedVisibility(visible = poolMode == FollowerPoolMode.FIXED_COUNT) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.pool_threshold_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(10L, 50L, 100L, 250L, 500L, 1000L).forEach { preset ->
                                    FilterChip(
                                        selected = poolFixed == preset,
                                        onClick = {
                                            fixedInputText = preset.toString()
                                            settingsManager.setPoolFixedThreshold(preset)
                                        },
                                        label = { Text("±$preset") }
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
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    AnimatedVisibility(visible = poolMode == FollowerPoolMode.PERCENTAGE) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.pool_percent_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(0.1, 0.5, 1.0, 2.0, 5.0).forEach { pct ->
                                    FilterChip(
                                        selected = poolPercent == pct,
                                        onClick = {
                                            percentInputText = pct.toString()
                                            settingsManager.setPoolPercentThreshold(pct)
                                        },
                                        label = { Text("$pct%") }
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
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Time-Window Digest
                    Text(
                        text = stringResource(R.string.pool_digest_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            0 to stringResource(R.string.pool_option_off),
                            15 to "15m",
                            60 to "1h",
                            360 to "6h",
                            1440 to "24h"
                        ).forEach { (mins, label) ->
                            FilterChip(
                                selected = poolDigest == mins,
                                onClick = { settingsManager.setPoolDigestWindowMinutes(mins) },
                                label = { Text(label) }
                            )
                        }
                    }

                    // Cooldown between alerts
                    Text(
                        text = stringResource(R.string.pool_cooldown_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            0 to stringResource(R.string.pool_option_off),
                            1 to "1m",
                            5 to "5m",
                            15 to "15m",
                            60 to "1h"
                        ).forEach { (mins, label) ->
                            FilterChip(
                                selected = poolCooldown == mins,
                                onClick = { settingsManager.setPoolCooldownMinutes(mins) },
                                label = { Text(label) }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    SettingSwitchRow(
                        title = stringResource(R.string.pool_anti_flicker_title),
                        description = stringResource(R.string.pool_anti_flicker_desc),
                        checked = poolAntiFlicker,
                        onCheckedChange = { settingsManager.setPoolAntiFlickerEnabled(it) }
                    )

                    SettingSwitchRow(
                        title = stringResource(R.string.pool_spike_title),
                        description = stringResource(R.string.pool_spike_desc),
                        checked = poolSpikeEnabled,
                        onCheckedChange = { settingsManager.setPoolSpikeAlertEnabled(it) }
                    )

                    SettingSwitchRow(
                        title = stringResource(R.string.pool_apply_following_title),
                        description = stringResource(R.string.pool_apply_following_desc),
                        checked = poolApplyFollowing,
                        onCheckedChange = { settingsManager.setPoolApplyToFollowing(it) }
                    )

                    SettingSwitchRow(
                        title = stringResource(R.string.pool_suppress_logs_title),
                        description = stringResource(R.string.pool_suppress_logs_desc),
                        checked = poolSuppressLogs,
                        onCheckedChange = { settingsManager.setPoolSuppressMinorAuditLogs(it) }
                    )

                    SettingSwitchRow(
                        title = stringResource(R.string.pool_completeness_guard_title),
                        description = stringResource(R.string.pool_completeness_guard_desc),
                        checked = listCompletenessGuard,
                        onCheckedChange = { settingsManager.setListCompletenessGuardEnabled(it) }
                    )
                }
            }

            // 3. Android System Notification Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.LargeShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = BorderStroke(ShapeTokens.BorderStandard, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
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

                    Spacer(modifier = Modifier.height(14.dp))

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
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentBlue
            )
        )
    }
}
