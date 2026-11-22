package com.instalogger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.instalogger.app.R
import com.instalogger.app.data.local.PostEntity
import com.instalogger.app.ui.animation.bouncyClickable
import java.io.File

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
private fun PostGridItem(
    post: PostEntity,
    onClick: () -> Unit
) {
    val localFile = post.localPath?.let { File(it) }
    val resolvedModel: Any = when {
        localFile != null && localFile.exists() && localFile.length() > 0L -> localFile
        post.displayUrl.isNotBlank() -> post.displayUrl
        else -> "http://127.0.0.1:3000/archives/${post.username}/posts/${post.id}.jpg"
    }

    Box(
        modifier = Modifier
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

        if (post.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
