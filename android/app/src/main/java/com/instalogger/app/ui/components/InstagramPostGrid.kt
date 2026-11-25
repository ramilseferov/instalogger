package com.instalogger.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.MediaAssetEntity
import com.instalogger.app.data.local.PostEntity
import com.instalogger.app.engine.media.PostDownloadState
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.util.ArchiveDirectoryManager
import com.instalogger.app.util.VaultPlaybackBridge
import com.instalogger.app.util.cleanDisplayText
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun InstagramPostGrid(
    posts: List<PostEntity>,
    onPostClick: (PostEntity) -> Unit,
    downloadStates: Map<String, PostDownloadState> = emptyMap(),
    modifier: Modifier = Modifier
) {
    if (posts.isEmpty()) {
        UnifiedEmptyState(
            title = stringResource(R.string.empty_target_posts),
            description = "",
            icon = Icons.Default.GridOn,
            modifier = modifier.fillMaxSize()
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(posts, key = { it.id }) { post ->
            PostGridItem(
                post = post,
                downloadState = downloadStates[post.id] ?: PostDownloadState.Idle,
                onClick = { onPostClick(post) }
            )
        }
    }
}

@Composable
fun PostGridItem(
    post: PostEntity,
    onClick: () -> Unit,
    downloadState: PostDownloadState = PostDownloadState.Idle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val thumbFile = post.localThumbPath?.let { File(it) }
    val localFile = post.localPath?.let { File(it) }
    val localImageFile = localFile?.takeIf { !it.name.contains(".mp4", ignoreCase = true) && !it.name.contains(".webm", ignoreCase = true) }
    val extArchive = File(ArchiveDirectoryManager.getArchiveDirectory(context), "${post.username}/posts/${post.id}.jpg")
    val resolvedModel: Any = when {
        thumbFile != null && thumbFile.exists() && thumbFile.length() > 0L -> thumbFile
        localImageFile != null && localImageFile.exists() && localImageFile.length() > 0L -> localImageFile
        extArchive.exists() && extArchive.length() > 0L -> extArchive
        post.displayUrl.isNotBlank() -> post.displayUrl
        else -> ""
    }

    val isArchivedOffline = !post.localPath.isNullOrBlank() && File(post.localPath).exists()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .bouncyClickable(pressScale = 0.96f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = resolvedModel,
            contentDescription = post.caption,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Top-right indicator: Live Download Progress OR Carousel Slide Count OR Video Badge
        if (downloadState.isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                if (downloadState is PostDownloadState.Downloading && downloadState.progress > 0f) {
                    CircularProgressIndicator(
                        progress = { (downloadState.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = AccentBlue,
                        trackColor = Color.White.copy(alpha = 0.25f)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AccentBlue
                    )
                }
            }
        } else if (post.slideCount > 1 || post.mediaType == "carousel") {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .clip(ShapeTokens.PillShape)
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = "Carousel",
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                    if (post.slideCount > 1) {
                        Text(
                            text = "${post.slideCount}",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else if (post.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .clip(ShapeTokens.PillShape)
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    val dur = post.durationSeconds
                    if (dur != null && dur > 0) {
                        Text(
                            text = formatDuration(dur),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Bottom-left like count badge if available
        if (post.likeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(ShapeTokens.PillShape)
                    .background(Color.Black.copy(alpha = 0.60f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "${post.likeCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Bottom-right archived Vault indicator
        if (isArchivedOffline && !downloadState.isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = "Vault Archived",
                    tint = StatusGreen,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@Composable
fun InstagramPostViewerDialog(
    post: PostEntity,
    targetAvatarModel: Any? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp

    val assets by app.database.mediaAssetDao()
        .getAssetsForPostFlow(post.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val archivedCommentsCount by app.database.postCommentDao()
        .getCommentsCountForPostFlow(post.id)
        .collectAsStateWithLifecycle(initialValue = 0)
    val downloadStates by app.mediaDownloadOrchestrator.downloadStates.collectAsStateWithLifecycle()
    val downloadState = downloadStates[post.id] ?: PostDownloadState.Idle
    var showCommentsSheet by remember(post.id) { mutableStateOf(false) }

    val timeFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val formattedDate = timeFormat.format(Date(post.postedAt))

    // Resolve carousel slides or single-post virtual slide
    val slides: List<ViewerSlide> = remember(post, assets) {
        if (assets.isNotEmpty()) {
            assets.map { asset ->
                val isVid = asset.mediaType == "video"
                val vaultFile = asset.localVaultPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                val thumbFile = asset.thumbVaultPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                val previewModel: Any = when {
                    thumbFile != null -> thumbFile
                    !isVid && vaultFile != null -> vaultFile
                    asset.remoteUrl.isNotBlank() && !isVid -> asset.remoteUrl
                    post.displayUrl.isNotBlank() -> post.displayUrl
                    else -> ""
                }
                ViewerSlide(
                    isVideo = isVid,
                    previewModel = previewModel,
                    mediaFile = vaultFile,
                    providerName = asset.providerName,
                    durationSeconds = asset.durationSeconds
                )
            }
        } else {
            val localFile = post.localPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            val thumbFile = post.localThumbPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            val localImageFile = localFile?.takeIf { !it.name.contains(".mp4", ignoreCase = true) && !it.name.contains(".webm", ignoreCase = true) }
            val extArchive = File(ArchiveDirectoryManager.getArchiveDirectory(context), "${post.username}/posts/${post.id}.jpg")
            val previewModel: Any = when {
                thumbFile != null -> thumbFile
                localImageFile != null -> localImageFile
                extArchive.exists() && extArchive.length() > 0L -> extArchive
                post.displayUrl.isNotBlank() -> post.displayUrl
                else -> ""
            }
            listOf(
                ViewerSlide(
                    isVideo = post.isVideo,
                    previewModel = previewModel,
                    mediaFile = localFile,
                    providerName = post.downloadEngine,
                    durationSeconds = post.durationSeconds
                )
            )
        }
    }

    val pagerState = rememberPagerState(pageCount = { slides.size.coerceAtLeast(1) })
    val activeSlide = slides.getOrElse(pagerState.currentPage) { slides.first() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (targetAvatarModel != null) {
                            AsyncImage(
                                model = targetAvatarModel,
                                contentDescription = post.username,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, AccentBlue, CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.DarkGray),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(22.dp))
                            }
                        }

                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "@${post.username}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val engineLabel = activeSlide.providerName ?: post.downloadEngine
                                if (!engineLabel.isNullOrBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .clip(ShapeTokens.PillShape)
                                            .background(AccentBlue.copy(alpha = 0.22f))
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = engineLabel,
                                            color = AccentBlue,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            Text(
                                text = formattedDate,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (slides.size > 1) {
                        Box(
                            modifier = Modifier
                                .clip(ShapeTokens.PillShape)
                                .background(Color.White.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1} / ${slides.size}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Middle Media Preview Area (HorizontalPager for Carousels & Single Posts)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val slide = slides[page]
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = slide.previewModel,
                                contentDescription = post.caption,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (slide.isVideo) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.68f))
                                        .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                                        .bouncyClickable {
                                            val file = slide.mediaFile
                                            if (file != null && file.exists()) {
                                                try {
                                                    val prepared = VaultPlaybackBridge.prepareShareableUri(context, file, "mp4")
                                                    if (prepared != null) {
                                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                                            setDataAndType(prepared.first, prepared.second)
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        context.startActivity(intent)
                                                    } else {
                                                        Toast.makeText(context, context.getString(R.string.error_open_video_player), Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, context.getString(R.string.error_open_video_player), Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                // Auto-trigger yt-dlp download if video is not archived yet
                                                app.mediaDownloadOrchestrator.enqueuePost(
                                                    post = post,
                                                    userInitiated = true,
                                                    forceRedownload = false
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (slide.mediaFile != null) Icons.Default.PlayArrow else Icons.Default.Download,
                                        contentDescription = "Play Video",
                                        tint = Color.White,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Live Download Progress Telemetry Strip
                if (downloadState !is PostDownloadState.Idle) {
                    DownloadStatusStrip(
                        state = downloadState,
                        onCancel = { app.mediaDownloadOrchestrator.cancelDownload(post.id) }
                    )
                }

                // Bottom Content & Actions Area
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Default.Favorite, contentDescription = null, tint = StatusRed, modifier = Modifier.size(20.dp))
                                Text(
                                    text = stringResource(R.string.likes_count_format, post.likeCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Surface(
                                shape = ShapeTokens.PillShape,
                                color = AccentBlue.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .clip(ShapeTokens.PillShape)
                                    .bouncyClickable(pressScale = 0.95f) { showCommentsSheet = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ChatBubbleOutline,
                                        contentDescription = stringResource(R.string.comments_sheet_title),
                                        tint = AccentBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    val displayCommentCount = maxOf(post.commentCount, archivedCommentsCount)
                                    Text(
                                        text = stringResource(R.string.comments_count_format, displayCommentCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AccentBlue,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (archivedCommentsCount > 0) {
                                        Icon(
                                            Icons.Default.VerifiedUser,
                                            contentDescription = null,
                                            tint = StatusGreen,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Right action buttons: Download/Redownload via yt-dlp/Orchestrator, Export/Share, Open IG
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = {
                                    if (downloadState.isActive) {
                                        app.mediaDownloadOrchestrator.cancelDownload(post.id)
                                    } else {
                                        app.mediaDownloadOrchestrator.enqueuePost(
                                            post = post,
                                            userInitiated = true,
                                            forceRedownload = true
                                        )
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        downloadState.isActive -> Icons.Default.Close
                                        activeSlide.mediaFile != null -> Icons.Default.Refresh
                                        else -> Icons.Default.Download
                                    },
                                    contentDescription = stringResource(
                                        if (activeSlide.mediaFile != null) R.string.action_redownload_hd else R.string.action_download_hd
                                    ),
                                    tint = if (downloadState.isActive) StatusRed else AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    val file = activeSlide.mediaFile
                                    if (file != null && file.exists()) {
                                        try {
                                            val fallbackExt = if (activeSlide.isVideo) "mp4" else "jpg"
                                            val prepared = VaultPlaybackBridge.prepareShareableUri(context, file, fallbackExt)
                                            if (prepared != null) {
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = prepared.second
                                                    putExtra(Intent.EXTRA_STREAM, prepared.first)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_export_media)))
                                            } else {
                                                Toast.makeText(context, context.getString(R.string.error_share_media), Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (_: Exception) {
                                            Toast.makeText(context, context.getString(R.string.error_share_media), Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.error_media_not_local), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_export_media), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                            }

                            IconButton(
                                onClick = {
                                    val uri = Uri.parse("https://instagram.com/p/${post.id}")
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, context.getString(R.string.error_open_instagram), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.view_on_instagram), tint = AccentBlue, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    val caption = post.caption?.cleanDisplayText()
                    if (!caption.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Instagram Caption", caption))
                                Toast.makeText(context, context.getString(R.string.msg_caption_copied), Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_copy_caption),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCommentsSheet) {
        PostCommentsBottomSheet(
            post = post,
            onDismiss = { showCommentsSheet = false }
        )
    }
}

private data class ViewerSlide(
    val isVideo: Boolean,
    val previewModel: Any,
    val mediaFile: File?,
    val providerName: String?,
    val durationSeconds: Int?
)

@Composable
private fun DownloadStatusStrip(
    state: PostDownloadState,
    onCancel: () -> Unit
) {
    val label = when (state) {
        is PostDownloadState.Queued -> stringResource(R.string.download_stage_queued)
        is PostDownloadState.Extracting -> stringResource(R.string.download_stage_extracting)
        is PostDownloadState.Downloading -> {
            val base = stringResource(R.string.download_stage_downloading, state.progress.toInt())
            val extra = listOfNotNull(
                state.detail?.takeIf { it.isNotBlank() },
                state.etaSeconds.takeIf { it > 0L }?.let { "${it}s" }
            ).joinToString(" · ")
            if (extra.isNotBlank()) "$base ($extra)" else base
        }
        is PostDownloadState.Merging -> stringResource(R.string.download_stage_merging)
        is PostDownloadState.Encrypting -> stringResource(R.string.download_stage_encrypting)
        is PostDownloadState.Paused -> stringResource(R.string.download_stage_paused)
        is PostDownloadState.Completed -> stringResource(R.string.download_stage_completed, state.providerId)
        is PostDownloadState.Cancelled -> stringResource(R.string.download_stage_cancelled)
        is PostDownloadState.Failed -> stringResource(R.string.download_stage_failed, state.message)
        is PostDownloadState.Idle -> ""
    }

    val accent = when (state) {
        is PostDownloadState.Completed -> StatusGreen
        is PostDownloadState.Failed -> StatusRed
        is PostDownloadState.Paused -> com.instalogger.app.ui.theme.StatusAmber
        is PostDownloadState.Cancelled -> Color.Gray
        else -> AccentBlue
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF14171D))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.isActive) {
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel_download),
                        color = StatusRed,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (state.isActive) {
            if (state is PostDownloadState.Downloading && state.progress > 0f) {
                LinearProgressIndicator(
                    progress = { (state.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accent,
                    trackColor = Color.White.copy(alpha = 0.15f)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accent,
                    trackColor = Color.White.copy(alpha = 0.15f)
                )
            }
        }
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
