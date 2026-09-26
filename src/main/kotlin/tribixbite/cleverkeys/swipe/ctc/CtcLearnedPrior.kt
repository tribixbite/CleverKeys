package tribixbite.cleverkeys.swipe.ctc

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * A learned-usage prior for the CTC final score: words the user actually uses are LIFTED
 * toward the lexicon's frequency ceiling (learning-system audit 2026-09-26, report 2 —
 * "I swipe `git` constantly and still get `got`/`for`").
 *
 * ## Why a lift toward the ceiling, not a relative-popularity boost
 *
 * On the maintainer's device `git` has 92 uses, `got` 98 and `for` 881. Any boost that ranks
 * words by how OFTEN they are used relative to each other keeps `for` and `got` ahead of `git`
 * forever. What the usage record actually says is "the user types this word — it is not rare
 * FOR THEM", so the prior raises a used word's effective lexicon frequency toward the top of
 * the scale and saturates: past a modest number of uses, every used word looks equally common,
 * and the swipe's own emission evidence decides between them. That is exactly the tie-break
 * the static lexicon was deciding wrongly.
 *
 * ## The policy (design review 2026-09-26)
 *
 * ```
 * n_eff  = usage·recency + SELECTION_WEIGHT·manualSelections
 * s(n)   = min(1, ln(1 + n_eff) / ln(1 + nSat))
 * f_eff  = f_base + (freqCeiling − f_base)·s(n)
 * bonus  = min(λ·(ln f_eff − ln f_base), bCap) + min(selMargin, SEL_STEP·manualSelections)
 * applied iff n_eff ≥ minEffectiveUses AND the word is a member of the merged lexicon
 * ```
 *
 * The bonus is added to [CtcBeamDecoder]'s FINAL score only. The per-frame prune key carries
 * no frequency term at all, so a final-score bonus reaches exactly the words a trie-frequency
 * change would reach — every complete word in the final beam — at ≤ beamWidth lookups per
 * decode and zero per-frame cost.
 *
 * ## Eligibility
 *
 * Only words for which [isLexiconWord] is true receive a bonus. The adapter passes membership
 * in the MERGED lexicon (`CtcLexiconMerge.ordinals`), which by construction excludes the
 * injected contraction alias keys (`dont`, `theyll` …): those are trie-only pseudo-words that
 * must never be preferred on a prior, only reached on emission evidence
 * ([CtcContractionKeys]).
 *
 * Pure JVM, no Android — [CtcPurityDriftTest] scans this package.
 *
 * @property evidence per-word usage evidence (null = no record).
 * @property isLexiconWord merged-lexicon membership; alias keys MUST return false.
 * @property policy the tunable constants.
 */
