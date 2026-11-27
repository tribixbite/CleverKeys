package tribixbite.cleverkeys.ui.settings.io

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pre-tag review 2026-11-25 S2: Settings imports ran in the activity's `lifecycleScope`, which a
 * rotation cancels, so a GIF pack could be left half-installed and the other imports reported
 * failure after their writes had landed. [ImportTracker] runs them for the process and hands the
 * follow-up to whichever Settings activity is in front. Real scopes and dispatchers throughout:
 * the cancellation semantics are exercised, not described.
 */
class ImportTrackerTest {

    /** A follow-up the test can identify. */
    private data class FollowUp(val name: String)

    private fun tracker() = ImportTracker<String, FollowUp>(Dispatchers.Default)

    private suspend fun ImportTracker<String, FollowUp>.awaitIdle() =
        withTimeout(5_000) { state.first { !it.inProgress } }

    /** The core of S2: the scope that started the import is cancelled (rotation) mid-import. */
    @Test
    fun anImportOutlivesTheScopeThatStartedIt() = runBlocking {
        val tracker = tracker()
        val gate = CompletableDeferred<Unit>()
        var workRanToTheEnd = false
        val activityScope = CoroutineScope(Job() + Dispatchers.Default)
        activityScope.launch {
            tracker.start("Importing…") {
                gate.await()
                workRanToTheEnd = true
                ImportTracker.Outcome("Imported", FollowUp("show result"))
            }
        }.join()

        activityScope.cancel()  // the old activity is destroyed
        gate.complete(Unit)
        val done = tracker.awaitIdle()

        assertThat(workRanToTheEnd).isTrue()
        assertThat(done.status).isEqualTo("Imported")
        assertThat(tracker.consumeEvent()).isEqualTo(FollowUp("show result"))
    }

    @Test
    fun aRotationHandsTheFollowUpToTheNewActivityOnce() = runBlocking {
        val tracker = tracker()
        val old = Any()
        val new = Any()
        val gate = CompletableDeferred<Unit>()
        tracker.attach(old)
        tracker.start("Importing…") { gate.await(); ImportTracker.Outcome(null, FollowUp("dialog")) }
        tracker.detach(old, finishing = false)  // recreated, not left
        tracker.attach(new)
        assertThat(tracker.state.value.inProgress).isTrue()
        assertThat(tracker.state.value.status).isEqualTo("Importing…")

        gate.complete(Unit)
        tracker.awaitIdle()

        assertThat(tracker.consumeEvent()).isEqualTo(FollowUp("dialog"))
        assertThat(tracker.consumeEvent()).isNull()
    }

    /** Review 2026-11-25 §4: the old activity's onDestroy can come after the new one's onCreate. */
    @Test
    fun aFinishingActivityDestroyedAfterTheNextOneAttachedAbandonsNothing() = runBlocking {
        val tracker = tracker()
        val first = Any()
        val second = Any()
        val gate = CompletableDeferred<Unit>()
        tracker.attach(first)
        tracker.start("Importing…") { gate.await(); ImportTracker.Outcome("done", FollowUp("x")) }
        tracker.attach(second)
        tracker.detach(first, finishing = true)

        gate.complete(Unit)
        val done = tracker.awaitIdle()

        assertThat(done.status).isEqualTo("done")
        assertThat(tracker.consumeEvent()).isEqualTo(FollowUp("x"))
    }

    @Test
    fun leavingSettingsForGoodDropsARunningImportsResult() = runBlocking {
        val tracker = tracker()
        val owner = Any()
        val gate = CompletableDeferred<Unit>()
        var workRanToTheEnd = false
        tracker.attach(owner)
        tracker.start("Importing…") {
            gate.await()
            workRanToTheEnd = true
            ImportTracker.Outcome("done", FollowUp("x"))
        }
        tracker.detach(owner, finishing = true)

        gate.complete(Unit)
        val done = tracker.awaitIdle()

        // The work itself still finished (its writes and broadcasts belong there); only the
        // follow-up and the message, which would arrive out of context later, are dropped.
        assertThat(workRanToTheEnd).isTrue()
        assertThat(done.status).isNull()
        assertThat(tracker.consumeEvent()).isNull()
    }

