package com.instalogger.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.instalogger.app.R
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.ShapeTokens

/**
 * Ergonomic reachability header for top viewing area.
 * Anchors badge content permanently on the start slot and action buttons permanently on the end slot
 * so action buttons NEVER shift or teleport when badges appear or disappear.
 */
@Composable
fun ReachabilityHeader(
    title: String,
    subtitle: String? = null,
    showLogo: Boolean = true,
    badgeContent: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        if (badgeContent != null || actions != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    badgeContent?.invoke()
                }

                if (actions != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showLogo) {
                Image(
                    painter = painterResource(id = R.drawable.ic_instalogger_logo),
                    contentDescription = "InstaLogger Logo",
                    modifier = Modifier.size(34.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Modern squircle action button container with bouncy spring touch feedback and 44dp accessibility bounds.
 * Supports disabled state with smooth dimming while preserving exact layout position.
 */
@Composable
fun HeaderActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = ShapeTokens.SmallShape
    Box(
        modifier = modifier
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .then(
                if (enabled) Modifier.bouncyClickable(pressScale = 0.92f, onClick = onClick)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}
