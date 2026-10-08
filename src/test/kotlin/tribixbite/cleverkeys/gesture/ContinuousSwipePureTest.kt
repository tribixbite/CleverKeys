package tribixbite.cleverkeys.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tribixbite.cleverkeys.gesture.ContinuousSelectionGate.Decision

/**
 * Pure-JVM contract for the continuous-swipe phrase machinery (review 2026-10-07, item 8):
 * segmentation ([ContinuousSwipe]), the serialized commit FIFO ([ContinuousSwipeQueue]) and
 * the selection-callback gate ([ContinuousSelectionGate]). The segmentation/queue cases were
 * previously native-only (`src/androidTest/.../ContinuousSwipeTest.kt`, which still drives the
 * real view timer); these classes have no Android dependency, so the host tier pins them too.
 *
 * Gate cases model Android's delivery order: `onUpdateSelection` reaches the IME through a
 * Binder call posted to the main looper, so the callbacks of a commit normally arrive AFTER
 * the synchronous commit code (and the gate's `complete`) has returned.
 */
class ContinuousSwipePureTest {
    private class Harness {
        val output = mutableListOf<ContinuousSwipe.Segment<String>>()
        var overflow = 0
        val swipe = ContinuousSwipe<String>(output::add) { overflow++ }
        fun letter(letter: String, time: Long) = swipe.sample(time.toFloat(), 1f, time + 1000, time, letter, false)
        fun space(time: Long, eligible: Boolean = true) = swipe.sample(1f, 10f, time + 1000, time, null, true, eligible)
    }

    // ---- segmentation ----

    @Test fun quickSpaceCrossingDoesNotSplit() {
        val h = Harness(); h.letter("w", 0); h.space(10); h.letter("e", 200); h.swipe.finish()
        assertFalse(h.swipe.hasBoundary); assertTrue(h.output.isEmpty())
    }

    @Test fun dwellTimerMakesBoundaryWithoutMoveSamples() {
        val h = Harness(); h.letter("w", 0); h.letter("e", 5); h.space(10)
        h.swipe.dwell(10 + ContinuousSwipe.DWELL_MS - 1); assertTrue(h.output.isEmpty())
        h.swipe.dwell(10 + ContinuousSwipe.DWELL_MS)
        assertEquals(listOf("w", "e"), h.output.single().keys)
        assertTrue(h.output.single().endedBySpace)
        assertTrue(h.output.single().samples.all { it.y == 1f })
    }

    @Test fun physicalSpaceEdgeCannotTriggerBoundary() {
        val h = Harness(); h.letter("a", 0); h.space(10, eligible = false); h.swipe.dwell(1000)
        assertTrue(h.output.isEmpty())
    }

    @Test fun leavingSpaceCancelsDwell() {
        val h = Harness(); h.letter("a", 0); h.space(10); h.letter("b", 100); h.swipe.dwell(1000)
        assertTrue(h.output.isEmpty())
    }

    @Test fun repeatedSpaceSamplesEmitOncePerVisit() {
        val h = Harness(); h.letter("a", 0); h.space(10); h.space(290); h.space(600); h.swipe.dwell(900)
        assertEquals(1, h.output.size); h.swipe.finish(); assertEquals(1, h.output.size)
    }

    @Test fun emptySpaceVisitsNeverCreateWords() {
        val h = Harness(); h.space(0); h.space(500); h.swipe.finish(); assertTrue(h.output.isEmpty())
    }

    @Test fun finalLiftFlushesLastSegmentWithoutSpaceExcursion() {
        val h = Harness(); h.letter("a", 0); h.space(10); h.space(290)
        h.swipe.sample(4f, 5f, 1300, 300, null, false)
        h.letter("b", 310); h.letter("c", 320); h.swipe.finish()
        assertEquals(2, h.output.size); assertFalse(h.output.last().endedBySpace)
        assertEquals(listOf("b", "c"), h.output.last().keys)
        assertEquals(1f, h.output.last().samples.first().y)
    }

    @Test fun finishWithoutBoundaryEmitsNothing() {
        val h = Harness(); h.letter("a", 0); h.letter("b", 10); h.swipe.finish()
        assertTrue(h.output.isEmpty()); assertFalse(h.swipe.hasBoundary)
    }

