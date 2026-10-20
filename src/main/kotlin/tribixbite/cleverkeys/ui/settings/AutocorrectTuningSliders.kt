package tribixbite.cleverkeys.ui.settings

import tribixbite.cleverkeys.autocorrect.AutocorrectTuning
import tribixbite.cleverkeys.autocorrect.AutocorrectTuning.Keys
import tribixbite.cleverkeys.autocorrect.ChannelWeights
import kotlin.math.roundToInt

/**
 * Slider geometry and value-display rules for the Auto-Correction "Ranking & Tuning" group
 * (`AutoCorrectionSection`). Android-free so `AutocorrectTuningSlidersTest` runs under the pure
 * JVM runner.
 *
 * Ranges always come from [AutocorrectTuning.Keys] (the definition shared with
 * `AutocorrectTuning.fromPrefs` and `SettingsValidation`); this object only adds the slider
 * increment and the number of decimals shown. Every increment is chosen so the shipped default
 * and the values on the eval doc's tuning grid (`docs/eval/2026-10-18-channel-autocorrect-ranking.md`
 * §1.4) are exact slider positions.
 */
internal object AutocorrectTuningSliders {

    /** One float slider: its preference key, the distance between positions, and display decimals. */
    class FloatSpec(val key: AutocorrectTuning.FloatKey, val increment: Float, val decimals: Int) {
        /** Compose `Slider.steps`: the positions strictly between the two range ends. */
        val steps: Int get() = stepsFor(key.range.endInclusive - key.range.start, increment)

        /** [value] rounded to the nearest slider position inside the key's range. */
        fun snap(value: Float): Float {
            val start = key.range.start
            val positions = ((value - start) / increment).roundToInt()
            // Round the product to 1e-4 so stored values read back as 0.1, not 0.099999994.
            val snapped = ((start + positions * increment) * ROUNDING).roundToInt() / ROUNDING
            return snapped.coerceIn(key.range)
        }

        /** The value with this spec's number of decimals, in the default locale. */
        fun format(value: Float): String = "%.${decimals}f".format(value)
    }

    /** One integer slider: its preference key and the distance between positions. */
    class IntSpec(val key: AutocorrectTuning.IntKey, val increment: Int) {
        val valueRange: ClosedFloatingPointRange<Float> =
            key.range.first.toFloat()..key.range.last.toFloat()

        /** Compose `Slider.steps`. */
        val steps: Int get() = stepsFor((key.range.last - key.range.first).toFloat(), increment.toFloat())

        /** Slider position → stored integer, on the increment grid and inside the range. */
        fun snap(value: Float): Int {
            val positions = ((value - key.range.first) / increment).roundToInt()
            return (key.range.first + positions * increment).coerceIn(key.range)
        }
    }

    private const val ROUNDING = 10_000f

    private fun stepsFor(width: Float, increment: Float): Int = (width / increment).roundToInt() - 1

    // ── Band ranking ──
    val BAND_GAP = FloatSpec(Keys.BAND_GAP, 0.01f, 2)
    val TRANSPOSITION_PENALTY = FloatSpec(Keys.TRANSPOSITION_PENALTY, 0.01f, 2)
    /** 4 rank steps per position: 0..128 in 32 positions, the shipped 64 in the middle. */
    val DROPPED_DOUBLE_MARGIN = IntSpec(Keys.DROPPED_DOUBLE_MARGIN, 4)

    // ── Gates and steps shared by both rankings ──
    /** Shown as a percentage, so [FloatSpec.decimals] is unused for it. */
    val MIN_EXACT_RATIO = FloatSpec(Keys.MIN_EXACT_RATIO, 0.05f, 2)
    val MAX_SUBSTITUTIONS = IntSpec(Keys.MAX_SUBSTITUTIONS, 1)
    val LENGTH_EDIT_BUDGET = FloatSpec(Keys.LENGTH_EDIT_BUDGET, 0.1f, 1)
    val INFLECTION_GUARD_MIN_STEM = IntSpec(Keys.INFLECTION_GUARD_MIN_STEM, 1)

