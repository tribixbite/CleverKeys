package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptic feedback events that can be individually enabled/disabled.
 * Each event uses optimized system haptic patterns for best battery life and latency.
 *
 * Based on Android best practices:
 * - performHapticFeedback is preferred over Vibrator for low latency and OEM optimization
 * - Different constants provide distinct tactile feels for different actions
 * - API-level fallbacks ensure broad device compatibility
 */
enum class HapticEvent {
    /** Standard key press haptic feedback - KEYBOARD_TAP (crisp, short) */
    KEY_PRESS,
    /** Tapping an item in the prediction/suggestion bar - TEXT_HANDLE_MOVE (lighter tick) */
    PREDICTION_TAP,
    /** Activating TrackPoint cursor mode on nav keys - CLOCK_TICK (subtle) */
    TRACKPOINT_ACTIVATE,
    /** Long press event (lock modifier, key repeat start) - LONG_PRESS (standard) */
    LONG_PRESS,
    /** Swipe gesture completion - GESTURE_END (confirming thud) */
    SWIPE_COMPLETE
}

/**
 * Handles haptic feedback for the keyboard with optimal system integration.
 *
 * Features:
 * - Per-event enable/disable toggles
 * - Uses performHapticFeedback for low latency and OEM-optimized patterns
 * - Falls back to Vibrator only when user sets custom duration
 * - Respects system haptic settings unless user explicitly overrides
 * - API-level appropriate haptic constants with fallbacks
 */
object VibratorCompat {
    private var vibratorService: Vibrator? = null

    // Cache system haptic setting to avoid IPC on every keypress
    private var cachedSystemHapticEnabled: Boolean? = null
    private var lastSettingCheck: Long = 0
    private const val SETTING_CACHE_MS = 5000L  // Re-check every 5 seconds

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
        // Master toggle - when disabled, no haptic feedback at all
        if (!config.haptic_enabled) {
            return
        }

        // Check if this specific event type is enabled in app settings
        if (!isEventEnabled(config, event)) {
            return
        }

        // Use custom duration if enabled, otherwise use system haptics
        if (config.vibrate_custom && config.vibrate_duration > 0) {
            // User wants custom vibration - use Vibrator directly
            // This bypasses system haptic settings (user explicitly enabled)
            vibratorVibrate(v, config.vibrate_duration)
        } else {
            // Use system haptic feedback - low latency, OEM optimized
            val hapticConstant = getHapticConstant(event)

            // FLAG_IGNORE_VIEW_SETTING: Always respect our app's per-event settings
            // FLAG_IGNORE_GLOBAL_SETTING: Only set if user explicitly enabled haptics in our app
            // This allows users who turned off system haptics to still get keyboard feedback
            val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING

            val performed = v.performHapticFeedback(hapticConstant, flags)

            // Fallback to manual vibration if performHapticFeedback fails
            // (e.g., unknown constant on older device)
            if (!performed) {
                vibratorVibrate(v, getDefaultDuration(event))
            }
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
     * Uses API-level appropriate constants with sensible fallbacks.
     *
     * Mapping rationale (per Android best practices):
     * - KEY_PRESS: KEYBOARD_TAP - specifically tuned by OEMs for typing (short, crisp)
     * - PREDICTION_TAP: TEXT_HANDLE_MOVE - lighter "tick" to distinguish from typing
     * - TRACKPOINT_ACTIVATE: CLOCK_TICK - subtle feedback for mode entry
     * - LONG_PRESS: LONG_PRESS - standard Android behavior
     * - SWIPE_COMPLETE: GESTURE_END - confirming "thud" for gesture completion
     */
    private fun getHapticConstant(event: HapticEvent): Int {
        return when (event) {
            HapticEvent.KEY_PRESS -> {
                // KEYBOARD_TAP available from API 27+ (O_MR1), fallback to VIRTUAL_KEY
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    HapticFeedbackConstants.KEYBOARD_TAP
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            }

            HapticEvent.PREDICTION_TAP -> {
                // TEXT_HANDLE_MOVE provides a lighter tick than KEYBOARD_TAP
                // Good for distinguishing suggestion selection from typing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    HapticFeedbackConstants.TEXT_HANDLE_MOVE
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            }

            HapticEvent.TRACKPOINT_ACTIVATE -> {
                // CLOCK_TICK is extremely subtle - perfect for mode activation
                // Available from API 21+ (LOLLIPOP)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    HapticFeedbackConstants.CLOCK_TICK
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            }

            HapticEvent.LONG_PRESS -> {
                // Standard Android long press haptic
                HapticFeedbackConstants.LONG_PRESS
            }

            HapticEvent.SWIPE_COMPLETE -> {
                // GESTURE_END provides a confirming "thud" on API 30+ (R)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.GESTURE_END
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            }
        }
    }

    /**
     * Get default vibration duration for fallback when performHapticFeedback fails.
     */
    private fun getDefaultDuration(event: HapticEvent): Long {
        return when (event) {
            HapticEvent.KEY_PRESS -> 10L
            HapticEvent.PREDICTION_TAP -> 8L  // Slightly shorter
            HapticEvent.TRACKPOINT_ACTIVATE -> 15L
            HapticEvent.LONG_PRESS -> 40L
            HapticEvent.SWIPE_COMPLETE -> 20L
        }
    }

    /**
     * Check if system haptic feedback is enabled.
     * Cached to avoid IPC overhead on every keypress.
     */
    @Suppress("unused")
    private fun isSystemHapticEnabled(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (cachedSystemHapticEnabled == null || now - lastSettingCheck > SETTING_CACHE_MS) {
            cachedSystemHapticEnabled = try {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    1  // Default to enabled
                ) != 0
            } catch (e: Exception) {
                true  // Default to enabled if we can't read
            }
            lastSettingCheck = now
        }
        return cachedSystemHapticEnabled ?: true
    }

    /**
     * Invalidate cached system setting. Call this from onResume or onStartInput
     * to ensure fresh value after user might have changed system settings.
     */
    @JvmStatic
    fun invalidateSettingsCache() {
        cachedSystemHapticEnabled = null
    }

    /**
     * Use the Vibrator API when performHapticFeedback is unavailable or
     * user wants custom duration control.
     */
    private fun vibratorVibrate(v: View, duration: Long) {
        try {
            val vibrator = getVibrator(v)
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                )
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
