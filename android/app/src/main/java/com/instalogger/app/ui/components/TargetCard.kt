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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.instalogger.app.R
import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.AccentBlueDark
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val ActiveStoryRingBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFFF58529),
        Color(0xFFFEDA77),
        Color(0xFFDD2A7B),
        Color(0xFF8134AF),
        Color(0xFF515BD4)
    )
)

private val AvatarFallbackBrush = Brush.linearGradient(
    colors = listOf(
        AccentBlue,
        AccentBlueDark
    )
)

private const val IG_IMAGE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val TargetTimeFormatThreadLocal = ThreadLocal.withInitial {
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
}

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
    val formattedLastCheck = remember(target.lastCheckedAt) {
        target.lastCheckedAt?.let { TargetTimeFormatThreadLocal.get()?.format(Date(it)) }
    }
    val lastCheckStr = if (formattedLastCheck != null) {
        stringResource(R.string.last_checked_format, formattedLastCheck)
    } else {
        stringResource(R.string.never_checked)
    }

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
                    val context = LocalContext.current
                    val imageSource by produceState<Any?>(
                        initialValue = target.avatarUrl?.takeIf { it.isNotBlank() },
                        key1 = target.username,
                        key2 = target.localAvatarPath,
                        key3 = target.avatarHash ?: target.lastCheckedAt
                    ) {
                        val resolvedFile = withContext(Dispatchers.IO) {
                            val localFile = target.localAvatarPath?.let { File(it) }
                            val internalFile = com.instalogger.app.util.ArchiveDirectoryManager.getInternalAvatar(context, target.username)
                            val archiveDirFile = File(
                                com.instalogger.app.util.ArchiveDirectoryManager.getArchiveDirectory(context),
                                "${target.username}/profile/avatar_current.jpg"
                            )
                            when {
                                internalFile != null && internalFile.exists() && internalFile.length() > 0L -> internalFile
                                localFile != null && localFile.exists() && localFile.canRead() && localFile.length() > 0L -> localFile
                                archiveDirFile.exists() && archiveDirFile.length() > 0L -> archiveDirFile
                                else -> null
                            }
                        }
                        value = resolvedFile ?: target.avatarUrl?.takeIf { it.isNotBlank() }
                    }

                    val avatarCacheKey = remember(target.username, target.avatarHash, target.lastCheckedAt) {
                        "avatar_${target.username.lowercase()}_${target.avatarHash ?: target.lastCheckedAt ?: 0L}"
                    }
                    val avatarImageRequest = remember(context, imageSource, avatarCacheKey) {
                        if (imageSource == null) {
                            null
                        } else {
                            ImageRequest.Builder(context)
                                .data(imageSource)
                                .memoryCacheKey(avatarCacheKey)
                                .size(192)
                                .crossfade(false)
                                .setHeader("User-Agent", IG_IMAGE_USER_AGENT)
                                .build()
                        }
                    }

                    // Fixed 60dp avatar container so toggling active story ring NEVER shifts username/badges
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .then(
                                if (target.hasActiveStory) {
                                    Modifier
                                        .border(2.5.dp, ActiveStoryRingBrush, CircleShape)
                                        .padding(3.dp)
                                } else {
                                    Modifier.padding(3.dp)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .then(
                                    if (!target.hasActiveStory) {
                                        Modifier.border(ShapeTokens.BorderActive, AccentBlue.copy(alpha = 0.8f), CircleShape)
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AvatarInitialsFallback(username = target.username)
                            if (avatarImageRequest != null) {
                                AsyncImage(
                                    model = avatarImageRequest,
                                    contentDescription = target.username,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "@${target.username}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (target.hasActiveStory) {
                                Box(
                                    modifier = Modifier
                                        .clip(ShapeTokens.PillShape)
                                        .background(Color(0xFFDD2A7B).copy(alpha = 0.16f))
                                        .border(0.75.dp, Color(0xFFDD2A7B).copy(alpha = 0.5f), ShapeTokens.PillShape)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.badge_active_story),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFDD2A7B),
                                        maxLines = 1
                                    )
                                }
                            }
                        }

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

                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .then(
                                if (!isChecking) Modifier.bouncyClickable(pressScale = 0.90f, onClick = onCheckNow)
                                else Modifier
                            ),
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
                            RotatingRefreshIcon(
                                isRefreshing = isChecking,
                                iconSize = 19.dp
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
    val initial = remember(username) {
        username.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AvatarFallbackBrush),
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