class CtcLearnedPrior(
    private val evidence: Evidence,
    private val isLexiconWord: (String) -> Boolean,
    val policy: Policy = Policy(),
) {

    /**
     * What the learning stores know about one word.
     *
     * @property usage times the word was committed (personalization vocabulary count).
     * @property recency 0..1 recency multiplier on [usage] (the vocabulary's own decay —
     *   `UserWordUsage.getRecencyScore`); values outside the range are clamped.
     * @property manualSelections times the user explicitly picked the word from the bar.
     */
    data class WordEvidence(val usage: Int, val recency: Double, val manualSelections: Int) {
        /** `usage·recency + SELECTION_WEIGHT·manualSelections`, with inputs clamped sane. */
        val effectiveUses: Double
            get() = usage.coerceAtLeast(0) * recency.coerceIn(0.0, 1.0) +
                SELECTION_WEIGHT * manualSelections.coerceAtLeast(0)
    }

    /** Word → evidence lookup, called at most once per complete word in the final beam. */
    fun interface Evidence {
        fun lookup(word: String): WordEvidence?
    }

    /**
     * The tunable constants. **No grid point cleared the ship bar** in the replay evaluation
     * (`docs/eval/2026-09-26-learned-unigram-swipe-replay.md`, measured at `d0ae3a13`), so
     * there is no tuned value: the defaults are the LEAST-DAMAGING grid point
     * (N_SAT 40, SEL_MARGIN 0), recorded so a future wiring starts from the safest measured
     * setting — not a recommendation to wire it as-is.
     *
     * @property nSat effective-use count at which the lift saturates at the ceiling.
     * @property bCap cap on the unigram (frequency-lift) part of the bonus, in final-score nats.
     * @property selMargin cap on the extra per-selection margin.
     * @property freqCeiling top of the lexicon frequency scale (255 for both the en byte scale
     *   and the CKDT `255 − rank` scale).
     * @property minEffectiveUses below this many effective uses there is no bonus at all.
     */
    data class Policy(
        val nSat: Double = N_SAT,
        val bCap: Double = B_CAP,
        val selMargin: Double = SEL_MARGIN,
        val freqCeiling: Double = F_CEIL,
        val minEffectiveUses: Double = MIN_EFFECTIVE_USES,
    ) {
        init {
            require(nSat > 0.0) { "nSat must be > 0, was $nSat" }
            require(bCap >= 0.0) { "bCap must be >= 0, was $bCap" }
            require(selMargin >= 0.0) { "selMargin must be >= 0, was $selMargin" }
            require(freqCeiling > 0.0) { "freqCeiling must be > 0, was $freqCeiling" }
        }

        /** `s(n) = min(1, ln(1+n)/ln(1+nSat))`; 0 for a non-positive count. */
        fun saturation(effectiveUses: Double): Double =
            if (effectiveUses <= 0.0) 0.0
            else min(1.0, ln(1.0 + effectiveUses) / ln(1.0 + nSat))

        /**
         * The final-score bonus for a word with [ev] whose trie log-frequency is
         * [logFreqBase] (`ln f_base`, as stored on [CtcTrieNode.logFreq]), under the
         * decoder's frequency weight [lambda]. Never negative; 0 below [minEffectiveUses].
         */
        fun bonus(ev: WordEvidence, logFreqBase: Double, lambda: Double): Double {
            val nEff = ev.effectiveUses
            if (nEff < minEffectiveUses) return 0.0
            val fBase = exp(logFreqBase)
            val unigram = if (fBase >= freqCeiling) {
                0.0 // already at (or above) the ceiling: nothing to lift toward
            } else {
                val fEff = fBase + (freqCeiling - fBase) * saturation(nEff)
                min(lambda * (ln(fEff) - logFreqBase), bCap).coerceAtLeast(0.0)
            }
            val selection = min(selMargin, SEL_STEP * ev.manualSelections.coerceAtLeast(0))
            return unigram + selection
        }

        /**
         * The largest bonus any evidence can give a word at [logFreqBase]:
         * `min(λ·ln(freqCeiling/f), bCap) + selMargin`. The replay's headroom bound.
         */
        fun maxLift(logFreqBase: Double, lambda: Double): Double {
            val headroom = (ln(freqCeiling) - logFreqBase).coerceAtLeast(0.0)
            return min(lambda * headroom, bCap) + selMargin
        }
    }

    /**
     * The bonus [CtcBeamDecoder] adds to [word]'s final score: 0 for a non-lexicon word
     * (injected alias key), a word with no evidence, or one below the use threshold.
     */
    fun bonusFor(word: String, logFreqBase: Double, lambda: Double): Double {
        if (this === NONE) return 0.0
        if (!isLexiconWord(word)) return 0.0
        val ev = evidence.lookup(word) ?: return 0.0
        return policy.bonus(ev, logFreqBase, lambda)
    }

    companion object {
        /**
         * Saturation count. Grid {10, 20, 40}; 40 broke the fewest decodes on every arm
         * (design proposed 20). See the eval doc — nothing in the grid is shippable.
         */
        const val N_SAT = 40.0

        /** Cap on the unigram lift, final-score nats. */
        const val B_CAP = 2.5

        /**
         * Cap on the manual-selection margin. Grid {0, 0.25, 0.5}; every non-zero value
         * raised the adversarial break rate (1.3 % -> 2.6-4.0 % of exposed traces on tune)
         * while P_real carries no selections to benefit from it, so 0.
         */
        const val SEL_MARGIN = 0.0

        /** Per-selection step of the selection margin. */
        const val SEL_STEP = 0.1

        /** A manual pick counts as this many ordinary uses toward [WordEvidence.effectiveUses]. */
        const val SELECTION_WEIGHT = 3.0

        /** Lexicon frequency ceiling ([CtcLexiconMerge.MAX_FREQ]). */
        const val F_CEIL = CtcLexiconMerge.MAX_FREQ

        /** No bonus below this many effective uses. */
        const val MIN_EFFECTIVE_USES = 3.0

        /**
         * No learned data: every bonus is 0 and [CtcBeamDecoder] skips the lookup entirely,
         * so decoding with NONE is byte-identical to the pre-prior decoder.
         */
        @JvmField
        val NONE: CtcLearnedPrior = CtcLearnedPrior(Evidence { null }, { false })
    }
}
