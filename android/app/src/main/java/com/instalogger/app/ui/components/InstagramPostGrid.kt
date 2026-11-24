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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.instalogger.app.R
import com.instalogger.app.data.local.PostEntity
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.util.ArchiveDirectoryManager
import com.instalogger.app.util.cleanDisplayText
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun InstagramPostGrid(
    posts: List<PostEntity>,
    onPostClick: (PostEntity) -> Unit,
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
                onClick = { onPostClick(post) }
            )
        }
    }
}

@Composable
fun PostGridItem(
    post: PostEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val localFile = post.localPath?.let { File(it) }
    val localImageFile = localFile?.takeIf { !it.name.contains(".mp4", ignoreCase = true) }
    val extArchive = File(ArchiveDirectoryManager.getArchiveDirectory(context), "${post.username}/posts/${post.id}.jpg")
    val resolvedModel: Any = when {
        localImageFile != null && localImageFile.exists() && localImageFile.length() > 0L -> localImageFile
        extArchive.exists() && extArchive.length() > 0L -> extArchive
        post.displayUrl.isNotBlank() -> post.displayUrl
        else -> ""
    }

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

        // Top-right video badge
        if (post.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
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
    }
}

@Composable
fun InstagramPostViewerDialog(
    post: PostEntity,
    targetAvatarModel: Any? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val localFile = post.localPath?.let { File(it) }
    val localImageFile = localFile?.takeIf { !it.name.contains(".mp4", ignoreCase = true) }
    val extArchive = File(ArchiveDirectoryManager.getArchiveDirectory(context), "${post.username}/posts/${post.id}.jpg")
    val resolvedModel: Any = when {
        localImageFile != null && localImageFile.exists() && localImageFile.length() > 0L -> localImageFile
        extArchive.exists() && extArchive.length() > 0L -> extArchive
        post.displayUrl.isNotBlank() -> post.displayUrl
        else -> ""
    }

    val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val formattedDate = timeFormat.format(Date(post.postedAt))

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
                        // User Avatar
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
                            Text(
                                text = "@${post.username}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formattedDate,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Close Button
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Middle Media Preview Area (Flexible height)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = resolvedModel,
                        contentDescription = post.caption,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (post.isVideo) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                .bouncyClickable {
                                    // Open video in external player if available
                                    if (localFile != null && localFile.exists()) {
                                        try {
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "video/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            Toast.makeText(context, context.getString(R.string.error_open_video_player), Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.error_media_not_local), Toast.LENGTH_SHORT).show()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play Video",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                // Bottom Content & Actions Area
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Actions Bar (Likes, Comments, Save/Share, External Link)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Likes Count
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Default.Favorite, contentDescription = null, tint = StatusRed, modifier = Modifier.size(20.dp))
                                Text(
                                    text = stringResource(R.string.likes_count_format, post.likeCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Comments Count
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Text(
                                    text = stringResource(R.string.comments_count_format, post.commentCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Right action buttons (Export / Open IG)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Share / Export Media
                            IconButton(
                                onClick = {
                                    if (localFile != null && localFile.exists()) {
                                        try {
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = if (post.isVideo) "video/*" else "image/*"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_export_media)))
                                        } catch (_: Exception) {
                                            Toast.makeText(context, context.getString(R.string.error_share_media), Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.error_media_not_local), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_export_media), tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            // Open in Instagram
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
                                Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.view_on_instagram), tint = AccentBlue, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // Caption & Copy Button
                    val caption = post.caption?.cleanDisplayText()
                    if (!caption.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = caption,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("caption", caption)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, context.getString(R.string.msg_caption_copied), Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_copy_caption), color = AccentBlue, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
