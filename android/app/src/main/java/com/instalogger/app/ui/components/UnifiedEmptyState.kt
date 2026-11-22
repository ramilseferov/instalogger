package com.instalogger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.ShapeTokens

/**
 * Standardized clean empty state card matching the application's ergonomic design language.
 */
@Composable
fun UnifiedEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeTokens.LargeShape)
                .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, ShapeTokens.LargeShape),
            shape = ShapeTokens.LargeShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val iconShape = ShapeTokens.Circle
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(iconShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, iconShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                )

                if (actionLabel != null && onActionClick != null) {
                    Spacer(modifier = Modifier.height(22.dp))

                    Button(
                        onClick = onActionClick,
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier.bouncyClickable(pressScale = 0.94f, onClick = onActionClick)
                    ) {
                        if (actionIcon != null) {
                            Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
