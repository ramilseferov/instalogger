package com.instalogger.app.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Hardware-accelerated transition blur modifier.
 * Returns `this` completely untouched when idle so the root container incurs ZERO graphicsLayer overhead.
 */
@Composable
fun Modifier.languageTransitionBlur(
    isBlurActive: Boolean,
    maxBlurRadius: Float = 22f,
    enterDurationMs: Int = 180,
    exitDurationMs: Int = 220
): Modifier {
    val animatedBlurState = animateFloatAsState(
        targetValue = if (isBlurActive) maxBlurRadius else 0f,
        animationSpec = tween(
            durationMillis = if (isBlurActive) enterDurationMs else exitDurationMs,
            easing = FastOutSlowInEasing
        ),
        label = "LanguageBlurAnimation"
    )

    val animatedAlphaState = animateFloatAsState(
        targetValue = if (isBlurActive) 0.78f else 1f,
        animationSpec = tween(
            durationMillis = if (isBlurActive) enterDurationMs else exitDurationMs,
            easing = FastOutSlowInEasing
        ),
        label = "LanguageAlphaAnimation"
    )

    val needsLayer by remember(isBlurActive) {
        derivedStateOf {
            isBlurActive || animatedBlurState.value > 0.5f || animatedAlphaState.value < 0.999f
        }
    }

    if (!needsLayer) {
        return this
    }

    return this.graphicsLayer {
        val currentBlur = animatedBlurState.value
        if (currentBlur > 0.5f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                renderEffect = RenderEffect.createBlurEffect(
                    currentBlur,
                    currentBlur,
                    Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            } else {
                alpha = animatedAlphaState.value
            }
        } else {
            renderEffect = null
            alpha = 1f
        }
    }
}

@Composable
fun LanguageBlurOverlay(
    isBlurActive: Boolean,
    modifier: Modifier = Modifier,
    veilAlpha: Float = 0.20f,
    enterDurationMs: Int = 180,
    exitDurationMs: Int = 220
) {
    val animatedAlphaState = animateFloatAsState(
        targetValue = if (isBlurActive) veilAlpha else 0f,
        animationSpec = tween(
            durationMillis = if (isBlurActive) enterDurationMs else exitDurationMs,
            easing = FastOutSlowInEasing
        ),
        label = "LanguageVeilAlpha"
    )

    val isVisible by remember(isBlurActive) {
        derivedStateOf { isBlurActive || animatedAlphaState.value > 0.005f }
    }

    if (isVisible) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer { alpha = animatedAlphaState.value }
                .background(Color.Black)
        )
    }
}
