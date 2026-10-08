package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.SwipePriority
import tribixbite.cleverkeys.swipe.ctc.CtcPriorityBonus
import java.util.Locale

/**
 * What a [SwipePriority] is worth to each swipe engine, and the one place both adapters turn
 * the stored per-word levels into engine inputs. Pure JVM.
 *
 * ## The levels (measured, `docs/eval/2026-10-08-user-swipe-priority.md`)
 *
 * CTC bonuses are final-score nats added to [tribixbite.cleverkeys.swipe.ctc.CtcBeamDecoder]'s
 * `ctc/len^γ + β·len + λ·ln f` for words already in the final beam. Geometric bonuses are in the
 * engine's `S(w)` units, added after pruning. The CTC values were chosen on the shipped-stack
 * replay (the targets' measured deficits); the geometric values equal them because both
 * engines map scores to the bar through a temperature-1 softmax, so an equal bonus is an
 * equal odds multiplier. On the geometric engine the reported words already win as plain
 * personal-dictionary words (its prior has no encoder end-of-trace bias), so there a level
 * only adds margin; its measured collateral is in the eval note §6.
 *
 * [SwipePriority.NORMAL] is exactly zero in both engines: a word the user never raised decodes
 * byte-for-byte as before this feature.
 */
object UserSwipePriorityBonus {

    /** CTC final-score bonus for [SwipePriority.HIGH] (nats). */
    const val CTC_HIGH = 2.0

    /** CTC final-score bonus for [SwipePriority.HIGHEST] (nats); ≤ [CtcPriorityBonus.MAX_BONUS]. */
    const val CTC_HIGHEST = 4.0

    /**
     * Geometric `S(w)` bonus for [SwipePriority.HIGH]. Numerically equal to [CTC_HIGH] on
     * purpose: both engines turn their scores into the bar's confidence with a temperature-1
     * softmax, so the same additive bonus multiplies the word's posterior odds by the same
     * factor (e² ≈ 7.4) in either engine — the same user intent. Measured on the local real
     * corpus in the eval note §6.
     */
    const val GEO_HIGH = 2.0f

    /** Geometric `S(w)` bonus for [SwipePriority.HIGHEST] (odds × e⁴ ≈ 55, like [CTC_HIGHEST]). */
    const val GEO_HIGHEST = 4.0f

    /** The CTC bonus for [priority]. */
    fun ctcBonus(priority: SwipePriority): Double = when (priority) {
        SwipePriority.NORMAL -> 0.0
        SwipePriority.HIGH -> CTC_HIGH
        SwipePriority.HIGHEST -> CTC_HIGHEST
    }

    /** The geometric bonus for [priority]. */
    fun geometricBonus(priority: SwipePriority): Float = when (priority) {
        SwipePriority.NORMAL -> 0f
        SwipePriority.HIGH -> GEO_HIGH
        SwipePriority.HIGHEST -> GEO_HIGHEST
    }

    /**
     * The CTC bonus table for one lexicon: every raised USER word ([userWords], the merged
     * custom + platform list the trie was built from) keyed by the surface the trie files it
     * under ([surfaceOf] — the en a–z strip, or a CKDT language's projection), lowercased.
     * Two user words on one surface keep the larger bonus. A word whose surface is null
     * (nothing typeable) is dropped. Empty → [CtcPriorityBonus.NONE].
     */
    fun ctcBySurface(
        userWords: List<Pair<String, Int>>,
        priorities: Map<String, SwipePriority>,
        surfaceOf: (String) -> String?,
    ): CtcPriorityBonus {
        val raised = SwipePriority.forUserWords(priorities, userWords.map { it.first })
        if (raised.isEmpty()) return CtcPriorityBonus.NONE
        val bySurface = HashMap<String, Double>()
        for ((word, level) in raised) {
            val surface = surfaceOf(word)?.lowercase(Locale.ROOT) ?: continue
            if (surface.isEmpty()) continue
            val bonus = ctcBonus(level)
            if (bonus > (bySurface[surface] ?: 0.0)) bySurface[surface] = bonus
        }
        return if (bySurface.isEmpty()) CtcPriorityBonus.NONE else CtcPriorityBonus(bySurface)
    }

    /**
     * The geometric bonus per user word, keyed exactly as listed in [userWords] (what
     * `GeometricUserWordMerge.merge` looks up). Only raised personal-dictionary words appear.
     */
    fun geometricBonusByWord(
        userWords: List<Pair<String, Int>>,
        priorities: Map<String, SwipePriority>,
    ): Map<String, Float> =
        SwipePriority.forUserWords(priorities, userWords.map { it.first })
            .mapValues { (_, level) -> geometricBonus(level) }
            .filterValues { it > 0f }
}
