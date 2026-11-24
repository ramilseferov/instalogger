package com.instalogger.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.instalogger.app.R
import com.instalogger.app.data.local.HighlightEntity
import com.instalogger.app.data.local.HighlightMediaEntity
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.util.VaultPlaybackBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun InstagramStoryViewer(
    highlight: HighlightEntity,
    mediaList: List<HighlightMediaEntity>,
    isLoading: Boolean = false,
    hasSession: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onNavigateToSession: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        if (isLoading || mediaList.isEmpty()) {
            HighlightEmptyOrLoadingOverlay(
                highlight = highlight,
                isLoading = isLoading,
                hasSession = hasSession,
                onRetry = onRetry,
                onNavigateToSession = onNavigateToSession,
                onDismiss = onDismiss
            )
            return@Dialog
        }

        var currentIndex by remember(highlight.id) { mutableIntStateOf(0) }
        var isPaused by remember { mutableStateOf(false) }
        var currentProgress by remember { mutableFloatStateOf(0f) }
        var activeDurationMs by remember { mutableLongStateOf(5000L) }

        val safeIndex = currentIndex.coerceIn(0, mediaList.size - 1)
        val currentMedia = mediaList[safeIndex]
        val localFile = remember(currentMedia.localPath) { File(currentMedia.localPath) }
        val isVideo = currentMedia.mediaType == "video" ||
            currentMedia.localPath.contains(".mp4", ignoreCase = true) ||
            currentMedia.localPath.contains(".webm", ignoreCase = true)

        // Reset duration to default 5s for images; videos override with real duration onPrepared
        LaunchedEffect(currentMedia.id) {
            activeDurationMs = if (isVideo) 15_000L else 5_000L
        }

        val stepIntervalMs = 50L
        LaunchedEffect(safeIndex, isPaused, activeDurationMs) {
            if (currentProgress >= 1f) currentProgress = 0f
            while (isActive) {
                delay(stepIntervalMs)
                if (!isPaused) {
                    val stepFraction = stepIntervalMs.toFloat() / activeDurationMs.coerceAtLeast(1000L).toFloat()
                    currentProgress += stepFraction
                    if (currentProgress >= 1f) {
                        currentProgress = 1f
                        if (currentIndex < mediaList.size - 1) {
                            currentIndex++
                            currentProgress = 0f
                        } else {
                            onDismiss()
                            break
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Media Render Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(safeIndex, mediaList.size) {
                        detectTapGestures(
                            onPress = {
                                isPaused = true
                                tryAwaitRelease()
                                isPaused = false
                            },
                            onTap = { offset ->
                                val screenWidth = size.width
                                if (offset.x < screenWidth * 0.35f) {
                                    if (currentIndex > 0) {
                                        currentIndex--
                                        currentProgress = 0f
                                    } else {
                                        onDismiss()
                                    }
                                } else {
                                    if (currentIndex < mediaList.size - 1) {
                                        currentIndex++
                                        currentProgress = 0f
                                    } else {
                                        onDismiss()
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isVideo) {
                    val playbackUri = remember(currentMedia.id, currentMedia.localPath) {
                        if (localFile.exists() && localFile.length() > 0L) {
                            VaultPlaybackBridge.prepareShareableUri(context, localFile, "mp4")?.first
                                ?: Uri.parse(currentMedia.mediaUrl)
                        } else {
                            Uri.parse(currentMedia.mediaUrl)
                        }
                    }

                    key(currentMedia.id) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(playbackUri)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = false
                                        if (mp.duration > 1000) {
                                            activeDurationMs = mp.duration.toLong()
                                        }
                                        start()
                                    }
                                    setOnCompletionListener {
                                        if (currentIndex < mediaList.size - 1) {
                                            currentIndex++
                                            currentProgress = 0f
                                        } else {
                                            onDismiss()
                                        }
                                    }
                                    setOnErrorListener { _, _, _ -> true }
                                }
                            },
                            update = { videoView ->
                                if (isPaused && videoView.isPlaying) {
                                    videoView.pause()
                                } else if (!isPaused && !videoView.isPlaying && videoView.currentPosition > 0) {
                                    videoView.start()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    val imageModel: Any = if (localFile.exists() && localFile.length() > 0L) localFile else currentMedia.mediaUrl
                    AsyncImage(
                        model = imageModel,
                        contentDescription = highlight.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Top Overlay: Segmented Progress Bars & Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    mediaList.forEachIndexed { idx, _ ->
                        val segmentProgress = when {
                            idx < safeIndex -> 1f
                            idx == safeIndex -> currentProgress.coerceIn(0f, 1f)
                            else -> 0f
                        }

                        val barShape = RoundedCornerShape(2.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(2.5.dp)
                                .clip(barShape)
                                .background(Color.White.copy(alpha = 0.35f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(segmentProgress)
                                    .background(Color.White)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val coverFile = highlight.localCoverPath?.let { File(it) }
                    val coverModel: Any? = when {
                        coverFile != null && coverFile.exists() && coverFile.length() > 0L -> coverFile
                        highlight.coverUrl.isNotBlank() -> highlight.coverUrl
                        else -> null
                    }

                    if (coverModel != null) {
                        AsyncImage(
                            model = coverModel,
                            contentDescription = highlight.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = highlight.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val timeFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
                        Text(
                            text = "${safeIndex + 1}/${mediaList.size} · ${timeFormat.format(Date(currentMedia.takenAt))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }

                    // Export / Share Archived Story Media (decrypted from Vault via VaultPlaybackBridge)
                    if (localFile.exists() && localFile.length() > 0L) {
                        IconButton(
                            onClick = {
                                try {
                                    val fallbackExt = if (isVideo) "mp4" else "jpg"
                                    val prepared = VaultPlaybackBridge.prepareShareableUri(context, localFile, fallbackExt)
                                    if (prepared != null) {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = prepared.second
                                            putExtra(Intent.EXTRA_STREAM, prepared.first)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_export_media)))
                                    }
                                } catch (_: Exception) {
                                    Toast.makeText(context, context.getString(R.string.error_share_media), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.action_export_media),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightEmptyOrLoadingOverlay(
    highlight: HighlightEntity,
    isLoading: Boolean,
    hasSession: Boolean,
    onRetry: (() -> Unit)?,
    onNavigateToSession: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val coverFile = highlight.localCoverPath?.let { File(it) }
            val coverModel: Any? = when {
                coverFile != null && coverFile.exists() && coverFile.length() > 0L -> coverFile
                highlight.coverUrl.isNotBlank() -> highlight.coverUrl
                else -> null
            }

            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = highlight.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                )
            }

            Text(
                text = highlight.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            if (isLoading) {
                CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                Text(
                    text = stringResource(R.string.highlight_loading_stories),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = stringResource(
                        if (!hasSession) R.string.highlight_session_required_title else R.string.highlight_empty_title
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(
                        if (!hasSession) R.string.highlight_session_required_desc else R.string.highlight_empty_desc
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!hasSession && onNavigateToSession != null) {
                        Button(
                            onClick = {
                                onDismiss()
                                onNavigateToSession()
                            },
                            shape = ShapeTokens.MediumShape,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_login_session))
                        }
                    }
                    if (onRetry != null) {
                        OutlinedButton(
                            onClick = onRetry,
                            shape = ShapeTokens.MediumShape
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_refresh_list), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
