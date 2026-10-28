package tribixbite.cleverkeys.ui.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.autocorrect.AutocorrectTuning
import tribixbite.cleverkeys.autocorrect.AutocorrectTuning.Keys
import tribixbite.cleverkeys.autocorrect.ChannelWeights
import tribixbite.cleverkeys.backup.PrefValue
import tribixbite.cleverkeys.backup.SETTINGS_DEFAULTS
import tribixbite.cleverkeys.backup.SettingsValidation
import java.io.File
import kotlin.math.abs

/**
 * The Auto-Correction "Ranking & Tuning" settings (2026-10-18): slider geometry, the λ display
 * rule, the γ "always keep" position, the group reset's key set, and that backup classification
 * and validation follow [AutocorrectTuning.Keys].
 */
class AutocorrectTuningSlidersTest {

    private val sliders = AutocorrectTuningSliders

    // ── Slider geometry ──

    @Test
    fun everyTuningKeyHasExactlyOneSlider_orIsASwitchOrDropdown() {
        assertThat(sliders.FLOATS.map { it.key.key }).containsExactlyElementsIn(Keys.FLOATS.map { it.key })
        assertThat(sliders.INTS.map { it.key.key }).containsExactlyElementsIn(Keys.INTS.map { it.key })
    }

    @Test
    fun defaultsSitExactlyOnASliderPosition() {
        for (spec in sliders.FLOATS) {
            assertWithMessage("${spec.key.key}: default ${spec.key.default} must be a slider position")
                .that(spec.snap(spec.key.default)).isEqualTo(spec.key.default)
        }
        for (spec in sliders.INTS) {
            assertWithMessage("${spec.key.key}: default ${spec.key.default} must be a slider position")
                .that(spec.snap(spec.key.default.toFloat())).isEqualTo(spec.key.default)
        }
    }

    @Test
    fun rangeEndsAreSliderPositions_andStepsCoverTheRange() {
        for (spec in sliders.FLOATS) {
            val r = spec.key.range
            assertThat(spec.snap(r.start)).isEqualTo(r.start)
            assertThat(spec.snap(r.endInclusive)).isEqualTo(r.endInclusive)
            // (steps + 1) intervals of one increment span the range exactly.
            val span = (spec.steps + 1) * spec.increment
            assertWithMessage("${spec.key.key}: ${spec.steps} steps of ${spec.increment}")
                .that(abs(span - (r.endInclusive - r.start))).isLessThan(1e-4f)
            assertThat(spec.steps).isAtLeast(1)
        }
        for (spec in sliders.INTS) {
            val r = spec.key.range
            assertThat((spec.steps + 1) * spec.increment).isEqualTo(r.last - r.first)
            assertThat(spec.valueRange.start).isEqualTo(r.first.toFloat())
            assertThat(spec.valueRange.endInclusive).isEqualTo(r.last.toFloat())
        }
    }

    @Test
    fun snapRoundsToTheGridAndClampsIntoTheRange() {
        // Float slider positions carry binary error; the stored value must be the decimal.
        assertThat(sliders.BAND_GAP.snap(0.099999994f)).isEqualTo(0.10f)
        assertThat(sliders.CHANNEL_FREQUENCY_WEIGHT.snap(0.0076f)).isEqualTo(0.0075f)
        assertThat(sliders.CHANNEL_INDEL_COST.snap(0.1f)).isEqualTo(Keys.CHANNEL_INDEL_COST.range.start)
        assertThat(sliders.CHANNEL_INFLECTION_COST.snap(12f)).isEqualTo(ChannelWeights.INFLECTION_ALWAYS_KEEP)
        assertThat(sliders.DROPPED_DOUBLE_MARGIN.snap(65.9f)).isEqualTo(64)
        assertThat(sliders.DROPPED_DOUBLE_MARGIN.snap(200f)).isEqualTo(128)
        assertThat(sliders.MAX_SUBSTITUTIONS.snap(0f)).isEqualTo(1)
    }

