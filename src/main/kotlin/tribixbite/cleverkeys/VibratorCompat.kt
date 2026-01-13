package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptic feedback events that can be individually enabled/disabled.
 * Each event can use system haptics or custom duration based on user preference.
 */
enum class HapticEvent {
    /** Standard key press haptic feedback */
    KEY_PRESS,
    /** Tapping an item in the prediction/suggestion bar */
    PREDICTION_TAP,
    /** Activating TrackPoint cursor mode on nav keys */
    TRACKPOINT_ACTIVATE,
    /** Long press event (lock modifier, key repeat start) */
    LONG_PRESS,
    /** Swipe gesture completion */
    SWIPE_COMPLETE
}

object VibratorCompat {
    private var vibratorService: Vibrator? = null

    /**
     * Trigger haptic feedback for a specific event type.
     * Respects per-event enable settings and uses system haptics when available.
     *
     * @param v The view to perform haptic feedback on
     * @param config The config containing haptic preferences
     * @param event The type of haptic event (defaults to KEY_PRESS for backward compatibility)
     */
    @JvmStatic
    @JvmOverloads
    fun vibrate(v: View, config: Config, event: HapticEvent = HapticEvent.KEY_PRESS) {
        // Check if this specific event type is enabled
        if (!isEventEnabled(config, event)) {
            return
        }

        // Use custom duration if enabled, otherwise use system haptics
        if (config.vibrate_custom && config.vibrate_duration > 0) {
            vibratorVibrate(v, config.vibrate_duration)
        } else {
            // Use appropriate system haptic feedback based on event type
            val hapticConstant = getHapticConstant(event)
            v.performHapticFeedback(
                hapticConstant,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }

    /**
     * Check if a specific haptic event type is enabled in config.
     */
    private fun isEventEnabled(config: Config, event: HapticEvent): Boolean {
        return when (event) {
            HapticEvent.KEY_PRESS -> config.haptic_key_press
            HapticEvent.PREDICTION_TAP -> config.haptic_prediction_tap
            HapticEvent.TRACKPOINT_ACTIVATE -> config.haptic_trackpoint_activate
            HapticEvent.LONG_PRESS -> config.haptic_long_press
            HapticEvent.SWIPE_COMPLETE -> config.haptic_swipe_complete
        }
    }

    /**
     * Get the appropriate HapticFeedbackConstant for each event type.
     * Different events use different system haptic patterns for distinctive feel.
     */
    private fun getHapticConstant(event: HapticEvent): Int {
        return when (event) {
            HapticEvent.KEY_PRESS -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticEvent.PREDICTION_TAP -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticEvent.TRACKPOINT_ACTIVATE -> {
                // Use confirm haptic for mode activation (stronger, more distinct)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            }
            HapticEvent.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
            HapticEvent.SWIPE_COMPLETE -> {
                // Use gesture end haptic on newer APIs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.GESTURE_END
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
            }
        }
    }

    /**
     * Use the older [Vibrator] when the newer API is not available or the user
     * wants more control over vibration duration.
     */
    private fun vibratorVibrate(v: View, duration: Long) {
        try {
            val vibrator = getVibrator(v)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (e: Exception) {
            // Ignore vibration errors (permission denied, no vibrator, etc.)
        }
    }

    private fun getVibrator(v: View): Vibrator {
        return vibratorService ?: run {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = v.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                v.context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibratorService = vibrator
            vibrator
        }
    }
}
