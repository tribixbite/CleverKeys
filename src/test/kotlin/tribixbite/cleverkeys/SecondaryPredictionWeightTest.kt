package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Pins the published secondary-language weighting promise:
 *
 * | version | published note |
 * |---|---|
 * | v1.1.95 | "Configurable secondary language weight slider (0.5x-1.5x)" |
 * | v1.1.97 | "Secondary language mode with weighted predictions" |
 *
 * ## What the promise decomposes into
 *
 * A user turning that slider expects three things to line up, and every one of them is a
 * different file:
 *
 *  - the slider's **range** is 0.5x–1.5x — `MultiLanguageSection`;
 *  - the value it writes is the value the predictor **reads** — one preference key shared by
 *    `MultiLanguageSection` (write), `Config` (read), `SettingsDefaults` (backup/restore) and
 *    `SettingsPersistence` (settings-screen load). A typo in any one of them makes the slider
 *    a silent no-op, which is exactly the kind of break no compiler catches;
 *  - the value **weights** secondary-dictionary candidates — `WordPredictor` multiplies the
 *    unified score by it before the candidates are sorted.
 *
 * The default (0.9x) is below 1.0 on purpose: with no user input, a secondary-language word
 * has to beat a primary-language word by more than 11% to outrank it.
 *
 * The slider and the multiply site are pinned by reading the sources: both live in classes
 * that need Compose / `android.content.SharedPreferences` (`MultiLanguageSection`) or a
 * 2,600-line Android-bound predictor (`WordPredictor`), so neither can be driven in a pure
 * JVM test — but the exact expressions are still checkable, and a rewrite that drops the
 * multiplication or widens the range turns this red.
 */
class SecondaryPredictionWeightTest {

    private companion object {
        const val PREF_KEY = "pref_secondary_prediction_weight"

        /** The announced slider bounds. */
        const val MIN_WEIGHT = 0.5f
        const val MAX_WEIGHT = 1.5f

        val SECTION = File(
            "src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/MultiLanguageSection.kt"
        )
        val PREDICTOR = File("src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt")
        val PERSISTENCE = File(
            "src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsPersistence.kt"
        )
        val BACKUP_DEFAULTS = File(
            "src/main/kotlin/tribixbite/cleverkeys/backup/SettingsDefaults.kt"
        )
        val CONFIG = File("src/main/kotlin/tribixbite/cleverkeys/Config.kt")
    }

    @Test
    fun defaultWeight_isZeroPointNine_andSitsInsideTheAnnouncedSliderRange() {
        assertThat(Defaults.SECONDARY_PREDICTION_WEIGHT).isEqualTo(0.9f)
        assertThat(Defaults.SECONDARY_PREDICTION_WEIGHT).isAtLeast(MIN_WEIGHT)
        assertThat(Defaults.SECONDARY_PREDICTION_WEIGHT).isAtMost(MAX_WEIGHT)
        // Below 1.0 == secondary candidates are demoted by default, not promoted.
        assertThat(Defaults.SECONDARY_PREDICTION_WEIGHT).isLessThan(1.0f)
    }

    @Test
    fun defaultWeight_demotesAnEquallyScoredSecondaryCandidateBelowThePrimary() {
        // The consequence a user sees, expressed over the same arithmetic WordPredictor runs:
        // `score = (baseScore * secondaryWeight).toInt()`.
        val baseScore = 10_000
        val secondaryScore = (baseScore * Defaults.SECONDARY_PREDICTION_WEIGHT).toInt()
        assertThat(secondaryScore).isEqualTo(9_000)
        assertThat(secondaryScore).isLessThan(baseScore)

        // At the slider's top end the ordering inverts — that is what "1.5x" buys.
        val boosted = (baseScore * MAX_WEIGHT).toInt()
        assertThat(boosted).isEqualTo(15_000)
        assertThat(boosted).isGreaterThan(baseScore)
    }

    @Test
    fun slider_exposesTheAnnouncedZeroPointFiveToOnePointFiveRange() {
        val src = SECTION.readText()
        assertThat(src).contains("valueRange = 0.5f..1.5f")
        // 20 steps over a 1.0-wide range == 0.05x granularity, which is what the "%.2f"
        // read-out displays.
        assertThat(src).contains("steps = 20")
        assertThat(src).contains("saveSetting(\"$PREF_KEY\", secondaryPredictionWeight)")
    }

    @Test
    fun onePreferenceKey_isSharedByTheWriterTheReaderAndBackupRestore() {
        // Writer (settings slider), reader (Config.refresh), settings-screen loader, and the
        // backup/restore default table must all name the same key.
        assertThat(SECTION.readText()).contains(PREF_KEY)
        assertThat(CONFIG.readText()).contains(
            "safeGetFloat(_prefs, \"$PREF_KEY\", Defaults.SECONDARY_PREDICTION_WEIGHT)"
        )
        assertThat(PERSISTENCE.readText()).contains(PREF_KEY)
        assertThat(BACKUP_DEFAULTS.readText()).contains(
            "\"$PREF_KEY\" to PrefValue.FloatV(Defaults.SECONDARY_PREDICTION_WEIGHT)"
        )
    }

    @Test
    fun predictor_multipliesSecondaryDictionaryScoresByTheConfiguredWeight() {
        val src = PREDICTOR.readText()
        // The fallback keeps the announced behaviour when Config is not yet initialised.
        assertThat(src).contains(
            "config?.secondary_prediction_weight ?: Defaults.SECONDARY_PREDICTION_WEIGHT"
        )
        assertThat(src).contains("(baseScore * secondaryWeight).toInt()")
    }
}
