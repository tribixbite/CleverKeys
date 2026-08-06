package tribixbite.cleverkeys.persist

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Dirty-flag + debounced asynchronous write-back helper for learned-data stores.
 *
 * Design (see docs/audit/2026-08-06-context-lm-nextword-rec.md §1.3):
 * - [markDirty] sets a dirty flag and (re)schedules a flush [debounceMs] in the
 *   future — each new mark within the window pushes the deadline out, so a burst
 *   of records coalesces into a single storage write.
 * - A hard cap [maxDelayMs] (measured from the first unflushed mark) guarantees
 *   a continuous typist still checkpoints: the scheduled delay never extends the
 *   flush beyond `firstDirtyAt + maxDelayMs`.
 * - [flush] is synchronous, idempotent, and safe from any thread; it no-ops when
 *   clean, so lifecycle call sites can invoke it unconditionally.
 * - [requestFlush] schedules an immediate asynchronous flush on the scheduler
 *   thread (for lifecycle sites on the main thread that must not serialize inline).
 *
 * If the flush action throws, the dirty flag is restored so data is retried on
 * the next mark/flush rather than silently dropped.
 */
class DebouncedPersister(
    private val debounceMs: Long,
    private val maxDelayMs: Long,
    private val scheduler: ScheduledExecutorService,
    private val flushAction: () -> Unit
) {
    companion object {
        /** Default debounce window between last record and write-back. */
        const val DEFAULT_DEBOUNCE_MS = 5_000L

        /** Default hard cap so continuous activity still checkpoints. */
        const val DEFAULT_MAX_DELAY_MS = 30_000L

        // Shared daemon scheduler for production stores. Daemon threads so the
        // pure-JVM test runner (JUnitCore main) can exit normally.
        @Volatile
        private var shared: ScheduledExecutorService? = null

        /** Process-wide shared scheduler for learned-data persistence. */
        fun sharedScheduler(): ScheduledExecutorService {
            return shared ?: synchronized(this) {
                shared ?: Executors.newSingleThreadScheduledExecutor(
                    ThreadFactory { r ->
                        Thread(r, "LearnedDataPersist").apply { isDaemon = true }
                    }
                ).also { shared = it }
            }
        }
    }

    private val lock = Any()

    // Serializes flushAction executions so a SYNCHRONOUS flush() that loses the
    // dirty-flag claim to an in-flight async flush still BLOCKS until that
    // flush's write completes (2026-08-06: requestFlush-after-user-delete raced
    // a subsequent flush() into returning before data hit storage).
    private val flushExecutionLock = Any()

    @Volatile
    private var dirty = false
    private var firstDirtyAtMs = 0L
    private var pending: ScheduledFuture<*>? = null

    /** @return true if there are unflushed changes. */
    fun isDirty(): Boolean = dirty

    /**
     * Mark the underlying store dirty and (re)schedule a debounced flush.
     * Cheap; safe to call per record on the hot path.
     */
    fun markDirty(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (!dirty) {
                dirty = true
                firstDirtyAtMs = nowMs
            }
            val elapsed = nowMs - firstDirtyAtMs
            val delay = minOf(debounceMs, maxOf(0L, maxDelayMs - elapsed))
            pending?.cancel(false)
            pending = try {
                scheduler.schedule({ flush() }, delay, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                null // Scheduler shut down (teardown race) — data flushes via explicit flush()
            }
        }
    }

    /** Schedule an immediate asynchronous flush (no-op when clean). */
    fun requestFlush() {
        if (!dirty) return
        try {
            scheduler.execute { flush() }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            flush() // Scheduler gone — flush inline rather than lose data
        }
    }

    /**
     * Flush now, on the calling thread. Idempotent; no-op when clean.
     * On failure the dirty flag is restored for retry.
     *
     * Returns only after any concurrently-running flush has finished its write
     * ([flushExecutionLock]) — callers rely on "flush() returned ⇒ previously
     * dirty data is in storage".
     */
    fun flush() {
        synchronized(flushExecutionLock) {
            val shouldRun = synchronized(lock) {
                pending?.cancel(false)
                pending = null
                if (dirty) {
                    dirty = false
                    true
                } else {
                    false
                }
            }
            if (!shouldRun) return
            try {
                flushAction()
            } catch (e: Exception) {
                synchronized(lock) {
                    if (!dirty) {
                        dirty = true
                        firstDirtyAtMs = System.currentTimeMillis()
                    }
                }
            }
        }
    }
}
