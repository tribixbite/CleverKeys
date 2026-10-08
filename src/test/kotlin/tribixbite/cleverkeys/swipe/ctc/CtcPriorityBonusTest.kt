package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The user swipe-priority seam in [CtcBeamDecoder] (2026-10-08,
 * `docs/eval/2026-10-08-user-swipe-priority.md`): final score only, exact, bounded, and unable
 * to pull in a word the beam did not keep. Synthetic emissions — the real-model numbers are in
 * `UserSwipePriorityReplayTest`.
 */
class CtcPriorityBonusTest {

    private val az = "abcdefghijklmnopqrstuvwxyz".toCharArray()

    /** Emissions that support BOTH `got` and `git` (i/o split), `got` ahead. */
    private fun gitGotEmissions(): CtcEmissions {
        val blank = az.size
        fun frame(vararg peaks: Pair<Int, Float>): FloatArray =
            FloatArray(az.size + 1) { c -> peaks.firstOrNull { it.first == c }?.second ?: -9f }
        val g = 'g' - 'a'; val i = 'i' - 'a'; val o = 'o' - 'a'; val t = 't' - 'a'
        val rows = listOf(
            frame(g to -0.05f), frame(blank to -0.1f),
            frame(o to -0.6f, i to -0.8f), frame(o to -0.6f, i to -0.8f),
            frame(blank to -0.1f), frame(t to -0.05f), frame(blank to -0.1f),
        )
        val values = FloatArray(rows.size * (az.size + 1))
        rows.forEachIndexed { k, r -> System.arraycopy(r, 0, values, k * r.size, r.size) }
        return CtcEmissions(values, rows.size, az.size + 1)
    }

    private fun trie(): CtcLexiconTrie = CtcLexiconTrie(az).also {
        it.insert("got", 221.0); it.insert("git", 173.0); it.insert("gut", 160.0)
        it.insert("zap", 255.0) // nowhere near the emissions
    }

    private val params = CtcScoringParams.tunedV2(beamWidth = 50, topK = 10)

    @Test
    fun noPriorityIsByteIdenticalToTheDefaultDecode() {
        val a = CtcBeamDecoder.decode(gitGotEmissions(), trie(), params)
        val b = CtcBeamDecoder.decode(gitGotEmissions(), trie(), params, priority = CtcPriorityBonus.NONE)
        val c = CtcBeamDecoder.decode(gitGotEmissions(), trie(), params, priority = CtcPriorityBonus(emptyMap()))
        assertThat(b).isEqualTo(a)
        assertThat(c).isEqualTo(a)
        assertThat(a.all { it.priorityBonus == 0.0 }).isTrue()
    }

    @Test
    fun theBonusIsAddedExactlyToTheRaisedWordAndCanReorder() {
        val base = CtcBeamDecoder.decode(gitGotEmissions(), trie(), params)
        assertWithMessage("fixture: got leads, was ${base.map { it.word }}").that(base.first().word).isEqualTo("got")
        val gap = base.first().finalScore - base.first { it.word == "git" }.finalScore
        assertWithMessage("fixture gap must be inside the bonus bound").that(gap).isLessThan(CtcPriorityBonus.MAX_BONUS)

        val bonus = gap + 0.1
        val raised = CtcBeamDecoder.decode(
            gitGotEmissions(), trie(), params, priority = CtcPriorityBonus(mapOf("git" to bonus)))
        val git = raised.first { it.word == "git" }
        assertThat(git.priorityBonus).isWithin(1e-12).of(bonus)
        assertThat(git.finalScore).isWithin(1e-9).of(base.first { it.word == "git" }.finalScore + bonus)
        assertThat(raised.first().word).isEqualTo("git")
        assertWithMessage("other words keep their exact candidates")
            .that(raised.first { it.word == "got" }).isEqualTo(base.first { it.word == "got" })
    }

    @Test
    fun aWordOutsideTheFinalBeamIsNeverPulledIn() {
        // A beam of 3 cannot keep `zap`'s prefix through frames that never emit z/a/p.
        val narrow = CtcScoringParams.tunedV2(beamWidth = 3, topK = 10)
        val base = CtcBeamDecoder.decode(gitGotEmissions(), trie(), narrow)
        assertWithMessage("fixture: zap pruned").that(base.map { it.word }).doesNotContain("zap")
        val raised = CtcBeamDecoder.decode(
            gitGotEmissions(), trie(), narrow, priority = CtcPriorityBonus(mapOf("zap" to 1_000.0)))
        assertThat(raised).isEqualTo(base)
    }

    @Test
    fun bonusesAreClampedToTheBound() {
        val p = CtcPriorityBonus(mapOf("a" to 1_000.0, "b" to -5.0, "c" to Double.NaN, "d" to 1.5))
        assertThat(p.bonusFor("a")).isEqualTo(CtcPriorityBonus.MAX_BONUS)
        assertThat(p.bonusFor("b")).isEqualTo(0.0)
        assertThat(p.bonusFor("c")).isEqualTo(0.0)
        assertThat(p.bonusFor("d")).isEqualTo(1.5)
        assertThat(p.bonusFor("missing")).isEqualTo(0.0)
        assertWithMessage("non-positive entries are dropped").that(p.size).isEqualTo(2)
        assertThat(CtcPriorityBonus(mapOf("x" to 0.0)).isEmpty).isTrue()
    }
}
