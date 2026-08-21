package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.NextWordPredictor
import kotlin.math.ln

/**
 * [SwipeContextRescorer] — the log-linear math, the identity property, and the rank-1 guard.
 *
 * Step 1 of `docs/specs/ctc-context-rescoring-and-tunables.md`. The feature it belongs to is
 * default-OFF and not yet wired to anything, so these tests are the ONLY thing standing behind
 * the math until the offline replay harness (step 5) produces accuracy evidence.
 *
 * The properties below are not arbitrary: each corresponds to a named risk in §6 of the spec, and
 * the identity property is the structural answer to "a default that changes ranking for a user
 * who has learned nothing is a bug".
 */
class SwipeContextRescorerTest {

    /**
     * Evidence carrying [boost] with counts that comfortably CLEAR the strict rank-1 floors, so
     * a test using this helper isolates the score-ratio guard. Tests of the floors themselves
     * build [SwipeContextRescorer.Evidence] explicitly.
     */
    private fun boosts(vararg v: Double) =
        v.map { SwipeContextRescorer.Evidence(it, frequency = 10, probability = 0.5f) }

    private fun ev(boost: Double, frequency: Int, probability: Float) =
        SwipeContextRescorer.Evidence(boost, frequency, probability)

    private fun scores(vararg v: Int) = v.toList()

