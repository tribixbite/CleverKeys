package tribixbite.cleverkeys.autocorrect

/**
 * Maps the user-facing "minimum frequency" autocorrect slider onto the
 * dictionary's ACTUAL runtime frequency scale.
 *
 * # Why this exists
 *
 * The settings slider is a fixed `100..2000` (default 100). But the runtime
 * dictionary frequency scale depends on how the dictionary was loaded:
 *   - V2 binary (`.bin`, the primary path): `BinaryDictionaryLoader` converts
 *     the stored rank to `1_000_000 - rank * 3900`, i.e. ~5,000 (rarest) to
 *     1,000,000 (most common).
 *   - JSON fallback: scaled to `100..10_000`.
 *
 * Comparing the raw slider value (max 2000) against the binary scale (min
 * ~5000) meant the floor was a NO-OP in the primary path — the slider did
 * nothing. On the JSON path the same value gated arbitrarily. Two different
 * behaviours for one setting.
 *
 * # What this does
 *
 * Express the floor as a FRACTION of the dictionary's own max frequency, so
 * the setting means the same thing regardless of load-path scale ("exclude
 * correction targets below X% of the most common word's frequency"):
 *
 *   slider 100  (default) -> fraction 0   -> floor 0          (no-op; correct
 *                                                              to any word —
 *                                                              preserves the
 *                                                              prior default
 *                                                              behaviour)
 *   slider 2000 (max)     -> fraction 1   -> floor MAX_STRICTNESS * maxFreq
 *
 * [MAX_STRICTNESS] < 1 guarantees the most-common words ALWAYS clear the floor,
 * so autocorrect can never be fully disabled by this slider (the bug the raw
 * comparison created on the JSON path).
 */
object FrequencyFloor {
    /** Slider domain — mirrors `AutoCorrectionSettingsActivity` (valueRange 100f..2000f). */
    const val SLIDER_MIN = 100
    const val SLIDER_MAX = 2000

    /**
     * Strictest floor as a fraction of the dictionary's max frequency. 0.6 ⇒
     * at the maximum slider setting, only correct to words in roughly the
     * top 40%-by-frequency; the most common words always remain eligible.
     */
    const val MAX_STRICTNESS = 0.6f

    /**
     * @param sliderValue the configured `autocorrect_confidence_min_frequency`.
     * @param maxFreq the maximum frequency present in the loaded dictionary.
     * @return an absolute frequency floor on the same scale as `maxFreq`.
     */
    fun effective(sliderValue: Int, maxFreq: Int): Int {
        if (maxFreq <= 0) return 0 // dictionary not loaded yet → don't gate
        val span = (SLIDER_MAX - SLIDER_MIN).toFloat()
        val t = ((sliderValue - SLIDER_MIN).toFloat() / span).coerceIn(0f, 1f)
        return (t * MAX_STRICTNESS * maxFreq).toInt()
    }
}
