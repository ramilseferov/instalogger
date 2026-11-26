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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

fun Modifier.languageTransitionBlur(
    isBlurActive: Boolean,
    maxBlurRadius: Float = 22f,
    enterDurationMs: Int = 180,
    exitDurationMs: Int = 220
): Modifier = this.composed {
    val animatedBlur by animateFloatAsState(
        targetValue = if (isBlurActive) maxBlurRadius else 0f,
        animationSpec = tween(
            durationMillis = if (isBlurActive) enterDurationMs else exitDurationMs,
            easing = FastOutSlowInEasing
        ),
        label = "LanguageBlurAnimation"
    )

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isBlurActive) 0.78f else 1f,
        animationSpec = tween(
            durationMillis = if (isBlurActive) enterDurationMs else exitDurationMs,
            easing = FastOutSlowInEasing
        ),
        label = "LanguageAlphaAnimation"
    )

    this.graphicsLayer {
        if (animatedBlur > 0.5f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                renderEffect = RenderEffect.createBlurEffect(
                    animatedBlur,
                    animatedBlur,
                    Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            } else {
                alpha = animatedAlpha
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
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isBlurActive) veilAlpha else 0f,
        animationSpec = tween(
            durationMillis = if (isBlurActive) enterDurationMs else exitDurationMs,
            easing = FastOutSlowInEasing
        ),
        label = "LanguageVeilAlpha"
    )

    if (animatedAlpha > 0.005f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = animatedAlpha))
        )
    }
}
