package com.instalogger.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens

@Immutable
data class TabItem(
    val title: String,
    val icon: ImageVector? = null,
    val count: Int? = null
)

@Immutable
data class SegmentedOption(
    val title: String,
    val subtitle: String? = null
)

private val SlidingPillSpring = spring<Float>(
    dampingRatio = 0.86f,
    stiffness = Spring.StiffnessMediumLow
)

@Composable
fun SlidingPillTabRow(
    tabs: List<TabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    val containerShape = ShapeTokens.MediumShape
    val pillShape = ShapeTokens.SmallShape

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(containerShape)
            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, containerShape),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = containerShape
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .height(42.dp)
        ) {
            val count = tabs.size
            val slotWidth = maxWidth / count
            val slotWidthPx = with(LocalDensity.current) { slotWidth.toPx() }.coerceAtLeast(1f)
            val indicatorAnim = remember { Animatable(safeIndex.toFloat()) }

            LaunchedEffect(safeIndex) {
                indicatorAnim.animateTo(
                    targetValue = safeIndex.toFloat(),
                    animationSpec = SlidingPillSpring
                )
            }

            // Draw-phase GPU sliding indicator: ZERO layout/recomposition passes per frame
            Box(
                modifier = Modifier
                    .width(slotWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = indicatorAnim.value * slotWidthPx
                    }
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        ShapeTokens.BorderSubtle,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
                        pillShape
                    )
            )

            val baseLabelStyle = MaterialTheme.typography.labelMedium
            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = safeIndex == index
                    val contentColorState = animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        label = "tab_content_color_$index"
                    )
                    val interactionSource = remember { MutableInteractionSource() }
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
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                if (!isSelected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onTabSelected(index)
                                }
                            }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (tab.icon != null) {
                                val painter = rememberVectorPainter(tab.icon)
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .drawBehind {
                                            with(painter) {
                                                draw(
                                                    size = size,
                                                    colorFilter = ColorFilter.tint(contentColorState.value)
                                                )
                                            }
                                        }
                                )
                            }

                            val titleText = if (tab.count != null) "${tab.title} (${tab.count})" else tab.title
                            BasicText(
                                text = titleText,
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

@Composable
fun AnimatedSegmentedSelector(
    options: List<SegmentedOption>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    accentColor: Color = AccentBlue
) {
    if (options.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    val safeIndex = selectedIndex.coerceIn(0, options.lastIndex)
    val trackShape = remember { RoundedCornerShape(14.dp) }
    val thumbShape = remember { RoundedCornerShape(10.dp) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(trackShape)
            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, trackShape),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = trackShape
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp)
                .height(height)
        ) {
            val slotWidth = maxWidth / options.size
            val slotWidthPx = with(LocalDensity.current) { slotWidth.toPx() }.coerceAtLeast(1f)
            val indicatorAnim = remember { Animatable(safeIndex.toFloat()) }

            LaunchedEffect(safeIndex) {
                indicatorAnim.animateTo(
                    targetValue = safeIndex.toFloat(),
                    animationSpec = SlidingPillSpring
                )
            }

            Box(
                modifier = Modifier
                    .width(slotWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = indicatorAnim.value * slotWidthPx
                    }
                    .clip(thumbShape)
                    .background(accentColor.copy(alpha = 0.16f))
                    .border(1.dp, accentColor.copy(alpha = 0.65f), thumbShape)
            )

            val baseTextStyle = MaterialTheme.typography.labelMedium
            Row(modifier = Modifier.fillMaxSize()) {
                options.forEachIndexed { index, opt ->
                    val isSelected = safeIndex == index
                    val textColorState = animateColorAsState(
                        targetValue = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface,
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        label = "seg_txt_$index"
                    )
                    val subColorState = animateColorAsState(
                        targetValue = if (isSelected) accentColor.copy(alpha = 0.88f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        label = "seg_sub_$index"
                    )
                    val interactionSource = remember { MutableInteractionSource() }
                    val titleStyle = remember(baseTextStyle, opt.subtitle != null, isSelected) {
                        baseTextStyle.copy(
                            fontSize = if (opt.subtitle != null) 11.5.sp else 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                    val subStyle = remember(baseTextStyle) {
                        baseTextStyle.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(thumbShape)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                if (!isSelected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelected(index)
                                }
                            }
                            .padding(horizontal = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BasicText(
                                text = opt.title,
                                style = titleStyle,
                                color = { textColorState.value },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!opt.subtitle.isNullOrBlank()) {
                                BasicText(
                                    text = opt.subtitle,
                                    style = subStyle,
                                    color = { subColorState.value },
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
