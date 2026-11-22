package com.instalogger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val shape = RoundedCornerShape(50)
    val borderModifier = if (borderColor != null) {
        Modifier.border(1.dp, borderColor, shape)
    } else Modifier

    Row(
        modifier = modifier
            .then(borderModifier)
            .clip(shape)
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = text,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun PrivacyBadge(isPrivate: Boolean) {
    if (isPrivate) {
        StatusBadge(
            text = "Məxfi",
            icon = Icons.Default.Lock,
            containerColor = StatusAmberLight,
            contentColor = StatusAmber
        )
    } else {
        StatusBadge(
            text = "Açıq",
            icon = Icons.Default.LockOpen,
            containerColor = StatusGreenLight,
            contentColor = StatusGreen
        )
    }
}

@Composable
fun TurboBadge(isTurbo: Boolean) {
    if (isTurbo) {
        StatusBadge(
            text = "Turbo",
            icon = Icons.Default.Bolt,
            containerColor = AccentBlueLight,
            contentColor = AccentBlue
        )
    }
}
