package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.instalogger.app.R
import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.AccentBlueDark
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.util.FormatUtils
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
    val cardShape = ShapeTokens.LargeShape
    val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    val lastCheckStr = target.lastCheckedAt?.let {
        stringResource(R.string.last_checked_format, timeFormat.format(Date(it)))
    } ?: stringResource(R.string.never_checked)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, cardShape),
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
                    // Profile Avatar Thumbnail with resilient fallback and loading state
                    val context = LocalContext.current
                    val localFile = target.localAvatarPath?.let { File(it) }
                    val internalFile = com.instalogger.app.util.ArchiveDirectoryManager.getInternalAvatar(context, target.username)
                    val archiveDirFile = File(com.instalogger.app.util.ArchiveDirectoryManager.getArchiveDirectory(context), "${target.username}/profile/avatar_current.jpg")
                    val resolvedAvatarFile = when {
                        internalFile != null && internalFile.exists() && internalFile.length() > 0 -> internalFile
                        localFile != null && localFile.exists() && localFile.canRead() && localFile.length() > 0 -> localFile
                        archiveDirFile.exists() && archiveDirFile.length() > 0 -> archiveDirFile
                        else -> null
                    }

                    val imageSource: Any? = when {
                        resolvedAvatarFile != null -> resolvedAvatarFile
                        !target.avatarUrl.isNullOrBlank() -> target.avatarUrl
                        else -> null
                    }

                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageSource)
                            .crossfade(true)
                            .setHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36")
                            .setHeader("Referer", "https://www.instagram.com/")
                            .build(),
                        contentDescription = target.username,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .border(ShapeTokens.BorderActive, AccentBlue.copy(alpha = 0.8f), CircleShape),
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = AccentBlue
                                )
                            }
                        },
                        error = {
                            AvatarInitialsFallback(username = target.username)
                        }
                    )

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
                            AnimatedVisibility(
                                visible = target.isTurbo,
                                enter = fadeIn(tween(200)) + scaleIn(
                                    initialScale = 0.7f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                ),
                                exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.7f)
                            ) {
                                TurboBadge(isTurbo = true)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Ergonomic Grouped Stats Container
                val statsShape = ShapeTokens.MediumShape
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(statsShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, statsShape)
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatItem(label = stringResource(R.string.followers), value = FormatUtils.formatCompactNumber(target.followersCount), modifier = Modifier.weight(1f))
                    StatItem(label = stringResource(R.string.following), value = FormatUtils.formatCompactNumber(target.followingCount), modifier = Modifier.weight(1f))
                    StatItem(label = stringResource(R.string.posts), value = FormatUtils.formatCompactNumber(target.mediaCount), modifier = Modifier.weight(1f))
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

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val actionButtonShape = ShapeTokens.SmallShape

                    // Refresh Button Pod (with 44dp min touch bounds)
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .bouncyClickable(pressScale = 0.90f, onClick = onCheckNow),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(actionButtonShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, actionButtonShape),
                            contentAlignment = Alignment.Center
                        ) {
                            RotatingRefreshButton(
                                isRefreshing = isChecking,
                                onClick = onCheckNow,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    // Turbo Toggle Pod with high-response spring animation
                    val isTurboActive = target.isTurbo
                    val haptic = LocalHapticFeedback.current

                    val turboBgColor by animateColorAsState(
                        targetValue = if (isTurboActive) AccentBlue.copy(alpha = 0.22f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        label = "turboBgColor"
                    )

                    val turboBorderColor by animateColorAsState(
                        targetValue = if (isTurboActive) AccentBlue.copy(alpha = 0.80f)
                        else MaterialTheme.colorScheme.outlineVariant,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        label = "turboBorderColor"
                    )

                    val turboIconTint by animateColorAsState(
                        targetValue = if (isTurboActive) AccentBlue
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 200),
                        label = "turboIconTint"
                    )

                    val boltScale by animateFloatAsState(
                        targetValue = if (isTurboActive) 1.15f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "boltScale"
                    )

                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .bouncyClickable(pressScale = 0.90f) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggleTurbo(!isTurboActive)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(actionButtonShape)
                                .background(turboBgColor)
                                .border(
                                    width = if (isTurboActive) ShapeTokens.BorderStandard else ShapeTokens.BorderSubtle,
                                    color = turboBorderColor,
                                    shape = actionButtonShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = stringResource(R.string.badge_turbo),
                                tint = turboIconTint,
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        scaleX = boltScale
                                        scaleY = boltScale
                                    }
                            )
                        }
                    }

                    // Delete Pod (with 44dp min touch bounds)
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .bouncyClickable(pressScale = 0.90f, onClick = onDelete),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(actionButtonShape)
                                .background(StatusRed.copy(alpha = 0.1f))
                                .border(ShapeTokens.BorderSubtle, StatusRed.copy(alpha = 0.25f), actionButtonShape),
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
}

@Composable
private fun AvatarInitialsFallback(username: String, modifier: Modifier = Modifier) {
    val initial = username.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            AccentBlue,
            AccentBlueDark
        )
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradientBrush),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
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
