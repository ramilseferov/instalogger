package com.instalogger.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.settings.NotificationCategory
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.HeaderActionButton
import com.instalogger.app.ui.components.NotificationItem
import com.instalogger.app.ui.components.NotificationSettingsBottomSheet
import com.instalogger.app.ui.components.ReachabilityHeader
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.components.UnifiedEmptyState
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private enum class NotificationFilter(val key: String) {
    ALL("all"),
    UNREAD("unread"),
    ACTIVITY("activity"),
    PROFILE("profile"),
    PRIVACY("privacy")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    onNavigateToDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    val notifications by app.database.notificationDao().getAllNotifications().collectAsStateWithLifecycle(initialValue = emptyList())
    val unreadCount by app.database.notificationDao().getUnreadCount().collectAsStateWithLifecycle(initialValue = 0)

    var selectedFilter by remember { mutableStateOf(NotificationFilter.ALL) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val filteredNotifications = remember(notifications, selectedFilter) {
        when (selectedFilter) {
            NotificationFilter.ALL -> notifications
            NotificationFilter.UNREAD -> notifications.filter { !it.isRead }
            NotificationFilter.ACTIVITY -> notifications.filter {
                NotificationCategory.fromEventType(it.eventType) == NotificationCategory.ACTIVITY_UPDATES
            }
            NotificationFilter.PROFILE -> notifications.filter {
                NotificationCategory.fromEventType(it.eventType) == NotificationCategory.PROFILE_CHANGES
            }
            NotificationFilter.PRIVACY -> notifications.filter {
                NotificationCategory.fromEventType(it.eventType) == NotificationCategory.PRIVACY_ALERTS
            }
        }
    }

    fun refreshAll() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            withContext(Dispatchers.IO) {
                app.profileInspector.syncAllFromDaemon()
                val activeTargets = app.database.targetDao().getActiveTargets()
                val sem = Semaphore(3)
                coroutineScope {
                    activeTargets.map { t ->
                        async {
                            sem.withPermit {
                                try {
                                    app.profileInspector.inspectTarget(t.username)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            isRefreshing = false
        }
    }

    Scaffold(
        topBar = {
            ReachabilityHeader(
                title = stringResource(R.string.notifications_title),
                subtitle = stringResource(R.string.unread_notifications_format, unreadCount),
                logoResId = R.drawable.ic_header_notifications,
                badgeContent = {
                    AnimatedVisibility(
                        visible = unreadCount > 0,
                        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.85f),
                        exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.85f)
                    ) {
                        StatusBadge(
                            text = stringResource(R.string.badge_unread_format, unreadCount),
                            containerColor = AccentBlue.copy(alpha = 0.14f),
                            contentColor = AccentBlue,
                            borderColor = AccentBlue.copy(alpha = 0.3f)
                        )
                    }
                },
                actions = {
                    // Fixed 3-button slot layout: buttons NEVER disappear or teleport when unread/list changes
                    HeaderActionButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.notif_settings_sheet_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    HeaderActionButton(
                        enabled = unreadCount > 0,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch {
                                withContext(Dispatchers.IO) { app.database.notificationDao().markAllAsRead() }
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.msg_all_notifs_marked_read),
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = stringResource(R.string.btn_mark_all_read),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    HeaderActionButton(
                        enabled = notifications.isNotEmpty(),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showClearConfirmDialog = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.btn_clear_all_notifications),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Horizontal Filter Chips Bar (always visible in stable position)
            val filterOptions = listOf(
                NotificationFilter.ALL to "${stringResource(R.string.notif_filter_all)} (${notifications.size})",
                NotificationFilter.UNREAD to "${stringResource(R.string.notif_filter_unread)} ($unreadCount)",
                NotificationFilter.ACTIVITY to stringResource(R.string.notif_filter_activity),
                NotificationFilter.PROFILE to stringResource(R.string.notif_filter_profile),
                NotificationFilter.PRIVACY to stringResource(R.string.notif_filter_privacy)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filterOptions.forEach { (filter, label) ->
                    val isSelected = selectedFilter == filter
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) AccentBlue.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        animationSpec = tween(200),
                        label = "filter_bg"
                    )
                    val chipBorder by animateColorAsState(
                        targetValue = if (isSelected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
                        animationSpec = tween(200),
                        label = "filter_border"
                    )
                    val chipText by animateColorAsState(
                        targetValue = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200),
                        label = "filter_text"
                    )
                    val chipShape = RoundedCornerShape(12.dp)
                    Box(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(chipBg)
                            .border(if (isSelected) 1.25.dp else 0.75.dp, chipBorder, chipShape)
                            .bouncyClickable(pressScale = 0.94f) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedFilter = filter
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = chipText,
                            maxLines = 1
                        )
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refreshAll() },
                modifier = Modifier.fillMaxSize()
            ) {
                if (filteredNotifications.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        UnifiedEmptyState(
                            icon = Icons.Default.NotificationsNone,
                            title = if (notifications.isEmpty()) {
                                stringResource(R.string.empty_notifications_title)
                            } else {
                                stringResource(R.string.empty_filtered_notifications_title)
                            },
                            description = if (notifications.isEmpty()) {
                                stringResource(R.string.empty_notifications_desc)
                            } else {
                                stringResource(R.string.empty_filtered_notifications_desc)
                            }
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredNotifications, key = { it.id }) { item ->
                            NotificationItem(
                                notification = item,
                                onMarkRead = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            app.database.notificationDao().markAsRead(item.id)
                                        }
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            app.database.notificationDao().deleteById(item.id)
                                        }
                                    }
                                },
                                onOpenTarget = { username ->
                                    onNavigateToDetail(username)
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_clear_notifs_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_clear_notifs_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) { app.database.notificationDao().clearAll() }
                            snackbarHostState.showSnackbar(
                                message = context.getString(R.string.msg_all_notifs_cleared),
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = ShapeTokens.SmallShape
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmDialog = false },
                    shape = ShapeTokens.SmallShape
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = ShapeTokens.LargeShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    if (showSettingsSheet) {
        NotificationSettingsBottomSheet(onDismissRequest = { showSettingsSheet = false })
    }
}
