package com.instalogger.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.data.repository.NotificationRepository
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.components.NotificationItem
import kotlinx.coroutines.launch

@Composable
fun NotificationCenterScreen(
    notificationRepository: NotificationRepository
) {
    val scope = rememberCoroutineScope()
    val notifications by notificationRepository.notifications.collectAsStateWithLifecycle(initialValue = emptyList())
    val unreadCount by notificationRepository.unreadCount.collectAsStateWithLifecycle(initialValue = 0)

    Scaffold(
        topBar = {
            InstaLoggerTopBar(
                title = "Bildiriş Mərkəzi",
                subtitle = if (unreadCount > 0) "$unreadCount oxunmamış bildiriş" else "Bütün bildirişlər oxunub",
                actions = {
                    if (notifications.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch { notificationRepository.markAllAsRead() }
                        }) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Hamısını Oxundu Et",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = {
                            scope.launch { notificationRepository.clearAll() }
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
        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
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
                        text = "Hədəflərdə hər hansı dəyişiklik aşkarlandıqda anlıq olaraq burada qeyd ediləcək.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notifications, key = { it.id }) { item ->
                    NotificationItem(
                        notification = item,
                        onMarkRead = {
                            scope.launch { notificationRepository.markAsRead(item.id) }
                        },
                        onDelete = {
                            scope.launch { notificationRepository.deleteNotification(item.id) }
                        }
                    )
                }
            }
        }
    }
}