    @Test
    fun leavingSettingsForGoodDropsAnUntakenFollowUp() = runBlocking {
        val tracker = tracker()
        val owner = Any()
        tracker.attach(owner)
        tracker.start("Importing…") { ImportTracker.Outcome("done", FollowUp("x")) }
        tracker.awaitIdle()

        tracker.detach(owner, finishing = true)

        assertThat(tracker.state.value.status).isNull()
        assertThat(tracker.consumeEvent()).isNull()
    }

    @Test
    fun comingBackBeforeTheImportEndsWantsItsResultAgain() = runBlocking {
        val tracker = tracker()
        val gate = CompletableDeferred<Unit>()
        val first = Any()
        tracker.attach(first)
        tracker.start("Importing…") { gate.await(); ImportTracker.Outcome("done", FollowUp("x")) }
        tracker.detach(first, finishing = true)
        tracker.attach(Any())

        gate.complete(Unit)
        tracker.awaitIdle()

        assertThat(tracker.consumeEvent()).isEqualTo(FollowUp("x"))
    }

    @Test
    fun aSecondStartWhileOneRunsIsRefused() = runBlocking {
        val tracker = tracker()
        val gate = CompletableDeferred<Unit>()
        assertThat(tracker.start("Importing…") { gate.await(); ImportTracker.Outcome("first", null) }).isTrue()
        assertThat(tracker.start("Importing…") { ImportTracker.Outcome("second", null) }).isFalse()

        gate.complete(Unit)
        assertThat(tracker.awaitIdle().status).isEqualTo("first")
    }

    @Test
    fun aCancellationInsideTheWorkStillReleasesTheButtons() = runBlocking {
        val tracker = tracker()
        tracker.start("Importing…") {
            delay(1)
            throw CancellationException("the work's own timeout")
        }
        val done = tracker.awaitIdle()
        assertThat(done.status).isNull()
        assertThat(tracker.consumeEvent()).isNull()
    }

    @Test
    fun anErrorEscapingTheWorkStillReleasesTheButtons() = runBlocking {
        val tracker = tracker()
        tracker.start("Importing…") { throw StackOverflowError("simulated") }
        assertThat(tracker.awaitIdle().status).isNull()
    }

    @Test
    fun catchingFailuresMapsAFailureButNeverACancellation() {
        val failed = catchingFailures<String>({ throw IllegalStateException("bad file") }) { "failed: ${it.message}" }
        assertThat(failed).isEqualTo("failed: bad file")

        assertThrows(CancellationException::class.java) {
            catchingFailures<String>({ throw CancellationException("rotated") }) { "failed" }
        }
    }

    @Test
    fun catchingFailuresInsideACancelledCoroutinePropagatesTheCancellation() = runBlocking {
        var reportedAsFailure = false
        val job = launch(Dispatchers.Default) {
            catchingFailures({ delay(60_000) }) { reportedAsFailure = true }
        }
        delay(50)
        job.cancel()
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(reportedAsFailure).isFalse()
    }

    /**
     * Fable audit D2 (2026-11-26): an import finished while Settings was stopped, so its follow-up
     * waits; a second share then arrives through onNewIntent before onStart. Starting it replaced
     * the whole state, the waiting follow-up included, so the first result was never shown.
     */
    @Test
    fun aSecondStartKeepsTheFollowUpNotYetTaken() = runBlocking {
        val tracker = tracker()
        tracker.start("Importing…") { ImportTracker.Outcome("Imported 1", FollowUp("result 1")) }
        tracker.awaitIdle()

        val gate = CompletableDeferred<Unit>()
        tracker.start("Importing…") { gate.await(); ImportTracker.Outcome("Imported 2", FollowUp("result 2")) }
        assertThat(tracker.state.value.event).isEqualTo(FollowUp("result 1"))
        gate.complete(Unit)
        tracker.awaitIdle()

        assertThat(tracker.consumeEvent()).isEqualTo(FollowUp("result 1"))
        assertThat(tracker.consumeEvent()).isEqualTo(FollowUp("result 2"))
        assertThat(tracker.consumeEvent()).isNull()
    }
}
