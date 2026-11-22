package com.instalogger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.instalogger.app.R
import com.instalogger.app.data.local.NotificationEntity
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NotificationItem(
    notification: NotificationEntity,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(22.dp)
    val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(notification.timestamp))

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(
                0.75.dp,
                if (!notification.isRead) AccentBlue.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant,
                cardShape
            )
            .bouncyClickable(pressScale = 0.98f, onClick = { if (!notification.isRead) onMarkRead() }),
        shape = cardShape,
        color = if (notification.isRead) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (notification.isRead) 1.dp else 3.dp
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val iconShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(iconShape)
                    .background(
                        if (notification.isRead) MaterialTheme.colorScheme.surfaceContainerHighest
                        else AccentBlue.copy(alpha = 0.16f)
                    )
                    .border(
                        0.5.dp,
                        if (notification.isRead) MaterialTheme.colorScheme.outlineVariant else AccentBlue.copy(alpha = 0.3f),
                        iconShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = if (notification.isRead) MaterialTheme.colorScheme.onSurfaceVariant else AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = notification.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (notification.isRead) FontWeight.SemiBold else FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!notification.isRead) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(AccentBlue)
                        )
                    }
                }

                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = notification.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val actionShape = RoundedCornerShape(10.dp)
                if (!notification.isRead) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(actionShape)
                            .background(AccentBlue.copy(alpha = 0.12f))
                            .border(0.5.dp, AccentBlue.copy(alpha = 0.25f), actionShape)
                            .bouncyClickable(pressScale = 0.88f, onClick = onMarkRead),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.action_mark_read),
                            tint = AccentBlue,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(actionShape)
                        .background(StatusRed.copy(alpha = 0.1f))
                        .border(0.5.dp, StatusRed.copy(alpha = 0.22f), actionShape)
                        .bouncyClickable(pressScale = 0.88f, onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = StatusRed,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}
