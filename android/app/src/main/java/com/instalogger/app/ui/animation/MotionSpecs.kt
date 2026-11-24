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
    private const val TAB_SLIDE_DURATION_MS = 280
    private const val DETAIL_SLIDE_DURATION_MS = 300

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
        stiffness = 420f
    )

    // Bottom Navigation Peer Tab Transitions (aligned with left-to-right tab layout):
    // Navigating right (toIdx > fromIdx, e.g., Dashboard -> Settings):
    // new screen enters from the RIGHT (+fullWidth -> 0), old screen exits to the LEFT (0 -> -fullWidth).
    val TabFlowRightEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(
        animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
        initialAlpha = 0.88f
    )

    val TabFlowRightExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth },
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeOut(
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        targetAlpha = 0.88f
    )

    // Navigating left (toIdx < fromIdx, e.g., Settings -> Dashboard):
    // new screen enters from the LEFT (-fullWidth -> 0), old screen exits to the RIGHT (0 -> +fullWidth).
    val TabFlowLeftEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth },
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(
        animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
        initialAlpha = 0.88f
    )

    val TabFlowLeftExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeOut(
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        targetAlpha = 0.88f
    )

    // Hierarchical Push & Pop Transitions (Detail Screen)
    val PushEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = DETAIL_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    )

    val PushExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -((fullWidth * 0.28f).toInt()) },
        animationSpec = tween(durationMillis = DETAIL_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeOut(
        animationSpec = tween(durationMillis = DETAIL_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        targetAlpha = 0.72f
    )

    val PopEnter: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> -((fullWidth * 0.28f).toInt()) },
        animationSpec = tween(durationMillis = DETAIL_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(
        animationSpec = tween(durationMillis = DETAIL_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        initialAlpha = 0.72f
    )

    val PopExit: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = DETAIL_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    )

    // Standard fallback transitions
    val NavEnterTransition: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    )

    val NavExitTransition: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -((fullWidth * 0.28f).toInt()) },
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeOut(
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        targetAlpha = 0.75f
    )

    val NavPopEnterTransition: EnterTransition = slideInHorizontally(
        initialOffsetX = { fullWidth -> -((fullWidth * 0.28f).toInt()) },
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        initialAlpha = 0.75f
    )

    val NavPopExitTransition: ExitTransition = slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = TAB_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    )
}
