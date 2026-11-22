package com.instalogger.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.instalogger.app.R
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.ShapeTokens
import java.io.File

/**
 * Standardized full-screen/modal image preview dialog across the application.
 */
@Composable
fun ImagePreviewDialog(
    imagePath: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        val dialogShape = ShapeTokens.LargeShape
        Surface(
            shape = dialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, dialogShape)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val imageFile = File(imagePath)
                val imageModel = if (imageFile.exists()) imageFile else imagePath

                AsyncImage(
                    model = imageModel,
                    contentDescription = stringResource(R.string.preview_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .clip(ShapeTokens.MediumShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    shape = ShapeTokens.SmallShape,
                    modifier = Modifier.bouncyClickable(pressScale = 0.94f, onClick = onDismiss)
                ) {
                    Text(stringResource(R.string.dialog_close))
                }
            }
        }
    }
}
