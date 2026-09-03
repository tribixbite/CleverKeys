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
    val angleDegrees: Float,
    /**
     * The `KeyboardData.Key.keys` sublabel slot this direction occupies
     * (1=NW, 2=NE, 3=SW, 4=SE, 5=W, 6=E, 7=N, 8=S — index 0 is the main key).
     * Single source of truth shared by the custom-mapping overlay drawing and the
     * default-sublabel suppression in `Keyboard2View.onDraw` (#171): a custom mapping
     * REPLACES the default glyph in its slot, so both sides must agree on the slot.
     */
    val subLabelIndex: Int
) {
    N("North", "N", 90f, 7),
    NE("Northeast", "NE", 45f, 2),
    E("East", "E", 0f, 6),
    SE("Southeast", "SE", 315f, 4),
    S("South", "S", 270f, 8),
    SW("Southwest", "SW", 225f, 3),
    W("West", "W", 180f, 5),
    NW("Northwest", "NW", 135f, 1);

    companion object {
        /**
         * Bitmask of [subLabelIndex] slots covered by [directions] — bit `i` set means
         * sublabel slot `i` is occupied by a custom mapping and its DEFAULT glyph must
         * not be drawn (#171). Cheap (one set iterator); the draw path only calls it
         * for keys that actually have custom mappings.
         */
        @JvmStatic
        fun coveredSubLabelMask(directions: Set<SwipeDirection>): Int {
            var mask = 0
            for (d in directions) mask = mask or (1 shl d.subLabelIndex)
            return mask
        }

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
