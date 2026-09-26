package tribixbite.cleverkeys.swipe.ctc

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * A learned prior for the CTC final score: words the user has shown the decoder gets WRONG for
 * them are LIFTED toward the lexicon's frequency ceiling (learning-system audit 2026-09-26,
 * report 2 — "I swipe `git` constantly and still get `got`/`for`").
 *
 * ## Two evidence modes
 *
 * - [EvidenceMode.CORRECTIONS] — **the default.** Evidence is the per-word swipe-CORRECTION
 *   count `c(Y)`: how often a swipe auto-inserted some X and the user replaced it with Y (bar
 *   tap, or backspace-undo then retype/re-swipe). It is sparse and high-precision — it names
 *   only words the engine keeps getting wrong for THIS user. A saturated lift is exactly a
 *   user-dictionary entry at the ceiling (a mechanism that already ships), clipped by [Policy.bCap].
 *   Eval: `docs/eval/2026-09-26-correction-driven-swipe-prior-replay.md`.
 * - [EvidenceMode.USAGE] — the first attempt: every word the user commits is lifted by its
 *   usage count. It FAILED the ship bar (lifting 1,766 used words broke about 2× as many
 *   untyped-target swipes as it fixed — `docs/eval/2026-09-26-learned-unigram-swipe-replay.md`)
 *   and is kept only as a measurable alternative via [Policy.usage]. Never the default.
 *
 * ## Why a lift toward the ceiling, not a relative-popularity boost
 *
 * On the maintainer's device `git` has 92 uses, `got` 98 and `for` 881. Any boost that ranks
 * words by how OFTEN they occur relative to each other keeps `for` and `got` ahead of `git`
 * forever. The prior instead raises the evidenced word's effective lexicon frequency toward
 * the top of the scale and saturates, so the swipe's own emission evidence decides between
 * it and its neighbours. That is exactly the tie-break the static lexicon was deciding wrongly.
 *
 * ## The formula
 *
 * ```
 * n      = corrections                                      (CORRECTIONS mode)
 *        | usage·recency + SELECTION_WEIGHT·manualSelections (USAGE mode)
 * s(n)   = min(1, ln(1 + n) / ln(1 + nSat))
 * f_eff  = f_base + (freqCeiling − f_base)·s(n)
 * bonus  = min(λ·(ln f_eff − ln f_base), bCap)
 *          + (USAGE mode only) min(selMargin, SEL_STEP·manualSelections)
 * applied iff n ≥ minEffectiveUses AND the word is a member of the merged lexicon
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
 * @property evidence per-word evidence (null = no record).
 * @property isLexiconWord merged-lexicon membership; alias keys MUST return false.
 * @property policy the tunable constants and the evidence mode.
 */
class CtcLearnedPrior(
    private val evidence: Evidence,
    private val isLexiconWord: (String) -> Boolean,
    val policy: Policy = Policy(),
) {

    /** Which field of [WordEvidence] a [Policy] reads as its evidence count. */
    enum class EvidenceMode {
        /** Swipe-correction count `c(Y)` — the default policy. */
        CORRECTIONS,

        /** Usage + manual selections — the failed first attempt, kept measurable. */
        USAGE,
    }

    /**
     * What the learning stores know about one word.
     *
     * @property usage times the word was committed (personalization vocabulary count).
     * @property recency 0..1 recency multiplier on [usage] (the vocabulary's own decay —
     *   `UserWordUsage.getRecencyScore`); values outside the range are clamped.
     * @property manualSelections times the user explicitly picked the word from the bar.
     * @property corrections the (possibly decayed, hence fractional) number of times a swipe
     *   auto-inserted some other word and the user replaced it with this one.
     */
    data class WordEvidence(
        val usage: Int = 0,
        val recency: Double = 1.0,
        val manualSelections: Int = 0,
        val corrections: Double = 0.0,
    ) {
        /** `usage·recency + SELECTION_WEIGHT·manualSelections`, with inputs clamped sane. */
        val effectiveUses: Double
            get() = usage.coerceAtLeast(0) * recency.coerceIn(0.0, 1.0) +
                SELECTION_WEIGHT * manualSelections.coerceAtLeast(0)

        companion object {
            /** Correction-only evidence (what a correction store supplies). */
            fun corrections(count: Double): WordEvidence = WordEvidence(corrections = count)
        }
    }

    /** Word → evidence lookup, called at most once per complete word in the final beam. */
    fun interface Evidence {
        fun lookup(word: String): WordEvidence?
    }

    /**
     * The tunable constants. The default is the CORRECTION-driven policy at the best
     * tune-direction point of the correction replay (measured at `e41f9463`). **No point
     * cleared the ship bar** — best confirm: 9 fixed / 4 broken, errRatio 0.44 vs < 0.20 — so
     * these are the least-bad measured values, NOT a shippable tuning; see
     * `docs/eval/2026-09-26-correction-driven-swipe-prior-replay.md`. [usage] builds the first
     * attempt's USAGE-mode policy at its least-damaging grid point.
     *
     * @property nSat evidence count at which the lift saturates at the ceiling.
     * @property bCap cap on the unigram (frequency-lift) part of the bonus, in final-score nats.
     * @property selMargin cap on the extra per-selection margin (USAGE mode only).
     * @property freqCeiling top of the lexicon frequency scale (255 for both the en byte scale
     *   and the CKDT `255 − rank` scale).
     * @property minEffectiveUses below this evidence count there is no bonus at all.
     * @property mode which evidence field is counted.
     */
    data class Policy(
        val nSat: Double = CORRECTION_N_SAT,
        val bCap: Double = CORRECTION_B_CAP,
        val selMargin: Double = 0.0,
        val freqCeiling: Double = F_CEIL,
        val minEffectiveUses: Double = CORRECTION_MIN_COUNT,
        val mode: EvidenceMode = EvidenceMode.CORRECTIONS,
    ) {
        init {
            require(nSat > 0.0) { "nSat must be > 0, was $nSat" }
            require(bCap >= 0.0) { "bCap must be >= 0, was $bCap" }
            require(selMargin >= 0.0) { "selMargin must be >= 0, was $selMargin" }
            require(freqCeiling > 0.0) { "freqCeiling must be > 0, was $freqCeiling" }
        }

        /** The evidence count [mode] reads from [ev]; garbage (negative, NaN) counts as 0. */
        fun evidenceCount(ev: WordEvidence): Double {
            val n = when (mode) {
                EvidenceMode.CORRECTIONS -> ev.corrections
                EvidenceMode.USAGE -> ev.effectiveUses
            }
            return if (n.isNaN() || n < 0.0) 0.0 else n
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
            val nEff = evidenceCount(ev)
            if (nEff <= 0.0 || nEff < minEffectiveUses) return 0.0
            val fBase = exp(logFreqBase)
            val unigram = if (fBase >= freqCeiling) {
                0.0 // already at (or above) the ceiling: nothing to lift toward
            } else {
                val fEff = fBase + (freqCeiling - fBase) * saturation(nEff)
                min(lambda * (ln(fEff) - logFreqBase), bCap).coerceAtLeast(0.0)
            }
            if (mode != EvidenceMode.USAGE) return unigram
            val selection = min(selMargin, SEL_STEP * ev.manualSelections.coerceAtLeast(0))
            return unigram + selection
        }

        /**
         * The largest bonus any evidence can give a word at [logFreqBase]:
         * `min(λ·ln(freqCeiling/f), bCap)` (+ `selMargin` in USAGE mode). The replay's
         * headroom bound.
         */
        fun maxLift(logFreqBase: Double, lambda: Double): Double {
            val headroom = (ln(freqCeiling) - logFreqBase).coerceAtLeast(0.0)
            return min(lambda * headroom, bCap) + if (mode == EvidenceMode.USAGE) selMargin else 0.0
        }

        companion object {
            /**
             * The first attempt's USAGE-mode policy, defaulting to its least-damaging grid
             * point (N_SAT 40, SEL_MARGIN 0, B_CAP 2.5, 3 effective uses). It FAILED the ship
             * bar at every grid point — measurable, never the default.
             */
            fun usage(
                nSat: Double = USAGE_N_SAT,
                bCap: Double = USAGE_B_CAP,
                selMargin: Double = USAGE_SEL_MARGIN,
                freqCeiling: Double = F_CEIL,
                minEffectiveUses: Double = USAGE_MIN_EFFECTIVE_USES,
            ): Policy = Policy(nSat, bCap, selMargin, freqCeiling, minEffectiveUses, EvidenceMode.USAGE)
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
        // ── CORRECTIONS mode (the default) — CorrectionPriorReplayTest, best tune net ─────
        // Grid cMin {1,2,3} × N_SAT {2,3,5} × B_CAP {1.0,1.5,2.5}; nothing cleared the bar.

        /** Correction count at which the lift reaches the ceiling (git pin: flips at 1–2). */
        const val CORRECTION_N_SAT = 3.0

        /**
         * Cap on the correction lift, final-score nats. 1.0/1.5/2.5 tie on the tune replay;
         * 1.0 halves the adversarial single-word blast (108 vs 186 words over 1 %).
         */
        const val CORRECTION_B_CAP = 1.0

        /**
         * No bonus below this many (decayed) corrections. cMin ≥ 2 leaves the trace pool with
         * ≤ 4 fixable traces per direction — unmeasurable, not measured-safe.
         */
        const val CORRECTION_MIN_COUNT = 1.0

        // ── USAGE mode (failed first attempt; Policy.usage) ──────────────────────────────

        /**
         * Usage saturation count. Grid {10, 20, 40}; 40 broke the fewest decodes on every arm
         * (design proposed 20). Nothing in that grid is shippable.
         */
        const val USAGE_N_SAT = 40.0

        /** Cap on the usage-mode unigram lift, final-score nats. */
        const val USAGE_B_CAP = 2.5

        /**
         * Cap on the usage-mode manual-selection margin. Grid {0, 0.25, 0.5}; every non-zero
         * value raised the adversarial break rate (1.3 % -> 2.6-4.0 % of exposed traces on
         * tune) while P_real carries no selections to benefit from it, so 0.
         */
        const val USAGE_SEL_MARGIN = 0.0

        /** No usage-mode bonus below this many effective uses. */
        const val USAGE_MIN_EFFECTIVE_USES = 3.0

        /** Per-selection step of the selection margin. */
        const val SEL_STEP = 0.1

        /** A manual pick counts as this many ordinary uses toward [WordEvidence.effectiveUses]. */
        const val SELECTION_WEIGHT = 3.0

        /** Lexicon frequency ceiling ([CtcLexiconMerge.MAX_FREQ]). */
        const val F_CEIL = CtcLexiconMerge.MAX_FREQ

        /**
         * No learned data: every bonus is 0 and [CtcBeamDecoder] skips the lookup entirely,
         * so decoding with NONE is byte-identical to the pre-prior decoder.
         */
        @JvmField
        val NONE: CtcLearnedPrior = CtcLearnedPrior(Evidence { null }, { false })
    }
}
