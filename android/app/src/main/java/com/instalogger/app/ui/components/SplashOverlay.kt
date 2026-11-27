package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instalogger.app.R
import com.instalogger.app.StartupProgressState
import kotlinx.coroutines.launch

private val BrandGradientColors = listOf(
    Color(0xFFFEDA75),
    Color(0xFFFA7E1E),
    Color(0xFFD62976),
    Color(0xFF962FBF),
    Color(0xFF4F5BD5)
)

@Composable
fun SplashOverlay(
    state: StartupProgressState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = !state.isReady,
        enter = EnterTransition.None,
        exit = fadeOut(animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)) +
            scaleOut(
                targetScale = 1.045f,
                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
            ),
        modifier = modifier.fillMaxSize()
    ) {
        val bgColor = MaterialTheme.colorScheme.background
        val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
        val surfaceHighest = MaterialTheme.colorScheme.surfaceContainerHighest
        val onBackground = MaterialTheme.colorScheme.onBackground
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
        val outlineVariant = MaterialTheme.colorScheme.outlineVariant
        val isDark = bgColor.red < 0.25f && bgColor.green < 0.25f && bgColor.blue < 0.25f

        // Smooth spring entrance for logo and wordmark
        val logoScale = remember { Animatable(0.84f) }
        val contentAlpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            launch {
                logoScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                contentAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                )
            }
        }

        // Continuous smooth clockwise rotation (0° -> +360°) for the brand logo
        val infiniteTransition = rememberInfiniteTransition(label = "splash_logo_motion")
        val clockwiseRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "clockwise_rotation"
        )

        // Subtle breathing halo scale around the rotating emblem
        val haloPulse by infiniteTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "halo_pulse"
        )

        val animatedProgress by animateFloatAsState(
            targetValue = state.progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
            label = "real_startup_progress"
        )

        val interactionSource = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {}
                )
                .drawBehind {
                    val center = Offset(size.width * 0.5f, size.height * 0.44f)
                    val radius = size.minDimension * 0.62f
                    val glowAlphaPrimary = if (isDark) 0.13f else 0.08f
                    val glowAlphaSecondary = if (isDark) 0.07f else 0.04f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFD62976).copy(alpha = glowAlphaPrimary),
                                Color(0xFF4F5BD5).copy(alpha = glowAlphaSecondary),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
                .testTag("splash_overlay")
        ) {
            // Centerpiece: Minimal emblem container with clockwise-rotating brand logo + wordmark
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 52.dp)
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(134.dp)
                ) {
                    // Soft ambient breathing outer ring
                    Box(
                        modifier = Modifier
                            .size(126.dp)
                            .graphicsLayer {
                                scaleX = haloPulse
                                scaleY = haloPulse
                            }
                            .clip(CircleShape)
                            .border(
                                width = 1.dp,
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color(0xFFFA7E1E).copy(alpha = if (isDark) 0.34f else 0.24f),
                                        Color(0xFFD62976).copy(alpha = if (isDark) 0.42f else 0.28f),
                                        Color(0xFF4F5BD5).copy(alpha = if (isDark) 0.34f else 0.24f),
                                        Color(0xFFFA7E1E).copy(alpha = if (isDark) 0.34f else 0.24f)
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    // Elevated circular emblem surface
                    Surface(
                        modifier = Modifier.size(104.dp),
                        shape = CircleShape,
                        color = surfaceContainer,
                        tonalElevation = if (isDark) 4.dp else 2.dp,
                        shadowElevation = if (isDark) 14.dp else 8.dp,
                        border = androidx.compose.foundation.BorderStroke(
                            width = 0.85.dp,
                            color = outlineVariant.copy(alpha = if (isDark) 0.55f else 0.8f)
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_instalogger_logo),
                                contentDescription = stringResource(R.string.app_name),
                                modifier = Modifier
                                    .size(66.dp)
                                    .graphicsLayer {
                                        rotationZ = clockwiseRotation
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = onBackground
                )
            }

            // Bottom real loading progress bar + live stage label + tabular percentage
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 36.dp, vertical = 34.dp)
                    .widthIn(max = 340.dp)
                    .graphicsLayer { alpha = contentAlpha.value },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = state.stageResId,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 })
                                .togetherWith(fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 3 })
                        },
                        modifier = Modifier.weight(1f),
                        label = "splash_stage_text"
                    ) { stageRes ->
                        Text(
                            text = stringResource(id = stageRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = "${(animatedProgress * 100f).toInt().coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = onBackground.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                // Sleek pill-shaped gradient loading bar reflecting real startup progress
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .testTag("splash_progress_bar")
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val corner = CornerRadius(size.height / 2f, size.height / 2f)
                        drawRoundRect(
                            color = surfaceHighest.copy(alpha = if (isDark) 0.9f else 0.85f),
                            cornerRadius = corner
                        )

                        val fillWidth = (size.width * animatedProgress).coerceAtLeast(size.height)
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = BrandGradientColors,
                                startX = 0f,
                                endX = size.width
                            ),
                            size = Size(width = fillWidth, height = size.height),
                            cornerRadius = corner
                        )
                    }
                }
            }
        }
    }
}
