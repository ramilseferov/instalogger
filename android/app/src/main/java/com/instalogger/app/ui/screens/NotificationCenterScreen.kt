package com.instalogger.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.ui.components.HeaderActionButton
import com.instalogger.app.ui.components.NotificationItem
import com.instalogger.app.ui.components.NotificationSettingsBottomSheet
import com.instalogger.app.ui.components.ReachabilityHeader
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()

    val notifications by app.database.notificationDao().getAllNotifications().collectAsStateWithLifecycle(initialValue = emptyList())
    val unreadCount by app.database.notificationDao().getUnreadCount().collectAsStateWithLifecycle(initialValue = 0)

    var isRefreshing by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            isRefreshing = true
            withContext(Dispatchers.IO) {
                app.profileInspector.syncAllFromDaemon()
                val activeTargets = app.database.targetDao().getActiveTargets()
                for (t in activeTargets) {
                    try {
                        app.profileInspector.inspectTarget(t.username)
                    } catch (e: Exception) {
                        // Ignore
                    }
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
                badgeContent = {
                    if (unreadCount > 0) {
                        StatusBadge(
                            text = "$unreadCount Yeni",
                            containerColor = AccentBlue.copy(alpha = 0.14f),
                            contentColor = AccentBlue,
                            borderColor = AccentBlue.copy(alpha = 0.3f)
                        )
                    }
                },
                actions = {
                    HeaderActionButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.notif_settings_sheet_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    if (notifications.isNotEmpty()) {
                        HeaderActionButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { app.database.notificationDao().markAllAsRead() }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = stringResource(R.string.btn_mark_all_read),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        HeaderActionButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { app.database.notificationDao().clearAll() }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.btn_clear_all_notifications),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refreshAll() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (notifications.isEmpty()) {
                val emptyShape = RoundedCornerShape(24.dp)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(emptyShape)
                            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, emptyShape),
                        shape = emptyShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val iconShape = CircleShape
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(iconShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, iconShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsNone,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = stringResource(R.string.empty_notifications_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.empty_notifications_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(notifications, key = { it.id }) { item ->
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
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        NotificationSettingsBottomSheet(onDismissRequest = { showSettingsSheet = false })
    }
}
