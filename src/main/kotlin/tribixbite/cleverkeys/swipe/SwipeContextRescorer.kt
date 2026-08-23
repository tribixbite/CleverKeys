package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.NextWordPredictor
import kotlin.math.ln

/**
 * Log-linear rescoring of a swipe slate by a learned bigram/trigram context signal.
 *
 * Step 1 of `docs/specs/ctc-context-rescoring-and-tunables.md` — the pure math, with no Android
 * dependency, no store access and no gating. Everything that decides *whether* to call this
 * (the master learning gate, the feature pref, the store floors) lives elsewhere by design, so
 * that this file can be reasoned about and tested in isolation.
 *
 * ## The combination
 *
 * ```
 * adjusted_i = ln(max(score_i, 1)) + WEIGHT * ln(boost_i)
 * ```
 *
 * re-sorted **descending, stable, input-index tiebreak**.
 *
 * `score_i` is the within-slate CTC posterior proxy (a softmax over final beam scores × 1000,
 * ints in 0..1000). It is engine-relative and meaningless across engines, but within one slate
 * the *ratios* are meaningful — which is exactly what rescoring needs.
 *
 * `boost_i` is `ContextModel.getContextBoost`: trigram preferred, bigram backoff,
 * `(1 + p)^2` clamped to `[1.0, 5.0]`, computed from confident probabilities only.
 *
 * ### Why log-linear and not the alternatives
 *
 * **Rank fusion is rejected** because it discards the score margins, and the margins ARE the
 * safety mechanism: a peaked slate (top-1 at 900/1000) must be far harder to overturn than a
 * flat one. **Linear-probability interpolation** is rejected because the two quantities are not
 * on a common scale — a boost is not a probability over the slate — and because it is not
 * identity-preserving at empty stores without special-casing.
 *
 * ### The identity property
 *
 * With no learned data every boost is `1.0`, so `ln(boost_i) = 0` exactly and `adjusted`
 * reduces to `ln(score_i)`. For a slate in engine rank order the stable sort then reproduces the
 * input order. **A user who has learned nothing gets the identical ranking**, structurally rather
 * than by special-casing — which is the answer to "a default that changes ranking for a user who
 * has learned nothing is a bug".
 *
 * [rescoreOrder] additionally short-circuits when no boost exceeds 1.0. That is not what makes
 * the property true; it makes it true *unconditionally* (including for a slate that somehow
 * arrives out of rank order) and avoids doing float work to compute an answer already known.
 *
 * ## What bounds the damage
 *
 * 1. **Rank-1 score-ratio guard** — a candidate may take rank 1 only if
 *    `score_i >= R_MIN * score_top`, i.e. the engine itself put it within a factor of two
 *    (≤ 0.69 nats behind). A confidently decoded swipe (top-1 900, runner-up 40) is
 *    arithmetically un-overturnable regardless of boost.
 * 2. **Rank-1 strict evidence floors** — and it must ALSO clear the stricter
 *    `NextWordPredictor` floors (see [promotableToRankOne]). Both must hold; either one failing
 *    restores the engine's top-1.
 * 3. **The boost ceiling** — `WEIGHT * ln(MAX_BOOST)` ≈ 0.80 nats at `WEIGHT = 0.5`. Context can
 *    never outvote strong emission evidence, only break near-ties.
 * 4. **Below rank 1 reordering is cheap** — ranks 2..K are bar alternates the user may tap, so a
 *    suboptimal ordering there costs nothing today's ordering doesn't already. No extra cap.
 *
 * Guards 1 and 2 deliberately protect ONLY rank 1, because rank 1 is what auto-inserts. A
 * rescoring blocked at rank 1 still reorders the alternates beneath it.
 */
object SwipeContextRescorer {

    /**
     * Weight on the context term, in nats per nat of boost.
     *
     * Starting point from the design; to be fitted by the offline replay harness (step 5) on a
     * tune half and confirmed on a held-out half. NOT a user-facing knob — see the spec's
     * Part 2 for why the raw scoring constants stay internal.
     */
    const val WEIGHT = 0.5

    /**
     * A candidate may take rank 1 only if its engine score is at least this fraction of the
     * engine's own top-1 score.
     *
     * This is the auto-commit protection and the single most important constant here: rank 1 is
     * inserted without the user choosing it, so promoting into it on a learned prior alone is
     * how context rescoring would introduce errors a user cannot anticipate.
     */
    const val R_MIN = 0.5

    /** Mirrors `ContextModel`'s clamp; defensive only — a well-behaved provider stays inside. */
    const val MAX_BOOST = 5.0

    /** Neutral boost: no confident learned continuation for this candidate. */
    const val NO_BOOST = 1.0

    /**
     * The store key for a slate word.
     *
     * Slate words reach the rescorer AFTER the adapter's display overlays, so they are already
     * `"don't"` and `"café"` — while the context stores are keyed on committed words lowercased
     * with word-internal apostrophes and hyphens KEPT (`NextWordPredictor`'s tokenizer contract).
     * Lowercasing is therefore the whole transform: stripping the apostrophe would miss every
     * contraction, and stripping accents would miss every accented word, in both cases silently
     * — the lookup would simply never hit and rescoring would look like a no-op rather than a bug.
     */
    fun storeKey(word: String): String = word.lowercase()

