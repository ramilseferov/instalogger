package com.instalogger.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Hardware vibrator & view haptic helper for PIN keypad interactions.
 * Combines View HapticFeedbackConstants (ignoring global view settings so PIN taps
 * always feel responsive) with direct hardware Vibrator micro-pulses.
 */
object PinHapticHelper {

    private fun resolveVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Crisp 12ms micro-vibration for digit taps on the PIN keypad.
     */
    fun performKeyTap(context: Context, view: View? = null) {
        try {
            @Suppress("DEPRECATION")
            view?.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        } catch (_: Exception) {}

        try {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(14L, 120))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(14L)
            }
        } catch (_: Exception) {}
    }

    /**
     * Lighter 9ms tick for backspace / delete key on the PIN keypad.
     */
    fun performBackspace(context: Context, view: View? = null) {
        try {
            @Suppress("DEPRECATION")
            view?.performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        } catch (_: Exception) {}

        try {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(10L, 90))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(10L)
            }
        } catch (_: Exception) {}
    }

    /**
     * Subtle double-tick confirmation pulse when PIN is unlocked or confirmed.
     */
    fun performSuccess(context: Context, view: View? = null) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        } catch (_: Exception) {}

        try {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 12L, 45L, 18L),
                        intArrayOf(0, 110, 0, 170),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0L, 12L, 45L, 18L), -1)
            }
        } catch (_: Exception) {}
    }

    /**
     * Distinct triple error pulse when an entered PIN is wrong or mismatched.
     */
    fun performError(context: Context, view: View? = null) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
            } else {
                @Suppress("DEPRECATION")
                view?.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            }
        } catch (_: Exception) {}

        try {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 28L, 45L, 28L, 45L, 36L),
                        intArrayOf(0, 190, 0, 190, 0, 230),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0L, 28L, 45L, 28L, 45L, 36L), -1)
            }
        } catch (_: Exception) {}
    }
}
