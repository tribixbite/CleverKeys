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
    fun `M7 - a prune's removals reach the next persist snapshot`() {
        val h = SelectionHistory(maxTrackedWords = 5)
        (0 until 6).forEach { i -> assertTrue(h.recordSelection("w$i")) }
        assertTrue("pruned keys must be deleted at the next write-back",
            h.snapshotForPersist().removals.isNotEmpty())
    }

    @Test
    fun `W6 - every recorded selection asks the wrapper to persist`() {
        // Was: only every 10th selection returned true, so up to 9 died with the process.
        val h = SelectionHistory()
        assertEquals(20, (1..20).count { h.recordSelection("word") })
    }

    // ------------------------------------------------------------ W4 decay

    @Test
    fun `W4 - decay halves counts and total, dropping words that reach zero`() {
        val h = SelectionHistory()
        h.load(mapOf("git" to 9, "got" to 1), 10)

        assertTrue(h.decay(1))

        assertEquals(4, h.selectionCount("git"))
        assertEquals(0, h.selectionCount("got"))
        assertEquals(5, h.totalSelections())
        assertEquals(setOf("got"), h.snapshotForPersist().removals)
    }

    @Test
    fun `W4 - decay compounds and zero halvings is a no-op`() {
        val h = SelectionHistory()
        h.load(mapOf("git" to 16), 16)
        assertFalse(h.decay(0))
        assertTrue(h.decay(3))
        assertEquals(2, h.selectionCount("git"))
        assertEquals(2, h.totalSelections())
    }

    @Test
    fun `W4 - halvings due counts whole periods and ignores a backwards clock`() {
        val p = SelectionHistory.DECAY_HALF_LIFE_MS
        assertEquals(0, SelectionHistory.decayHalvingsDue(1_000L, 1_000L + p - 1))
        assertEquals(1, SelectionHistory.decayHalvingsDue(1_000L, 1_000L + p))
        assertEquals(2, SelectionHistory.decayHalvingsDue(1_000L, 1_000L + 2 * p + 5))
        assertEquals(0, SelectionHistory.decayHalvingsDue(1_000L, 500L))
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