    /**
     * The learned evidence for one slate candidate.
     *
     * Deliberately a boost PLUS the raw counts, not a boost alone. Moves below rank 1 need only
     * the boost, but a promotion INTO rank 1 must additionally clear the strict next-word floors
     * (see [promotableToRankOne]) — and those are expressed in frequency and probability, which a
     * single collapsed multiplier cannot reconstruct.
     *
     * Mirrors `contextaware.ContextContinuation`. It is restated here rather than imported so this
     * file stays free of the context package and remains a pure, dependency-light unit; the caller
     * does the one-line adaptation.
     *
     * @param boost `ContextModel.getContextBoost` — `(1 + p)^2` clamped to `[1.0, 5.0]`.
     * @param frequency observation count of this continuation in its store.
     * @param probability conditional probability within its store.
     */
    data class Evidence(
        val boost: Double,
        val frequency: Int,
        val probability: Float,
    ) {
        companion object {
            /** No confident learned continuation — contributes exactly zero to the sort. */
            val NONE = Evidence(NO_BOOST, 0, 0f)
        }
    }

    /**
     * May this candidate's learned evidence promote it into rank 1?
     *
     * Rank 1 auto-inserts, so a context-driven promotion into it is a silent **commit**. The
     * design's rule: general in-slate reordering rides on the stores' own floors (frequency ≥ 2,
     * `MIN_BIGRAM_PROB`/`MIN_TRIGRAM_PROB`, all applied inside `getContextBoost`), but a rank-1
     * promotion must additionally clear the STRICTER floors that `NextWordPredictor` uses to
     * generate suggestions from nothing.
     *
     * The asymmetry is deliberate and worth stating: a next-word suggestion at this confidence
     * still requires the user to TAP it. Here nothing is tapped — the word is written. The bar for
     * writing silently must be at least as high as the bar for offering.
     *
     * The two constants are referenced from [NextWordPredictor], never re-declared, so the two
     * paths cannot drift apart.
     */
    fun promotableToRankOne(evidence: Evidence): Boolean =
        evidence.frequency >= NextWordPredictor.MIN_LEARNED_FREQUENCY &&
            evidence.probability >= NextWordPredictor.MIN_LEARNED_PROBABILITY

    /**
     * Reorder a slate by context, returning the new order as ORIGINAL indices.
     *
     * Indices rather than reordered words because the caller holds parallel lists — words,
     * scores, and `SuggestionMeta` provenance — and returning a permutation is the only shape
     * that cannot silently misalign them.
     *
     * [weight] and [rMin] default to the shipped constants, so **every production call site is
     * unchanged**. They are parameters only so the step-5 offline harness can sweep a grid on one
     * decode instead of re-decoding per point — the spec's own plan for fitting them
     * (`ctc-context-rescoring-and-tunables.md` §7.1: "tune `W`/`R_MIN` on a tune half, confirm on
     * held-out half"). Callers in the app must NOT pass them; if a tuned value is ever adopted it
     * belongs in the constants, where the guard's behaviour stays inspectable in one place.
     *
     * @param scores engine scores in ENGINE RANK ORDER (descending); `scores[0]` is the top-1
     *   whose displacement the guard protects.
     * @param evidence per-candidate learned evidence, parallel to [scores]. Use [Evidence.NONE]
     *   where the stores had nothing confident.
     * @param weight nats per nat of boost; defaults to [WEIGHT].
     * @param rMin rank-1 score-ratio floor; defaults to [R_MIN].
     * @return a permutation of `scores.indices`.
     */
    fun rescoreOrder(
        scores: List<Int>,
        evidence: List<Evidence>,
        weight: Double = WEIGHT,
        rMin: Double = R_MIN,
    ): List<Int> {
        require(scores.size == evidence.size) {
            "scores/evidence must be parallel: ${scores.size} vs ${evidence.size}"
        }
        if (scores.size < 2) return scores.indices.toList()

        // Nothing learned for any candidate: the answer is the input, exactly.
        if (evidence.none { it.boost > NO_BOOST }) return scores.indices.toList()

        val adjusted = DoubleArray(scores.size) { i ->
            val boost = evidence[i].boost.coerceIn(NO_BOOST, MAX_BOOST)
            ln(maxOf(scores[i], 1).toDouble()) + weight * ln(boost)
        }

        // Descending by adjusted score; ties broken by input index so the sort is stable and the
        // identity property holds for equal-scored candidates.
        val order = scores.indices.sortedWith(
            compareByDescending<Int> { adjusted[it] }.thenBy { it }
        )

        return applyRankOneGuard(order, scores, evidence, rMin)
    }

    /**
     * Enforce BOTH rank-1 protections on a computed [order].
     *
     * A candidate may lead only if it clears the score-ratio guard (the engine itself put it
     * within a factor of two) AND the strict evidence floors ([promotableToRankOne]). Failing
     * either restores the engine's top-1 to rank 1 while everything else keeps its rescored
     * order — the rescoring is NOT discarded, because ranks 2..K are alternates the user chooses
     * explicitly and reordering them is cheap.
     */
    private fun applyRankOneGuard(
        order: List<Int>,
        scores: List<Int>,
        evidence: List<Evidence>,
        rMin: Double = R_MIN,
    ): List<Int> {
        val engineTop = 0
        val promoted = order.first()
        if (promoted == engineTop) return order

        val topScore = scores[engineTop]
        // A zero-scored top-1 makes the ratio test meaningless (every score is >= 0 * anything),
        // so the guard cannot be evaluated. Fail SAFE: refuse the promotion. Rank 1 auto-inserts,
        // so "cannot evaluate the protection" must mean "do not promote", never "promote freely".
        //
        // This is defensive rather than reachable: the scores are a softmax over the slate scaled
        // by 1000, so the maximum is at least 1000/K and cannot round to zero for any real K.
        val withinRatio = topScore > 0 && scores[promoted] >= rMin * topScore
        if (withinRatio && promotableToRankOne(evidence[promoted])) return order

        return listOf(engineTop) + order.filter { it != engineTop }
    }
}
