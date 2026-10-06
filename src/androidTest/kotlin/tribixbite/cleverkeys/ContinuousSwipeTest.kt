package tribixbite.cleverkeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.gesture.ContinuousSwipe
import tribixbite.cleverkeys.gesture.ContinuousSwipeQueue

/** Native execution of the production segmentation and asynchronous FIFO contracts. */
@RunWith(AndroidJUnit4::class)
class ContinuousSwipeTest {
    private class Harness {
        val output = mutableListOf<ContinuousSwipe.Segment<String>>()
        var overflow = 0
        val swipe = ContinuousSwipe<String>(output::add) { overflow++ }
        fun letter(letter: String, time: Long) = swipe.sample(time.toFloat(), 1f, time + 1000, time, letter, false)
        fun space(time: Long, eligible: Boolean = true) = swipe.sample(1f, 10f, time + 1000, time, null, true, eligible)
    }
    @Test fun quickSpaceCrossingDoesNotSplit() {
        val h = Harness(); h.letter("w", 0); h.space(10); h.letter("e", 200); h.swipe.finish()
        assertFalse(h.swipe.hasBoundary); assertTrue(h.output.isEmpty())
    }
    @Test fun stationaryTimerMakesBoundaryWithoutMoveJitter() {
        val h = Harness(); h.letter("w", 0); h.letter("e", 5); h.space(10)
        h.swipe.dwell(289); assertTrue(h.output.isEmpty())
        h.swipe.dwell(290); assertEquals(listOf("w", "e"), h.output.single().keys)
        assertTrue(h.output.single().endedBySpace)
        assertTrue(h.output.single().samples.all { it.y == 1f })
    }
    @Test fun physicalSpaceEdgesCannotTriggerBoundary() {
        val h = Harness(); h.letter("a", 0); h.space(10, false); h.swipe.dwell(1000)
        assertTrue(h.output.isEmpty())
    }
    @Test fun leavingSpaceCancelsDwell() {
        val h = Harness(); h.letter("a", 0); h.space(10); h.letter("b", 100); h.swipe.dwell(1000)
        assertTrue(h.output.isEmpty())
    }
    @Test fun repeatedSpaceSamplesEmitOnlyOncePerVisit() {
        val h = Harness(); h.letter("a", 0); h.space(10); h.space(290); h.space(600); h.swipe.dwell(900)
        assertEquals(1, h.output.size); h.swipe.finish(); assertEquals(1, h.output.size)
    }
    @Test fun emptySpaceVisitsNeverCreateWords() {
        val h = Harness(); h.space(0); h.space(500); h.swipe.finish(); assertTrue(h.output.isEmpty())
    }
    @Test fun finalLiftFlushesLastLetterSegmentWithoutSpaceExcursion() {
        val h = Harness(); h.letter("a", 0); h.space(10); h.space(290)
        h.swipe.sample(4f,5f,1300,300,null,false)
        h.letter("b",310); h.letter("c",320); h.swipe.finish()
        assertEquals(2,h.output.size); assertFalse(h.output.last().endedBySpace)
        assertEquals(listOf("b","c"),h.output.last().keys)
        assertEquals(1f,h.output.last().samples.first().y)
    }
    @Test fun cancelDoesNotFlushPendingLetters() {
        val h=Harness(); h.letter("a",0); h.swipe.cancel(); h.space(1000); h.swipe.finish(); assertTrue(h.output.isEmpty())
    }
    @Test fun samplesHaveImmutableIndependentLists() {
        val h=Harness();h.letter("a",0);h.space(1);h.space(281);h.letter("b",300);h.swipe.finish()
        assertEquals(listOf("a"),h.output.first().keys);assertEquals(listOf("b"),h.output.last().keys)
    }
    @Test fun backwardAndDuplicateSamplesAreExcluded() {
        val h=Harness();h.letter("a",10);h.letter("b",10);h.letter("c",9);h.space(20);h.space(300)
        assertEquals(listOf("a"),h.output.single().keys)
    }
    @Test fun pointLimitAbortsInsteadOfTruncating() {
        val h=Harness();repeat(ContinuousSwipe.MAX_POINTS+1){h.letter("a",it.toLong())};h.space(3000);h.space(4000)
        assertEquals(1,h.overflow);assertTrue(h.output.isEmpty())
    }
    @Test fun fifoWaitsForAcknowledgedCommitBeforeNextDecode() {
        val dispatched=mutableListOf<Int>(); val callbacks=mutableListOf<(Boolean)->Unit>();var committed=0
        val queue=ContinuousSwipeQueue<Int>({true},{n,_,done-> assertEquals(n-1,committed);dispatched.add(n);callbacks.add(done)},{fail("unexpected abort")})
        queue.enqueue(1);queue.enqueue(2);queue.enqueue(3);assertEquals(listOf(1),dispatched)
        committed=1;callbacks[0](true);assertEquals(listOf(1,2),dispatched)
        committed=2;callbacks[1](true);assertEquals(listOf(1,2,3),dispatched)
    }
    @Test fun failedCommitDropsLaterSegments() {
        val dispatched=mutableListOf<Int>();var callback:((Boolean)->Unit)?=null;var aborted=0
        val queue=ContinuousSwipeQueue<Int>({true},{n,_,done->dispatched.add(n);callback=done},{aborted++})
        queue.enqueue(1);queue.enqueue(2);callback!!(false)
        assertEquals(listOf(1),dispatched);assertEquals(1,aborted)
    }
    @Test fun cancellationRejectsLateAndDuplicateCallback() {
        var callback:((Boolean)->Unit)?=null;var guard:(()->Boolean)?=null;var calls=0
        val queue=ContinuousSwipeQueue<Int>({true},{_,current,done->calls++;guard=current;callback=done},{fail("unexpected abort")})
        queue.enqueue(1);queue.enqueue(2);queue.cancel();assertFalse(guard!!());callback!!(true);callback!!(true);assertEquals(1,calls)
    }
    @Test fun changedFieldOrSelectionRejectsDecodedResult() {
        var valid=true;var guard:(()->Boolean)?=null;var callback:((Boolean)->Unit)?=null;var calls=0;var aborted=0
        val queue=ContinuousSwipeQueue<Int>({valid},{_,current,done->calls++;guard=current;callback=done},{aborted++})
        queue.enqueue(1);queue.enqueue(2);valid=false;assertFalse(guard!!());callback!!(false)
        assertEquals(1,calls);assertEquals(1,aborted)
    }
    @Test fun queueLimitAbortsInsteadOfDroppingOneWord() {
        var aborted=0;val queue=ContinuousSwipeQueue<Int>({true},{_,_,_->},{aborted++})
        repeat(ContinuousSwipe.MAX_SEGMENTS+2){queue.enqueue(it)};assertEquals(1,aborted)
    }
    /** Drive the real view's Handler timer without substituting the segmenter logic. */
    private fun viewTimer(cancel: Boolean = false, edge: Boolean = false): Boolean {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val emitted = java.util.concurrent.CountDownLatch(1)
        lateinit var view: Keyboard2View
        instrumentation.runOnMainSync {
            assertTrue(TestConfigHelper.ensureConfigInitialized(context))
            ComposeKeyData.initialize(context)
            view = Keyboard2View(context)
            val layout = requireNotNull(tribixbite.cleverkeys.prefs.LayoutsPreference.layoutOfString(context.resources, "latn_qwerty_us"))
            view.setKeyboard(layout)
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val letter = layout.rows.flatMap { it.keys }.first { tribixbite.cleverkeys.swipe.KeyLetter.centreLetterOf(it.keys[0]) == 'a' }
            val segmenter = ContinuousSwipe<KeyboardData.Key>({ emitted.countDown() }) { fail("unexpected overflow") }
            val time = android.os.SystemClock.uptimeMillis()
            segmenter.sample(50f, 50f, System.currentTimeMillis()-1, time, letter, false)
            Keyboard2View::class.java.getDeclaredField("continuousSegmenter").apply { isAccessible=true; set(view, segmenter) }
            Keyboard2View::class.java.getDeclaredField("continuousSpaceBounds").apply { isAccessible=true; set(view,
                tribixbite.cleverkeys.a11y.KeyboardGeometry.KeyBounds(100f,100f,300f,200f)) }
            Keyboard2View::class.java.getDeclaredMethod("sampleContinuousSwipe", Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, KeyboardData.Key::class.java).apply { isAccessible=true }.invoke(view,
                    if (edge) 101f else 200f,150f,null)
            if (cancel) view.cancelContinuousSwipe()
        }
        val result = emitted.await(1, java.util.concurrent.TimeUnit.SECONDS)
        instrumentation.runOnMainSync { view.cancelContinuousSwipe() }
        return result
    }
    @Test fun actualViewTimerEmitsStationarySpaceDwell() { assertTrue(viewTimer()) }
    @Test fun actualViewCancelRemovesPendingSpaceTimer() { assertFalse(viewTimer(cancel=true)) }
    @Test fun actualViewPhysicalSpaceEdgeNeverSchedulesBoundary() { assertFalse(viewTimer(edge=true)) }
    @Test fun decoderExceptionAbortsQueueWithoutDispatchingNextSegment() {
        var aborted=0; var calls=0
        val queue=ContinuousSwipeQueue<Int>({true},{_,_,_->calls++;throw IllegalStateException("decoder unavailable")},{aborted++})
        queue.enqueue(1);queue.enqueue(2)
        assertEquals(1,calls);assertEquals(1,aborted)
    }

}