    /** The eval doc's tuning grid (§1.4) must be reachable on the sliders. */
    @Test
    fun theEvalTuningGridIsReachable() {
        fun reachable(spec: AutocorrectTuningSliders.FloatSpec, values: List<Float>) {
            for (v in values) {
                assertWithMessage("${spec.key.key}: grid value $v").that(spec.snap(v)).isEqualTo(v)
            }
        }
        reachable(sliders.CHANNEL_DISTANCE_COST, listOf(0f, 0.5f, 1f, 2f, 4f))
        reachable(sliders.CHANNEL_INDEL_COST, listOf(0.8f, 1.0f, 1.25f, 1.5f))
        reachable(sliders.CHANNEL_DOUBLE_COST, listOf(0.3f, 0.6f, 1.0f))
        reachable(sliders.CHANNEL_TRANSPOSITION_COST, listOf(0.6f, 0.9f, 1.2f))
        reachable(sliders.CHANNEL_FREQUENCY_WEIGHT,
            listOf(0.005f, 0.0075f, 0.01f, 0.015f, 0.02f, 0.03f, 0.04f, 0.06f))
        // Round 2 (§3.2).
        reachable(sliders.CHANNEL_DISTANCE_COST, listOf(0.5f, 1f, 2f, 4f))
        reachable(sliders.CHANNEL_INDEL_COST, listOf(1.25f, 1.5f, 2.0f))
        reachable(sliders.CHANNEL_DOUBLE_COST, listOf(0.3f, 0.45f, 0.6f, 1.0f))
        reachable(sliders.CHANNEL_TRANSPOSITION_COST, listOf(0.9f, 1.2f, 1.5f))
        reachable(sliders.CHANNEL_FREQUENCY_WEIGHT, listOf(0.0025f, 0.005f, 0.0075f, 0.01f, 0.015f))
        reachable(sliders.CHANNEL_ALIAS_BONUS, listOf(0f, 0.25f, 0.5f))
        reachable(sliders.CHANNEL_INFLECTION_COST, listOf(0.5f, 1f, 1.5f, 2f, 3f))
        // Round 5 (§13.1).
        reachable(sliders.CHANNEL_MAX_CORRECTION_COST, listOf(1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 3.0f, 3.5f, 4.0f))
        assertThat(sliders.isMaxCorrectionOff(sliders.maxCorrectionCostSliderValue(Float.POSITIVE_INFINITY))).isTrue()
        assertThat(ChannelWeights.maxCorrectionCostFromPref(ChannelWeights.MAX_CORRECTION_OFF)).isEqualTo(Float.POSITIVE_INFINITY)
        // Round 6 (§16.1).
        reachable(sliders.CHANNEL_COMPLETION_COST, listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f))
        assertThat(sliders.isCompletionOff(sliders.completionCostSliderValue(Float.POSITIVE_INFINITY))).isTrue()
        assertThat(ChannelWeights.completionCostFromPref(ChannelWeights.COMPLETION_OFF)).isEqualTo(Float.POSITIVE_INFINITY)
        // Round 3 (§7.1).
        reachable(sliders.CHANNEL_SHORT_DELETION_COST, listOf(0f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f))
    }

    // ── Display rules ──

    @Test
    fun frequencyWeightReadsAsRankStepsPerWrongKey() {
        assertThat(sliders.rankStepsPerWrongKey(0.02f)).isEqualTo(50)
        assertThat(sliders.rankStepsPerWrongKey(0.0075f)).isEqualTo(133)
        assertThat(sliders.rankStepsPerWrongKey(0.1f)).isEqualTo(10)
        assertThat(sliders.rankStepsPerWrongKey(0.0025f)).isEqualTo(400)
        assertWithMessage("λ = 0 means frequency is ignored").that(sliders.rankStepsPerWrongKey(0f)).isNull()
        assertThat(sliders.rankStepsPerWrongKey(Float.NaN)).isNull()
    }

    @Test
    fun inflectionCostTopPositionIsAlwaysKeep() {
        // The runtime maps the stored top value to ∞; the slider maps ∞ back to the top.
        assertThat(ChannelWeights.inflectionCostFromPref(ChannelWeights.INFLECTION_ALWAYS_KEEP))
            .isEqualTo(Float.POSITIVE_INFINITY)
        assertThat(sliders.inflectionCostSliderValue(Float.POSITIVE_INFINITY))
            .isEqualTo(ChannelWeights.INFLECTION_ALWAYS_KEEP)
        // The shipped γ is the round-2 choice 1.0 (eval doc §4), an ordinary slider position.
        assertThat(sliders.inflectionCostSliderValue(AutocorrectTuning.DEFAULT.channel.inflectionCost)).isEqualTo(1f)
        assertThat(sliders.isAlwaysKeep(sliders.inflectionCostSliderValue(AutocorrectTuning.DEFAULT.channel.inflectionCost)))
            .isFalse()
        assertThat(sliders.inflectionCostSliderValue(2.5f)).isEqualTo(2.5f)
        assertThat(sliders.isAlwaysKeep(9.75f)).isFalse()
        assertThat(Keys.CHANNEL_INFLECTION_COST.range.endInclusive).isEqualTo(ChannelWeights.INFLECTION_ALWAYS_KEEP)
    }

    // ── Reset ──

    @Test
    fun groupResetRemovesExactlyTheTuningKeys() {
        assertThat(sliders.RESET_KEYS).containsExactlyElementsIn(Keys.ALL_KEYS)
        assertThat(sliders.RESET_KEYS).containsNoDuplicates()
        assertThat(sliders.RESET_KEYS).hasSize(22)
        // None of the basic settings above the group.
        assertThat(sliders.RESET_KEYS).containsNoneOf(
            "autocorrect_enabled", "autocorrect_min_word_length", "autocorrect_char_match_threshold",
            "autocorrect_confidence_min_frequency", "autocorrect_max_length_diff", "autocorrect_prefix_length",
        )
    }

    @Test
    fun fullSettingsResetAlsoCoversTheTuningKeys() {
        val existing = Keys.ALL_KEYS.toSet()
        assertThat(SettingsResetPolicy.keysToRemove(existing)).containsExactlyElementsIn(existing)
    }

    /** The section's reset must remove [AutocorrectTuningSliders.RESET_KEYS], not a hand list. */
    @Test
    fun sectionResetUsesTheSharedKeySet() {
        val section = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/AutoCorrectionSection.kt").readText()
        assertThat(section).contains("for (key in AutocorrectTuningSliders.RESET_KEYS) editor.remove(key)")
        assertThat(section).contains("autocorrectTuning = AutocorrectTuning.DEFAULT")
    }

    // ── Backup classification and validation follow Keys ──

    @Test
    fun settingsDefaultsCarryEveryTuningKeyWithItsType() {
        for (k in Keys.FLOATS) assertThat(SETTINGS_DEFAULTS[k.key]).isEqualTo(PrefValue.FloatV(k.default))
        for (k in Keys.INTS) assertThat(SETTINGS_DEFAULTS[k.key]).isEqualTo(PrefValue.IntV(k.default))
        for (k in Keys.BOOLS) assertThat(SETTINGS_DEFAULTS[k.key]).isEqualTo(PrefValue.Bool(k.default))
        for (k in Keys.STRINGS) assertThat(SETTINGS_DEFAULTS[k.key]).isEqualTo(PrefValue.Str(k.default))
    }

    @Test
    fun validationAcceptsTheRangeAndRejectsOutside() {
        for (k in Keys.FLOATS) {
            assertThat(SettingsValidation.isFloatPreference(k.key)).isTrue()
            for (v in listOf(k.range.start, k.default, k.range.endInclusive)) {
                assertWithMessage("${k.key}=$v").that(SettingsValidation.validate(k.key, PrefValue.FloatV(v))).isNull()
            }
            assertThat(SettingsValidation.validate(k.key, PrefValue.FloatV(k.range.endInclusive + 0.01f))).isNotNull()
            assertThat(SettingsValidation.validate(k.key, PrefValue.FloatV(k.range.start - 0.01f))).isNotNull()
            assertThat(SettingsValidation.validate(k.key, PrefValue.FloatV(Float.NaN))).isNotNull()
            assertThat(SettingsValidation.validate(k.key, PrefValue.Str("0.1"))).isNotNull()
        }
        for (k in Keys.INTS) {
            assertThat(SettingsValidation.intRangeFor(k.key)).isEqualTo(k.range)
            assertThat(SettingsValidation.validate(k.key, PrefValue.IntV(k.default))).isNull()
            assertThat(SettingsValidation.validate(k.key, PrefValue.IntV(k.range.last + 1))).isNotNull()
            assertThat(SettingsValidation.validate(k.key, PrefValue.IntV(k.range.first - 1))).isNotNull()
            assertThat(SettingsValidation.validate(k.key, PrefValue.Bool(true))).isNotNull()
        }
        for (k in Keys.BOOLS) {
            assertThat(SettingsValidation.validate(k.key, PrefValue.Bool(!k.default))).isNull()
        }
        for (k in Keys.STRINGS) {
            for (v in k.values) assertThat(SettingsValidation.validate(k.key, PrefValue.Str(v))).isNull()
            assertThat(SettingsValidation.validate(k.key, PrefValue.Str("bogus"))).isNotNull()
            assertThat(SettingsValidation.validate(k.key, PrefValue.Bool(true))).isNotNull()
        }
    }
}
