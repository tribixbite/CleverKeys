package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * Pure JVM validation of the coroutine-scope lifecycle contract introduced in [Pointers] by R-6
 * (core-ime audit): `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` plus
 * `fun close() { scope.cancel() }`, cancelled from [Keyboard2View.onDetachedFromWindow].
 *
 * [Pointers] itself needs an Android [android.content.Context]/[android.os.Handler] and cannot be
 * constructed under the pure JUnitCore runner, so these tests exercise the EXACT scope primitive
 * `Pointers` now owns — a `SupervisorJob() + Dispatchers.IO` scope with a `close()`-style cancel —
 * and assert the behaviour `Pointers.close()`/`onDetachedFromWindow` depends on:
 *   1. cancelling the scope makes it inactive and cancels its launched child job;
 *   2. the child observes cancellation and stops (no leak past close);
 *   3. `close()` is idempotent (double-cancel is safe, matching a detach-then-destroy sequence);
 *   4. `SupervisorJob` isolates a failing child from the scope (mirrors why we chose it over a
 *      plain `Job`, so one launch's failure can't tear the scope down early).
 */
class CoroutineScopeLifecycleTest {

    /** Mirror of the field + method Pointers introduced, so the test tracks production 1:1. */
    private class ScopeOwner(exceptionHandler: CoroutineExceptionHandler? = null) {
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                (exceptionHandler ?: CoroutineExceptionHandler { _, throwable ->
                    throw AssertionError("unexpected coroutine failure", throwable)
                })
        )
        fun close() { scope.cancel() }
    }

    @Test
    fun `close cancels the scope and its launched child job`() = runBlocking {
        val owner = ScopeOwner()
        val started = CompletableDeferred<Unit>()
        val job: Job = owner.scope.launch {
            started.complete(Unit)
            // Emulate the never-completing / long-running work a leaked scope would keep alive.
            kotlinx.coroutines.awaitCancellation()
        }
        withTimeout(2_000) { started.await() } // ensure the child actually started

        assertThat(owner.scope.isActive).isTrue()
        assertThat(job.isActive).isTrue()

        owner.close()

        assertThat(owner.scope.isActive).isFalse()
        withTimeout(2_000) { job.join() } // child must observe cancellation and finish
        assertThat(job.isCancelled).isTrue()
        assertThat(job.isActive).isFalse()
    }

    @Test
    fun `child launched after close does not run`() {
        val owner = ScopeOwner()
        owner.close()
        // Launching on an already-cancelled scope yields an already-cancelled job that never runs.
        var ran = false
        val job = owner.scope.launch { ran = true }
        assertThat(job.isCancelled).isTrue()
        assertThat(ran).isFalse()
    }

    @Test
    fun `close is idempotent - double cancel is safe`() {
        val owner = ScopeOwner()
        owner.close()
        owner.close() // detach then destroy: must not throw
        assertThat(owner.scope.isActive).isFalse()
    }

    @Test
    fun `SupervisorJob isolates a failing child so the scope survives sibling failure`() = runBlocking {
        val captured = CompletableDeferred<Throwable>()
        val owner = ScopeOwner(CoroutineExceptionHandler { _, throwable ->
            captured.complete(throwable)
        })
        val failed = CompletableDeferred<Unit>()
        // A failing child under a SupervisorJob must NOT cancel the parent scope.
        owner.scope.launch {
            try {
                throw IllegalStateException("boom")
            } finally {
                failed.complete(Unit)
            }
        }
        withTimeout(2_000) { failed.await() }
        assertThat(withTimeout(2_000) { captured.await() }).isInstanceOf(IllegalStateException::class.java)
        // Give the failure a beat to (not) propagate, then assert the scope is still usable.
        assertThat(owner.scope.isActive).isTrue()

        val ran = CompletableDeferred<Unit>()
        owner.scope.launch { ran.complete(Unit) }
        withTimeout(2_000) { ran.await() } // a fresh child still runs → scope not torn down

        owner.close()
        assertThat(owner.scope.isActive).isFalse()
    }
}
