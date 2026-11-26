package com.instalogger.app.ui.animation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Screen transition specifications and spring physics curves.
 */
object MotionSpecs {
    private const val TAB_TRANSITION_MS = 185
    private const val DETAIL_SLIDE_DURATION_MS = 230

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

    // Fluid Spring Physics
    val FluidSpring = spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 460f
    )

    // Bottom Navigation Peer Tab Transitions:
    // GPU-friendly subtle directional micro-shift (±6%) + silky crossfade (zero full-width double overdraw)
    val TabFlowRightEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> (fullWidth * 0.06f).toInt() },
        animationSpec = tween(durationMillis = TAB_TRANSITION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(
        animationSpec = tween(durationMillis = TAB_TRANSITION_MS, easing = LinearOutSlowInEasing),
        initialAlpha = 0f
    )

    val TabFlowRightExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -(fullWidth * 0.05f).toInt() },
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing)
    ) + fadeOut(
        animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
        targetAlpha = 0f
    )

    val TabFlowLeftEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> -(fullWidth * 0.06f).toInt() },
        animationSpec = tween(durationMillis = TAB_TRANSITION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(
        animationSpec = tween(durationMillis = TAB_TRANSITION_MS, easing = LinearOutSlowInEasing),
        initialAlpha = 0f
    )

    val TabFlowLeftExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> (fullWidth * 0.05f).toInt() },
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing)
    ) + fadeOut(
        animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
        targetAlpha = 0f
    )

    // Hierarchical Push & Pop Transitions (Detail Screen)
    val PushEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> (fullWidth * 0.16f).toInt() },
        animationSpec = tween(durationMillis = DETAIL_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(
        animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)
    )

    val PushExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -(fullWidth * 0.08f).toInt() },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
    ) + fadeOut(
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        targetAlpha = 0.85f
    )

    val PopEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> -(fullWidth * 0.08f).toInt() },
        animationSpec = tween(durationMillis = DETAIL_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(
        animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing),
        initialAlpha = 0.85f
    )

    val PopExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> (fullWidth * 0.16f).toInt() },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
    ) + fadeOut(
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
    )

    // Standard fallback transitions
    val NavEnterTransition: EnterTransition = PushEnter
    val NavExitTransition: ExitTransition = PushExit
    val NavPopEnterTransition: EnterTransition = PopEnter
    val NavPopExitTransition: ExitTransition = PopExit
}
