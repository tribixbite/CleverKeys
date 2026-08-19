package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Pins the Greek final-sigma repair — one of the two app-side prerequisites
 * `CleverKeys-ML/ctc/PHASE_O.md` §3.3 names for multi-script CTC.
 *
 * The failure this guards is silent: `σ` and `ς` are distinct keys in different rows on
 * `grek_qwerty`, so an unrepaired word-final sigma makes the trie path disagree with the gesture
 * the user actually draws. There is no exception and no empty bar — just a word that can never
 * be decoded, for 25.7 % of the pack.
 */
class CtcGreekOrthographyTest {

    @Test
    fun `word-final sigma becomes final sigma`() {
        // Inputs are spelled with the DEFECT — a word-final σ — because that is what the pack
        // actually contains; expectations use the correct ς. Nominative singular endings are
        // overwhelmingly -ος, which is why the defect reaches a quarter of the vocabulary.
        val s = CtcGreekOrthography.SIGMA
        val fs = CtcGreekOrthography.FINAL_SIGMA

        assertThat(CtcGreekOrthography.repairFinalSigma("άνθρωπο$s")).isEqualTo("άνθρωπο$fs")
        assertThat(CtcGreekOrthography.repairFinalSigma("λόγο$s")).isEqualTo("λόγο$fs")
        // Degenerate but real: a bare sigma is a valid single-character token.
        assertThat(CtcGreekOrthography.repairFinalSigma("$s")).isEqualTo("$fs")
        assertThat(CtcGreekOrthography.repairFinalSigma("$fs")).isEqualTo("$fs")
    }

    @Test
    fun `medial sigma is never touched`() {
        // θάλασσα — a legitimate medial doubling. Rewriting either σ would break the word as
        // thoroughly as the defect being fixed, so this is the assertion that keeps the repair
        // from over-reaching.
        val s = CtcGreekOrthography.SIGMA
        val fs = CtcGreekOrthography.FINAL_SIGMA
        assertThat(CtcGreekOrthography.repairFinalSigma("θάλα${s}${s}α")).isEqualTo("θάλα${s}${s}α")
        // One word carrying BOTH: the medial σ must survive while the final one is rewritten.
        assertThat(CtcGreekOrthography.repairFinalSigma("κό${s}μο$s")).isEqualTo("κό${s}μο$fs")
    }

    @Test
    fun `repair is idempotent so it composes with a regenerated pack`() {
        val already = "λόγο" + CtcGreekOrthography.FINAL_SIGMA
        assertThat(CtcGreekOrthography.repairFinalSigma(already)).isEqualTo(already)
        val defective = "λόγο" + CtcGreekOrthography.SIGMA
        assertThat(CtcGreekOrthography.repairFinalSigma(CtcGreekOrthography.repairFinalSigma(defective)))
            .isEqualTo(already)
        // Empty and non-Greek input must pass through untouched — the repair runs over a whole
        // lexicon and must not corrupt anything it does not understand.
        assertThat(CtcGreekOrthography.repairFinalSigma("")).isEqualTo("")
        assertThat(CtcGreekOrthography.repairFinalSigma("hello")).isEqualTo("hello")
    }

    @Test
    fun `lexicon repair keeps the higher frequency on collision`() {
        // A pack containing BOTH spellings maps them onto one key. Letting insertion order
        // decide would silently discard the better-attested weight, which is the kind of thing
        // that only shows up as slightly-wrong ranking months later.
        val repaired = CtcGreekOrthography.repairLexicon(
            linkedMapOf(("λόγο" + CtcGreekOrthography.SIGMA) to 12.0, ("λόγο" + CtcGreekOrthography.FINAL_SIGMA) to 250.0)
        )
        assertThat(repaired).hasSize(1)
        assertThat(repaired["λόγο" + CtcGreekOrthography.FINAL_SIGMA]).isEqualTo(250.0)

        val reversed = CtcGreekOrthography.repairLexicon(
            linkedMapOf(("λόγο" + CtcGreekOrthography.FINAL_SIGMA) to 250.0, ("λόγο" + CtcGreekOrthography.SIGMA) to 12.0)
        )
        assertWithMessage("collision resolution must not depend on map order")
            .that(reversed).isEqualTo(repaired)
    }

    @Test
    fun `affectedCount measures what the repair would rewrite`() {
        val s = CtcGreekOrthography.SIGMA
        val words = listOf("λόγο$s", "θάλα${s}${s}α", "κό${s}μο$s", "λόγο" + CtcGreekOrthography.FINAL_SIGMA, "")
        assertThat(CtcGreekOrthography.affectedCount(words)).isEqualTo(2)
    }
}
