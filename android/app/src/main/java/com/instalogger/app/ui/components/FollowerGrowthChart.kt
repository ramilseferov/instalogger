package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instalogger.app.R
import com.instalogger.app.data.local.FollowerHistoryEntity
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusAmber
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

private val FollowingCurveColor = StatusAmber
private val PositiveGreen = StatusGreen
private val NegativeRed = StatusRed

@Composable
fun FollowerGrowthChart(
    history: List<FollowerHistoryEntity>,
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
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AccentBlue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoGraph,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = stringResource(R.string.chart_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.chart_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Legend with protected horizontal spacing
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendItem(color = AccentBlue, label = stringResource(R.string.followers))
                    LegendItem(color = FollowingCurveColor, label = stringResource(R.string.following))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.chart_no_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Latest Stats Bar with Net Diff
                val latest = history.last()
                val earliest = history.first()
                val followerDiff = latest.followersCount - earliest.followersCount
                val followingDiff = latest.followingCount - earliest.followingCount

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeTokens.MediumShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatBadge(
                        label = stringResource(R.string.followers),
                        count = latest.followersCount,
                        diff = followerDiff,
                        color = AccentBlue
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    StatBadge(
                        label = stringResource(R.string.following),
                        count = latest.followingCount,
                        diff = followingDiff,
                        color = FollowingCurveColor
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Interactive Chart Canvas
                InteractiveBezierChart(
                    history = history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun StatBadge(
    label: String,
    count: Long,
    diff: Long,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column {
            Text(
                text = FormatUtils.formatCompactNumber(count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (diff != 0L) {
            val isPositive = diff > 0
            val badgeColor = if (isPositive) PositiveGreen else NegativeRed
            val badgeText = if (isPositive) "+$diff" else "$diff"

            Row(
                modifier = Modifier
                    .clip(ShapeTokens.PillShape)
                    .background(badgeColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isPositive) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun InteractiveBezierChart(
    history: List<FollowerHistoryEntity>,
    modifier: Modifier = Modifier
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val points = remember(history) {
        if (history.size == 1) {
            listOf(history.first(), history.first())
        } else {
            history
        }
    }

    val maxFollowers = remember(points) { points.maxOf { it.followersCount }.coerceAtLeast(1L) }
    val minFollowers = remember(points) { points.minOf { it.followersCount } }
    val followerRange = (maxFollowers - minFollowers).coerceAtLeast(1L).toFloat()

    val maxFollowing = remember(points) { points.maxOf { it.followingCount }.coerceAtLeast(1L) }
    val minFollowing = remember(points) { points.minOf { it.followingCount } }
    val followingRange = (maxFollowing - minFollowing).coerceAtLeast(1L).toFloat()

    val overallMax = maxOf(maxFollowers, maxFollowing)
    val overallMin = minOf(minFollowers, minFollowing)
    val range = (overallMax - overallMin).coerceAtLeast(1L).toFloat()

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val width = size.width
                            val step = width / (points.size - 1).coerceAtLeast(1)
                            val idx = (offset.x / step).toInt().coerceIn(0, points.size - 1)
                            selectedIndex = idx
                        },
                        onDrag = { change, _ ->
                            val width = size.width
                            val step = width / (points.size - 1).coerceAtLeast(1)
                            val idx = (change.position.x / step).toInt().coerceIn(0, points.size - 1)
                            selectedIndex = idx
                        },
                        onDragEnd = { selectedIndex = null },
                        onDragCancel = { selectedIndex = null }
                    )
                }
                .pointerInput(points) {
                    detectTapGestures(
                        onTap = { offset ->
                            val width = size.width
                            val step = width / (points.size - 1).coerceAtLeast(1)
                            val idx = (offset.x / step).toInt().coerceIn(0, points.size - 1)
                            selectedIndex = if (selectedIndex == idx) null else idx
                        }
                    )
                }
        ) {
            val width = size.width
            val height = size.height
            val paddingBottom = 16f
            val paddingTop = 16f
            val usableHeight = height - paddingBottom - paddingTop

            val stepX = if (points.size > 1) width / (points.size - 1) else width

            // Build smooth Bezier path for followers
            val followerPath = Path()
            val followerFillPath = Path()

            val followingPath = Path()
            val followingFillPath = Path()

            val followerOffsets = mutableListOf<Offset>()
            val followingOffsets = mutableListOf<Offset>()

            points.forEachIndexed { i, pt ->
                val x = i * stepX
                val fNorm = (pt.followersCount - overallMin).toFloat() / range
                val fy = height - paddingBottom - (fNorm * usableHeight)
                followerOffsets.add(Offset(x, fy))

                val ingNorm = (pt.followingCount - overallMin).toFloat() / range
                val ingY = height - paddingBottom - (ingNorm * usableHeight)
                followingOffsets.add(Offset(x, ingY))
            }

            // Draw smooth Bezier curve for followers
            if (followerOffsets.isNotEmpty()) {
                followerPath.moveTo(followerOffsets[0].x, followerOffsets[0].y)
                followerFillPath.moveTo(followerOffsets[0].x, height - paddingBottom)
                followerFillPath.lineTo(followerOffsets[0].x, followerOffsets[0].y)

                for (i in 0 until followerOffsets.size - 1) {
                    val p0 = followerOffsets[i]
                    val p1 = followerOffsets[i + 1]
                    val cx = (p0.x + p1.x) / 2
                    followerPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    followerFillPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                }

                followerFillPath.lineTo(followerOffsets.last().x, height - paddingBottom)
                followerFillPath.close()

                // Draw gradient fill
                drawPath(
                    path = followerFillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            AccentBlue.copy(alpha = 0.28f),
                            AccentBlue.copy(alpha = 0.02f)
                        ),
                        startY = paddingTop,
                        endY = height
                    )
                )

                // Draw curve line
                drawPath(
                    path = followerPath,
                    color = AccentBlue,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Draw smooth Bezier curve for following
            if (followingOffsets.isNotEmpty()) {
                followingPath.moveTo(followingOffsets[0].x, followingOffsets[0].y)
                followingFillPath.moveTo(followingOffsets[0].x, height - paddingBottom)
                followingFillPath.lineTo(followingOffsets[0].x, followingOffsets[0].y)

                for (i in 0 until followingOffsets.size - 1) {
                    val p0 = followingOffsets[i]
                    val p1 = followingOffsets[i + 1]
                    val cx = (p0.x + p1.x) / 2
                    followingPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    followingFillPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                }

                followingFillPath.lineTo(followingOffsets.last().x, height - paddingBottom)
                followingFillPath.close()

                drawPath(
                    path = followingFillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            FollowingCurveColor.copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        startY = paddingTop,
                        endY = height
                    )
                )

                drawPath(
                    path = followingPath,
                    color = FollowingCurveColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Draw selected index scrubber line & dots
            selectedIndex?.let { idx ->
                if (idx in followerOffsets.indices) {
                    val fOffset = followerOffsets[idx]
                    val ingOffset = followingOffsets[idx]

                    // Vertical dashed indicator
                    drawLine(
                        color = Color.White.copy(alpha = 0.6f),
                        start = Offset(fOffset.x, paddingTop),
                        end = Offset(fOffset.x, height - paddingBottom),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    // Dots
                    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = fOffset)
                    drawCircle(color = AccentBlue, radius = 3.5.dp.toPx(), center = fOffset)

                    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = ingOffset)
                    drawCircle(color = FollowingCurveColor, radius = 3.5.dp.toPx(), center = ingOffset)
                }
            }
        }

        // Floating Tooltip when scrubbed
        selectedIndex?.let { idx ->
            if (idx in points.indices) {
                val pt = points[idx]
                val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                val formattedTime = timeFormat.format(Date(pt.timestamp))

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                        .clip(ShapeTokens.SmallShape)
                        .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f),
                            fontSize = 10.sp
                        )
                        Text(
                            text = stringResource(R.string.chart_scrubber_followers, pt.followersCount),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AccentBlue,
                            fontSize = 11.sp
                        )
                        Text(
                            text = stringResource(R.string.chart_scrubber_following, pt.followingCount),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = FollowingCurveColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
