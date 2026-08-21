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
}
