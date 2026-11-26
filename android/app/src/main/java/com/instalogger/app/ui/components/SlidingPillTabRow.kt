package com.instalogger.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens

data class TabItem(
    val title: String,
    val icon: ImageVector? = null,
    val count: Int? = null
)

data class SegmentedOption(
    val title: String,
    val subtitle: String? = null
)

/**
 * Hardware-friendly segmented tab row with a real gliding pill indicator.
 * Uses a single animated offset (`animateDpAsState` spring) so switching tabs
 * glides smoothly at 120 FPS without layout thrashing.
 */
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
            val indicatorOffset by animateDpAsState(
                targetValue = slotWidth * safeIndex,
                animationSpec = spring(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "sliding_tab_pill_x"
            )

            // Gliding Pill Indicator
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(slotWidth)
                    .fillMaxHeight()
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        ShapeTokens.BorderSubtle,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
                        pillShape
                    )
            )

            // Tab Labels Row
            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = safeIndex == index
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        label = "tab_content_color_$index"
                    )
                    val interactionSource = remember { MutableInteractionSource() }

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
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            val titleText = if (tab.count != null) "${tab.title} (${tab.count})" else tab.title
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = contentColor,
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

/**
 * Reusable 120 FPS gliding pill selector for option groups (video quality, speed presets,
 * post sync limits, update channels, etc.). Smooth, calm, and eye-friendly.
 */
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
    val trackShape = RoundedCornerShape(14.dp)
    val thumbShape = RoundedCornerShape(10.dp)

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
            val indicatorOffset by animateDpAsState(
                targetValue = slotWidth * safeIndex,
                animationSpec = spring(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "segmented_pill_x"
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(slotWidth)
                    .fillMaxHeight()
                    .clip(thumbShape)
                    .background(accentColor.copy(alpha = 0.16f))
                    .border(1.dp, accentColor.copy(alpha = 0.65f), thumbShape)
            )

            Row(modifier = Modifier.fillMaxSize()) {
                options.forEachIndexed { index, opt ->
                    val isSelected = safeIndex == index
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface,
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        label = "seg_txt_$index"
                    )
                    val subColor by animateColorAsState(
                        targetValue = if (isSelected) accentColor.copy(alpha = 0.88f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        label = "seg_sub_$index"
                    )
                    val interactionSource = remember { MutableInteractionSource() }

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
                            Text(
                                text = opt.title,
                                fontSize = if (opt.subtitle != null) 11.5.sp else 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!opt.subtitle.isNullOrBlank()) {
                                Text(
                                    text = opt.subtitle,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = subColor,
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
