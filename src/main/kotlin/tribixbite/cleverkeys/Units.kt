package tribixbite.cleverkeys

/**
 * Unit-typed wrapper for gesture geometry values expressed as a PERCENT of a key's
 * diagonal (hypotenuse). The short-gesture thresholds (`short_gesture_min_distance`,
 * `short_gesture_max_distance`) are percentages, while everything they get compared
 * against (finger displacement, path length) is pixels — three Pointers pre-checks
 * and the calibration activity all once compared the raw percent number against px
 * (~half the intended threshold). Wrapping the percent in a zero-cost value class
 * makes that mistake a compile error: the only way to compare is [toPx], which
 * forces the caller to supply the key diagonal.
 *
 * `@JvmInline value class` compiles to the bare underlying Int in most positions
 * (no boxing) — the same mechanism as Compose's `Dp`/`TextUnit`.
 */
@JvmInline
value class PercentOfKey(val v: Int) {
    /** Convert to pixels against a specific key's diagonal (hypotenuse) in px. */
    fun toPx(keyDiagonalPx: Float): Float = keyDiagonalPx * v / 100f

    override fun toString(): String = "$v%"
}
