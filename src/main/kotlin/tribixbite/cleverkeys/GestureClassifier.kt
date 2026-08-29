package tribixbite.cleverkeys

import tribixbite.cleverkeys.prefs.ConfigSnapshot

/**
 * Unified gesture classifier that determines if a touch gesture is a TAP or SWIPE.
 * Eliminates race conditions by providing single source of truth for gesture classification.
 *
 * Stateless: the decision is a pure function of the [GestureData] and the [ConfigSnapshot]
 * the caller captured for that gesture (ARC-072). The snapshot is a per-call argument rather
 * than a constructor field because one classifier instance lives as long as the keyboard view
 * and classifies many gestures — capturing at construction would freeze the threshold at
 * keyboard-creation time and never see a settings change.
 *
 * The former `Context` constructor parameter is gone along with the global-[Config] read:
 * its only remaining user was a `dpToPx` helper that had no callers.
 */
class GestureClassifier {

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
     *
     * @param config the configuration captured for this gesture; supplies the configurable
     *   tap-duration threshold.
     */
    fun classify(gesture: GestureData, config: ConfigSnapshot): GestureType {
        // Calculate dynamic threshold based on key size
        // Use half the key width as minimum swipe distance
        // Note: gesture.keyWidth is already in pixels (from key.width * _keyWidth)
        val minSwipeDistance = gesture.keyWidth / 2.0f

        // Clear criteria: SWIPE if left starting key AND (distance OR time threshold met)
        return if (gesture.hasLeftStartingKey &&
            (gesture.totalDistance >= minSwipeDistance ||
             gesture.timeElapsed > config.tap_duration_threshold)) {
            GestureType.SWIPE
        } else {
            GestureType.TAP
        }
    }
}
