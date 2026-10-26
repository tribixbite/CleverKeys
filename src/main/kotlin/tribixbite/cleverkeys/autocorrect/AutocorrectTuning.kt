package tribixbite.cleverkeys.autocorrect

import android.content.SharedPreferences
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults

/**
 * How `WordPredictor.autoCorrect` chooses between the dictionary words that pass its candidate
 * gates (eval: `docs/eval/2026-10-18-channel-autocorrect-ranking.md`).
 *
 * - [BAND]: the original fold. A candidate more than [AutocorrectTuning.bandGap] better on raw
 *   match score wins; inside the band, structural rules and then frequency decide.
 * - [CHANNEL]: rule C. Minimum `edit cost + λ · rank`, so frequency trades against the number
 *   and kind of edits on one scale instead of overriding them inside a band.
 */
enum class AutocorrectRanking(val prefValue: String) {
    BAND("band"),
    CHANNEL("channel");

    companion object {
        /** Unknown or missing pref strings fall back to [Defaults.AUTOCORRECT_RANKING]. */
        fun fromPref(value: String?): AutocorrectRanking =
            entries.firstOrNull { it.prefValue == value }
                ?: entries.first { it.prefValue == Defaults.AUTOCORRECT_RANKING }
    }
}

/**
 * Where the short-word deletion cost δ applies (round 4, eval doc §11). It is right for Hebrew,
 * whose one-letter prefixes make "delete onto a two-letter word" a wrong correction, and wrong
 * for English, where an extra letter around `in`/`to`/`it` is a real typo (round 3, §10).
 */
enum class ShortDeletionScope(val prefValue: String) {
    /** Only typed words written in Hebrew script. */
    HEBREW("hebrew"),
    /** Every language. */
    ALL("all");

    companion object {
        fun fromPref(value: String?): ShortDeletionScope =
            entries.firstOrNull { it.prefValue == value }
                ?: entries.first { it.prefValue == Defaults.AUTOCORRECT_CHANNEL_SHORT_DELETION_SCOPE }
    }
}

/**
 * Rule C's edit weights (eval doc §1.1). A substitution `a → b` costs
 * `1 + distanceCost · keyDistance(a, b)` with `keyDistance ∈ [0, 1]`; one wrong key at the
 * nearest position is the unit every other weight is measured against.
 *
 * @property distanceCost s₁ — extra cost of a substitution per unit of normalized key distance
 *   (0 = every wrong key costs the same; 4 = a far-corner key costs five neighbour slips).
 * @property indelCost g — a missing or extra letter.
 * @property doubleCost g₂ — a missing or extra letter that repeats its neighbour (`helo` →
 *   `hello`, `gamees` → `games`).
 * @property transpositionCost τ — two adjacent letters swapped (`teh` → `the`).
 * @property frequencyWeight λ — cost per rank step of rarity; `1/λ` rank steps equal one
 *   wrong key.
 * @property shortWordDeletionCost δ — added when the candidate is shorter than the typed word
 *   and has at most [ChannelEditCost.SHORT_WORD_MAX_LENGTH] letters, so a mistyped one-letter
 *   prefix is not simply deleted onto a common short word (`לןא` → `לא`, round 3, eval doc §7).
 * @property shortDeletionScope where δ applies ([ShortDeletionScope]).
 * @property maxCorrectionCost κ — a correction whose edit cost exceeds this is not made, so a
 *   valid word the dictionary lacks is not rewritten into a distant one (round 5, eval doc §13).
 *   [Float.POSITIVE_INFINITY] = off.
 * @property aliasBonus β — subtracted from a contraction alias key's total cost, so `donr`
 *   prefers `don't` over an equally close `done` (round 2, eval doc §3.1).
 * @property inflectionCost γ — guard G: the cost of believing a typed word is an unlisted
 *   inflection of a known stem. [Float.POSITIVE_INFINITY] keeps every such word (the band
 *   ranking's guard).
 */
