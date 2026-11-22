package com.instalogger.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.data.local.ChangeLogEntity
import com.instalogger.app.ui.components.AvatarComparisonCard
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.components.NotificationItem
import com.instalogger.app.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()

    val notifications by app.database.notificationDao().getAllNotifications().collectAsStateWithLifecycle(initialValue = emptyList())
    val unreadCount by app.database.notificationDao().getUnreadCount().collectAsStateWithLifecycle(initialValue = 0)
    val allLogs by app.database.changeLogDao().getAllLogs().collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            isRefreshing = true
            val activeTargets = withContext(Dispatchers.IO) { app.database.targetDao().getActiveTargets() }
            for (t in activeTargets) {
                try {
                    app.profileInspector.inspectTarget(t.username)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            isRefreshing = false
        }
    }

    Scaffold(
        topBar = {
            InstaLoggerTopBar(
                title = if (selectedTab == 0) "Audit Jurnalı" else "Bildirişlər",
                subtitle = if (selectedTab == 0) "Bütün hədəflər üzrə canlı qeydlər" else "${unreadCount} oxunmamış bildiriş",
                actions = {
                    if (selectedTab == 0 && allLogs.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { app.database.changeLogDao().clearAll() }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Bütün Loqları Sil",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (selectedTab == 1 && notifications.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { app.database.notificationDao().markAllAsRead() }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Hamısını Oxundu Et",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { app.database.notificationDao().clearAll() }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Hamısını Sil",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab selector
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = AccentBlue
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Audit Jurnalı (${allLogs.size})") },
                    icon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Bildirişlər (${notifications.size})") },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refreshAll() },
                modifier = Modifier.fillMaxSize()
            ) {
                when (selectedTab) {
                    0 -> {
                        // All change logs across all targets
                        if (allLogs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Audit jurnalı boşdur",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Hədəf profillərdə aşkar edilən hər bir dəyişiklik avtomatik burada toplanır. Yeniləmək üçün aşağı sürüşdürün.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(allLogs, key = { it.id }) { log ->
                                    if (log.eventType == "avatar_change") {
                                        AvatarComparisonCard(log = log)
                                    } else {
                                        GlobalLogCard(
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
                    1 -> {
                        // System Notifications
                        if (notifications.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NotificationsNone,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Bildiriş yoxdur",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Yeni dəyişiklik aşkarlandıqda anlıq olaraq burada qeyd ediləcək.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
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
                                        }
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
private fun GlobalLogCard(
    log: ChangeLogEntity,
    onDelete: () -> Unit
) {
    val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(log.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "@${log.targetUsername} — ${log.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!log.oldValue.isNullOrBlank() || !log.newValue.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${log.oldValue ?: "Yoxdur"}  ->  ${log.newValue ?: "Yoxdur"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
