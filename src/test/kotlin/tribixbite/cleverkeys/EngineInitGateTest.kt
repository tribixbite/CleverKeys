package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM tests for [EngineInitGate], the one-shot gate that replaced the neural-engine
 * init busy-wait. Verifies waiters park (not spin), pending actions flush exactly once, and
 * completion/clear semantics — all without touching Android types.
 */
class EngineInitGateTest {

    @Test
    fun awaitReturnsPromptlyWhenSignalled() {
        val gate = EngineInitGate()
        // A background thread completes the attempt shortly after we start waiting.
        Thread {
            Thread.sleep(50)
            gate.markAttemptComplete()
        }.start()

        val start = System.currentTimeMillis()
        val completed = gate.awaitAttempt(2, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - start

        assertThat(completed).isTrue()
        // Must wake promptly on signal, well under the 2s timeout ceiling.
        assertThat(elapsed).isLessThan(2000L)
    }

    @Test
    fun waiterParksNotBusyWaits() {
        val gate = EngineInitGate()
        // The waiter must block (park) until signalled — if it busy-waited it would consume
        // the whole timeout even though completion happens quickly. We assert it returns
        // as soon as the attempt completes.
        val readyToComplete = CountDownLatch(1)
        Thread {
            readyToComplete.await()
            gate.markAttemptComplete()
        }.start()

        // At this point the attempt is NOT complete; a poll must report so.
        assertThat(gate.hasCompletedAttempt).isFalse()

        readyToComplete.countDown()
        val completed = gate.awaitAttempt(2, TimeUnit.SECONDS)

        assertThat(completed).isTrue()
        assertThat(gate.hasCompletedAttempt).isTrue()
    }

    @Test
    fun awaitTimesOutFalse() {
        val gate = EngineInitGate()
        // Nothing ever completes the attempt → await must time out and report false.
        val completed = gate.awaitAttempt(50, TimeUnit.MILLISECONDS)
        assertThat(completed).isFalse()
        assertThat(gate.hasCompletedAttempt).isFalse()
    }

    @Test
    fun pendingActionRunsOnCompletion() {
        val gate = EngineInitGate()
        val ran = AtomicInteger(0)
        gate.setPending { ran.incrementAndGet() }

        // Not complete yet → action must NOT have run.
        assertThat(ran.get()).isEqualTo(0)

        gate.markAttemptComplete()
        assertThat(ran.get()).isEqualTo(1)
    }

    @Test
    fun pendingRunsImmediatelyIfAlreadyComplete() {
        val gate = EngineInitGate()
        gate.markAttemptComplete()

        val ran = AtomicInteger(0)
        // Registered after completion → must fire synchronously.
        gate.setPending { ran.incrementAndGet() }

        assertThat(ran.get()).isEqualTo(1)
    }

    @Test
    fun latestPendingWins() {
        val gate = EngineInitGate()
        val first = AtomicInteger(0)
        val second = AtomicInteger(0)

        gate.setPending { first.incrementAndGet() }
        // Overwrite the pending action before completion — only the latest should run.
        gate.setPending { second.incrementAndGet() }

        gate.markAttemptComplete()

        assertThat(first.get()).isEqualTo(0)
        assertThat(second.get()).isEqualTo(1)
    }

    @Test
    fun pendingRunsExactlyOnceUnderRace() {
        // Race setPending against markAttemptComplete from separate threads. Whichever
        // observes completion must flush, and the action must run EXACTLY once — never zero
        // (lost) and never twice (double-flushed).
        repeat(200) {
            val gate = EngineInitGate()
            val ran = AtomicInteger(0)
            val startGun = CountDownLatch(1)

            val setter = Thread {
                startGun.await()
                gate.setPending { ran.incrementAndGet() }
            }
            val completer = Thread {
                startGun.await()
                gate.markAttemptComplete()
            }
            setter.start()
            completer.start()
            startGun.countDown()
            setter.join()
            completer.join()

            // After both settle, the attempt is complete; if the action hadn't run yet the
            // final setPending path would have flushed it. Give any late flush a chance.
            assertThat(ran.get()).isEqualTo(1)
        }
    }

    @Test
    fun markAttemptCompleteIdempotent() {
        val gate = EngineInitGate()
        val ran = AtomicInteger(0)
        gate.setPending { ran.incrementAndGet() }

        gate.markAttemptComplete()
        gate.markAttemptComplete() // second call must be a no-op for flushing
        gate.markAttemptComplete()

        assertThat(gate.hasCompletedAttempt).isTrue()
        // The pending action was claimed on the first flush → runs exactly once.
        assertThat(ran.get()).isEqualTo(1)
    }

    @Test
    fun clearPendingDropsAction() {
        val gate = EngineInitGate()
        val ran = AtomicInteger(0)
        gate.setPending { ran.incrementAndGet() }

        gate.clearPending()
        gate.markAttemptComplete()

        // Cleared before completion → never runs.
        assertThat(ran.get()).isEqualTo(0)
    }

    // --- Re-arm support (F2): a completed gate can start a fresh attempt cycle so a persistent
    // init failure can be retried in the background after backoff, off the main thread. ---

    @Test
    fun rearmAfterCompletionResetsCompletionAndAllowsSecondCycle() {
        val gate = EngineInitGate()
        gate.markAttemptComplete()
        assertThat(gate.hasCompletedAttempt).isTrue()

        // Re-arm: a new attempt is pending again, so a fresh waiter must park (not see completed).
        assertThat(gate.rearmIfCompleted()).isTrue()
        assertThat(gate.hasCompletedAttempt).isFalse()

        // A pending action registered for the SECOND cycle must not fire until the second
        // markAttemptComplete — proving the latch was genuinely reset, not left open.
        val ran = AtomicInteger(0)
        gate.setPending { ran.incrementAndGet() }
        assertThat(ran.get()).isEqualTo(0)

        gate.markAttemptComplete()
        assertThat(ran.get()).isEqualTo(1)
        assertThat(gate.hasCompletedAttempt).isTrue()
    }

    @Test
    fun rearmIsNoOpWhileAttemptStillPending() {
        val gate = EngineInitGate()
        // Attempt not yet complete → nothing to re-arm.
        assertThat(gate.rearmIfCompleted()).isFalse()
        assertThat(gate.hasCompletedAttempt).isFalse()
    }

    @Test
    fun rearmedWaiterParksUntilSecondCompletion() {
        val gate = EngineInitGate()
        gate.markAttemptComplete()
        gate.rearmIfCompleted()

        // A waiter on the re-armed cycle must time out while the second attempt is unfinished.
        assertThat(gate.awaitAttempt(50, TimeUnit.MILLISECONDS)).isFalse()

        Thread {
            Thread.sleep(50)
            gate.markAttemptComplete()
        }.start()
        // Then wake promptly once the second attempt completes.
        assertThat(gate.awaitAttempt(2, TimeUnit.SECONDS)).isTrue()
    }
}
