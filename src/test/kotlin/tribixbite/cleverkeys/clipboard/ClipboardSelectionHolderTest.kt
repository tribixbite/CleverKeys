package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import org.junit.Test
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardTab

/**
 * The service-scoped selection holder (maintainer request 2026-10-07): the selection outlives
 * every view, pane and input-view teardown, and a confirmed bulk action runs to completion on
 * the holder's own scope, independent of whichever view started it.
 */
class ClipboardSelectionHolderTest {

    private fun row(id: Long) = ClipboardEntry("row-$id", id, rowId = id)

    @Test
    fun startKeepsAnExistingSelectionOnTheSameTabAndReplacesAnotherTabs() {
        val holder = ClipboardSelectionHolder()
        val first = holder.start(ClipboardTab.PINNED)
        first.toggle(row(1))

        assertThat(holder.start(ClipboardTab.PINNED)).isSameInstanceAs(first)
        assertThat(holder.selection!!.size).isEqualTo(1)
        assertThat(holder.tab).isEqualTo(ClipboardTab.PINNED)

        val other = holder.start(ClipboardTab.TODOS)
        assertThat(other).isNotSameInstanceAs(first)
        assertThat(other.size).isEqualTo(0)
        holder.end()
        assertThat(holder.selection).isNull()
        assertThat(holder.tab).isNull()
    }

    @Test
    fun aSuccessfulConfirmedActionEndsTheSelectionAndNotifiesTheAttachedView() {
        val holder = ClipboardSelectionHolder(Dispatchers.Unconfined, Dispatchers.Unconfined)
        holder.start(ClipboardTab.HISTORY).toggle(row(1))
        var observed = 0
        holder.observer = { observed++ }
        var finished: Result<Int>? = null

        assertThat(holder.runConfirmed({ Result.success(1) }) { finished = it }).isTrue()

        assertThat(finished!!.getOrNull()).isEqualTo(1)
        assertThat(holder.selection).isNull()
        assertThat(holder.running).isFalse()
        assertThat(observed).isEqualTo(1)
    }

    @Test
    fun aFailedOrThrowingActionKeepsTheSelectionForARetry() {
        val holder = ClipboardSelectionHolder(Dispatchers.Unconfined, Dispatchers.Unconfined)
        holder.start(ClipboardTab.HISTORY).toggle(row(1))
        var failure: Throwable? = null

        holder.runConfirmed<Int>({ Result.failure(IllegalStateException("rolled back")) }) { failure = it.exceptionOrNull() }
        assertThat(failure).hasMessageThat().isEqualTo("rolled back")
        assertThat(holder.selection!!.size).isEqualTo(1)

        holder.runConfirmed<Int>({ throw IllegalStateException("disk full") }) { failure = it.exceptionOrNull() }
        assertThat(failure).hasMessageThat().isEqualTo("disk full")
        assertThat(holder.selection!!.size).isEqualTo(1)
        assertThat(holder.running).isFalse()
    }

    @Test
    fun aRunningActionRefusesASecondOneAndReportsToTheViewAttachedWhenItFinishes() {
        val main = QueueDispatcher()
        val holder = ClipboardSelectionHolder(main, Dispatchers.Unconfined)
        holder.start(ClipboardTab.HISTORY).toggle(row(1))
        var workRan = false
        var finished = false

        // The view that confirmed detaches right after: it registered no observer that lives on,
        // and the work is not bound to its scope.
        holder.observer = null
        assertThat(holder.runConfirmed({ workRan = true; Result.success(1) }) { finished = true }).isTrue()
        assertThat(holder.running).isTrue()
        assertThat(holder.runConfirmed({ Result.success(2) }) { }).isFalse()

        // A different (re-inflated) view attaches and is the one told about completion.
        var newViewRefreshed = false
        holder.observer = { newViewRefreshed = true }
        main.drain()

        assertThat(workRan).isTrue()
        assertThat(finished).isTrue()
        assertThat(newViewRefreshed).isTrue()
        assertThat(holder.selection).isNull()
    }

    /** A main dispatcher that runs queued work only when the test drains it. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
}
