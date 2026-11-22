package com.instalogger.app.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.R
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.util.AppLockManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppLockScreen(
    appLockManager: AppLockManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val isBiometricEnabled by appLockManager.isBiometricEnabled.collectAsStateWithLifecycle()
    val lockoutSeconds by appLockManager.lockoutRemainingSeconds.collectAsStateWithLifecycle()

    var enteredPin by remember { mutableStateOf("") }
    var isErrorShake by remember { mutableStateOf(false) }

    // Intercept hardware and gesture back button to strictly exit app while locked
    BackHandler {
        (context as? Activity)?.finishAffinity()
    }

    // Shake animation offset
    val shakeOffset = remember { Animatable(0f) }

    fun triggerShake() {
        scope.launch {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            isErrorShake = true
            repeat(3) {
                shakeOffset.animateTo(20f, tween(50, easing = LinearEasing))
                shakeOffset.animateTo(-20f, tween(50, easing = LinearEasing))
            }
            shakeOffset.animateTo(0f, tween(50, easing = LinearEasing))
            enteredPin = ""
            isErrorShake = false
        }
    }

    fun promptBiometric() {
        val activity = context as? FragmentActivity ?: return
        val biometricManager = BiometricManager.from(context)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        appLockManager.unlockBiometric()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.lock_biometric_title))
                .setSubtitle(context.getString(R.string.lock_biometric_desc))
                .setNegativeButtonText(context.getString(R.string.lock_use_pin))
                .build()

            prompt.authenticate(promptInfo)
        }
    }

    // Auto-prompt biometric on launch if enabled
    LaunchedEffect(Unit) {
        if (isBiometricEnabled && !appLockManager.isLockedOut()) {
            delay(250L)
            promptBiometric()
        }
    }

    // Full-screen impenetrable shield (consumes all pointer events)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                // Absorb any stray touches
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header Icon & Title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            )
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = if (lockoutSeconds > 0) {
                        stringResource(R.string.lock_lockout_warning, lockoutSeconds)
                    } else {
                        stringResource(R.string.lock_enter_pin_desc)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (lockoutSeconds > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // PIN Indicator Bubbles
            Row(
                modifier = Modifier
                    .graphicsLayer { translationX = shakeOffset.value }
                    .padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pinLength = 4
                for (i in 0 until pinLength) {
                    val isFilled = i < enteredPin.length
                    val bubbleColor by animateColorAsState(
                        targetValue = when {
                            isErrorShake -> MaterialTheme.colorScheme.error
                            isFilled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        animationSpec = tween<Color>(150),
                        label = "BubbleColor"
                    )

                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(bubbleColor)
                            .border(
                                1.5.dp,
                                if (isFilled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                    )
                }
            }

            // Numeric Keypad
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isLocked = lockoutSeconds > 0

                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("bio", "0", "del")
                )

                for (row in keys) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (key in row) {
                            when (key) {
                                "bio" -> {
                                    if (isBiometricEnabled) {
                                        Box(
                                            modifier = Modifier
                                                .size(72.dp)
                                                .clip(CircleShape)
                                                .bouncyClickable(enabled = !isLocked) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    promptBiometric()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Fingerprint,
                                                contentDescription = "Biometric",
                                                tint = if (!isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.size(72.dp))
                                    }
                                }
                                "del" -> {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .bouncyClickable(enabled = enteredPin.isNotEmpty() && !isLocked) {
                                            if (enteredPin.isNotEmpty()) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                enteredPin = enteredPin.dropLast(1)
                                            }
                                        },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Backspace,
                                            contentDescription = "Delete",
                                            tint = if (enteredPin.isNotEmpty() && !isLocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                else -> {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                                            .bouncyClickable(enabled = !isLocked) {
                                                if (enteredPin.length < 4 && !isLocked) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    enteredPin += key

                                                    if (enteredPin.length == 4) {
                                                        val success = appLockManager.unlockWithPin(enteredPin)
                                                        if (!success) {
                                                            triggerShake()
                                                        }
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = key,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (!isLocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
