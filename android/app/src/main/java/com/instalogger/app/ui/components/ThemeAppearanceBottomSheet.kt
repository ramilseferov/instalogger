package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instalogger.app.R
import com.instalogger.app.settings.ThemeMode
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.util.LanguageManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeAppearanceBottomSheet(
    currentThemeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(top = 12.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        }
    ) {
        LanguageManager.ProvideLocalizedContext {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentBlue.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.theme_section_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.settings_hub_theme_row_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dialog_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemePreviewTile(
                    mode = ThemeMode.LIGHT,
                    title = stringResource(R.string.theme_light),
                    icon = Icons.Default.LightMode,
                    accentColor = Color(0xFFF59E0B),
                    isSelected = currentThemeMode == ThemeMode.LIGHT,
                    onClick = {
                        if (currentThemeMode != ThemeMode.LIGHT) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectTheme(ThemeMode.LIGHT)
                        }
                    },
                    testTag = "theme_option_light",
                    modifier = Modifier.weight(1f),
                )

                ThemePreviewTile(
                    mode = ThemeMode.DARK,
                    title = stringResource(R.string.theme_dark),
                    icon = Icons.Default.DarkMode,
                    accentColor = Color(0xFF818CF8),
                    isSelected = currentThemeMode == ThemeMode.DARK,
                    onClick = {
                        if (currentThemeMode != ThemeMode.DARK) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectTheme(ThemeMode.DARK)
                        }
                    },
                    testTag = "theme_option_dark",
                    modifier = Modifier.weight(1f),
                )

                ThemePreviewTile(
                    mode = ThemeMode.SYSTEM,
                    title = stringResource(R.string.theme_system),
                    icon = Icons.Default.BrightnessAuto,
                    accentColor = AccentBlue,
                    isSelected = currentThemeMode == ThemeMode.SYSTEM,
                    onClick = {
                        if (currentThemeMode != ThemeMode.SYSTEM) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectTheme(ThemeMode.SYSTEM)
                        }
                    },
                    testTag = "theme_option_system",
                    modifier = Modifier.weight(1f),
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                AnimatedContent(
                    targetState = currentThemeMode,
                    transitionSpec = {
                        fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(150))
                    },
                    label = "active_theme_description_anim",
                ) { mode ->
                    val (icon, tint, titleRes, descRes) = when (mode) {
                        ThemeMode.LIGHT -> QuadThemeMeta(
                            Icons.Default.LightMode,
                            Color(0xFFF59E0B),
                            R.string.theme_light,
                            R.string.theme_light_desc,
                        )
                        ThemeMode.DARK -> QuadThemeMeta(
                            Icons.Default.DarkMode,
                            Color(0xFF818CF8),
                            R.string.theme_dark,
                            R.string.theme_dark_desc,
                        )
                        ThemeMode.SYSTEM -> QuadThemeMeta(
                            Icons.Default.BrightnessAuto,
                            AccentBlue,
                            R.string.theme_system,
                            R.string.theme_system_desc,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(titleRes),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(descRes),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

private data class QuadThemeMeta(
    val icon: ImageVector,
    val tint: Color,
    val titleRes: Int,
    val descRes: Int,
)

@Composable
private fun ThemePreviewTile(
    mode: ThemeMode,
    title: String,
    icon: ImageVector,
    accentColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.96f
            isSelected -> 1.0f
            else -> 0.985f
        },
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium),
        label = "theme_tile_scale",
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "theme_tile_border",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.75.dp,
        animationSpec = tween(200),
        label = "theme_tile_border_w",
    )
    val cardBg by animateColorAsState(
        targetValue = if (isSelected) {
            AccentBlue.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "theme_tile_bg",
    )

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            )
            .testTag(testTag),
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (mode == ThemeMode.DARK) Color(0xFF0D0E12) else Color(0xFFF4F5F8)),
            ) {
                when (mode) {
                    ThemeMode.LIGHT -> MiniPhoneCanvas(isDarkCanvas = false)
                    ThemeMode.DARK -> MiniPhoneCanvas(isDarkCanvas = true)
                    ThemeMode.SYSTEM -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color(0xFFF4F5F8)),
                            ) {
                                MiniPhoneHalfCanvas(isDarkCanvas = false)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color(0xFF0D0E12)),
                            ) {
                                MiniPhoneHalfCanvas(isDarkCanvas = true)
                            }
                        }
                    }
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) AccentBlue else accentColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MiniPhoneCanvas(isDarkCanvas: Boolean) {
    val cardColor = if (isDarkCanvas) Color(0xFF191B21) else Color.White
    val barColor = if (isDarkCanvas) Color(0xFF2D313B) else Color(0xFFE2E8F0)
    val subBarColor = if (isDarkCanvas) Color(0xFF23262E) else Color(0xFFEDF2F7)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(barColor),
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentBlue.copy(alpha = 0.85f)),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cardColor)
                .padding(6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(barColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(subBarColor),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(AccentBlue),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .height(10.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .background(cardColor),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(AccentBlue),
                )
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(barColor),
                )
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(barColor),
                )
            }
        }
    }
}

@Composable
private fun MiniPhoneHalfCanvas(isDarkCanvas: Boolean) {
    val cardColor = if (isDarkCanvas) Color(0xFF191B21) else Color.White
    val barColor = if (isDarkCanvas) Color(0xFF2D313B) else Color(0xFFE2E8F0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 5.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(5.dp)
                .clip(CircleShape)
                .background(barColor),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(cardColor)
                .padding(5.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(barColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(AccentBlue),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(cardColor),
        )
    }
}
