package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.RescoringMetrics.Outcome

/**
 * [RescoringMetrics] — the measurement core of the step-5 replay harness.
 *
 * These matter more than they look. The harness they serve will produce the single number that
 * decides whether context rescoring is enabled by default, and the last time this project wrote
 * an A/B metric by hand it got it backwards: `scripts/ctc_injection_ab.py` classified by the
 * SHAPE of the change and reported a fix as a regression. The rule pinned here is that only the
 * target decides.
 */
class RescoringMetricsTest {

    // ── classification ───────────────────────────────────────────────────────────────

    @Test
    fun `a wrong top-1 made right is FIXED`() {
        assertThat(RescoringMetrics.classify("their", "there", "their")).isEqualTo(Outcome.FIXED)
    }

    @Test
    fun `a right top-1 made wrong is BROKEN`() {
        assertWithMessage(
            "this is the promotion error — a word the user swiped correctly, lost BECAUSE the " +
                "feature was on. It is the cost that gates shipping, so it must never be " +
                "folded in with ordinary engine misses."
        ).that(RescoringMetrics.classify("there", "there", "their")).isEqualTo(Outcome.BROKEN)
    }

    @Test
    fun `an unchanged top-1 is UNCHANGED even when it is wrong`() {
        assertThat(RescoringMetrics.classify("their", "there", "there")).isEqualTo(Outcome.UNCHANGED)
        assertThat(RescoringMetrics.classify("there", "there", "there")).isEqualTo(Outcome.UNCHANGED)
    }

    @Test
    fun `a change between two wrong answers is a WASH`() {
        assertThat(RescoringMetrics.classify("their", "there", "theyre")).isEqualTo(Outcome.WASH)
    }

    @Test
    fun `classification is by the TARGET, never by the shape of the change`() {
        // The `ctc_injection_ab.py` trap, restated as a test. A rule like "a real word became a
        // contraction, therefore a regression" would call this BROKEN. The user swiped the
        // contraction, so it is a FIX. Only the target decides.
        assertThat(RescoringMetrics.classify("l'aurait", "laurent", "l'aurait"))
            .isEqualTo(Outcome.FIXED)
        // ...and the same shape of change, when the target is the plain word, IS a break.
        assertThat(RescoringMetrics.classify("laurent", "laurent", "l'aurait"))
            .isEqualTo(Outcome.BROKEN)
    }

    @Test
    fun `case-only differences are not decode errors`() {
        // The slate carries display forms while corpus targets are typically lowercased.
        assertThat(RescoringMetrics.classify("i'm", "I'm", "I'm")).isEqualTo(Outcome.UNCHANGED)
        assertThat(RescoringMetrics.classify("café", "cafe", "Café")).isEqualTo(Outcome.FIXED)
    }

    @Test
    fun `a null engine top-1 is handled rather than crashing the replay`() {
        // The engine can return nothing for a trace; a harness that throws here loses the run.
        assertThat(RescoringMetrics.classify("word", null, "word")).isEqualTo(Outcome.FIXED)
        assertThat(RescoringMetrics.classify("word", null, null)).isEqualTo(Outcome.UNCHANGED)
        assertThat(RescoringMetrics.classify("word", "word", null)).isEqualTo(Outcome.BROKEN)
    }

    // ── the two ship-bar numbers ─────────────────────────────────────────────────────

    private fun tally(fixed: Int, broken: Int, unchanged: Int = 0, wash: Int = 0) =
        RescoringMetrics.Tally(fixed, broken, unchanged, wash)

    @Test
    fun `deltaTop1 is the net change as a fraction of all traces`() {
        assertThat(tally(fixed = 12, broken = 2, unchanged = 86).deltaTop1)
            .isWithin(1e-9).of(0.10)
        assertThat(tally(fixed = 2, broken = 12, unchanged = 86).deltaTop1)
            .isWithin(1e-9).of(-0.10)
    }

    @Test
    fun `promotionErrorRatio is breakages per FIX, not per trace`() {
        // Per-trace would make the number shrink simply by replaying more unchanged traces,
        // which is not a safety improvement — it is dilution.
        assertThat(tally(fixed = 10, broken = 2, unchanged = 10_000).promotionErrorRatio)
            .isWithin(1e-9).of(0.20)
        assertThat(tally(fixed = 10, broken = 2, unchanged = 0).promotionErrorRatio)
            .isWithin(1e-9).of(0.20)
    }

