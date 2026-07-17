package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-JVM tests for [PredictionTaskRunner], the executor wrapper that owns the single-threaded
 * prediction executor and its in-flight task. Verifies shutdown interrupts running work, cancels
 * previous tasks on resubmit, and silently drops submissions after shutdown.
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