    // ── identity ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a slate with no learned data comes back in exactly its input order`() {
        val s = scores(900, 400, 120, 30)
        val order = SwipeContextRescorer.rescoreOrder(s, boosts(1.0, 1.0, 1.0, 1.0))
        assertWithMessage(
            "a user who has learned nothing must get the engine's own ranking. This is the " +
                "whole basis for the feature ever being safe to default on."
        ).that(order).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun `identity holds even for a slate that arrives out of rank order`() {
        // The contract says the slate is engine-ordered, but the identity property must not
        // DEPEND on that — otherwise a caller bug turns "no learned data" into silent reordering.
        val order = SwipeContextRescorer.rescoreOrder(scores(100, 900, 50), boosts(1.0, 1.0, 1.0))
        assertThat(order).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `equal scores keep input order via the stable tiebreak`() {
        val order = SwipeContextRescorer.rescoreOrder(scores(500, 500, 500), boosts(1.0, 1.0, 2.0))
        assertWithMessage("index 2 is boosted so it leads; 0 and 1 keep their relative order")
            .that(order).containsExactly(2, 0, 1).inOrder()
    }

    @Test
    fun `empty and single-candidate slates are returned unchanged`() {
        assertThat(SwipeContextRescorer.rescoreOrder(emptyList(), emptyList())).isEmpty()
        assertThat(SwipeContextRescorer.rescoreOrder(scores(700), boosts(5.0))).containsExactly(0)
    }

    @Test
    fun `mismatched input lengths fail loudly rather than silently misaligning`() {
        // Parallel lists that drift are how a rescorer starts attaching scores to the wrong words.
        try {
            SwipeContextRescorer.rescoreOrder(scores(900, 400), boosts(1.0))
            throw AssertionError("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("parallel")
        }
    }

    // ── the context term actually does something ─────────────────────────────────────

    @Test
    fun `a boost promotes a near-tie`() {
        // 500 vs 460 is 0.083 nats apart; a 2.0 boost contributes 0.5*ln(2) = 0.347 nats.
        val order = SwipeContextRescorer.rescoreOrder(scores(500, 460), boosts(1.0, 2.0))
        assertWithMessage("breaking near-ties is the entire point of the feature")
            .that(order).containsExactly(1, 0).inOrder()
    }

    @Test
    fun `a boost does NOT overturn a clear engine preference`() {
        // 900 vs 500 is 0.588 nats; the maximum possible context term is 0.5*ln(5) = 0.805 nats,
        // so this one IS overturnable at max boost — but not at a modest one.
        val order = SwipeContextRescorer.rescoreOrder(scores(900, 500), boosts(1.0, 1.5))
        assertWithMessage("0.5*ln(1.5) = 0.203 nats cannot close a 0.588 nat gap")
            .that(order).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `the boost ceiling is bounded at roughly 0_8 nats`() {
        // Pins the safety envelope itself, so raising WEIGHT or MAX_BOOST cannot silently widen
        // what context is able to overturn.
        val ceiling = SwipeContextRescorer.WEIGHT * ln(SwipeContextRescorer.MAX_BOOST)
        assertThat(ceiling).isWithin(0.01).of(0.805)
        assertWithMessage(
            "context must never be able to close more than a ~2.24x score ratio; if this " +
                "fails, re-derive the §6 damage bounds before shipping"
        ).that(Math.exp(ceiling)).isLessThan(2.3)
    }

    // ── rank-1 displacement guard (§6.1) ─────────────────────────────────────────────

    @Test
    fun `a peaked slate is un-overturnable even at maximum boost`() {
        // The spec's worked example: top-1 at 900, runner-up at 40. 40 < 0.5*900, so the guard
        // refuses the promotion regardless of what the arithmetic says.
        val order = SwipeContextRescorer.rescoreOrder(scores(900, 40), boosts(1.0, 5.0))
        assertWithMessage(
            "a confidently decoded swipe must be arithmetically un-overturnable — this is the " +
                "auto-commit protection, because rank 1 inserts without the user choosing it"
        ).that(order.first()).isEqualTo(0)
    }

    @Test
    fun `promotion is allowed exactly at the R_MIN boundary`() {
        // 450 == 0.5 * 900, so the engine itself put it within a factor of two: promotable.
        val order = SwipeContextRescorer.rescoreOrder(scores(900, 450), boosts(1.0, 5.0))
        assertWithMessage("the guard is `>=`, so the boundary case must promote")
            .that(order).containsExactly(1, 0).inOrder()
    }

    @Test
    fun `promotion is refused just below the R_MIN boundary`() {
        val order = SwipeContextRescorer.rescoreOrder(scores(900, 449), boosts(1.0, 5.0))
        assertThat(order.first()).isEqualTo(0)
    }

    @Test
    fun `a blocked promotion still reorders the alternates below rank 1`() {
        // §6.3: ranks 2..K are alternates the user taps, so reordering them is cheap and must
        // NOT be discarded just because the rank-1 promotion was refused. Getting this wrong
        // would throw away most of the feature's value to enforce a guard that only concerns
        // rank 1.
        val order = SwipeContextRescorer.rescoreOrder(
            scores(900, 100, 90), boosts(1.0, 1.0, 5.0)
        )
        assertWithMessage("engine top-1 is restored to rank 1")
            .that(order.first()).isEqualTo(0)
        assertWithMessage("but the boosted candidate still outranks the one it beat")
            .that(order).containsExactly(0, 2, 1).inOrder()
    }

    @Test
    fun `a zero-scored top-1 makes the guard unevaluable, so promotion is refused`() {
        // Fail-safe direction, and the direction matters: with `topScore == 0` the ratio test is
        // vacuous (every score is >= 0), so "cannot evaluate the protection" must resolve to
        // "do not promote" — rank 1 auto-inserts. Defensive rather than reachable: the scores are
        // a softmax scaled by 1000, so the max is at least 1000/K and never rounds to zero.
        val order = SwipeContextRescorer.rescoreOrder(scores(0, 0), boosts(1.0, 3.0))
        assertThat(order).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `out-of-range boosts are clamped instead of trusted`() {
        // A provider bug must not be able to demote a candidate (boost < 1) or exceed the
        // ceiling the §6 bounds are derived from.
        // Chosen so clamping DECIDES the outcome: unclamped, 0.5*ln(0.01) = -2.30 nats would
        // sink index 0 below index 1 (3.91 vs 5.75) and flip the order. Clamped, index 0 keeps
        // ln(500) = 6.22 and leads. Scores are 500/300 so the ratio guard does not mask the test.
        val demoted = SwipeContextRescorer.rescoreOrder(scores(500, 300), boosts(0.01, 1.1))
        assertWithMessage("a sub-1.0 boost is clamped to neutral, so it cannot DEMOTE a candidate")
            .that(demoted).containsExactly(0, 1).inOrder()

        val huge = SwipeContextRescorer.rescoreOrder(scores(900, 449), boosts(1.0, 1e9))
        assertWithMessage("an absurd boost is clamped, so the rank-1 guard still refuses")
            .that(huge.first()).isEqualTo(0)
    }

    // ── rank-1 strict evidence floors (§4) ───────────────────────────────────────────

    /**
     * The SECOND rank-1 protection, independent of the score ratio.
     *
     * §4: general in-slate reordering rides on the stores' own floors, but a promotion INTO
     * rank 1 must additionally clear the stricter `NextWordPredictor` floors. The reason is
     * the asymmetry between offering and writing: a next-word suggestion at this confidence
     * still needs a user TAP, whereas a rank-1 promotion is written with nothing tapped.
     */
    @Test
    fun `a promotion needs the strict floors even when the score ratio allows it`() {
        // 450 == 0.5 * 900, so the ratio guard permits this — the floors must be what stops it.
        val thin = ev(boost = 5.0, frequency = 1, probability = 0.5f) // freq below MIN(2)
        val order = SwipeContextRescorer.rescoreOrder(
            scores(900, 450), listOf(SwipeContextRescorer.Evidence.NONE, thin)
        )
        assertWithMessage(
            "seen once is not enough to silently WRITE a word — the same evidence would be " +
                "allowed to offer a next-word suggestion, which the user must still tap"
        ).that(order.first()).isEqualTo(0)
    }

    @Test
    fun `a promotion needs the probability floor too, not just frequency`() {
        val unlikely = ev(boost = 5.0, frequency = 50, probability = 0.04f) // below MIN(0.05)
        val order = SwipeContextRescorer.rescoreOrder(
            scores(900, 450), listOf(SwipeContextRescorer.Evidence.NONE, unlikely)
        )
        assertWithMessage("a frequent but low-probability continuation must not take rank 1")
            .that(order.first()).isEqualTo(0)
    }

    @Test
    fun `a promotion succeeds when BOTH floors are exactly met`() {
        val atFloor = ev(
            boost = 5.0,
            frequency = NextWordPredictor.MIN_LEARNED_FREQUENCY,
            probability = NextWordPredictor.MIN_LEARNED_PROBABILITY,
        )
        val order = SwipeContextRescorer.rescoreOrder(
            scores(900, 450), listOf(SwipeContextRescorer.Evidence.NONE, atFloor)
        )
        assertWithMessage("both floors are `>=`, so the exact boundary must promote")
            .that(order).containsExactly(1, 0).inOrder()
    }

    @Test
    fun `evidence too thin for rank 1 can still reorder the alternates`() {
        // The floors gate PROMOTION, not the whole rescoring — same principle as the ratio guard.
        val thin = ev(boost = 5.0, frequency = 1, probability = 0.01f)
        val order = SwipeContextRescorer.rescoreOrder(
            scores(900, 100, 90),
            listOf(SwipeContextRescorer.Evidence.NONE, SwipeContextRescorer.Evidence.NONE, thin),
        )
        assertThat(order).containsExactly(0, 2, 1).inOrder()
    }

    @Test
    fun `promotableToRankOne reads the floors from NextWordPredictor rather than redeclaring them`() {
        // If these ever diverge, the "offering vs writing" argument silently inverts — the swipe
        // path could commit a word the next-word path would not even suggest.
        assertThat(SwipeContextRescorer.promotableToRankOne(
            ev(2.0, NextWordPredictor.MIN_LEARNED_FREQUENCY, NextWordPredictor.MIN_LEARNED_PROBABILITY)
        )).isTrue()
        assertThat(SwipeContextRescorer.promotableToRankOne(
            ev(2.0, NextWordPredictor.MIN_LEARNED_FREQUENCY - 1, 1.0f)
        )).isFalse()
        assertThat(SwipeContextRescorer.promotableToRankOne(
            ev(2.0, 999, NextWordPredictor.MIN_LEARNED_PROBABILITY - 0.001f)
        )).isFalse()
        assertThat(SwipeContextRescorer.promotableToRankOne(SwipeContextRescorer.Evidence.NONE))
            .isFalse()
    }

    // ── store keys (§6.4) ────────────────────────────────────────────────────────────

    @Test
    fun `store keys keep internal apostrophes and accents, lowercased`() {
        // Slate words arrive AFTER the adapter's display overlays, so they are already the
        // display forms. The stores are keyed on committed words lowercased with word-internal
        // apostrophes/hyphens KEPT. Stripping either would make every contraction and every
        // accented word miss silently — rescoring would look like a no-op rather than a bug.
        assertThat(SwipeContextRescorer.storeKey("Don't")).isEqualTo("don't")
        assertThat(SwipeContextRescorer.storeKey("café")).isEqualTo("café")
        assertThat(SwipeContextRescorer.storeKey("Café")).isEqualTo("café")
        assertThat(SwipeContextRescorer.storeKey("co-op")).isEqualTo("co-op")
        assertThat(SwipeContextRescorer.storeKey("qu'est-ce")).isEqualTo("qu'est-ce")
        assertWithMessage("the transform is lowercasing and nothing else")
            .that(SwipeContextRescorer.storeKey("PEUT-ÊTRE")).isEqualTo("peut-être")
    }

    // ── the constants are the safety envelope ───────────────────────────────────────

    @Test
    fun `the tuning constants match the documented design`() {
        // These are cited by number throughout the spec's §6 damage analysis. Changing one
        // without re-deriving those bounds is the failure this pins.
        assertThat(SwipeContextRescorer.WEIGHT).isEqualTo(0.5)
        assertThat(SwipeContextRescorer.R_MIN).isEqualTo(0.5)
        assertThat(SwipeContextRescorer.MAX_BOOST).isEqualTo(5.0)
        assertThat(SwipeContextRescorer.NO_BOOST).isEqualTo(1.0)
    }
}