    @Test
    fun `breaking decodes while fixing none is infinitely bad, not zero`() {
        // A naive `broken / fixed` would divide by zero; returning 0.0 would read as PERFECT and
        // pass the ship bar on the worst possible result.
        val disaster = tally(fixed = 0, broken = 7)
        assertThat(disaster.promotionErrorRatio).isPositiveInfinity()
        assertWithMessage("the worst possible outcome must not clear the bar")
            .that(disaster.meetsShipBar()).isFalse()
    }

    @Test
    fun `no breakages is a ratio of zero`() {
        assertThat(tally(fixed = 5, broken = 0).promotionErrorRatio).isEqualTo(0.0)
    }

    @Test
    fun `the ship bar needs BOTH a net gain and few breakages`() {
        assertWithMessage("net gain, breakages under 20% of fixes")
            .that(tally(fixed = 10, broken = 1, unchanged = 89).meetsShipBar()).isTrue()

        assertWithMessage(
            "a positive delta is NOT sufficient: 10 fixed and 8 broken nets +2, but eight users " +
                "lost a word they had swiped correctly. That trade is what the second number exists " +
                "to refuse."
        ).that(tally(fixed = 10, broken = 8, unchanged = 82).meetsShipBar()).isFalse()

        assertWithMessage("a clean ratio with no net gain must also fail")
            .that(tally(fixed = 0, broken = 0, unchanged = 100).meetsShipBar()).isFalse()

        assertWithMessage("exactly at the bar is not under it")
            .that(tally(fixed = 10, broken = 2, unchanged = 88).meetsShipBar()).isFalse()
    }

    @Test
    fun `an empty replay reports zero rather than dividing by zero`() {
        val empty = RescoringMetrics.Tally()
        assertThat(empty.total).isEqualTo(0)
        assertThat(empty.deltaTop1).isEqualTo(0.0)
        assertThat(empty.meetsShipBar()).isFalse()
    }

    @Test
    fun `record accumulates each outcome into its own bucket`() {
        val t = RescoringMetrics.Tally()
        t.record(Outcome.FIXED); t.record(Outcome.FIXED)
        t.record(Outcome.BROKEN)
        t.record(Outcome.WASH)
        t.record(Outcome.UNCHANGED)
        assertThat(t.total).isEqualTo(5)
        assertThat(t.fixed).isEqualTo(2)
        assertThat(t.broken).isEqualTo(1)
        assertThat(t.toString()).contains("n=5")
    }

    // ── top-K membership (alternates-only mode, 2026-09-26) ──────────────────────────

    private val engine = listOf("was", "war", "wat", "wet", "wit", "way")

    @Test
    fun `a target moved from slot 4 into slot 2 ENTERED the top 3`() {
        val rescored = listOf("was", "wet", "war", "wat", "wit", "way")
        assertThat(RescoringMetrics.classifyTopK("wet", engine, rescored, k = 3))
            .isEqualTo(RescoringMetrics.TopKOutcome.ENTERED)
    }

    @Test
    fun `a target pushed from slot 3 to slot 4 LEFT the top 3`() {
        // The alternates-mode cost: a user who would have tapped slot 3 no longer sees it.
        val rescored = listOf("was", "war", "wit", "wat", "wet", "way")
        assertThat(RescoringMetrics.classifyTopK("wat", engine, rescored, k = 3))
            .isEqualTo(RescoringMetrics.TopKOutcome.LEFT)
    }

    @Test
    fun `movement inside the top K or outside it is not a membership change`() {
        val rescored = listOf("was", "wat", "war", "way", "wit", "wet")
        assertThat(RescoringMetrics.classifyTopK("war", engine, rescored, k = 3))
            .isEqualTo(RescoringMetrics.TopKOutcome.STAYED_IN)
        assertThat(RescoringMetrics.classifyTopK("wit", engine, rescored, k = 3))
            .isEqualTo(RescoringMetrics.TopKOutcome.STAYED_OUT)
        assertWithMessage("a target absent from the slate can never enter it by reordering")
            .that(RescoringMetrics.classifyTopK("wot", engine, rescored, k = 3))
            .isEqualTo(RescoringMetrics.TopKOutcome.STAYED_OUT)
    }

    @Test
    fun `top-K membership is case-insensitive, like classify`() {
        val rescored = listOf("Was", "Wet", "war", "wat", "wit", "way")
        assertThat(RescoringMetrics.classifyTopK("WET", engine, rescored, k = 3))
            .isEqualTo(RescoringMetrics.TopKOutcome.ENTERED)
    }

