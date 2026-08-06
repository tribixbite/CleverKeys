package tribixbite.cleverkeys.persist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM tests for [DebouncedPersister] — the dirty-flag + debounced
 * write-back substrate shared by BigramStore and UserVocabulary
 * (audit 2026-08-06 §1.3).
 */
class DebouncedPersisterTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun `flush is a no-op when clean`() {
        val flushes = AtomicInteger(0)
        val p = DebouncedPersister(50, 500, scheduler) { flushes.incrementAndGet() }

        p.flush()
        p.flush()

        assertEquals(0, flushes.get())
        assertFalse(p.isDirty())
    }

    @Test
    fun `markDirty schedules exactly one debounced flush for a burst`() {
        val flushes = AtomicInteger(0)
        val p = DebouncedPersister(80, 2000, scheduler) { flushes.incrementAndGet() }

        // Burst of marks well inside the debounce window
        repeat(20) {
            p.markDirty()
            Thread.sleep(2)
        }
        assertTrue(p.isDirty())
        assertEquals("no flush should fire inside the debounce window", 0, flushes.get())

        waitUntil { flushes.get() == 1 }
        assertFalse(p.isDirty())

        // Quiescent afterwards — no spurious extra flushes
        Thread.sleep(200)
        assertEquals(1, flushes.get())
    }

    @Test
    fun `continuous marking still checkpoints via the max-delay cap`() {
        val flushes = AtomicInteger(0)
        // debounce 100ms, cap 300ms: marking every 30ms would push the deadline
        // forever without the cap.
        val p = DebouncedPersister(100, 300, scheduler) { flushes.incrementAndGet() }

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 700 && flushes.get() == 0) {
            p.markDirty()
            Thread.sleep(30)
        }
        assertTrue("cap must force a checkpoint despite continuous marking", flushes.get() >= 1)
    }

    @Test
    fun `explicit flush runs synchronously and clears dirty`() {
        val flushes = AtomicInteger(0)
        val p = DebouncedPersister(10_000, 60_000, scheduler) { flushes.incrementAndGet() }

        p.markDirty()
        assertTrue(p.isDirty())
        p.flush()
        assertEquals(1, flushes.get())
        assertFalse(p.isDirty())

        // Scheduled task (if any survives) must not double-flush a clean store
        Thread.sleep(100)
        assertEquals(1, flushes.get())
    }

    @Test
    fun `requestFlush flushes asynchronously`() {
        val flushes = AtomicInteger(0)
        val p = DebouncedPersister(10_000, 60_000, scheduler) { flushes.incrementAndGet() }

        p.markDirty()
        p.requestFlush()
        waitUntil { flushes.get() == 1 }
        assertFalse(p.isDirty())
    }

    @Test
    fun `failed flush restores dirty flag for retry`() {
        val attempts = AtomicInteger(0)
        val p = DebouncedPersister(10_000, 60_000, scheduler) {
            if (attempts.incrementAndGet() == 1) throw RuntimeException("disk full")
        }

        p.markDirty()
        p.flush() // first attempt throws
        assertEquals(1, attempts.get())
        assertTrue("dirty must be restored after a failed flush", p.isDirty())

        p.flush() // retry succeeds
        assertEquals(2, attempts.get())
        assertFalse(p.isDirty())
    }

    @Test
    fun `marks after a flush schedule a new flush`() {
        val flushes = AtomicInteger(0)
        val p = DebouncedPersister(60, 2000, scheduler) { flushes.incrementAndGet() }

        p.markDirty()
        waitUntil { flushes.get() == 1 }

        p.markDirty()
        waitUntil { flushes.get() == 2 }
        assertFalse(p.isDirty())
    }
}