    @Test fun cancelDoesNotFlushPendingLetters() {
        val h = Harness(); h.letter("a", 0); h.swipe.cancel(); h.space(1000); h.swipe.finish()
        assertTrue(h.output.isEmpty())
    }

    @Test fun segmentsHaveIndependentLists() {
        val h = Harness(); h.letter("a", 0); h.space(1); h.space(281); h.letter("b", 300); h.swipe.finish()
        assertEquals(listOf("a"), h.output.first().keys); assertEquals(listOf("b"), h.output.last().keys)
    }

    @Test fun backwardAndDuplicateSamplesAreExcluded() {
        val h = Harness(); h.letter("a", 10); h.letter("b", 10); h.letter("c", 9); h.space(20); h.space(300)
        assertEquals(listOf("a"), h.output.single().keys)
    }

    @Test fun pointLimitAbortsInsteadOfTruncating() {
        val h = Harness()
        repeat(ContinuousSwipe.MAX_POINTS + 1) { h.letter("a", it.toLong()) }
        h.space(3000); h.space(4000)
        assertEquals(1, h.overflow); assertTrue(h.output.isEmpty())
    }

    // ---- CTC raw sub-paths and the lift sample (short-word fix, 2026-10-07) ----

    /** A two-word phrase `a b|c`: boundary after `a`, finger then on `b`, `c`. */
    private fun phrase(): Harness {
        val h = Harness(); h.letter("a", 0); h.space(10); h.space(290)
        h.letter("b", 310); h.letter("c", 320); return h
    }

    @Test fun finalSegmentCarriesTheLiftSampleForCtcOnly() {
        val h = phrase()
        val lift = ContinuousSwipe.Sample(320.5f, 1f, 1400)
        h.swipe.finish(lift)
        val last = h.output.last()
        assertEquals(lift, last.lift)
        // Engines' shared raw sub-path is unchanged; the CTC copy gains exactly one sample.
        assertEquals(listOf(1310L, 1320L), last.samples.map { it.timestamp })
        assertEquals(last.samples + lift, last.ctcSamples)
    }

    @Test fun spaceEndedSegmentsCarryNoLiftAndCtcSeesTheirRawSamples() {
        val h = phrase(); h.swipe.finish(ContinuousSwipe.Sample(320f, 1f, 1400))
        val first = h.output.first()
        assertTrue(first.endedBySpace)
        assertEquals(null, first.lift)
        assertEquals(first.samples, first.ctcSamples)
    }

    @Test fun liftOnTheSpacebarAddsNoSample() {
        // Lifted inside SPACE without the dwell: the word ends at its last letter sample;
        // a spacebar position would be a false final key for the encoder.
        val h = phrase(); h.space(330)
        h.swipe.finish(ContinuousSwipe.Sample(1f, 10f, 1400))
        assertEquals(null, h.output.last().lift)
        assertEquals(h.output.last().samples, h.output.last().ctcSamples)
    }

    @Test fun liftNotLaterThanTheLastSampleOrNonFiniteIsDropped() {
        val same = phrase(); same.swipe.finish(ContinuousSwipe.Sample(320f, 1f, 1320))
        assertEquals(null, same.output.last().lift)
        val nan = phrase(); nan.swipe.finish(ContinuousSwipe.Sample(Float.NaN, 1f, 1400))
        assertEquals(null, nan.output.last().lift)
    }

    /**
     * A long hold on the final letter before lift is clamped to 500 ms (the recognizer's own
     * MAX_POINT_INTERVAL_MS): an unclamped 2 s gap would turn most of the encoder's 64
     * time-resampled columns into one stationary point (typing audit, 2026-10-08).
     */
    @Test fun aLongFinalHoldIsClampedBeforeItReachesCtc() {
        val h = phrase()
        h.swipe.finish(ContinuousSwipe.Sample(320.5f, 1f, 1320 + 2_000))
        val lift = h.output.last().lift!!
        assertEquals(1320L + ContinuousSwipe.MAX_LIFT_GAP_MS, lift.timestamp)
        assertEquals(320.5f, lift.x)
        // A stop within the bound (the `ad` case's 200 ms) is untouched.
        val short = phrase(); short.swipe.finish(ContinuousSwipe.Sample(320.5f, 1f, 1520))
        assertEquals(1520L, short.output.last().lift!!.timestamp)
    }

