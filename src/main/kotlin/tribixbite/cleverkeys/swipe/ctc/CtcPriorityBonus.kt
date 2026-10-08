package tribixbite.cleverkeys.swipe.ctc

/**
 * The per-word **swipe priority** the user set in the Dictionary Manager, as the CTC decoder
 * sees it: decoded surface → additive final-score bonus (nats). See
 * `tribixbite.cleverkeys.swipe.UserSwipePriorityBonus` for the levels and
 * `docs/eval/2026-10-08-user-swipe-priority.md` for the measurement that chose them.
 *
 * ## Why a bonus beyond the frequency cap
 *
 * A personal-dictionary word already enters the lexicon at the top of the frequency scale
 * (255, [CtcLexiconMerge]). For the words this feature exists for, that is not enough: the
 * encoder emits nothing for a straight-through interior letter and reads the end of a short
 * trace through its own word prior (`adb` → `an`, `ad` → `as`, `wet` → `we`; 2026-10-07 notes),
 * so the target trails by 1–4 final-score points that no frequency inside the scale can
 * close. The user opting a word in is the evidence the static scale lacks.
 *
 * ## Why it cannot make a word appear on an unrelated swipe
 *
 * The bonus is added to [CtcBeamDecoder]'s FINAL score only — the same seam the learned prior
 * uses. The per-frame prune key carries no frequency and no bonus, so the beam that survives
 * is exactly the beam without priorities, and a boosted word can only be re-ranked when the
 * trace's own emissions kept its prefix alive to the last frame. A word far from the trace is
 * never in that beam and is untouched however high its priority.
 *
 * Every bonus is clamped to `[0, MAX_BONUS]`, so a corrupted or hand-edited store cannot
 * produce a negative (demoting) or unbounded value. Pure JVM.
 *
 * @property bySurface lowercase decoded surface → bonus in final-score nats.
 */
class CtcPriorityBonus(bySurface: Map<String, Double>) {

    private val bonuses: Map<String, Double> = bySurface
        .mapValues { (_, b) -> if (b.isNaN()) 0.0 else b.coerceIn(0.0, MAX_BONUS) }
        .filterValues { it > 0.0 }

    /** True when no surface carries a bonus (the decoder then skips the lookup). */
    val isEmpty: Boolean get() = bonuses.isEmpty()

    /** The bonus for decoded surface [word] (exact key, as the trie spells it); 0 when none. */
    fun bonusFor(word: String): Double = bonuses[word] ?: 0.0

    /** Number of surfaces carrying a bonus (diagnostics and tests). */
    val size: Int get() = bonuses.size

    companion object {
        /**
         * Hard ceiling on any one bonus, in final-score nats. Equal to the highest level the
         * eval note measured ([tribixbite.cleverkeys.swipe.UserSwipePriorityBonus.CTC_HIGHEST]
         * is at or below it); a stored value above it is treated as this.
         */
        const val MAX_BONUS = 4.0

        /** No priorities: [CtcBeamDecoder] skips every lookup, so decoding is byte-identical. */
        @JvmField
        val NONE: CtcPriorityBonus = CtcPriorityBonus(emptyMap())
    }
}
