package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.instalogger.app.R
import com.instalogger.app.data.local.NotificationEntity
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusAmber
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.*

private data class NotificationVisualSpec(
    val icon: ImageVector,
    val accentColor: Color,
    val badgeLabelRes: Int
)

@Composable
private fun resolveNotificationVisuals(eventType: String): NotificationVisualSpec {
    return when (eventType.lowercase()) {
        "follower_spike" -> NotificationVisualSpec(
            icon = Icons.Default.Bolt,
            accentColor = StatusAmber,
            badgeLabelRes = R.string.notif_badge_spike
        )
        "follower_pool_flush", "following_pool_flush" -> NotificationVisualSpec(
            icon = Icons.Default.Layers,
            accentColor = AccentBlue,
            badgeLabelRes = R.string.notif_badge_pool
        )
        "followers_change", "follower_count_change", "follower_gained", "follower_lost" -> NotificationVisualSpec(
            icon = Icons.AutoMirrored.Filled.TrendingUp,
            accentColor = StatusGreen,
            badgeLabelRes = R.string.notif_badge_followers
        )
        "following_change", "following_count_change", "following_gained", "following_lost" -> NotificationVisualSpec(
            icon = Icons.Default.People,
            accentColor = Color(0xFF6366F1),
            badgeLabelRes = R.string.notif_badge_following
        )
        "avatar_change" -> NotificationVisualSpec(
            icon = Icons.Default.AccountCircle,
            accentColor = Color(0xFF8B5CF6),
            badgeLabelRes = R.string.notif_badge_avatar
        )
        "bio_change", "name_change" -> NotificationVisualSpec(
            icon = Icons.Default.Edit,
            accentColor = Color(0xFF14B8A6),
            badgeLabelRes = R.string.notif_badge_profile
        )
        "privacy_change" -> NotificationVisualSpec(
            icon = Icons.Default.LockOpen,
            accentColor = StatusAmber,
            badgeLabelRes = R.string.notif_badge_privacy
        )
        "post_count_change" -> NotificationVisualSpec(
            icon = Icons.Default.GridOn,
            accentColor = AccentBlue,
            badgeLabelRes = R.string.notif_badge_posts
        )
        "story_posted" -> NotificationVisualSpec(
            icon = Icons.Default.PlayCircle,
            accentColor = Color(0xFFDD2A7B),
            badgeLabelRes = R.string.notif_badge_story
        )
        else -> NotificationVisualSpec(
            icon = Icons.Default.Notifications,
            accentColor = AccentBlue,
            badgeLabelRes = R.string.notif_badge_system
        )
    }
}

@Composable
fun NotificationItem(
    notification: NotificationEntity,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    onOpenTarget: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val cardShape = ShapeTokens.LargeShape
    val visuals = resolveNotificationVisuals(notification.eventType)

    val formattedTime = remember(notification.timestamp) {
        formatRelativeTimestamp(context, notification.timestamp)
    }

    val isUnread = !notification.isRead

    val containerColor by animateColorAsState(
        targetValue = if (isUnread) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(260),
        label = "notif_container_color"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isUnread) visuals.accentColor.copy(alpha = 0.42f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        animationSpec = tween(260),
        label = "notif_border_color"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = if (isUnread) visuals.accentColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(240),
        label = "notif_icon_bg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isUnread) visuals.accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(240),
        label = "notif_icon_tint"
    )
    val unreadStripAlpha by animateFloatAsState(
        targetValue = if (isUnread) 1f else 0f,
        animationSpec = tween(260),
        label = "notif_unread_strip"
    )

    val readBtnBg by animateColorAsState(
        targetValue = if (isUnread) AccentBlue.copy(alpha = 0.14f) else StatusGreen.copy(alpha = 0.10f),
        animationSpec = tween(240),
        label = "read_btn_bg"
    )
    val readBtnBorder by animateColorAsState(
        targetValue = if (isUnread) AccentBlue.copy(alpha = 0.35f) else StatusGreen.copy(alpha = 0.25f),
        animationSpec = tween(240),
        label = "read_btn_border"
    )
    val readBtnScale by animateFloatAsState(
        targetValue = if (isUnread) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "read_btn_scale"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(ShapeTokens.BorderSubtle, borderColor, cardShape)
            .bouncyClickable(pressScale = 0.985f) {
                if (isUnread) onMarkRead()
                if (notification.username.isNotBlank()) {
                    onOpenTarget(notification.username)
                }
            },
        shape = cardShape,
        color = containerColor,
        tonalElevation = if (isUnread) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Permanent left accent bar slot (animates alpha smoothly without shifting content)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(visuals.accentColor.copy(alpha = unreadStripAlpha))
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val iconShape = ShapeTokens.SmallShape
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(iconShape)
                            .background(iconContainerColor)
                            .border(
                                ShapeTokens.BorderSubtle,
                                if (isUnread) visuals.accentColor.copy(alpha = 0.32f) else MaterialTheme.colorScheme.outlineVariant,
                                iconShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = visuals.icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (notification.username.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Text(
                                        text = "@${notification.username}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = visuals.accentColor.copy(alpha = if (isUnread) 0.14f else 0.08f)
                            ) {
                                Text(
                                    text = stringResource(visuals.badgeLabelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isUnread) visuals.accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Fixed-width Action Slot: Animated Read Morph Button + Delete Button
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val actionShape = ShapeTokens.SmallShape

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .graphicsLayer {
                                    scaleX = readBtnScale
                                    scaleY = readBtnScale
                                }
                                .then(
                                    if (isUnread) {
                                        Modifier.bouncyClickable(pressScale = 0.85f) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onMarkRead()
                                        }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(actionShape)
                                    .background(readBtnBg)
                                    .border(ShapeTokens.BorderSubtle, readBtnBorder, actionShape),
                                contentAlignment = Alignment.Center
                            ) {
                                AnimatedContent(
                                    targetState = isUnread,
                                    transitionSpec = {
                                        (fadeIn(tween(180)) + scaleIn(
                                            initialScale = 0.55f,
                                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                        )) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.65f))
                                    },
                                    label = "read_icon_morph"
                                ) { unread ->
                                    if (unread) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = stringResource(R.string.action_mark_read),
                                            tint = AccentBlue,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.DoneAll,
                                            contentDescription = stringResource(R.string.notif_status_read),
                                            tint = StatusGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .bouncyClickable(pressScale = 0.85f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDelete()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(actionShape)
                                    .background(StatusRed.copy(alpha = 0.10f))
                                    .border(ShapeTokens.BorderSubtle, StatusRed.copy(alpha = 0.22f), actionShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = StatusRed,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }

                // Main Readable Content Box (Title + Structured Message)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = notification.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    if (notification.message.isNotBlank()) {
                        Text(
                            text = notification.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun formatRelativeTimestamp(context: android.content.Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = (now - timestamp).coerceAtLeast(0L)
    val clockStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    val dateStr = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

    return when {
        diffMs < 60_000L -> "${context.getString(R.string.time_just_now)} · $clockStr"
        diffMs < 3_600_000L -> "${context.getString(R.string.time_minutes_ago, (diffMs / 60_000L).toInt())} · $clockStr"
        diffMs < 86_400_000L -> "${context.getString(R.string.time_hours_ago, (diffMs / 3_600_000L).toInt())} · $clockStr"
        else -> dateStr
    }
}
