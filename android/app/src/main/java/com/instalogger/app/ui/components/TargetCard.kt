package com.instalogger.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    val cardShape = RoundedCornerShape(26.dp)
    val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    val lastCheckStr = target.lastCheckedAt?.let {
        stringResource(R.string.last_checked_format, timeFormat.format(Date(it)))
    } ?: stringResource(R.string.never_checked)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, cardShape)
            .clickable { onClick() },
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
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
                        model = imageSource,
                        contentDescription = target.username,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, AccentBlue, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
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

                Column(
                    modifier = Modifier.weight(1f)
                ) {
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

                    Spacer(modifier = Modifier.height(4.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

            // Stats row (No overflow, equal distribution)
            val followersVal = if (target.followersCount > 0) target.followersCount.toString() else if (target.isPrivate) "—" else "0"
            val followingVal = if (target.followingCount > 0) target.followingCount.toString() else if (target.isPrivate) "—" else "0"
            val postsVal = if (target.mediaCount > 0) target.mediaCount.toString() else "0"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(label = stringResource(R.string.followers), value = followersVal, modifier = Modifier.weight(1f))
                StatItem(label = stringResource(R.string.following), value = followingVal, modifier = Modifier.weight(1f))
                StatItem(label = stringResource(R.string.posts), value = postsVal, modifier = Modifier.weight(1f))
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline
            )

            // Bottom Action Row
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

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Smooth rotating refresh button
                    RotatingRefreshButton(
                        isRefreshing = isChecking,
                        onClick = onCheckNow,
                        modifier = Modifier.size(36.dp)
                    )

                    IconButton(
                        onClick = { onToggleTurbo(!target.isTurbo) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = stringResource(R.string.badge_turbo),
                            tint = if (target.isTurbo) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = StatusRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
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
