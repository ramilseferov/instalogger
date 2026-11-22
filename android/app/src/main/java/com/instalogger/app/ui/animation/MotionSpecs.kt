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
 * Standardized spring physics specifications and iOS-fluid screen transition curves.
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

    // iOS Fluid Spring Physics (Stiffness 380f, Damping 0.88f)
    private val IosFluidSpring = spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = 0.88f,
        stiffness = 380f
    )

    // Bottom Navigation Peer Tab Transitions:
    // When navigating to the right (e.g., Dashboard -> Settings), screen flows to the right
    val TabFlowRightEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> -((fullWidth * 0.22f).toInt()) },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
    ) + fadeIn(animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing))

    val TabFlowRightExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> ((fullWidth * 0.22f).toInt()) },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
    ) + fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing))

    // When navigating to the left (e.g., Settings -> Dashboard), screen flows to the left
    val TabFlowLeftEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> ((fullWidth * 0.22f).toInt()) },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
    ) + fadeIn(animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing))

    val TabFlowLeftExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -((fullWidth * 0.22f).toInt()) },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
    ) + fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing))

    // iOS Hierarchical Push & Pop Transitions (Detail Screen)
    val IosPushEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
    ) + fadeIn(animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing))

    val IosPushExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -((fullWidth * 0.33f).toInt()) },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
    ) + fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing))

    val IosPopEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> -((fullWidth * 0.33f).toInt()) },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
    ) + fadeIn(animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing))

    val IosPopExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f)
    ) + fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing))

    // Standard fallback transitions
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
