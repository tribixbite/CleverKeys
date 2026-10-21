package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.clipboard.ClipboardPendingDeletion.Change
import tribixbite.cleverkeys.clipboard.ClipboardPendingDeletion.CommitReason
import tribixbite.cleverkeys.clipboard.ClipboardPendingDeletion.Pending

/**
 * GH #175(2): the deferred delete behind swipe-to-delete. A swiped row is hidden at once and
 * deleted for real only when the undo window expires or something that changes what the user
 * is looking at happens first; Undo un-hides it and nothing is ever written. Driven with a
 * fake scheduler so the 5 s window is deterministic.
 */
class ClipboardPendingDeletionTest {

    /** Records scheduled timeouts; [fire] runs one as if its delay elapsed. */
    private class FakeScheduler : ClipboardPendingDeletion.Scheduler {
        inner class Task(val delayMs: Long, val action: () -> Unit) : ClipboardPendingDeletion.Cancellable {
            var cancelled = false
            override fun cancel() { cancelled = true }
        }
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, action: () -> Unit): ClipboardPendingDeletion.Cancellable =
            Task(delayMs, action).also { tasks += it }
        fun fire(task: Task) { if (!task.cancelled) task.action() }
    }

    private val scheduler = FakeScheduler()
    private val committed = mutableListOf<Pending>()
    private val changes = mutableListOf<Pair<Change, Pending>>()
    private val pending = ClipboardPendingDeletion(scheduler, commitAction = { committed += it }).also {
        it.listener = { change, p -> changes += change to p }
    }

    @Test
    fun beginHidesTheRowOnItsTabOnlyAndArmsTheFiveSecondWindow() {
        pending.begin(ClipboardTab.PINNED, "note")

        assertThat(pending.hides(ClipboardTab.PINNED, "note")).isTrue()
        assertWithMessage("pinned/todo are independent COPY tables: other tabs keep the row")
            .that(pending.hides(ClipboardTab.HISTORY, "note")).isFalse()
        assertThat(pending.hides(ClipboardTab.PINNED, "other")).isFalse()
        assertThat(scheduler.tasks.single().delayMs).isEqualTo(ClipboardPendingDeletion.UNDO_WINDOW_MS)
        assertThat(ClipboardPendingDeletion.UNDO_WINDOW_MS).isEqualTo(5_000L)
        assertThat(changes).containsExactly(Change.HIDDEN to Pending(ClipboardTab.PINNED, "note"))
        assertWithMessage("nothing is deleted until the window ends").that(committed).isEmpty()
    }

    @Test
    fun theTimeoutCommitsExactlyOnce() {
        pending.begin(ClipboardTab.HISTORY, "a")

        scheduler.fire(scheduler.tasks.single())

        assertThat(committed).containsExactly(Pending(ClipboardTab.HISTORY, "a"))
        assertThat(pending.pending).isNull()
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.TIMEOUT)
        assertThat(pending.hides(ClipboardTab.HISTORY, "a")).isFalse()
        assertThat(changes.last()).isEqualTo(Change.COMMITTED to Pending(ClipboardTab.HISTORY, "a"))
        assertThat(pending.commitNow(CommitReason.PANE_HIDDEN)).isFalse()
        assertThat(committed).hasSize(1)
    }

    @Test
    fun undoRestoresTheRowCancelsTheTimerAndNeverDeletes() {
        pending.begin(ClipboardTab.TODOS, "buy milk")

        assertThat(pending.undo()).isTrue()

        assertThat(pending.pending).isNull()
        assertThat(pending.hides(ClipboardTab.TODOS, "buy milk")).isFalse()
        assertThat(scheduler.tasks.single().cancelled).isTrue()
        scheduler.fire(scheduler.tasks.single())
        assertThat(committed).isEmpty()
        assertThat(changes.map { it.first }).containsExactly(Change.HIDDEN, Change.RESTORED).inOrder()
        assertWithMessage("a second undo has nothing to restore").that(pending.undo()).isFalse()
    }

    @Test
    fun everyTriggerCommitsThePendingRowImmediately() {
        for (reason in CommitReason.values().filter { it != CommitReason.TIMEOUT }) {
            committed.clear()
            pending.begin(ClipboardTab.HISTORY, "row-$reason")

            assertWithMessage("$reason").that(pending.commitNow(reason)).isTrue()

            assertWithMessage("$reason").that(committed).containsExactly(Pending(ClipboardTab.HISTORY, "row-$reason"))
            assertWithMessage("$reason").that(pending.lastCommitReason).isEqualTo(reason)
            assertWithMessage("$reason: timer cancelled").that(scheduler.tasks.last().cancelled).isTrue()
            assertWithMessage("$reason: idempotent").that(pending.commitNow(reason)).isFalse()
        }
        assertThat(committed).hasSize(1)
    }

    @Test
    fun aSecondSwipeCommitsTheFirstBeforeHidingTheNext() {
        pending.begin(ClipboardTab.HISTORY, "first")
        val firstTimer = scheduler.tasks.single()

        pending.begin(ClipboardTab.HISTORY, "second")

        assertThat(committed).containsExactly(Pending(ClipboardTab.HISTORY, "first"))
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.NEXT_SWIPE)
        assertThat(firstTimer.cancelled).isTrue()
        assertThat(pending.hides(ClipboardTab.HISTORY, "first")).isFalse()
        assertThat(pending.hides(ClipboardTab.HISTORY, "second")).isTrue()
        assertThat(changes.map { it.first })
            .containsExactly(Change.HIDDEN, Change.COMMITTED, Change.HIDDEN).inOrder()
        // The first row's stale timer must not commit the second row early.
        scheduler.fire(firstTimer)
        assertThat(committed).hasSize(1)
        scheduler.fire(scheduler.tasks.last())
        assertThat(committed.last()).isEqualTo(Pending(ClipboardTab.HISTORY, "second"))
    }

    @Test
    fun aFailingDeleteStillEndsThePendingStateSoTheRowIsNotHiddenForever() {
        val failing = ClipboardPendingDeletion(scheduler, commitAction = { error("db locked") })
        failing.begin(ClipboardTab.HISTORY, "x")

        assertThat(failing.commitNow(CommitReason.PANE_HIDDEN)).isTrue()

        assertThat(failing.pending).isNull()
        assertThat(failing.hides(ClipboardTab.HISTORY, "x")).isFalse()
    }

    @Test
    fun withNothingPendingTriggersAndUndoAreNoOps() {
        assertThat(pending.commitNow(CommitReason.TAB_SWITCH)).isFalse()
        assertThat(pending.undo()).isFalse()
        assertThat(committed).isEmpty()
        assertThat(changes).isEmpty()
        assertThat(scheduler.tasks).isEmpty()
    }
}