data class ChannelWeights(
    val distanceCost: Float = Defaults.AUTOCORRECT_CHANNEL_DISTANCE_COST,
    val indelCost: Float = Defaults.AUTOCORRECT_CHANNEL_INDEL_COST,
    val doubleCost: Float = Defaults.AUTOCORRECT_CHANNEL_DOUBLE_COST,
    val transpositionCost: Float = Defaults.AUTOCORRECT_CHANNEL_TRANSPOSITION_COST,
    val frequencyWeight: Float = Defaults.AUTOCORRECT_CHANNEL_FREQUENCY_WEIGHT,
    val aliasBonus: Float = Defaults.AUTOCORRECT_CHANNEL_ALIAS_BONUS,
    val shortWordDeletionCost: Float = Defaults.AUTOCORRECT_CHANNEL_SHORT_DELETION_COST,
    val shortDeletionScope: ShortDeletionScope = ShortDeletionScope.fromPref(Defaults.AUTOCORRECT_CHANNEL_SHORT_DELETION_SCOPE),
    val maxCorrectionCost: Float = maxCorrectionCostFromPref(Defaults.AUTOCORRECT_CHANNEL_MAX_CORRECTION_COST),
    val inflectionCost: Float = inflectionCostFromPref(Defaults.AUTOCORRECT_CHANNEL_INFLECTION_COST),
) {
    companion object {
        /**
         * Stored γ at or above this value means "always keep the inflection" (∞). It is the top
         * of [AutocorrectTuning.Keys.CHANNEL_INFLECTION_COST]'s range, so the slider's last
         * position writes exactly this value.
         */
        const val INFLECTION_ALWAYS_KEEP = 10f

        /** Stored κ at or above this value means "off" (∞); the top of its slider. */
        const val MAX_CORRECTION_OFF = 10f

        /** Pref value → κ, mapping the "off" position to [Float.POSITIVE_INFINITY]. */
        fun maxCorrectionCostFromPref(stored: Float): Float =
            if (stored.isNaN() || stored >= MAX_CORRECTION_OFF) Float.POSITIVE_INFINITY
            else stored.coerceAtLeast(0f)

        /** Pref value → γ, mapping the "always keep" position to [Float.POSITIVE_INFINITY]. */
        fun inflectionCostFromPref(stored: Float): Float =
            if (stored.isNaN() || stored >= INFLECTION_ALWAYS_KEEP) Float.POSITIVE_INFINITY
            else stored.coerceAtLeast(0f)
    }
}

/**
 * Every weight and switch of `WordPredictor.autoCorrect` beyond the long-standing basic
 * settings (min length, match threshold, length difference, prefix, frequency floor), as one
 * immutable snapshot. The maintainer asked (2026-10-18) for all of them to be user-tunable;
 * each default reproduces the behaviour that shipped before they were settings.
 *
 * `Config.refresh()` builds it with [fromPrefs] into `Config.autocorrect_tuning`; a null field
 * (an Objenesis test Config, early startup) means [DEFAULT]. It is part of the autocorrect memo
 * key, so a settings change never serves a correction computed under the old values.
 *
 * @property bandGap score difference beyond which the band ranking ignores frequency.
 * @property transpositionPenalty band ranking: a transposition scores `1 − penalty / length`.
 * @property minExactRatio candidate gate: share of same-length positions that must match.
 * @property maxSubstitutions candidate gate: most substituted positions a same-length word may have.
 * @property lengthEditBudget candidate gate: a different-length word may have weighted edit
 *   distance up to `length difference + budget`.
 * @property droppedDoubleEnabled band ranking's step 1.45 (`helo` → `hello`). The channel
 *   ranking models this typo with [ChannelWeights.doubleCost] instead.
 * @property droppedDoubleMarginSteps how many rank steps rarer the re-doubled word may be.
 * @property elongationCollapseEnabled step 1.4 (`gamees` → `games`), both rankings.
 * @property inflectionGuardEnabled step 1.5; off lets words like `immunizations` be corrected.
 * @property inflectionGuardMinStem shortest stem that makes the guard apply.
 */
