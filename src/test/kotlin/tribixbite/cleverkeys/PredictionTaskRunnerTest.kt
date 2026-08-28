package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-JVM tests for [PredictionTaskRunner], the executor wrapper that owns the single-threaded
 * prediction executor and its in-flight tasks. Verifies shutdown interrupts running work, cancels
 * previous tasks on resubmit, and silently drops submissions after shutdown.
 *
 * The `background*` tests pin the WP9 audit M-2 fix: a prewarm (background slot) must never
 * cancel an in-flight swipe decode (foreground slot), while a decode still supersedes both an
 * older decode and an in-flight prewarm. See `docs/history/audits/remediation/3-core-ime.md` M-2.
 */
class PredictionTaskRunnerTest {

    @Test
    fun shutdownMarksExecutorShutdown() {
        val runner = PredictionTaskRunner()
        assertThat(runner.isShutdown).isFalse()

        runner.shutdown()

        assertThat(runner.isShutdown).isTrue()
    }

    @Test
    fun shutdownInterruptsRunningTask() {
        val runner = PredictionTaskRunner()
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val finished = CountDownLatch(1)

        runner.cancelAndSubmit {
            started.countDown()
            try {
                // Block until interrupted by shutdownNow().
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                interrupted.set(true)
            } finally {
                finished.countDown()
            }
        }

        // Wait until the task is actually running, then shut down.
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue()
        runner.shutdown()

        // The running task must observe the interrupt and finish within 1s.
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(interrupted.get()).isTrue()
    }

    @Test
    fun cancelAndSubmitCancelsPrevious() {
        val runner = PredictionTaskRunner()
        val firstStarted = CountDownLatch(1)
        val firstInterrupted = AtomicBoolean(false)
        val firstDone = CountDownLatch(1)

        // Submit a long-running first task.
        runner.cancelAndSubmit {
            firstStarted.countDown()
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                firstInterrupted.set(true)
            } finally {
                firstDone.countDown()
            }
        }

        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()

        // Submitting a second task must cancel (interrupt) the first.
        val secondRan = CountDownLatch(1)
        runner.cancelAndSubmit { secondRan.countDown() }

        assertThat(firstDone.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(firstInterrupted.get()).isTrue()
        // Second task still executes on the (single-threaded) executor.
        assertThat(secondRan.await(1, TimeUnit.SECONDS)).isTrue()

        runner.shutdown()
    }

    /**
     * M-2 core pin: a background submission (prewarm) must NOT cancel the running foreground
     * task (swipe decode). Before the two-slot split this was a silent lost swipe.
     */
    @Test
    fun submitBackgroundDoesNotCancelRunningForegroundTask() {
        val runner = PredictionTaskRunner()
        val decodeStarted = CountDownLatch(1)
        val decodeInterrupted = AtomicBoolean(false)
        val decodeCompleted = AtomicBoolean(false)
        val decodeDone = CountDownLatch(1)
        val release = CountDownLatch(1)

        runner.cancelAndSubmit {
            decodeStarted.countDown()
            try {
                // Stand in for the decode's work; released by the test, not by a cancel.
                if (!release.await(5, TimeUnit.SECONDS)) throw IllegalStateException("not released")
                decodeCompleted.set(!Thread.currentThread().isInterrupted)
            } catch (e: InterruptedException) {
                decodeInterrupted.set(true)
            } finally {
                decodeDone.countDown()
            }
        }
        assertThat(decodeStarted.await(1, TimeUnit.SECONDS)).isTrue()

        // Prewarm arrives while the decode runs — it must queue, not cancel.
        val warmRan = CountDownLatch(1)
        runner.submitBackground { warmRan.countDown() }

        release.countDown()
        assertThat(decodeDone.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(decodeInterrupted.get()).isFalse()
        assertThat(decodeCompleted.get()).isTrue()
        // The prewarm still runs afterwards on the single thread.
        assertThat(warmRan.await(2, TimeUnit.SECONDS)).isTrue()

        runner.shutdown()
    }

    /** A newer prewarm supersedes an older prewarm (same slot). */
    @Test
    fun submitBackgroundCancelsPreviousBackgroundTask() {
        val runner = PredictionTaskRunner()
        val firstStarted = CountDownLatch(1)
        val firstInterrupted = AtomicBoolean(false)
        val firstDone = CountDownLatch(1)

        runner.submitBackground {
            firstStarted.countDown()
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                firstInterrupted.set(true)
            } finally {
                firstDone.countDown()
            }
        }
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()

        val secondRan = CountDownLatch(1)
        runner.submitBackground { secondRan.countDown() }

        assertThat(firstDone.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(firstInterrupted.get()).isTrue()
        assertThat(secondRan.await(1, TimeUnit.SECONDS)).isTrue()

        runner.shutdown()
    }

    /** The user's gesture wins the thread: a decode cancels an in-flight prewarm. */
    @Test
    fun cancelAndSubmitCancelsRunningBackgroundTask() {
        val runner = PredictionTaskRunner()
        val warmStarted = CountDownLatch(1)
        val warmInterrupted = AtomicBoolean(false)
        val warmDone = CountDownLatch(1)

        runner.submitBackground {
            warmStarted.countDown()
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                warmInterrupted.set(true)
            } finally {
                warmDone.countDown()
            }
        }
        assertThat(warmStarted.await(1, TimeUnit.SECONDS)).isTrue()

        val decodeRan = CountDownLatch(1)
        runner.cancelAndSubmit { decodeRan.countDown() }

        assertThat(warmDone.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(warmInterrupted.get()).isTrue()
        assertThat(decodeRan.await(1, TimeUnit.SECONDS)).isTrue()

        runner.shutdown()
    }

    /**
     * A cancelled predecessor that never consumes its interrupt must not leak the flag into
     * the next task — tasks treat `isInterrupted` as "I was superseded, drop my result", so a
     * leaked flag is a silently discarded decode.
     */
    @Test
    fun interruptFromCancelledTaskDoesNotLeakIntoNextTask() {
        val runner = PredictionTaskRunner()
        val warmStarted = CountDownLatch(1)
        val warmRelease = CountDownLatch(1)
        val warmDone = CountDownLatch(1)

        // Busy task that IGNORES interrupts entirely (never blocks, never clears the flag).
        runner.submitBackground {
            warmStarted.countDown()
            while (warmRelease.count > 0L) { /* spin until released, ignoring interrupts */ }
            warmDone.countDown()
        }
        assertThat(warmStarted.await(1, TimeUnit.SECONDS)).isTrue()

        val sawInterrupt = AtomicBoolean(true)
        val decodeRan = CountDownLatch(1)
        runner.cancelAndSubmit {
            sawInterrupt.set(Thread.currentThread().isInterrupted)
            decodeRan.countDown()
        }
        // Let the interrupted-but-oblivious predecessor finish so the decode can start.
        warmRelease.countDown()
        assertThat(warmDone.await(2, TimeUnit.SECONDS)).isTrue()

        assertThat(decodeRan.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(sawInterrupt.get()).isFalse()

        runner.shutdown()
    }

    @Test
    fun submitAfterShutdownIsSilentlyDropped() {
        val runner = PredictionTaskRunner()
        runner.shutdown()

        val ran = AtomicBoolean(false)
        // Must not throw RejectedExecutionException; the task is simply dropped.
        runner.cancelAndSubmit { ran.set(true) }

        // Give any (erroneously) scheduled task a brief window; it should never run.
        Thread.sleep(50)
        assertThat(ran.get()).isFalse()
        assertThat(runner.isShutdown).isTrue()
    }
}
