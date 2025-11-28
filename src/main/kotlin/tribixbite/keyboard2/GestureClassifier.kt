package tribixbite.keyboard2

import android.content.Context
import android.util.TypedValue

/**
 * Unified gesture classifier that determines if a touch gesture is a TAP or SWIPE
 * Eliminates race conditions by providing single source of truth for gesture classification
 */
class GestureClassifier(private val context: Context) {
    // Use configurable tap duration threshold from settings
    private val maxTapDurationMs: Long
        get() = Config.globalConfig().tap_duration_threshold

    enum class GestureType {
        TAP,
        SWIPE
    }

    /**
     * Data structure containing all gesture information needed for classification
     */
    data class GestureData(
        @JvmField val hasLeftStartingKey: Boolean,
        @JvmField val totalDistance: Float,
        @JvmField val timeElapsed: Long,
        @JvmField val keyWidth: Float
    )

    /**
     * Classify a gesture as TAP or SWIPE based on multiple criteria
     *
     * A gesture is a SWIPE if:
     * - User left the starting key AND
     * - (Distance exceeds minimum threshold OR time exceeds tap duration)
     *
     * Otherwise it's a TAP
     */
    fun classify(gesture: GestureData): GestureType {
        // Calculate dynamic threshold based on key size
        // Use half the key width as minimum swipe distance
        // Note: gesture.keyWidth is already in pixels (from key.width * _keyWidth)
        val minSwipeDistance = gesture.keyWidth / 2.0f

        // Clear criteria: SWIPE if left starting key AND (distance OR time threshold met)
        return if (gesture.hasLeftStartingKey &&
            (gesture.totalDistance >= minSwipeDistance ||
             gesture.timeElapsed > maxTapDurationMs)) {
            GestureType.SWIPE
        } else {
            GestureType.TAP
        }
    }

    /**
     * Convert dp to pixels using display density
     */
    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}
