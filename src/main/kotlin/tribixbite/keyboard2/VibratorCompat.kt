package tribixbite.keyboard2

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Cross-platform vibration compatibility
 * Kotlin implementation with modern Android API support
 */
class VibratorCompat(private val context: Context) {
    
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    
    /**
     * Perform haptic feedback
     */
    fun performHapticFeedback(duration: Long = 20L) {
        val vib = vibrator ?: return
        
        if (!vib.hasVibrator()) return
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(duration)
            }
        } catch (e: Exception) {
            logE("Vibration failed", e)
        }
    }
    
    /**
     * Perform click feedback
     */
    fun performClickFeedback() {
        performHapticFeedback(50L)
    }
    
    /**
     * Perform long press feedback
     */
    fun performLongPressFeedback() {
        performHapticFeedback(100L)
    }
    
    /**
     * Check if vibrator is available
     */
    val hasVibrator: Boolean
        get() = vibrator?.hasVibrator() ?: false

    private fun logE(message: String, throwable: Throwable) {
        android.util.Log.e("VibratorCompat", message, throwable)
    }
}