    // ---- serialized queue ----

    @Test fun fifoWaitsForAcknowledgedCommitBeforeNextDecode() {
        val dispatched = mutableListOf<Int>(); val callbacks = mutableListOf<(Boolean) -> Unit>(); var committed = 0
        val queue = ContinuousSwipeQueue<Int>({ true }, { n, _, done ->
            assertEquals(n - 1, committed); dispatched.add(n); callbacks.add(done)
        }, { fail("unexpected abort") })
        queue.enqueue(1); queue.enqueue(2); queue.enqueue(3); assertEquals(listOf(1), dispatched)
        committed = 1; callbacks[0](true); assertEquals(listOf(1, 2), dispatched)
        committed = 2; callbacks[1](true); assertEquals(listOf(1, 2, 3), dispatched)
    }

    @Test fun failedCommitDropsLaterSegments() {
        val dispatched = mutableListOf<Int>(); var callback: ((Boolean) -> Unit)? = null; var aborted = 0
        val queue = ContinuousSwipeQueue<Int>({ true }, { n, _, done -> dispatched.add(n); callback = done }, { aborted++ })
        queue.enqueue(1); queue.enqueue(2); callback!!(false)
        assertEquals(listOf(1), dispatched); assertEquals(1, aborted)
    }

    @Test fun cancellationRejectsLateAndDuplicateCallback() {
        var callback: ((Boolean) -> Unit)? = null; var guard: (() -> Boolean)? = null; var calls = 0
        val queue = ContinuousSwipeQueue<Int>({ true }, { _, current, done -> calls++; guard = current; callback = done },
            { fail("unexpected abort") })
        queue.enqueue(1); queue.enqueue(2); queue.cancel()
        assertFalse(guard!!()); callback!!(true); callback!!(true); assertEquals(1, calls)
    }

    @Test fun changedEditorRejectsDecodedResult() {
        var valid = true; var guard: (() -> Boolean)? = null; var callback: ((Boolean) -> Unit)? = null
        var calls = 0; var aborted = 0
        val queue = ContinuousSwipeQueue<Int>({ valid }, { _, current, done -> calls++; guard = current; callback = done }, { aborted++ })
        queue.enqueue(1); queue.enqueue(2); valid = false; assertFalse(guard!!()); callback!!(false)
        assertEquals(1, calls); assertEquals(1, aborted)
    }

    @Test fun queueLimitAbortsInsteadOfDroppingOneWord() {
        var aborted = 0
        val queue = ContinuousSwipeQueue<Int>({ true }, { _, _, _ -> }, { aborted++ })
        repeat(ContinuousSwipe.MAX_SEGMENTS + 2) { queue.enqueue(it) }
        assertEquals(1, aborted)
    }

    @Test fun decoderExceptionAbortsWithoutDispatchingNextSegment() {
        var aborted = 0; var calls = 0
        val queue = ContinuousSwipeQueue<Int>({ true }, { _, _, _ -> calls++; throw IllegalStateException("decoder unavailable") },
            { aborted++ })
        queue.enqueue(1); queue.enqueue(2)
        assertEquals(1, calls); assertEquals(1, aborted)
    }

    @Test fun outstandingWorkCoversWaitingAndDecodingSegmentsOnly() {
        var callback: ((Boolean) -> Unit)? = null
        val queue = ContinuousSwipeQueue<Int>({ true }, { _, _, done -> callback = done }, {})
        assertFalse(queue.hasOutstandingWork)
        queue.enqueue(1); assertTrue(queue.hasOutstandingWork) // decoding
        queue.enqueue(2); callback!!(true); assertTrue(queue.hasOutstandingWork) // next one decoding
        callback!!(true); assertFalse(queue.hasOutstandingWork) // all acknowledged
        queue.enqueue(3); queue.cancel(); assertFalse(queue.hasOutstandingWork)
    }

    // ---- selection gate (review finding 1) ----

