package com.instalogger.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Standardized design tokens for InstaLogger.
 * Eliminates arbitrary corner radii and establishes a predictable visual hierarchy.
 */
object ShapeTokens {
    // Corner Radii
    val CornerSmall = 12.dp      // Action buttons, chips, mini badges, item action pods
    val CornerMedium = 16.dp     // Internal pods, grouped stats containers, text fields
    val CornerLarge = 24.dp      // Primary cards, dialogs, bottom sheets, root containers
    val CornerPill = 100.dp      // Capsule pill badges, tab sliders, floating indicators

    // Standard Shapes
    val SmallShape = RoundedCornerShape(CornerSmall)
    val MediumShape = RoundedCornerShape(CornerMedium)
    val LargeShape = RoundedCornerShape(CornerLarge)
    val PillShape = RoundedCornerShape(CornerPill)
    val DockShape = RoundedCornerShape(26.dp)
    val Circle = CircleShape

    // Standard Border Stroke Widths
    val BorderSubtle = 0.75.dp
    val BorderStandard = 1.dp
    val BorderActive = 1.5.dp
}

val AppShapes = Shapes(
    small = ShapeTokens.SmallShape,
    medium = ShapeTokens.MediumShape,
    large = ShapeTokens.LargeShape,
    extraLarge = RoundedCornerShape(28.dp)
)
