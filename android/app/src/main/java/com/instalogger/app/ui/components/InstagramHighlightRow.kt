package com.instalogger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.instalogger.app.data.local.HighlightEntity
import com.instalogger.app.ui.animation.bouncyClickable
import java.io.File

private val InstagramStoryGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFFF58529),
        Color(0xFFFEDA77),
        Color(0xFFDD2A7B),
        Color(0xFF8134AF),
        Color(0xFF515BD4)
    )
)

@Composable
fun InstagramHighlightRow(
    highlights: List<HighlightEntity>,
    onHighlightClick: (HighlightEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (highlights.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(highlights, key = { it.id }) { highlight ->
                HighlightCircleItem(
                    highlight = highlight,
                    onClick = { onHighlightClick(highlight) }
                )
            }
        }
    }
}

@Composable
private fun HighlightCircleItem(
    highlight: HighlightEntity,
    onClick: () -> Unit
) {
    val localFile = highlight.localCoverPath?.let { File(it) }
    val resolvedModel: Any? = when {
        localFile != null && localFile.exists() && localFile.length() > 0L -> localFile
        highlight.coverUrl.isNotBlank() -> highlight.coverUrl
        else -> null
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(68.dp)
            .bouncyClickable(pressScale = 0.92f, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .border(2.dp, InstagramStoryGradient, CircleShape)
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            if (resolvedModel != null) {
                AsyncImage(
                    model = resolvedModel,
                    contentDescription = highlight.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = highlight.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
