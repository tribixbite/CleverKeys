package tribixbite.cleverkeys.customization

/**
 * Represents the 8 cardinal and inter-cardinal directions for short swipe gestures.
 * These directions correspond to the physical swipe direction from a key's center.
 */
enum class SwipeDirection(
    /** Display name for UI */
    val displayName: String,
    /** Short label for compact display */
    val shortLabel: String,
    /** Angle in degrees (0 = East, counter-clockwise) */
    val angleDegrees: Float
) {
    N("North", "N", 90f),
    NE("Northeast", "NE", 45f),
    E("East", "E", 0f),
    SE("Southeast", "SE", 315f),
    S("South", "S", 270f),
    SW("Southwest", "SW", 225f),
    W("West", "W", 180f),
    NW("Northwest", "NW", 135f);

    companion object {
        /**
         * Get direction from angle in degrees.
         * @param angle Angle in degrees (0 = East, counter-clockwise positive)
         * @return The closest SwipeDirection
         */
        fun fromAngle(angle: Float): SwipeDirection {
            // Normalize angle to 0-360
            val normalizedAngle = ((angle % 360) + 360) % 360

            // Each direction covers 45 degrees centered on its angle
            // Add 22.5 to shift the boundaries between directions
            val shiftedAngle = (normalizedAngle + 22.5f) % 360

            return when {
                shiftedAngle < 45f -> E
                shiftedAngle < 90f -> NE
                shiftedAngle < 135f -> N
                shiftedAngle < 180f -> NW
                shiftedAngle < 225f -> W
                shiftedAngle < 270f -> SW
                shiftedAngle < 315f -> S
                else -> SE
            }
        }

        /**
         * Get direction from delta x and y.
         * @param dx Delta X (positive = right)
         * @param dy Delta Y (positive = down, screen coordinates)
         * @return The corresponding SwipeDirection
         */
        fun fromDelta(dx: Float, dy: Float): SwipeDirection {
            // Convert to angle (atan2 uses standard math coordinates, y-up)
            // Screen coordinates have y-down, so negate dy
            val angle = Math.toDegrees(kotlin.math.atan2(-dy.toDouble(), dx.toDouble())).toFloat()
            return fromAngle(angle)
        }

        /**
         * Get all directions in clockwise order starting from North.
         */
        fun clockwiseFromNorth(): List<SwipeDirection> = listOf(N, NE, E, SE, S, SW, W, NW)
    }
}
