package tribixbite.cleverkeys.contextaware

import kotlin.math.pow

/**
 * Per-context retention policy shared by [BigramStore] (context = previous word) and
 * [TrigramStore] (context = two-word prefix). Pure JVM; unit-tested in
 * `ContinuationBudgetTest`.
 *
 * ## The defect this replaces (W3, learning-system audit 2026-09-26)
 *
 * Both stores used to cap each context with "sort by probability, truncate to N". Once a
 * context held N continuations, a NEW one entered at frequency 1, sorted last (it has the
 * smallest probability of any sibling, and among equal-frequency siblings the stable sort
 * put the most recently appended entry last) and was truncated in the SAME call. It could
 * therefore never reach the query floor ([BigramStore.DEFAULT_MIN_FREQUENCY] = 2): a busy
 * context word like "the" stopped learning new continuations forever.
 *
 * ## The policy
 *
 * A context may hold [Limits.establishedCap] + [Limits.graceSlots] entries. An entry is
 * *sub-floor* while its frequency is below [Limits.promotionFrequency] (it cannot be served
 * yet) and *established* once it reaches it. On overflow exactly one victim is chosen, and
 * the entry recorded in the current call is NEVER the victim:
 *
 * On bulk paths (backup import — no entry recorded "now") the grace reservation is moot and
 * every entry simply competes on [agedScore]. On the live recording path:
 *
 * 1. If sub-floor entries outnumber [Limits.graceSlots], the STALEST sub-floor entry goes.
 *    Newcomers compete only with each other for the grace slots, so two new continuations
 *    typed alternately both keep their first observation long enough to be seen again.
 * 2. Otherwise the ESTABLISHED entry with the lowest [agedScore] goes. Because sub-floor
 *    entries can occupy at most [Limits.graceSlots] slots, at least
 *    [Limits.establishedCap] established entries always survive — newcomers only ever
 *    displace the context's weakest established continuations, never its served top-N.
 *
 * ## Aging without a wall clock
 *
 * Each entry records `lastSeen`: its context's observation total at the moment the entry
 * was last observed. `contextTotal - lastSeen` is how many OTHER observations of the same
 * context happened since — a staleness measured in the user's own typing, so it neither
 * decays while the phone sits idle nor needs a timestamp per entry. [agedScore] halves an
 * entry's frequency every [Limits.halfLifeObservations] of staleness, so a continuation that
 * was frequent once but is no longer used eventually yields its slot to a fresh one, while a
 * frequent AND recent continuation keeps it. (Serving order is unaffected — it stays by
 * conditional probability; aging only decides who is evicted.)
 */
internal object ContinuationBudget {

    /**
     * Default aging half-life, in observations of the same context. 100 means a
     * continuation last typed 100 context-observations ago counts half as much as a fresh
     * one of the same frequency: long enough that a context's regular continuations never
     * age out between uses, short enough that a dead one yields within a few hundred
     * further uses of the context.
     */
    const val DEFAULT_HALF_LIFE_OBSERVATIONS = 100

    /**
     * @property establishedCap entries guaranteed to survive among established ones
     * @property graceSlots extra slots newcomers can occupy while proving themselves
     * @property promotionFrequency frequency at which an entry counts as established; the
     *   stores pass their query floor so "established" == "servable"
     * @property halfLifeObservations staleness (in context observations) that halves an
     *   entry's eviction score
     */
    data class Limits(
        val establishedCap: Int,
        val graceSlots: Int,
        val promotionFrequency: Int,
        val halfLifeObservations: Int
    ) {
        init {
            require(establishedCap >= 1) { "establishedCap must be >= 1" }
            require(graceSlots >= 1) { "graceSlots must be >= 1" }
            require(promotionFrequency >= 1) { "promotionFrequency must be >= 1" }
            require(halfLifeObservations >= 1) { "halfLifeObservations must be >= 1" }
        }

        /** Hard per-context bound on retained entries. */
        val maxEntries: Int get() = establishedCap + graceSlots
    }

    /**
     * Eviction score: frequency discounted by staleness (half-life in context observations).
     * A negative staleness (a total reduced by rollback/removal after the entry was seen) is
     * treated as fresh.
     */
    fun agedScore(frequency: Int, staleness: Int, halfLifeObservations: Int): Double =
        frequency * 0.5.pow(staleness.coerceAtLeast(0).toDouble() / halfLifeObservations)

    /**
     * Shrink [entries] in place to [Limits.maxEntries] using the policy in the class doc.
     *
     * @param keep the entry recorded in the current call (never evicted), or null for
     *   bulk paths such as backup import
     * @param frequencyOf / [lastSeenOf] accessors for the entry type
     * @param contextTotal the context's current observation total (staleness reference)
     * @return the evicted entries (empty when within budget)
     */
    fun <T> enforce(
        entries: MutableList<T>,
        keep: T?,
        contextTotal: Int,
        limits: Limits,
        frequencyOf: (T) -> Int,
        lastSeenOf: (T) -> Int
    ): List<T> {
        val evicted = ArrayList<T>()
        while (entries.size > limits.maxEntries) {
            val victimIndex = pickVictim(entries, keep, contextTotal, limits, frequencyOf, lastSeenOf)
            if (victimIndex < 0) break // only the kept entry is left to choose — cannot happen with graceSlots >= 1
            evicted.add(entries.removeAt(victimIndex))
        }
        return evicted
    }

    private fun <T> pickVictim(
        entries: List<T>,
        keep: T?,
        contextTotal: Int,
        limits: Limits,
        frequencyOf: (T) -> Int,
        lastSeenOf: (T) -> Int
    ): Int {
        // Bulk paths (backup import, keep == null) have no newcomer to shelter: every entry
        // competes on the aged score, so a freq-1 import never holds a slot over a freq-13 one.
        val bulk = keep == null
        val subFloorCount = entries.count { frequencyOf(it) < limits.promotionFrequency }
        val evictSubFloor = subFloorCount > limits.graceSlots

        var bestIndex = -1
        var bestScore = Double.MAX_VALUE
        var bestLastSeen = Int.MAX_VALUE
        for ((index, entry) in entries.withIndex()) {
            if (keep != null && entry === keep) continue
            val isSubFloor = frequencyOf(entry) < limits.promotionFrequency
            if (!bulk && isSubFloor != evictSubFloor) continue

            // Sub-floor entries are all "unproven": staleness alone decides (the score is
            // constant so the lastSeen tie-break below picks the stalest). Established
            // entries — and every entry on a bulk path — compete on the aged score,
            // stalest first on ties.
            val score = if (!bulk && evictSubFloor) 0.0 else agedScore(
                frequencyOf(entry),
                contextTotal - lastSeenOf(entry),
                limits.halfLifeObservations
            )
            val seen = lastSeenOf(entry)
            if (score < bestScore || (score == bestScore && seen < bestLastSeen)) {
                bestIndex = index
                bestScore = score
                bestLastSeen = seen
            }
        }
        return bestIndex
    }
}
