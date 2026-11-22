package com.instalogger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instalogger.app.R
import com.instalogger.app.ui.theme.*

@Composable
fun StatusBadge(
    text: String,
    icon: ImageVector? = null,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val shape = ShapeTokens.SmallShape
    val borderModifier = if (borderColor != null) {
        Modifier.border(ShapeTokens.BorderSubtle, borderColor, shape)
    } else Modifier

    Row(
        modifier = modifier
            .then(borderModifier)
            .clip(shape)
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = text,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp
        )
    }
}

@Composable
fun PrivacyBadge(isPrivate: Boolean) {
    val isDark = isSystemInDarkTheme()
    if (isPrivate) {
        StatusBadge(
            text = stringResource(R.string.badge_private),
            icon = Icons.Default.Lock,
            containerColor = if (isDark) StatusAmberSubtleDark else StatusAmberLight,
            contentColor = if (isDark) StatusAmber else StatusAmberDark,
            borderColor = if (isDark) StatusAmber.copy(alpha = 0.3f) else StatusAmber.copy(alpha = 0.2f)
        )
    } else {
        StatusBadge(
            text = stringResource(R.string.badge_public),
            icon = Icons.Default.LockOpen,
            containerColor = if (isDark) StatusGreenSubtleDark else StatusGreenLight,
            contentColor = if (isDark) StatusGreen else StatusGreenDark,
            borderColor = if (isDark) StatusGreen.copy(alpha = 0.3f) else StatusGreen.copy(alpha = 0.2f)
        )
    }
}

@Composable
fun TurboBadge(isTurbo: Boolean) {
    if (isTurbo) {
        val isDark = isSystemInDarkTheme()
        StatusBadge(
            text = stringResource(R.string.badge_turbo),
            icon = Icons.Default.Bolt,
            containerColor = if (isDark) AccentBlueSubtleDark else AccentBlueSubtleLight,
            contentColor = AccentBlue,
            borderColor = AccentBlue.copy(alpha = 0.35f)
        )
    }
}