data class AutocorrectTuning(
    val ranking: AutocorrectRanking = AutocorrectRanking.fromPref(Defaults.AUTOCORRECT_RANKING),
    val bandGap: Float = Defaults.AUTOCORRECT_BAND_GAP,
    val transpositionPenalty: Float = Defaults.AUTOCORRECT_TRANSPOSITION_PENALTY,
    val minExactRatio: Float = Defaults.AUTOCORRECT_MIN_EXACT_RATIO,
    val maxSubstitutions: Int = Defaults.AUTOCORRECT_MAX_SUBSTITUTIONS,
    val lengthEditBudget: Float = Defaults.AUTOCORRECT_LENGTH_EDIT_BUDGET,
    val droppedDoubleEnabled: Boolean = Defaults.AUTOCORRECT_DROPPED_DOUBLE_ENABLED,
    val droppedDoubleMarginSteps: Int = Defaults.AUTOCORRECT_DROPPED_DOUBLE_MARGIN,
    val elongationCollapseEnabled: Boolean = Defaults.AUTOCORRECT_ELONGATION_COLLAPSE_ENABLED,
    val inflectionGuardEnabled: Boolean = Defaults.AUTOCORRECT_INFLECTION_GUARD_ENABLED,
    val inflectionGuardMinStem: Int = Defaults.AUTOCORRECT_INFLECTION_GUARD_MIN_STEM,
    val channel: ChannelWeights = ChannelWeights(),
) {
    /** A float preference with its accepted range (shared by the reader, validation and UI). */
    class FloatKey(val key: String, val default: Float, val range: ClosedFloatingPointRange<Float>)

    /** An integer preference with its accepted range. */
    class IntKey(val key: String, val default: Int, val range: IntRange)

    /** A boolean preference. */
    class BoolKey(val key: String, val default: Boolean)

    /** A string preference restricted to [values]. */
    class StringKey(val key: String, val default: String, val values: List<String>)

    /**
     * The preference keys, defaults and ranges — the single definition used by
     * [fromPrefs], `SettingsValidation`, `SettingsDefaults` and the settings screen.
     */
    object Keys {
        val RANKING = StringKey("autocorrect_ranking", Defaults.AUTOCORRECT_RANKING,
            AutocorrectRanking.entries.map { it.prefValue })
        val BAND_GAP = FloatKey("autocorrect_band_gap", Defaults.AUTOCORRECT_BAND_GAP, 0f..0.3f)
        val TRANSPOSITION_PENALTY = FloatKey("autocorrect_transposition_penalty",
            Defaults.AUTOCORRECT_TRANSPOSITION_PENALTY, 0f..0.5f)
        val MIN_EXACT_RATIO = FloatKey("autocorrect_min_exact_ratio", Defaults.AUTOCORRECT_MIN_EXACT_RATIO, 0.3f..0.8f)
        val MAX_SUBSTITUTIONS = IntKey("autocorrect_max_substitutions", Defaults.AUTOCORRECT_MAX_SUBSTITUTIONS, 1..3)
        val LENGTH_EDIT_BUDGET = FloatKey("autocorrect_length_edit_budget",
            Defaults.AUTOCORRECT_LENGTH_EDIT_BUDGET, 0f..1.5f)
        val CHANNEL_SHORT_DELETION_SCOPE = StringKey("autocorrect_channel_short_deletion_scope",
            Defaults.AUTOCORRECT_CHANNEL_SHORT_DELETION_SCOPE, ShortDeletionScope.entries.map { it.prefValue })
        val DROPPED_DOUBLE_ENABLED = BoolKey("autocorrect_dropped_double_enabled",
            Defaults.AUTOCORRECT_DROPPED_DOUBLE_ENABLED)
        val DROPPED_DOUBLE_MARGIN = IntKey("autocorrect_dropped_double_margin",
            Defaults.AUTOCORRECT_DROPPED_DOUBLE_MARGIN, 0..128)
        val ELONGATION_COLLAPSE_ENABLED = BoolKey("autocorrect_elongation_collapse_enabled",
            Defaults.AUTOCORRECT_ELONGATION_COLLAPSE_ENABLED)
        val INFLECTION_GUARD_ENABLED = BoolKey("autocorrect_inflection_guard_enabled",
            Defaults.AUTOCORRECT_INFLECTION_GUARD_ENABLED)
        val INFLECTION_GUARD_MIN_STEM = IntKey("autocorrect_inflection_guard_min_stem",
            Defaults.AUTOCORRECT_INFLECTION_GUARD_MIN_STEM, 3..8)
        val CHANNEL_DISTANCE_COST = FloatKey("autocorrect_channel_distance_cost",
            Defaults.AUTOCORRECT_CHANNEL_DISTANCE_COST, 0f..4f)
        val CHANNEL_INDEL_COST = FloatKey("autocorrect_channel_indel_cost",
            Defaults.AUTOCORRECT_CHANNEL_INDEL_COST, 0.5f..2f)
        val CHANNEL_DOUBLE_COST = FloatKey("autocorrect_channel_double_cost",
            Defaults.AUTOCORRECT_CHANNEL_DOUBLE_COST, 0.1f..2f)
        val CHANNEL_TRANSPOSITION_COST = FloatKey("autocorrect_channel_transposition_cost",
            Defaults.AUTOCORRECT_CHANNEL_TRANSPOSITION_COST, 0.3f..2f)
        val CHANNEL_FREQUENCY_WEIGHT = FloatKey("autocorrect_channel_frequency_weight",
            Defaults.AUTOCORRECT_CHANNEL_FREQUENCY_WEIGHT, 0f..0.1f)
        val CHANNEL_SHORT_DELETION_COST = FloatKey("autocorrect_channel_short_deletion_cost",
            Defaults.AUTOCORRECT_CHANNEL_SHORT_DELETION_COST, 0f..2f)
        val CHANNEL_MAX_CORRECTION_COST = FloatKey("autocorrect_channel_max_correction_cost",
            Defaults.AUTOCORRECT_CHANNEL_MAX_CORRECTION_COST, 1f..ChannelWeights.MAX_CORRECTION_OFF)
        val CHANNEL_ALIAS_BONUS = FloatKey("autocorrect_channel_alias_bonus",
            Defaults.AUTOCORRECT_CHANNEL_ALIAS_BONUS, 0f..1f)
        val CHANNEL_INFLECTION_COST = FloatKey("autocorrect_channel_inflection_cost",
            Defaults.AUTOCORRECT_CHANNEL_INFLECTION_COST, 0f..ChannelWeights.INFLECTION_ALWAYS_KEEP)

        val FLOATS = listOf(BAND_GAP, TRANSPOSITION_PENALTY, MIN_EXACT_RATIO, LENGTH_EDIT_BUDGET,
            CHANNEL_DISTANCE_COST, CHANNEL_INDEL_COST, CHANNEL_DOUBLE_COST, CHANNEL_TRANSPOSITION_COST,
            CHANNEL_FREQUENCY_WEIGHT, CHANNEL_ALIAS_BONUS, CHANNEL_SHORT_DELETION_COST, CHANNEL_MAX_CORRECTION_COST,
            CHANNEL_INFLECTION_COST)
        val INTS = listOf(MAX_SUBSTITUTIONS, DROPPED_DOUBLE_MARGIN, INFLECTION_GUARD_MIN_STEM)
        val BOOLS = listOf(DROPPED_DOUBLE_ENABLED, ELONGATION_COLLAPSE_ENABLED, INFLECTION_GUARD_ENABLED)
        val STRINGS = listOf(RANKING, CHANNEL_SHORT_DELETION_SCOPE)

        /** Every key above, for "reset to defaults" and backup classification. */
        val ALL_KEYS: List<String> =
            FLOATS.map { it.key } + INTS.map { it.key } + BOOLS.map { it.key } + STRINGS.map { it.key }
    }

    companion object {
        /** The shipped defaults. */
        val DEFAULT = AutocorrectTuning()

        /** The live tuning on [config]; null (no config, or an Objenesis test Config) is [DEFAULT]. */
        fun of(config: Config?): AutocorrectTuning = config?.autocorrect_tuning ?: DEFAULT

        /**
         * Reads every key from [prefs], recovering from type-corrupted values the same way the
         * rest of Config does and clamping each value into its key's range (an out-of-range
         * backup or hand-edited pref can never produce a negative cost or a zero-width gate).
         */
        fun fromPrefs(prefs: SharedPreferences): AutocorrectTuning {
            fun f(k: FloatKey) = Config.safeGetFloat(prefs, k.key, k.default).let {
                if (it.isNaN()) k.default else it.coerceIn(k.range)
            }
            fun i(k: IntKey) = Config.safeGetInt(prefs, k.key, k.default).coerceIn(k.range)
            fun b(k: BoolKey) = Config.safeGetBoolean(prefs, k.key, k.default)
            fun s(k: StringKey) = Config.safeGetString(prefs, k.key, k.default).takeIf { it in k.values } ?: k.default
            return AutocorrectTuning(
                ranking = AutocorrectRanking.fromPref(s(Keys.RANKING)),
                bandGap = f(Keys.BAND_GAP),
                transpositionPenalty = f(Keys.TRANSPOSITION_PENALTY),
                minExactRatio = f(Keys.MIN_EXACT_RATIO),
                maxSubstitutions = i(Keys.MAX_SUBSTITUTIONS),
                lengthEditBudget = f(Keys.LENGTH_EDIT_BUDGET),
                droppedDoubleEnabled = b(Keys.DROPPED_DOUBLE_ENABLED),
                droppedDoubleMarginSteps = i(Keys.DROPPED_DOUBLE_MARGIN),
                elongationCollapseEnabled = b(Keys.ELONGATION_COLLAPSE_ENABLED),
                inflectionGuardEnabled = b(Keys.INFLECTION_GUARD_ENABLED),
                inflectionGuardMinStem = i(Keys.INFLECTION_GUARD_MIN_STEM),
                channel = ChannelWeights(
                    distanceCost = f(Keys.CHANNEL_DISTANCE_COST),
                    indelCost = f(Keys.CHANNEL_INDEL_COST),
                    doubleCost = f(Keys.CHANNEL_DOUBLE_COST),
                    transpositionCost = f(Keys.CHANNEL_TRANSPOSITION_COST),
                    frequencyWeight = f(Keys.CHANNEL_FREQUENCY_WEIGHT),
                    aliasBonus = f(Keys.CHANNEL_ALIAS_BONUS),
                    shortWordDeletionCost = f(Keys.CHANNEL_SHORT_DELETION_COST),
                    shortDeletionScope = ShortDeletionScope.fromPref(s(Keys.CHANNEL_SHORT_DELETION_SCOPE)),
                    maxCorrectionCost = ChannelWeights.maxCorrectionCostFromPref(f(Keys.CHANNEL_MAX_CORRECTION_COST)),
                    inflectionCost = ChannelWeights.inflectionCostFromPref(f(Keys.CHANNEL_INFLECTION_COST)),
                ),
            )
        }
    }
}
