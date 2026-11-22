package com.instalogger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.instalogger.app.R
import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.StatusRed
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TargetCard(
    target: TargetEntity,
    isChecking: Boolean,
    onClick: () -> Unit,
    onCheckNow: () -> Unit,
    onToggleTurbo: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(24.dp)
    val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    val lastCheckStr = target.lastCheckedAt?.let {
        stringResource(R.string.last_checked_format, timeFormat.format(Date(it)))
    } ?: stringResource(R.string.never_checked)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, cardShape),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Main clickable target body that navigates to detail screen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bouncyClickable(pressScale = 0.98f, onClick = onClick)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Profile Avatar Thumbnail
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val localFile = target.localAvatarPath?.let { File(it) }
                    val internalFile = com.instalogger.app.util.ArchiveDirectoryManager.getInternalAvatar(context, target.username)
                    val resolvedAvatarFile = when {
                        internalFile != null && internalFile.exists() && internalFile.length() > 0 -> internalFile
                        localFile != null && localFile.exists() && localFile.canRead() && localFile.length() > 0 -> localFile
                        else -> null
                    }

                    val imageSource: Any? = when {
                        resolvedAvatarFile != null -> resolvedAvatarFile
                        !target.avatarUrl.isNullOrBlank() -> target.avatarUrl
                        !target.instagramId.isNullOrBlank() -> "http://127.0.0.1:3000/archives/${target.instagramId}/profile/avatar_current.jpg"
                        else -> "http://127.0.0.1:3000/archives/${target.username}/profile/avatar_current.jpg"
                    }

                    if (imageSource != null) {
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(context)
                                .data(imageSource)
                                .crossfade(true)
                                .build(),
                            contentDescription = target.username,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, AccentBlue.copy(alpha = 0.8f), CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "@${target.username}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (target.fullName.isNotBlank()) {
                            Text(
                                text = target.fullName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PrivacyBadge(isPrivate = target.isPrivate)
                            if (target.isTurbo) {
                                TurboBadge(isTurbo = true)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Ergonomic Grouped Stats Container
                val statsShape = RoundedCornerShape(16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(statsShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, statsShape)
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatItem(label = stringResource(R.string.followers), value = target.followersCount.toString(), modifier = Modifier.weight(1f))
                    StatItem(label = stringResource(R.string.following), value = target.followingCount.toString(), modifier = Modifier.weight(1f))
                    StatItem(label = stringResource(R.string.posts), value = target.mediaCount.toString(), modifier = Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Action & Status Row (Completely isolated from card body click)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = lastCheckStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val actionButtonShape = RoundedCornerShape(12.dp)

                    // Refresh Button Pod
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(actionButtonShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, actionButtonShape),
                        contentAlignment = Alignment.Center
                    ) {
                        RotatingRefreshButton(
                            isRefreshing = isChecking,
                            onClick = onCheckNow,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Turbo Toggle Pod
                    val isTurboActive = target.isTurbo
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(actionButtonShape)
                            .background(
                                if (isTurboActive) AccentBlue.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .border(
                                width = if (isTurboActive) 1.dp else 0.5.dp,
                                color = if (isTurboActive) AccentBlue.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                                shape = actionButtonShape
                            )
                            .bouncyClickable(pressScale = 0.94f) {
                                onToggleTurbo(!isTurboActive)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = stringResource(R.string.badge_turbo),
                            tint = if (isTurboActive) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Delete Pod
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(actionButtonShape)
                            .background(StatusRed.copy(alpha = 0.1f))
                            .border(0.5.dp, StatusRed.copy(alpha = 0.25f), actionButtonShape)
                            .bouncyClickable(pressScale = 0.94f, onClick = onDelete),
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
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
