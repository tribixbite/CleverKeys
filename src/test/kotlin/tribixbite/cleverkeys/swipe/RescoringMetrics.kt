package tribixbite.cleverkeys.swipe

/**
 * The measurement core of the context-rescoring replay harness (step 5 of
 * `docs/specs/ctc-context-rescoring-and-tunables.md` §7.1).
 *
 * Corpus-independent on purpose: whichever corpus ends up seeding the learned stores, the
 * question asked of each trace is the same, and it is a PAIRED question — what did rescoring do
 * to this decode, relative to not rescoring it.
 *
 * ## Why this is not just "top-1 accuracy before vs after"
 *
 * An aggregate top-1 delta hides the thing that actually decides whether the feature is safe to
 * enable. Rescoring can fix N decodes and break M others and still show a positive delta; the
 * broken ones are words the user swiped correctly and got wrong *because* the feature was on,
 * which is a qualitatively worse failure than one the engine got wrong on its own. §7.3 therefore
 * gates the default flip on the two numbers SEPARATELY: a net gain, AND breakages well under
 * fixes.
 *
 * ## The classification trap this exists to avoid
 *
 * `scripts/ctc_injection_ab.py` originally scored an A/B by the SHAPE of the change — "top-1 went
 * from a real word to an injected key, therefore a regression" — and flagged a fix as a
 * regression, because the right answer happened to have that shape. The rule here is that only
 * the TARGET decides. A change is judged by whether it moved the decode toward or away from what
 * the user actually swiped, never by what kind of word moved.
 */
object RescoringMetrics {

    /** What rescoring did to one decode, judged against the target and nothing else. */
    enum class Outcome {
        /** Rescoring made a wrong top-1 right. The win. */
        FIXED,

        /** Rescoring made a RIGHT top-1 wrong. The promotion error — the cost that gates shipping. */
        BROKEN,

        /** Top-1 is the same word either way. The overwhelming majority of traces. */
        UNCHANGED,

        /** Top-1 changed but was wrong both times. Neither a win nor a cost. */
        WASH,
    }

    /**
     * Classify one decode.
     *
     * Comparison is case-insensitive because the slate carries display forms (`"I'm"`, `"Café"`)
     * while corpus targets are typically lowercased — a case-only difference is not a decode
     * error and must not be counted as one in either direction.
     *
     * @param target the word the user actually swiped.
     * @param engineTop1 top-1 without rescoring; null when the engine returned nothing.
     * @param rescoredTop1 top-1 with rescoring applied.
     */
    fun classify(target: String, engineTop1: String?, rescoredTop1: String?): Outcome {
        if (engineTop1.equals(rescoredTop1, ignoreCase = true)) return Outcome.UNCHANGED
        val engineRight = engineTop1.equals(target, ignoreCase = true)
        val rescoredRight = rescoredTop1.equals(target, ignoreCase = true)
        return when {
            rescoredRight && !engineRight -> Outcome.FIXED
            engineRight && !rescoredRight -> Outcome.BROKEN
            else -> Outcome.WASH
        }
    }

    /**
     * Running counts over a replay, plus the two numbers §7.3 gates on.
     *
     * Deliberately holds raw counts rather than only ratios: a ratio computed from four decodes
     * reads identically to one computed from forty thousand, and the harness must be able to say
     * which it is.
     */
    data class Tally(
        var fixed: Int = 0,
        var broken: Int = 0,
        var unchanged: Int = 0,
        var wash: Int = 0,
    ) {
        val total: Int get() = fixed + broken + unchanged + wash

        fun record(outcome: Outcome) {
            when (outcome) {
                Outcome.FIXED -> fixed++
                Outcome.BROKEN -> broken++
                Outcome.UNCHANGED -> unchanged++
                Outcome.WASH -> wash++
            }
        }

        /**
         * Net change in top-1 accuracy, as a fraction of all traces.
         *
         * Positive means rescoring helped overall. This alone is NOT sufficient to ship — see
         * [promotionErrorRatio].
         */
        val deltaTop1: Double
            get() = if (total == 0) 0.0 else (fixed - broken).toDouble() / total

        /**
         * Breakages per fix. **This is the number that gates the default flip**, not the delta.
         *
         * §7.3's proposed bar is < 0.20 — fewer than one newly-broken decode per five fixed.
         * Returns `Double.POSITIVE_INFINITY` when the feature broke decodes and fixed none, which
         * is the correct reading of "infinitely bad trade" and sorts correctly against any bar.
         */
        val promotionErrorRatio: Double
            get() = when {
                broken == 0 -> 0.0
                fixed == 0 -> Double.POSITIVE_INFINITY
                else -> broken.toDouble() / fixed
            }

        /** Does this replay clear §7.3's bar? Both conditions, not either. */
        fun meetsShipBar(maxErrorRatio: Double = SHIP_BAR_ERROR_RATIO): Boolean =
            deltaTop1 > 0.0 && promotionErrorRatio < maxErrorRatio

        override fun toString(): String =
            "n=$total fixed=$fixed broken=$broken wash=$wash unchanged=$unchanged " +
                "Δtop1=%+.4f errRatio=%.3f".format(deltaTop1, promotionErrorRatio)
    }

    /** §7.3's proposed threshold: breakages must stay under 20% of fixes. */
    const val SHIP_BAR_ERROR_RATIO = 0.20
}
