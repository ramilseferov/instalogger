package com.instalogger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.instalogger.app.R
import com.instalogger.app.data.local.ChangeLogEntity
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusGreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AvatarComparisonCard(
    log: ChangeLogEntity,
    modifier: Modifier = Modifier
) {
    val cardShape = ShapeTokens.LargeShape
    val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(log.timestamp))

    var previewImagePath by remember { mutableStateOf<String?>(null) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(
                    text = stringResource(R.string.avatar_updated_badge),
                    icon = Icons.Default.Image,
                    containerColor = AccentBlue.copy(alpha = 0.14f),
                    contentColor = AccentBlue,
                    borderColor = AccentBlue.copy(alpha = 0.3f)
                )

                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Visual Diff Row: Old vs New Avatar in Grouped Pod
            val diffPodShape = ShapeTokens.MediumShape
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(diffPodShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, diffPodShape)
                    .padding(vertical = 14.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Previous Avatar
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.avatar_previous),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )

                    AvatarThumbnail(
                        path = log.oldAvatarPath ?: log.oldValue,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        onClick = {
                            val p = log.oldAvatarPath ?: log.oldValue
                            if (!p.isNullOrBlank()) previewImagePath = p
                        }
                    )
                }

                val arrowPodShape = CircleShape
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(arrowPodShape)
                        .background(AccentBlue.copy(alpha = 0.14f))
                        .border(ShapeTokens.BorderSubtle, AccentBlue.copy(alpha = 0.3f), arrowPodShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // New Avatar
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.avatar_new),
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusGreen,
                        fontWeight = FontWeight.Bold
                    )

                    AvatarThumbnail(
                        path = log.newAvatarPath ?: log.newValue,
                        borderColor = StatusGreen,
                        onClick = {
                            val p = log.newAvatarPath ?: log.newValue
                            if (!p.isNullOrBlank()) previewImagePath = p
                        }
                    )
                }
            }
        }
    }

    previewImagePath?.let { path ->
        ImagePreviewDialog(
            imagePath = path,
            onDismiss = { previewImagePath = null }
        )
    }
}

@Composable
private fun AvatarThumbnail(
    path: String?,
    borderColor: Color,
    onClick: () -> Unit
) {
    val file = path?.let { File(it) }
    val exists = file != null && file.exists()

    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .border(ShapeTokens.BorderActive, borderColor, CircleShape)
            .bouncyClickable(enabled = exists, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (exists) {
            AsyncImage(
                model = file,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