    @Test
    fun `k larger than the slate treats every slate word as in`() {
        assertThat(RescoringMetrics.classifyTopK("way", engine, engine.reversed(), k = 20))
            .isEqualTo(RescoringMetrics.TopKOutcome.STAYED_IN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `k below one is a caller bug, not an empty window`() {
        RescoringMetrics.classifyTopK("was", engine, engine, k = 0)
    }

    @Test
    fun `TopKTally delta is net entries over all cases`() {
        val t = RescoringMetrics.TopKTally()
        repeat(3) { t.record(RescoringMetrics.TopKOutcome.ENTERED) }
        t.record(RescoringMetrics.TopKOutcome.LEFT)
        repeat(96) { t.record(RescoringMetrics.TopKOutcome.STAYED_IN) }
        assertThat(t.total).isEqualTo(100)
        assertThat(t.deltaInTopK).isWithin(1e-9).of(0.02)
        assertWithMessage("baseline membership counts the engine order: in-before = stayed + left")
            .that(t.baselineInTopK).isEqualTo(97)
        assertThat(t.rescoredInTopK).isEqualTo(99)
        assertThat(RescoringMetrics.TopKTally().deltaInTopK).isEqualTo(0.0)
    }

    @Test
    fun `the alternates bar needs a one-point gain AND a rank 1 that never moved`() {
        fun topK(entered: Int, left: Int, total: Int) = RescoringMetrics.TopKTally(
            entered = entered, left = left, stayedIn = 0, stayedOut = total - entered - left,
        )
        val still = tally(fixed = 0, broken = 0, unchanged = 100)
        assertWithMessage("exactly +1 point with rank 1 untouched clears the bar")
            .that(RescoringMetrics.meetsAlternatesBar(topK(1, 0, 100), still)).isTrue()
        assertWithMessage("+0.5 point is under the bar")
            .that(RescoringMetrics.meetsAlternatesBar(topK(1, 0, 200), tally(0, 0, unchanged = 200)))
            .isFalse()
        assertWithMessage(
            "ANY rank-1 change fails the bar, even a fix and even a wash — alternates mode's whole " +
                "claim is zero auto-commit exposure by construction"
        ).that(RescoringMetrics.meetsAlternatesBar(topK(50, 0, 100), tally(1, 0, unchanged = 99)))
            .isFalse()
        assertThat(
            RescoringMetrics.meetsAlternatesBar(topK(50, 0, 100), tally(0, 0, unchanged = 99, wash = 1))
        ).isFalse()
    }

    // ── the oracle context model ─────────────────────────────────────────────────────

    @Test
    fun `oracle evidence boosts the target alone, at the ceiling, past the strict floors`() {
        val ev = RescoringMetrics.oracleEvidence(listOf("was", "War", "wat"), target = "war")
        assertThat(ev[0]).isEqualTo(SwipeContextRescorer.Evidence.NONE)
        assertThat(ev[1].boost).isEqualTo(SwipeContextRescorer.MAX_BOOST)
        assertThat(SwipeContextRescorer.promotableToRankOne(ev[1])).isTrue()
        assertThat(ev[2]).isEqualTo(SwipeContextRescorer.Evidence.NONE)
    }

    @Test
    fun `under the shipped guards the oracle promotes exactly the targets within R_MIN`() {
        // At WEIGHT=0.5 the ceiling is 0.5*ln(5)=0.80 nats > ln(2)=0.69 nats, so R_MIN — not the
        // weight — is what binds a perfect context model. That is what makes this an UPPER bound.
        val words = listOf("was", "war", "wat")
        val within = SwipeContextRescorer.rescoreOrder(
            listOf(600, 300, 100), RescoringMetrics.oracleEvidence(words, "war"),
        )
        assertThat(words[within.first()]).isEqualTo("war")
        val below = SwipeContextRescorer.rescoreOrder(
            listOf(600, 299, 101), RescoringMetrics.oracleEvidence(words, "war"),
        )
        assertThat(words[below.first()]).isEqualTo("was")
    }

    @Test
    fun `rMin of infinity pins rank 1 however strong the evidence — alternates-only mode`() {
        val words = listOf("was", "war", "wat", "wet")
        val scores = listOf(400, 380, 360, 350)
        val ev = RescoringMetrics.oracleEvidence(words, "wet")
        assertWithMessage("precondition: under the shipped R_MIN this evidence DOES take rank 1")
            .that(words[SwipeContextRescorer.rescoreOrder(scores, ev).first()]).isEqualTo("wet")
        val order = SwipeContextRescorer.rescoreOrder(scores, ev, rMin = Double.POSITIVE_INFINITY)
        assertThat(order.first()).isEqualTo(0)
        assertWithMessage("rank 1 is pinned, but the alternates beneath it are still rescored")
            .that(words[order[1]]).isEqualTo("wet")
    }
}