    /**
     * Tap "I" (caret 1), then continuous-swipe "want": SuggestionHandler writes the typed
     * word's separator " " (caret 2) and then "want " (caret 7) as two editor writes. Their
     * callbacks arrive after `complete` returned and the view published caret 7.
     */
    @Test fun typedPrefixTwoWriteCommitCallbacksAfterCompleteDoNotCancel() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit()
        assertTrue(gate.complete(accepted = true, oldStart = 1, nowStart = 7))
        assertEquals(Decision.IGNORE, gate.onSelection(2, 2, expected = 7))
        assertEquals(Decision.IGNORE, gate.onSelection(7, 7, expected = 7))
    }

    /** Auto-space off: the word write and the boundary's separator write, delivered late. */
    @Test fun appendedSeparatorCallbacksAfterCompleteDoNotCancel() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit()
        assertTrue(gate.complete(accepted = true, oldStart = 10, nowStart = 15)) // "want" (14) + " " (15)
        assertEquals(Decision.IGNORE, gate.onSelection(14, 14, expected = 15))
        assertEquals(Decision.IGNORE, gate.onSelection(15, 15, expected = 15))
    }

    /** A late callback of commit N may land while commit N+1 is being written. */
    @Test fun previousCommitCallbackRecordedDuringNextCommitIsAccepted() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit()
        assertTrue(gate.complete(accepted = true, oldStart = 1, nowStart = 7))
        gate.prepareCommit()
        assertEquals(Decision.IGNORE, gate.onSelection(2, 2, expected = 7))
        assertEquals(Decision.IGNORE, gate.onSelection(7, 7, expected = 7))
        assertTrue(gate.complete(accepted = true, oldStart = 7, nowStart = 10))
        assertEquals(Decision.IGNORE, gate.onSelection(10, 10, expected = 10))
    }

    @Test fun manualCaretMoveAfterCommitStillCancels() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit()
        assertTrue(gate.complete(accepted = true, oldStart = 1, nowStart = 7))
        assertEquals(Decision.CANCEL, gate.onSelection(0, 0, expected = 7))
    }

    @Test fun rangeSelectionAtAnOwnedPositionStillCancels() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit()
        assertTrue(gate.complete(accepted = true, oldStart = 1, nowStart = 7))
        assertEquals(Decision.CANCEL, gate.onSelection(2, 7, expected = 7))
    }

    @Test fun unexpectedCallbackDuringCommitRejectsTheCommit() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit()
        assertEquals(Decision.IGNORE, gate.onSelection(0, 0, expected = 1))
        assertFalse(gate.complete(accepted = true, oldStart = 1, nowStart = 7))
    }

    @Test fun beforeAnyCommitOnlyTheStartingCaretIsOwned() {
        val gate = ContinuousSelectionGate()
        assertEquals(Decision.IGNORE, gate.onSelection(4, 4, expected = 4))
        assertEquals(Decision.CANCEL, gate.onSelection(5, 5, expected = 4))
    }

    @Test fun recordedCallbackFloodCancels() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit()
        repeat(ContinuousSelectionGate.MAX_RECORDED) { assertEquals(Decision.IGNORE, gate.onSelection(7, 7, expected = 1)) }
        assertEquals(Decision.CANCEL, gate.onSelection(7, 7, expected = 1))
    }

    @Test fun ownedPositionsLastOnlyUntilTheNextCommit() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit(); assertTrue(gate.complete(accepted = true, oldStart = 1, nowStart = 7))
        gate.prepareCommit(); assertTrue(gate.complete(accepted = true, oldStart = 7, nowStart = 10))
        assertEquals(Decision.CANCEL, gate.onSelection(2, 2, expected = 10))
    }

    @Test fun lateCallbackFloodAfterACommitCancels() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit(); assertTrue(gate.complete(accepted = true, oldStart = 1, nowStart = 7))
        repeat(ContinuousSelectionGate.MAX_LATE) { assertEquals(Decision.IGNORE, gate.onSelection(7, 7, expected = 7)) }
        assertEquals(Decision.CANCEL, gate.onSelection(7, 7, expected = 7))
    }

    @Test fun rejectedCommitDoesNotGrantItsPositions() {
        val gate = ContinuousSelectionGate()
        gate.prepareCommit()
        assertFalse(gate.complete(accepted = false, oldStart = 1, nowStart = 7))
        assertEquals(Decision.CANCEL, gate.onSelection(2, 2, expected = 1))
    }
}
