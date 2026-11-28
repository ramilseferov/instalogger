package com.instalogger.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instalogger.app.Screen
import com.instalogger.app.ui.animation.MotionSpecs
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.ShapeTokens

private val DockPillSpring = spring<Float>(
    dampingRatio = 0.82f,
    stiffness = 420f
)

// GPU-accelerated 120 FPS Navigation dock with Draw-phase sliding indicator & color morphing.
@Composable
fun AnimatedBottomBar(
    items: List<Screen>,
    currentRoute: String?,
    unreadCount: Int,
    onItemSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val dockShape = ShapeTokens.DockShape
    val pillShape = remember { RoundedCornerShape(20.dp) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(dockShape)
                .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, dockShape),
            shape = dockShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                val itemCount = items.size.coerceAtLeast(1)
                val tabWidth = maxWidth / itemCount
                val tabWidthPx = with(LocalDensity.current) { tabWidth.toPx() }.coerceAtLeast(1f)

                val selectedIndex = items.indexOfFirst { it.route == currentRoute }.coerceIn(0, itemCount - 1)
                val pillOffsetAnim = remember { Animatable(selectedIndex.toFloat()) }

                LaunchedEffect(selectedIndex) {
                    pillOffsetAnim.animateTo(
                        targetValue = selectedIndex.toFloat(),
                        animationSpec = DockPillSpring
                    )
                }

                // 1. Sliding Active Capsule Pill Indicator (Draw-phase GPU translation only!)
                Box(
                    modifier = Modifier
                        .width(tabWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationX = pillOffsetAnim.value * tabWidthPx
                        }
                        .padding(horizontal = 3.dp, vertical = 3.dp)
                        .clip(pillShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
                        .border(
                            0.75.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            pillShape
                        )
                )

                val baseLabelStyle = MaterialTheme.typography.labelSmall

                // 2. Interactive Navigation Items (Draw-phase icon scale, icon tint & text color)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEach { screen ->
                        val isSelected = currentRoute == screen.route
                        val labelText = stringResource(screen.titleRes)
                        val iconPainter = rememberVectorPainter(screen.icon)

                        val iconScaleState = animateFloatAsState(
                            targetValue = if (isSelected) 1.12f else 1.0f,
                            animationSpec = MotionSpecs.SpringBouncy,
                            label = "nav_icon_scale"
                        )

                        val contentColorState = animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(200),
                            label = "nav_content_color"
                        )

                        val labelStyle = remember(baseLabelStyle, isSelected) {
                            baseLabelStyle.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(pillShape)
                                .bouncyClickable(
                                    pressScale = 0.93f,
                                    onClick = { onItemSelected(screen) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.graphicsLayer {
                                        val s = iconScaleState.value
                                        scaleX = s
                                        scaleY = s
                                    }
                                ) {
                                    if (screen == Screen.Notifications && unreadCount > 0) {
                                        BadgedBox(
                                            badge = {
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                                ) {
                                                    Text(
                                                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(21.dp)
                                                    .drawBehind {
                                                        with(iconPainter) {
                                                            draw(
                                                                size = size,
                                                                colorFilter = ColorFilter.tint(contentColorState.value)
                                                            )
                                                        }
                                                    }
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(21.dp)
                                                .drawBehind {
                                                    with(iconPainter) {
                                                        draw(
                                                            size = size,
                                                            colorFilter = ColorFilter.tint(contentColorState.value)
                                                        )
                                                    }
                                                }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(3.dp))

                                BasicText(
                                    text = labelText,
                                    style = labelStyle,
                                    color = { contentColorState.value },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
