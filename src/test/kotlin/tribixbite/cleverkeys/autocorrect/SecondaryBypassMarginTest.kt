package tribixbite.cleverkeys.autocorrect

import com.google.common.truth.Truth.assertWithMessage
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.ShippedTapDictionary
import tribixbite.cleverkeys.WordPredictor

/**
 * Autocorrect step 1's secondary-word bypass (`aa9b57ba`) and the margin rule measured by
 * `docs/eval/2026-11-26-secondary-bypass-gate.md`, over the shipped en / de / es dictionaries.
 *
 * The rule FAILED its pre-registered gate (see the doc's Result): at the DEV-chosen margin it
 * fixed under 10% of the eligible typos, `wich` is corrected to `with` at every margin, and the
 * German real word `fuss` (de+en) needs a margin above 48. So production keeps the bypass
 * ([WordPredictor.SECONDARY_BYPASS_MARGIN_STEPS] = [Int.MAX_VALUE]); these tests pin that, and that
 * the margin seam the evaluation uses still works for a re-run.
 */
class SecondaryBypassMarginTest {

    companion object {
        private lateinit var enDe: WordPredictor
        private lateinit var enEs: WordPredictor

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            SecondaryBypassEval.setUpClass()
            val loaded = ShippedTapDictionary.withContractionAliases()
            enDe = SecondaryBypassEval.predictor("en", loaded.dictionary, loaded.prefixIndex, loaded.aliases, "de")
            val again = ShippedTapDictionary.withContractionAliases()
            enEs = SecondaryBypassEval.predictor("en", again.dictionary, again.prefixIndex, again.aliases, "es")
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() = SecondaryBypassEval.tearDownClass()
    }

    private val tuning = AutocorrectTuning.DEFAULT

    @Test
    fun productionKeepsEverySecondaryWordAsTyped() {
        assertWithMessage("gate failed: the bypass stays unconditional")
            .that(WordPredictor.SECONDARY_BYPASS_MARGIN_STEPS).isEqualTo(Int.MAX_VALUE)
        for (w in listOf("pero", "mucho", "todo", "porque")) {
            assertWithMessage("en+es $w").that(enEs.autoCorrect(w, tuning)).isEqualTo(w)
        }
        for (w in listOf("nicht", "auch", "doch", "schon")) {
            assertWithMessage("en+de $w").that(enDe.autoCorrect(w, tuning)).isEqualTo(w)
        }
        // The known cost (2.1 open item): a German-list typo is a "word" too.
        assertWithMessage("en+de thier, kept by the bypass").that(enDe.autoCorrect("thier", tuning)).isEqualTo("thier")
    }

    @Test
    fun theMarginSeamDecidesByTheFrequencyGap() {
        // thier: their is 120 byte steps more common than the German list entry (eval doc).
        assertWithMessage("never bypass").that(enDe.autoCorrect("thier", tuning, Int.MIN_VALUE)).isEqualTo("their")
        assertWithMessage("margin below the gap").that(enDe.autoCorrect("thier", tuning, 112)).isEqualTo("their")
        assertWithMessage("margin at the gap").that(enDe.autoCorrect("thier", tuning, 120)).isEqualTo("thier")
        assertWithMessage("secondaryWordYields at 0").that(enDe.secondaryWordYields("thier", "their", 0)).isTrue()
        // pero → per is 40 steps LESS common than pero: kept at any margin ≥ −40.
        assertWithMessage("pero at margin 0").that(enEs.autoCorrect("pero", tuning, 0)).isEqualTo("pero")
        assertWithMessage("pero never bypassed").that(enEs.autoCorrect("pero", tuning, Int.MIN_VALUE)).isEqualTo("per")
    }
}
