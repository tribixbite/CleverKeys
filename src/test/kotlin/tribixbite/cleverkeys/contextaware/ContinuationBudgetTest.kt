package tribixbite.cleverkeys.contextaware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit contract of [ContinuationBudget] — the per-context retention policy the n-gram
 * stores share (W3, learning-system audit 2026-09-26). Store-level behaviour is pinned by
 * `NgramContinuationLearnabilityTest`; this pins the policy's rules in isolation.
 */
class ContinuationBudgetTest {

    private data class E(val name: String, val frequency: Int, val lastSeen: Int)

    private val limits = ContinuationBudget.Limits(
        establishedCap = 3, graceSlots = 2, promotionFrequency = 2, halfLifeObservations = 10
    )

    private fun enforce(entries: MutableList<E>, keep: E?, total: Int) =
        ContinuationBudget.enforce(entries, keep, total, limits, { it.frequency }, { it.lastSeen })

    @Test
    fun `within budget nothing is evicted`() {
        val list = mutableListOf(E("a", 5, 1), E("b", 1, 2), E("c", 1, 3))
        assertTrue(enforce(list, null, 10).isEmpty())
        assertEquals(3, list.size)
    }

    @Test
    fun `the kept entry is never the victim even when it is the weakest`() {
        val newcomer = E("new", 1, 20)
        val list = mutableListOf(E("a", 9, 19), E("b", 9, 19), E("c", 9, 19), E("d", 9, 19), E("e", 9, 19), newcomer)
        val evicted = enforce(list, newcomer, 20)
        assertEquals(1, evicted.size)
        assertTrue(newcomer in list)
    }

    @Test
    fun `sub-floor entries beyond the grace slots evict the stalest newcomer`() {
        val keep = E("n3", 1, 30)
        val list = mutableListOf(E("a", 5, 29), E("b", 5, 29), E("c", 5, 29), E("n1", 1, 10), E("n2", 1, 20), keep)
        val evicted = enforce(list, keep, 30)
        assertEquals(listOf("n1"), evicted.map { it.name })
    }

    @Test
    fun `within the grace slots the weakest aged established entry is evicted`() {
        val keep = E("n2", 1, 30)
        // "stale" has the higher raw frequency but 30 observations of staleness (3 half-lives):
        // 6 * 0.125 = 0.75 < "fresh"'s 2.
        val list = mutableListOf(E("stale", 6, 0), E("fresh", 2, 30), E("x", 4, 30), E("y", 4, 30), E("n1", 1, 29), keep)
        val evicted = enforce(list, keep, 30)
        assertEquals(listOf("stale"), evicted.map { it.name })
    }

    @Test
    fun `among equal frequencies the least recently seen is evicted`() {
        val keep = E("new", 1, 50)
        val list = mutableListOf(E("a", 3, 50), E("b", 3, 50), E("older", 3, 49), E("c", 3, 50), E("d", 3, 50), keep)

        val evicted = enforce(list, keep, 50)
        assertEquals(listOf("older"), evicted.map { it.name })
    }

    @Test
    fun `bulk paths without a kept entry evict purely by aged score`() {
        // An import: the freq-1 entry must not hold a grace slot over stronger entries.
        val list = mutableListOf(E("a", 9, 10), E("b", 8, 10), E("c", 7, 10), E("d", 6, 10), E("e", 5, 10), E("one", 1, 10))
        val evicted = enforce(list, null, 10)
        assertEquals(listOf("one"), evicted.map { it.name })
    }

    @Test
    fun `aged score halves per half-life and treats negative staleness as fresh`() {
        assertEquals(8.0, ContinuationBudget.agedScore(8, 0, 10), 1e-9)
        assertEquals(4.0, ContinuationBudget.agedScore(8, 10, 10), 1e-9)
        assertEquals(8.0, ContinuationBudget.agedScore(8, -5, 10), 1e-9)
    }

    // ── Language-wide prune ([ContinuationBudget.globalVictims]) ─────────────────────────

    private data class G(val name: String, val frequency: Int, val tick: Long, val p: Float = 0.5f)

    private fun victims(entries: List<G>, count: Int, clock: Long, kept: String? = null) =
        ContinuationBudget.globalVictims(
            entries.asSequence(), count, clock, halfLifeCommits = 100,
            isKept = { it.name == kept }, frequencyOf = { it.frequency },
            globalSeenOf = { it.tick }, tieBreak = { it.p }
        ).map { it.name }

    @Test
    fun `global victims are the lowest frequency discounted by global age`() {
        // stale: 4 * 0.5^(300/100) = 0.5; newcomer: 1 * 0.5^0 = 1.0; busy: 9 * 0.5^0.1 ≈ 8.4.
        val entries = listOf(G("busy", 9, 990), G("newcomer", 1, 1000), G("stale", 4, 700))
        assertEquals(listOf("stale"), victims(entries, 1, clock = 1000))
        assertEquals(listOf("stale", "newcomer"), victims(entries, 2, clock = 1000))
    }

    @Test
    fun `global victims never include the kept entry and tie-break on age then probability`() {
        val entries = listOf(G("a", 2, 50, 0.9f), G("b", 2, 50, 0.1f), G("older", 2, 40, 0.9f), G("keep", 1, 10))
        // "keep" is the weakest by far but is sheltered. Scores: older 2*0.5^0.6 ≈ 1.32 < a = b = 2*0.5^0.5 ≈ 1.41, so older goes first; a/b
        // tie on score and tick, so the lower probability (b) goes next.
        assertEquals(listOf("older", "b"), victims(entries, 2, clock = 100, kept = "keep"))
        assertTrue(victims(entries, 0, clock = 100).isEmpty())
    }
}
