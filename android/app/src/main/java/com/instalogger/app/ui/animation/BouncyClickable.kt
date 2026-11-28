package com.instalogger.app.ui.animation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

private val BouncyPressSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium
)

@Composable
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    pressScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier {
    if (!enabled) return this

    val haptic = LocalHapticFeedback.current
    val currentOnClick by rememberUpdatedState(onClick)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) pressScale else 1f,
        animationSpec = BouncyPressSpring,
        label = "bouncy_scale"
    )

    return this
        .graphicsLayer {
            val s = scaleState.value
            scaleX = s
            scaleY = s
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnClick()
            }
        )
}

@Composable
fun Modifier.bouncyScale(
    isPressed: Boolean,
    pressScale: Float = 0.95f
): Modifier {
    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) pressScale else 1f,
        animationSpec = MotionSpecs.SpringBouncy,
        label = "bouncy_scale_direct"
    )
    return this.graphicsLayer {
        val s = scaleState.value
        scaleX = s
        scaleY = s
    }
}

@Composable
fun Modifier.shimmerLoading(
    isLoading: Boolean,
    durationMillis: Int = 1100
): Modifier {
    if (!isLoading) return this

    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnimState = transition.animateFloat(
        initialValue = -500f,
        targetValue = 1500f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val baseColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
    val highlightColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f)
    val shimmerColors = remember(baseColor, highlightColor) {
        listOf(baseColor, highlightColor, baseColor)
    }

    // Draw-phase only shimmer sweep: ZERO recompositions per frame!
    return this.drawBehind {
        val offset = translateAnimState.value
        drawRect(
            brush = Brush.linearGradient(
                colors = shimmerColors,
                start = Offset(offset, 0f),
                end = Offset(offset + 400f, 400f)
            )
        )
    }
}
