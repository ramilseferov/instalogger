package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
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
import com.instalogger.app.settings.NotificationEventKey
import com.instalogger.app.settings.NotificationTreeGroup
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
    val allTargets by app.database.targetDao().getAllTargets().collectAsStateWithLifecycle(initialValue = emptyList())
    val treeRevision by settingsManager.notifTreeRevision.collectAsStateWithLifecycle()
    var selectedScopeUsername by remember { mutableStateOf<String?>(null) } // null = Global
    val expandedGroups = remember {
        mutableStateMapOf(
            NotificationTreeGroup.FOLLOWERS to true,
            NotificationTreeGroup.CONTENT to true,
            NotificationTreeGroup.PROFILE to false
        )
    }

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
        com.instalogger.app.util.LanguageManager.ProvideLocalizedContext {
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
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SettingsSectionBox {
                                    Text(
                                        text = stringResource(R.string.notif_scope_profiles_label),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val globalSelected = selectedScopeUsername == null
                                        ProfileScopeChip(
                                            label = stringResource(R.string.notif_scope_global),
                                            isSelected = globalSelected,
                                            hasCustomOverride = false,
                                            isMuted = false,
                                            isGlobal = true,
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                selectedScopeUsername = null
                                            }
                                        )
                                        allTargets.forEach { target ->
                                            val isSelected = selectedScopeUsername == target.username
                                            val isCustom = remember(treeRevision, target.username) {
                                                settingsManager.hasTargetNotificationOverride(target.username)
                                            }
                                            val isMuted = remember(treeRevision, target.username) {
                                                settingsManager.isTargetNotificationsMuted(target.username)
                                            }
                                            ProfileScopeChip(
                                                label = "@${target.username}",
                                                isSelected = isSelected,
                                                hasCustomOverride = isCustom,
                                                isMuted = isMuted,
                                                isGlobal = false,
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    selectedScopeUsername = target.username
                                                }
                                            )
                                        }
                                    }

                                    // Per-Profile Override & Mute Controls when a specific profile is selected
                                    val scopedUser = selectedScopeUsername
                                    AnimatedVisibility(
                                        visible = scopedUser != null,
                                        enter = expandVertically(tween(200)) + fadeIn(tween(160)),
                                        exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
                                    ) {
                                        if (scopedUser != null) {
                                            val isMuted = remember(treeRevision, scopedUser) {
                                                settingsManager.isTargetNotificationsMuted(scopedUser)
                                            }
                                            val isCustom = remember(treeRevision, scopedUser) {
                                                settingsManager.hasTargetNotificationOverride(scopedUser)
                                            }
                                            Column(
                                                modifier = Modifier.padding(top = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                                                SettingSwitchRow(
                                                    title = stringResource(R.string.notif_profile_mute_toggle),
                                                    description = stringResource(R.string.notif_profile_mute_desc),
                                                    checked = isMuted,
                                                    onCheckedChange = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        settingsManager.setTargetNotificationsMuted(scopedUser, it)
                                                    }
                                                )

                                                SettingSwitchRow(
                                                    title = stringResource(R.string.notif_profile_custom_toggle),
                                                    description = stringResource(R.string.notif_profile_custom_desc),
                                                    checked = isCustom,
                                                    onCheckedChange = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        settingsManager.setTargetNotificationOverrideEnabled(scopedUser, it)
                                                    }
                                                )

                                                if (isCustom || isMuted) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            settingsManager.resetTargetNotificationOverrides(scopedUser)
                                                        },
                                                        shape = ShapeTokens.MediumShape,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(40.dp)
                                                    ) {
                                                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(stringResource(R.string.notif_profile_reset_global))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Hierarchical Tree Branches
                                val scopedUser = selectedScopeUsername
                                val isScopeMuted = remember(treeRevision, scopedUser) {
                                    scopedUser != null && settingsManager.isTargetNotificationsMuted(scopedUser)
                                }

                                NotificationTreeGroup.entries.forEach { group ->
                                    val leaves = remember(group) { NotificationEventKey.forGroup(group) }
                                    val isExpanded = expandedGroups[group] == true
                                    val groupColor = when (group) {
                                        NotificationTreeGroup.FOLLOWERS -> StatusGreen
                                        NotificationTreeGroup.CONTENT -> AccentBlue
                                        NotificationTreeGroup.PROFILE -> Color(0xFF8B5CF6)
                                    }
                                    val groupIcon = when (group) {
                                        NotificationTreeGroup.FOLLOWERS -> Icons.Default.People
                                        NotificationTreeGroup.CONTENT -> Icons.Default.PhotoLibrary
                                        NotificationTreeGroup.PROFILE -> Icons.Default.Person
                                    }
                                    val enabledCount = remember(treeRevision, scopedUser, group) {
                                        leaves.count { leaf ->
                                            if (scopedUser != null) settingsManager.isTargetEventKeyEnabled(scopedUser, leaf)
                                            else settingsManager.isEventKeyEnabled(leaf)
                                        }
                                    }
                                    val allEnabled = enabledCount == leaves.size
                                    val chevronRot by animateFloatAsState(
                                        targetValue = if (isExpanded) 180f else 0f,
                                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                                        label = "tree_chevron_${group.id}"
                                    )

                                    SettingsSectionBox {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .alpha(if (isScopeMuted) 0.45f else 1f)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        expandedGroups[group] = !isExpanded
                                                    }
                                                    .padding(vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(groupColor.copy(alpha = if (enabledCount > 0) 0.16f else 0.08f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = groupIcon,
                                                        contentDescription = null,
                                                        tint = if (enabledCount > 0) groupColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(19.dp)
                                                    )
                                                }

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = stringResource(group.titleRes),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                        Surface(
                                                            shape = ShapeTokens.PillShape,
                                                            color = groupColor.copy(alpha = 0.13f)
                                                        ) {
                                                            Text(
                                                                text = stringResource(R.string.notif_tree_active_count, enabledCount, leaves.size),
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = groupColor,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = stringResource(group.descRes),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                Icon(
                                                    imageVector = Icons.Default.ExpandMore,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        .size(18.dp)
                                                        .graphicsLayer { rotationZ = chevronRot }
                                                )

                                                Switch(
                                                    checked = allEnabled && !isScopeMuted,
                                                    enabled = !isScopeMuted,
                                                    onCheckedChange = { next ->
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        if (scopedUser != null) {
                                                            settingsManager.setTargetTreeGroupEnabled(scopedUser, group, next)
                                                        } else {
                                                            settingsManager.setTreeGroupEnabled(group, next)
                                                        }
                                                    },
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = Color.White,
                                                        checkedTrackColor = groupColor
                                                    )
                                                )
                                            }

                                            // Collapsible Child Leaf Nodes with Crisp Tree Connector Lines
                                            AnimatedVisibility(
                                                visible = isExpanded,
                                                enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                                                exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(130))
                                            ) {
                                                val lineColor = groupColor.copy(alpha = 0.36f)
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 8.dp)
                                                ) {
                                                    leaves.forEachIndexed { leafIdx, leaf ->
                                                        val isLastLeaf = leafIdx == leaves.lastIndex
                                                        val leafChecked = remember(treeRevision, scopedUser, leaf) {
                                                            if (scopedUser != null) settingsManager.isTargetEventKeyEnabled(scopedUser, leaf)
                                                            else settingsManager.isEventKeyEnabled(leaf)
                                                        }

                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(IntrinsicSize.Min),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Canvas(
                                                                modifier = Modifier
                                                                    .width(26.dp)
                                                                    .fillMaxHeight()
                                                            ) {
                                                                val xTrunk = 12.dp.toPx()
                                                                val midY = size.height / 2f
                                                                val strokePx = 1.6.dp.toPx()
                                                                drawLine(
                                                                    color = lineColor,
                                                                    start = Offset(xTrunk, 0f),
                                                                    end = Offset(xTrunk, if (isLastLeaf) midY else size.height),
                                                                    strokeWidth = strokePx,
                                                                    cap = StrokeCap.Round
                                                                )
                                                                drawLine(
                                                                    color = lineColor,
                                                                    start = Offset(xTrunk, midY),
                                                                    end = Offset(size.width - 2.dp.toPx(), midY),
                                                                    strokeWidth = strokePx,
                                                                    cap = StrokeCap.Round
                                                                )
                                                            }

                                                            Row(
                                                                modifier = Modifier
                                                                    .weight(1f)
                                                                    .padding(vertical = 6.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Column(
                                                                    modifier = Modifier
                                                                        .weight(1f)
                                                                        .padding(end = 10.dp)
                                                                ) {
                                                                    Text(
                                                                        text = stringResource(leaf.titleRes),
                                                                        style = MaterialTheme.typography.bodyMedium,
                                                                        fontWeight = FontWeight.Medium,
                                                                        color = MaterialTheme.colorScheme.onSurface
                                                                    )
                                                                    Text(
                                                                        text = stringResource(leaf.descRes),
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        fontSize = 11.5.sp,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                    )
                                                                }

                                                                Switch(
                                                                    checked = leafChecked && !isScopeMuted,
                                                                    enabled = !isScopeMuted,
                                                                    onCheckedChange = { next ->
                                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                        if (scopedUser != null) {
                                                                            settingsManager.setTargetEventKeyEnabled(scopedUser, leaf, next)
                                                                        } else {
                                                                            settingsManager.setEventKeyEnabled(leaf, next)
                                                                        }
                                                                    },
                                                                    colors = SwitchDefaults.colors(
                                                                        checkedThumbColor = Color.White,
                                                                        checkedTrackColor = groupColor
                                                                    )
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
                        }

                        else -> {
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

                                    val modes = remember {
                                        listOf(
                                            FollowerPoolMode.OFF,
                                            FollowerPoolMode.FIXED_COUNT,
                                            FollowerPoolMode.PERCENTAGE,
                                            FollowerPoolMode.AUTO_ADAPTIVE
                                        )
                                    }
                                    val modeOptions = listOf(
                                        SegmentedOption(stringResource(R.string.pool_mode_off)),
                                        SegmentedOption(stringResource(R.string.pool_mode_fixed)),
                                        SegmentedOption(stringResource(R.string.pool_mode_percent)),
                                        SegmentedOption(stringResource(R.string.pool_mode_auto))
                                    )
                                    AnimatedSegmentedSelector(
                                        options = modeOptions,
                                        selectedIndex = modes.indexOf(poolMode).coerceAtLeast(0),
                                        onSelected = { idx -> settingsManager.setPoolMode(modes[idx]) },
                                        height = 40.dp
                                    )

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
}

@Composable
private fun ProfileScopeChip(
    label: String,
    isSelected: Boolean,
    hasCustomOverride: Boolean,
    isMuted: Boolean,
    isGlobal: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (isSelected) AccentBlue.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "scope_chip_bg"
    )
    val border by animateColorAsState(
        targetValue = if (isSelected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "scope_chip_bdr"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "scope_chip_txt"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(if (isSelected) 1.25.dp else 0.75.dp, border),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (isGlobal) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(14.dp)
                )
            } else if (isMuted) {
                Icon(
                    imageVector = Icons.Default.NotificationsOff,
                    contentDescription = null,
                    tint = com.instalogger.app.ui.theme.StatusRed,
                    modifier = Modifier.size(13.dp)
                )
            } else if (hasCustomOverride) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(AccentBlue)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
                maxLines = 1
            )
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
