package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Pure-JVM tests for [SelectionHistory] — the extracted core of
 * `UserAdaptationManager` (M7, review 2026-08-06):
 *
 * - **Bounded retention**: capacity pruning queues the pruned words as
 *   REMOVALS that [SelectionHistory.snapshotForPersist] drains, so the Android
 *   wrapper deletes their `word_selections_<word>` preference keys instead of
 *   letting every word ever selected persist forever and resurrect on load.
 * - **Concurrency**: recording and multiplier reads are safe across threads
 *   (ConcurrentHashMap + atomic merge — the previous plain map was written on
 *   the main thread and read on the prediction executor).
 * - **H3 read gate**: disabled ⇒ the multiplier read is inert (1.0), mirroring
 *   the write-side no-op; `WordPredictor.setConfig` keeps the flag synced to
 *   the master `on_device_learning_enabled` gate.
 */
class SelectionHistoryTest {

    // ------------------------------------------------------- multiplier math

    @Test
    fun `multiplier is neutral below the activation floor and boosts above it`() {
        val h = SelectionHistory()
        repeat(4) { h.recordSelection("hello") }
        assertEquals(1.0f, h.multiplierFor("hello"), 0f) // total 4 < floor 5

        h.recordSelection("hello") // total 5
        // relativeFreq 1.0 * 0.3 * 10 = 3.0 → capped at 2.0
        assertEquals(2.0f, h.multiplierFor("hello"), 1e-6f)
        assertEquals(1.0f, h.multiplierFor("unknown"), 0f)
    }

    @Test
    fun `multiplier normalizes case and whitespace like recording does`() {
        val h = SelectionHistory()
        repeat(6) { h.recordSelection("  Boston ") }
        assertTrue(h.multiplierFor("boston") > 1.0f)
        assertEquals(6, h.selectionCount("BOSTON"))
    }

    // -------------------------------------------------------- H3 read gating

    @Test
    fun `H3 - disabled history is inert for BOTH writes and reads`() {
        val h = SelectionHistory()
        repeat(10) { h.recordSelection("tracked") }
        assertTrue(h.multiplierFor("tracked") > 1.0f)

        h.enabled = false
        // Read inert: learned history must not re-rank with the master off.
        assertEquals(1.0f, h.multiplierFor("tracked"), 0f)
        // Write inert: nothing records while disabled.
        assertFalse(h.recordSelection("tracked"))
        assertEquals(10, h.totalSelections())

        // Re-enabling restores the (unchanged) learned state.
        h.enabled = true
        assertTrue(h.multiplierFor("tracked") > 1.0f)
    }

    // -------------------------------------------------- M7 bounded retention

    @Test
    fun `M7 - pruning queues removals and snapshot drains them`() {
        val h = SelectionHistory(maxTrackedWords = 10)
        // "popular" is selected often; the filler words once each.
        repeat(5) { h.recordSelection("popular") }
        (0 until 10).forEach { h.recordSelection("filler$it") } // 11th word triggers prune

        assertTrue("prune must have run", h.trackedWordCount() <= 10)
        val snapshot = h.snapshotForPersist()
        assertTrue("pruned words must be reported for key deletion", snapshot.removals.isNotEmpty())
        // The frequently selected word survives the prune.
        assertTrue("popular" in snapshot.counts)
        assertFalse("popular" in snapshot.removals)
        // Removals and surviving counts are disjoint.
        assertTrue(snapshot.removals.none { it in snapshot.counts })

        // Drained: a second snapshot reports no stale removals.
        assertTrue(h.snapshotForPersist().removals.isEmpty())
    }

    @Test
    fun `M7 - recordSelection requests an immediate save when a prune ran`() {
        val h = SelectionHistory(maxTrackedWords = 5)
        var saveRequested = false
        (0 until 6).forEach { i ->
            if (h.recordSelection("w$i") && h.snapshotForPersist().removals.isNotEmpty()) {
                saveRequested = true
            }
        }
        assertTrue("prune must trigger a save so pruned keys get deleted promptly", saveRequested)
    }

    @Test
    fun `M7 - save cadence still fires every 10 selections without a prune`() {
        val h = SelectionHistory()
        val saves = (1..20).count { h.recordSelection("word") }
        assertEquals(2, saves) // at totals 10 and 20
    }

    @Test
    fun `reset clears counts, totals, and pending removals`() {
        val h = SelectionHistory(maxTrackedWords = 5)
        (0 until 7).forEach { h.recordSelection("w$it") }
        h.reset()
        assertEquals(0, h.totalSelections())
        assertEquals(0, h.trackedWordCount())
        val snap = h.snapshotForPersist()
        assertTrue(snap.counts.isEmpty())
        assertTrue(snap.removals.isEmpty())
    }

    @Test
    fun `load replaces state and clears pending removals`() {
        val h = SelectionHistory(maxTrackedWords = 5)
        (0 until 7).forEach { h.recordSelection("w$it") } // creates pending removals
        h.load(mapOf("alpha" to 3, "beta" to 2), 5)

        assertEquals(3, h.selectionCount("alpha"))
        assertEquals(5, h.totalSelections())
        assertTrue(h.snapshotForPersist().removals.isEmpty())
    }

    // ---------------------------------------------------- M7 concurrency

    @Test
    fun `M7 - concurrent recording from multiple threads loses no counts`() {
        val h = SelectionHistory(maxTrackedWords = 10_000)
        val threads = 4
        val perThread = 500
        val start = CountDownLatch(1)

        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                repeat(perThread) { i ->
                    h.recordSelection("shared")
                    h.recordSelection("t$t-w${i % 50}")
                    // Concurrent multiplier reads must never throw or corrupt.
                    h.multiplierFor("shared")
                }
            }
        }
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertEquals(threads * perThread, h.selectionCount("shared"))
        assertEquals(threads * perThread * 2, h.totalSelections())
    }
}
