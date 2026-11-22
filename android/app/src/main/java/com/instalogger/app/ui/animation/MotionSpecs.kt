package com.instalogger.app.ui.animation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Standardized spring physics specifications and screen transition curves.
 */
object MotionSpecs {
    // Spring specifications
    val SpringBouncy = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val SpringLowBouncy = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val SpringSnappy = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val SpringGentle = spring<Float>(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessLow
    )

    // Navigation slide & fade screen transitions
    val NavEnterTransition: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> (fullWidth * 0.14f).toInt() },
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    ) + fadeIn(animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing))

    val NavExitTransition: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -(fullWidth * 0.14f).toInt() },
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    ) + fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing))

    val NavPopEnterTransition: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> -(fullWidth * 0.14f).toInt() },
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    ) + fadeIn(animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing))

    val NavPopExitTransition: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> (fullWidth * 0.14f).toInt() },
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    ) + fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing))
}
