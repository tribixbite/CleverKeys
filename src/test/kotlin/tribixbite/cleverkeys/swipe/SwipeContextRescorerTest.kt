package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
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

    private fun boosts(vararg v: Double) = v.toList()
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
        val demoted = SwipeContextRescorer.rescoreOrder(scores(500, 490), boosts(0.01, 1.0))
        assertWithMessage("a sub-1.0 boost is clamped to neutral, so it cannot demote index 0")
            .that(demoted).containsExactly(0, 1).inOrder()

        val huge = SwipeContextRescorer.rescoreOrder(scores(900, 449), boosts(1.0, 1e9))
        assertWithMessage("an absurd boost is clamped, so the rank-1 guard still refuses")
            .that(huge.first()).isEqualTo(0)
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
