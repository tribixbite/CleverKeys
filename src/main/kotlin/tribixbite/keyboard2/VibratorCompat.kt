package tribixbite.keyboard2

import android.content.Context
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

object VibratorCompat {
    private var vibratorService: Vibrator? = null

    @JvmStatic
    fun vibrate(v: View, config: Config) {
        if (config.vibrate_custom) {
            if (config.vibrate_duration > 0) {
                vibratorVibrate(v, config.vibrate_duration)
            }
        } else {
            v.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }

    /**
     * Use the older [Vibrator] when the newer API is not available or the user
     * wants more control.
     */
    private fun vibratorVibrate(v: View, duration: Long) {
        try {
            getVibrator(v).vibrate(duration)
        } catch (e: Exception) {
            // Ignore vibration errors
        }
    }

    private fun getVibrator(v: View): Vibrator {
        return vibratorService ?: run {
            val vibrator = v.context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibratorService = vibrator
            vibrator
        }
    }
}
