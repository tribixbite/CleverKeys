package tribixbite.cleverkeys

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Pure-JVM core of the selection-adaptation store (M7, review 2026-08-06):
 * word-selection counting, the frequency-based adaptation multiplier, bounded
 * pruning WITH removal tracking, and the persist-snapshot contract.
 *
 * Extracted from [UserAdaptationManager] so the retention and concurrency
 * contracts are unit-testable without Android:
 *
 * - **Bounded retention**: [pruneIfNeeded] evictions are remembered in a
 *   pending-removals set that [snapshotForPersist] drains, so the Android
 *   wrapper can DELETE the pruned `word_selections_<word>` preference keys.
 *   (The previous implementation pruned in RAM only — every word ever selected
 *   persisted forever and resurrected on the next load.)
 * - **Concurrency**: counts live in a [ConcurrentHashMap] with atomic
 *   [ConcurrentHashMap.merge] increments — selections are recorded on the main
 *   thread while the prediction executor reads multipliers concurrently.
 * - **Read gating** (H3): [multiplierFor] is inert (1.0) while [enabled] is
 *   false, mirroring the write-side no-op — the production wrapper keeps
 *   [enabled] synced to the master `on_device_learning_enabled` gate.
 */
class SelectionHistory(
    private val maxTrackedWords: Int = DEFAULT_MAX_TRACKED_WORDS,
    private val minSelectionsForAdaptation: Int = DEFAULT_MIN_SELECTIONS_FOR_ADAPTATION,
    private val adaptationStrength: Float = DEFAULT_ADAPTATION_STRENGTH
) {
    companion object {
        const val DEFAULT_MAX_TRACKED_WORDS = 1000
        const val DEFAULT_MIN_SELECTIONS_FOR_ADAPTATION = 5
        const val DEFAULT_ADAPTATION_STRENGTH = 0.3f

        /** Fraction of [maxTrackedWords] retained after a prune (bottom 20% dropped). */
        const val PRUNE_KEEP_FRACTION = 0.8

        /** Persist cadence: save every N recorded selections. */
        const val SAVE_EVERY_N_SELECTIONS = 10

        /** Maximum multiplier so no single word dominates ranking. */
        const val MAX_MULTIPLIER = 2.0f
    }

    /** word (normalized lowercase) → selection count. Thread-safe. */
    private val selectionCounts = ConcurrentHashMap<String, Int>()

    /** Words pruned from RAM whose persisted keys still need deletion. */
    private val pendingRemovals: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val totalSelections = AtomicInteger(0)

    /** Master-gate mirror: false makes BOTH recording and reads inert. */
    @Volatile
    var enabled: Boolean = true

    /**
     * Replace in-RAM state from persisted data (wrapper's load path).
     * Pending removals are cleared — persisted state is the new baseline.
     */
    fun load(counts: Map<String, Int>, total: Int) {
        selectionCounts.clear()
        pendingRemovals.clear()
        counts.forEach { (word, count) -> if (count > 0) selectionCounts[word] = count }
        totalSelections.set(total.coerceAtLeast(0))
    }

    /**
     * Record one selection of [word].
     *
     * @return true when the caller should persist NOW — every
     *   [SAVE_EVERY_N_SELECTIONS] selections, or immediately after a prune so
     *   the pruned preference keys don't linger unremoved.
     */
    fun recordSelection(word: String?): Boolean {
        if (!enabled || word.isNullOrBlank()) return false

        val normalized = word.lowercase().trim()
        selectionCounts.merge(normalized, 1, Int::plus)
        val total = totalSelections.incrementAndGet()

        val pruned = pruneIfNeeded()
        return pruned || total % SAVE_EVERY_N_SELECTIONS == 0
    }

    /**
     * Adaptation multiplier for [word]: 1.0 (neutral) when disabled, unknown,
     * or below the activation floor; up to [MAX_MULTIPLIER] for frequently
     * selected words. Same formula as the pre-extraction implementation.
     */
    fun multiplierFor(word: String?): Float {
        val total = totalSelections.get()
        if (!enabled || word == null || total < minSelectionsForAdaptation) return 1.0f

        val count = selectionCounts[word.lowercase().trim()] ?: return 1.0f
        if (count == 0) return 1.0f

        val relativeFrequency = count.toFloat() / total
        return min(1.0f + (relativeFrequency * adaptationStrength * 10.0f), MAX_MULTIPLIER)
    }

    /** Selection count for [word] (0 when unknown). */
    fun selectionCount(word: String?): Int {
        if (word == null) return 0
        return selectionCounts[word.lowercase().trim()] ?: 0
    }

    fun totalSelections(): Int = totalSelections.get()

    fun trackedWordCount(): Int = selectionCounts.size

    /** Top [limit] most-selected words (diagnostics). */
    fun topWords(limit: Int): List<Pair<String, Int>> =
        selectionCounts.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }

    /** Wipe everything, including pending removals (wrapper also clears its prefs). */
    fun reset() {
        selectionCounts.clear()
        pendingRemovals.clear()
        totalSelections.set(0)
    }

    /**
     * Consistent snapshot for persistence. DRAINS the pending-removals set: the
     * caller MUST delete each `removals` key from its backing store, otherwise
     * pruned words would persist forever and resurrect on the next load (M7).
     */
    fun snapshotForPersist(): PersistSnapshot {
        val removals = HashSet(pendingRemovals)
        pendingRemovals.removeAll(removals)
        // Counts are copied AFTER draining removals so a word re-selected between
        // the two reads appears in counts (re-written) even if also in removals —
        // wrappers must apply removals BEFORE writes for last-writer-wins safety.
        return PersistSnapshot(HashMap(selectionCounts), removals, totalSelections.get())
    }

    /** @property removals persisted keys the caller must DELETE before writing [counts]. */
    data class PersistSnapshot(
        val counts: Map<String, Int>,
        val removals: Set<String>,
        val total: Int
    )

    /**
     * Drop the least-selected words when tracking exceeds [maxTrackedWords],
     * keeping [PRUNE_KEEP_FRACTION] of capacity. Pruned words are queued for
     * persisted-key deletion.
     *
     * @return true when a prune ran
     */
    private fun pruneIfNeeded(): Boolean {
        if (selectionCounts.size <= maxTrackedWords) return false

        val targetSize = (maxTrackedWords * PRUNE_KEEP_FRACTION).toInt()
        val toRemove = selectionCounts.entries
            .sortedBy { it.value }
            .take((selectionCounts.size - targetSize).coerceAtLeast(0))
            .map { it.key }

        toRemove.forEach { word ->
            selectionCounts.remove(word)
            pendingRemovals.add(word)
        }
        return toRemove.isNotEmpty()
    }
}
