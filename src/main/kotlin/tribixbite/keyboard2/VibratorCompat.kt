package tribixbite.keyboard2

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Cross-platform vibration compatibility (matches Java implementation)
 *
 * Handles both system haptic feedback and custom vibration durations
 */
object VibratorCompat {

    private var vibratorService: Vibrator? = null

    /**
     * Perform vibration based on config settings (matches Java behavior)
     */
    @JvmStatic
    fun vibrate(view: View, config: Config) {
        if (config.vibrate_custom) {
            // Custom vibration with user-specified duration
            if (config.vibrate_duration > 0) {
                vibratorVibrate(view, config.vibrate_duration)
            }
        } else {
            // System default haptic feedback
            // FLAG_IGNORE_VIEW_SETTING ensures feedback even if user disabled in system settings
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }

    /**
     * Use the older Vibrator API when the newer API is not available
     * or the user wants more control over duration
     */
    private fun vibratorVibrate(view: View, duration: Long) {
        try {
            val vibrator = getVibrator(view)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(
                    duration,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (e: Exception) {
            // Silently ignore vibration failures (matches Java)
        }
    }

    private fun getVibrator(view: View): Vibrator {
        if (vibratorService == null) {
            vibratorService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = view.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
        return vibratorService ?: throw IllegalStateException("Vibrator service not available")
    }
}
