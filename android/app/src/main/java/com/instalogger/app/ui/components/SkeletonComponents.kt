package com.instalogger.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instalogger.app.R
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens

fun Modifier.shimmerEffect(durationMillis: Int = 1300): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim by transition.animateFloat(
        initialValue = -300f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 300f, translateAnim - 300f),
        end = Offset(translateAnim, translateAnim)
    )

    this.background(brush)
}

@Composable
fun SkeletonTargetCard(
    username: String? = null,
    modifier: Modifier = Modifier
) {
    val cardShape = ShapeTokens.LargeShape

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .shimmerEffect()
                )

                Column(modifier = Modifier.weight(1f)) {
                    if (!username.isNullOrBlank()) {
                        Text(
                            text = "@$username",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(ShapeTokens.PillShape)
                                    .background(AccentBlue.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.loading_profile_data),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentBlue,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .width(130.dp)
                                .height(16.dp)
                                .clip(ShapeTokens.SmallShape)
                                .shimmerEffect()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .width(85.dp)
                                .height(12.dp)
                                .clip(ShapeTokens.SmallShape)
                                .shimmerEffect()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(14.dp)
                        .clip(ShapeTokens.PillShape)
                        .shimmerEffect()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .shimmerEffect()
                    )
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .shimmerEffect()
                    )
                }
            }
        }
    }
}

@Composable
fun SkeletonProfileHero(
    modifier: Modifier = Modifier
) {
    val heroShape = ShapeTokens.LargeShape

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(heroShape)
            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, heroShape),
        shape = heroShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .shimmerEffect()
                )

                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .height(20.dp)
                            .clip(ShapeTokens.SmallShape)
                            .shimmerEffect()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .width(90.dp)
                            .height(14.dp)
                            .clip(ShapeTokens.SmallShape)
                            .shimmerEffect()
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bio shimmer lines
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(12.dp)
                    .clip(ShapeTokens.SmallShape)
                    .shimmerEffect()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(12.dp)
                    .clip(ShapeTokens.SmallShape)
                    .shimmerEffect()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stats Pod placeholder
            val statsPodShape = ShapeTokens.MediumShape
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(statsPodShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, statsPodShape)
                    .padding(vertical = 12.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(16.dp)
                                .clip(ShapeTokens.SmallShape)
                                .shimmerEffect()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(48.dp)
                                .height(10.dp)
                                .clip(ShapeTokens.SmallShape)
                                .shimmerEffect()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonPostGrid(
    modifier: Modifier = Modifier,
    count: Int = 3,
    showStatusCard: Boolean = true,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showStatusCard) {
            val cardShape = ShapeTokens.MediumShape
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = cardShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AccentBlue)
                        )
                        Text(
                            text = if (count > 0) {
                                stringResource(R.string.posts_syncing_format, count)
                            } else {
                                stringResource(R.string.loading_posts)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (onRetry != null) {
                        TextButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_refresh_list),
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        val displayCount = if (count > 0) count.coerceIn(1, 15) else 3
        val rows = (1..displayCount).chunked(3)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (row in rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (item in row) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(ShapeTokens.SmallShape)
                                .shimmerEffect()
                        )
                    }
                    repeat(3 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
