package com.instalogger.app.ui.components

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.instalogger.app.data.local.HighlightEntity
import com.instalogger.app.data.local.HighlightMediaEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun InstagramStoryViewer(
    highlight: HighlightEntity,
    mediaList: List<HighlightMediaEntity>,
    onDismiss: () -> Unit
) {
    if (mediaList.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableFloatStateOf(0f) }

    val safeIndex = currentIndex.coerceIn(0, mediaList.size - 1)
    val currentMedia = mediaList[safeIndex]

    val storyDurationMs = 5000L
    val stepIntervalMs = 50L
    val stepFraction = stepIntervalMs.toFloat() / storyDurationMs.toFloat()

    // Progress bar auto-advancer
    LaunchedEffect(safeIndex, isPaused) {
        currentProgress = 0f
        while (isActive) {
            delay(stepIntervalMs)
            if (!isPaused) {
                currentProgress += stepFraction
                if (currentProgress >= 1f) {
                    currentProgress = 1f
                    if (currentIndex < mediaList.size - 1) {
                        currentIndex++
                    } else {
                        onDismiss()
                        break
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Media Render Area (Background)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(safeIndex) {
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
                                    } else {
                                        onDismiss()
                                    }
                                } else {
                                    if (currentIndex < mediaList.size - 1) {
                                        currentIndex++
                                    } else {
                                        onDismiss()
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val localFile = File(currentMedia.localPath)
                val isVideo = currentMedia.mediaType == "video" || currentMedia.localPath.endsWith(".mp4", ignoreCase = true)

                if (isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val uri = if (localFile.exists() && localFile.length() > 0L) {
                                    Uri.fromFile(localFile)
                                } else {
                                    Uri.parse(currentMedia.mediaUrl)
                                }
                                setVideoURI(uri)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = false
                                    start()
                                }
                                setOnCompletionListener {
                                    if (currentIndex < mediaList.size - 1) {
                                        currentIndex++
                                    } else {
                                        onDismiss()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val imageModel: Any = if (localFile.exists() && localFile.length() > 0L) localFile else currentMedia.mediaUrl
                    AsyncImage(
                        model = imageModel,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Top Overlay: Segmented Progress Bars and Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Segmented Progress Bar
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

                // Story Header Info
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
                        val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                        val formattedTime = timeFormat.format(Date(currentMedia.takenAt))
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f)
                        )
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