    // ── Channel ranking ──
    val CHANNEL_DISTANCE_COST = FloatSpec(Keys.CHANNEL_DISTANCE_COST, 0.1f, 1)
    val CHANNEL_INDEL_COST = FloatSpec(Keys.CHANNEL_INDEL_COST, 0.05f, 2)
    val CHANNEL_DOUBLE_COST = FloatSpec(Keys.CHANNEL_DOUBLE_COST, 0.05f, 2)
    val CHANNEL_TRANSPOSITION_COST = FloatSpec(Keys.CHANNEL_TRANSPOSITION_COST, 0.05f, 2)
    /** 0.0025 per position covers the whole λ grid (0.005 … 0.06). */
    val CHANNEL_FREQUENCY_WEIGHT = FloatSpec(Keys.CHANNEL_FREQUENCY_WEIGHT, 0.0025f, 4)
    /** β: 0.05 per position covers the round-2 grid (0, 0.25, 0.5). */
    val CHANNEL_ALIAS_BONUS = FloatSpec(Keys.CHANNEL_ALIAS_BONUS, 0.05f, 2)
    /** δ: 0.05 per position covers the round-3 grid (0.25 … 1.5). */
    val CHANNEL_SHORT_DELETION_COST = FloatSpec(Keys.CHANNEL_SHORT_DELETION_COST, 0.05f, 2)
    /** κ: 0.25 per position covers the round-5 grid (1.5 … 4.0); the last position (10) is off. */
    val CHANNEL_MAX_CORRECTION_COST = FloatSpec(Keys.CHANNEL_MAX_CORRECTION_COST, 0.25f, 2)
    /** The last position (10) is [ChannelWeights.INFLECTION_ALWAYS_KEEP]. */
    val CHANNEL_INFLECTION_COST = FloatSpec(Keys.CHANNEL_INFLECTION_COST, 0.25f, 2)

    val FLOATS: List<FloatSpec> = listOf(
        BAND_GAP, TRANSPOSITION_PENALTY, MIN_EXACT_RATIO, LENGTH_EDIT_BUDGET,
        CHANNEL_DISTANCE_COST, CHANNEL_INDEL_COST, CHANNEL_DOUBLE_COST, CHANNEL_TRANSPOSITION_COST,
        CHANNEL_FREQUENCY_WEIGHT, CHANNEL_ALIAS_BONUS, CHANNEL_SHORT_DELETION_COST, CHANNEL_MAX_CORRECTION_COST,
        CHANNEL_INFLECTION_COST,
    )
    val INTS: List<IntSpec> = listOf(DROPPED_DOUBLE_MARGIN, MAX_SUBSTITUTIONS, INFLECTION_GUARD_MIN_STEM)

    /**
     * How many rank steps of rarity cost as much as one wrong neighbouring key under the
     * channel ranking: `round(1 / λ)`. Null when λ is 0, i.e. frequency is ignored and only the
     * edits decide.
     */
    fun rankStepsPerWrongKey(frequencyWeight: Float): Int? =
        if (frequencyWeight <= 0f || frequencyWeight.isNaN()) null else (1f / frequencyWeight).roundToInt()

    /** γ as a slider position: the "always keep" ∞ sits on the last position. */
    fun inflectionCostSliderValue(inflectionCost: Float): Float =
        if (inflectionCost.isInfinite() || inflectionCost >= ChannelWeights.INFLECTION_ALWAYS_KEEP) {
            ChannelWeights.INFLECTION_ALWAYS_KEEP
        } else {
            inflectionCost
        }

    /** κ as a slider position: "off" (∞) sits on the last position. */
    fun maxCorrectionCostSliderValue(maxCorrectionCost: Float): Float =
        if (maxCorrectionCost.isInfinite() || maxCorrectionCost >= ChannelWeights.MAX_CORRECTION_OFF) {
            ChannelWeights.MAX_CORRECTION_OFF
        } else {
            maxCorrectionCost
        }

    /** True when the κ slider value means "no maximum". */
    fun isMaxCorrectionOff(sliderValue: Float): Boolean = sliderValue >= ChannelWeights.MAX_CORRECTION_OFF

    /** True when the γ slider value means "always keep the word form". */
    fun isAlwaysKeep(sliderValue: Float): Boolean = sliderValue >= ChannelWeights.INFLECTION_ALWAYS_KEEP

    /**
     * The preference keys "Reset autocorrect tuning" removes: exactly the tuning keys, so the
     * basic auto-correction settings above the group are untouched. Removing a key (rather than
     * writing its default) leaves `AutocorrectTuning.fromPrefs` on the shipped default, and a
     * later change of a default in `Config.Defaults` then reaches the user.
     */
    val RESET_KEYS: List<String> get() = Keys.ALL_KEYS
}
