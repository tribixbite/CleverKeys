package tribixbite.cleverkeys.swipe

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
 * 1. **Rank-1 displacement guard** — a candidate may take rank 1 only if
 *    `score_i >= R_MIN * score_top`, i.e. the engine itself put it within a factor of two
 *    (≤ 0.69 nats behind). A confidently decoded swipe (top-1 900, runner-up 40) is
 *    arithmetically un-overturnable regardless of boost.
 * 2. **The boost ceiling** — `WEIGHT * ln(MAX_BOOST)` ≈ 0.80 nats at `WEIGHT = 0.5`. Context can
 *    never outvote strong emission evidence, only break near-ties.
 * 3. **Below rank 1 reordering is cheap** — ranks 2..K are bar alternates the user may tap, so a
 *    suboptimal ordering there costs nothing today's ordering doesn't already. No extra cap.
 *
 * The guard deliberately protects only rank 1, because rank 1 is what auto-inserts.
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
     * Reorder a slate by context, returning the new order as ORIGINAL indices.
     *
     * Indices rather than reordered words because the caller holds parallel lists — words,
     * scores, and `SuggestionMeta` provenance — and returning a permutation is the only shape
     * that cannot silently misalign them.
     *
     * @param scores engine scores in ENGINE RANK ORDER (descending); `scores[0]` is the top-1
     *   whose displacement the guard protects.
     * @param boosts per-candidate context boost, parallel to [scores]. `1.0` means no confident
     *   learned continuation. Values are clamped to `[NO_BOOST, MAX_BOOST]`.
     * @return a permutation of `scores.indices`.
     */
    fun rescoreOrder(scores: List<Int>, boosts: List<Double>): List<Int> {
        require(scores.size == boosts.size) {
            "scores/boosts must be parallel: ${scores.size} vs ${boosts.size}"
        }
        if (scores.size < 2) return scores.indices.toList()

        // Nothing learned for any candidate: the answer is the input, exactly.
        if (boosts.none { it > NO_BOOST }) return scores.indices.toList()

        val adjusted = DoubleArray(scores.size) { i ->
            val boost = boosts[i].coerceIn(NO_BOOST, MAX_BOOST)
            ln(maxOf(scores[i], 1).toDouble()) + WEIGHT * ln(boost)
        }

        // Descending by adjusted score; ties broken by input index so the sort is stable and the
        // identity property holds for equal-scored candidates.
        val order = scores.indices.sortedWith(
            compareByDescending<Int> { adjusted[it] }.thenBy { it }
        )

        return applyRankOneGuard(order, scores)
    }

    /**
     * Enforce the rank-1 displacement guard on a computed [order].
     *
     * If the newly promoted leader was not within `R_MIN` of the engine's own top-1, the engine's
     * top-1 is restored to rank 1 and everything else keeps its rescored order. Note this does
     * NOT discard the rescoring — ranks 2..K stay reordered, because those are alternates the
     * user chooses explicitly and reordering them is cheap.
     */
    private fun applyRankOneGuard(order: List<Int>, scores: List<Int>): List<Int> {
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
        if (topScore > 0 && scores[promoted] >= R_MIN * topScore) return order

        return listOf(engineTop) + order.filter { it != engineTop }
    }
}
