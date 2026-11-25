package com.instalogger.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.instalogger.app.R

@Composable
fun RotatingRefreshIcon(
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    val angle = if (isRefreshing) {
        val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
        val animated by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "angle"
        )
        animated
    } else {
        0f
    }

    Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = stringResource(R.string.btn_refresh),
        tint = if (isRefreshing) tint.copy(alpha = 0.72f) else tint,
        modifier = modifier
            .size(iconSize)
            .rotate(angle)
    )
}

@Composable
fun RotatingRefreshButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    IconButton(
        onClick = {
            if (!isRefreshing) {
                onClick()
            }
        },
        enabled = !isRefreshing,
        modifier = modifier
    ) {
        RotatingRefreshIcon(
            isRefreshing = isRefreshing,
            iconSize = iconSize,
            tint = tint
        )
    }
}
