package tribixbite.cleverkeys

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ProcessTextReturn.Decision

/**
 * The hand-off of a process-text app's result ([ProcessTextReturn]): only the latest request
 * counts, a result is delivered at most once, and it is written into the editor only when the
 * same field still reads back the selection it was made from — the clipboard gets it otherwise.
 * Receipts are plain strings here (the IME uses EditorReadback; only equality matters).
 */
class ProcessTextReturnTest {

    @Before fun setUp() = ProcessTextReturn.reset()
    @After fun tearDown() { ProcessTextReturn.reset(); ProcessTextReturn.onResultReady = null }

    @Test
    fun aResultIsAcceptedOnceForThePendingRequestAndTakenOnce() {
        var notified = 0
        ProcessTextReturn.onResultReady = { notified++ }
        val origin = ProcessTextReturn.begin("pkg", 3, 1, "receipt")
        assertTrue(ProcessTextReturn.isPending(origin.requestId))
        assertTrue(ProcessTextReturn.complete(origin.requestId, "result"))
        assertEquals(1, notified)
        assertFalse(ProcessTextReturn.isPending(origin.requestId))
        assertFalse("a second result for the same request", ProcessTextReturn.complete(origin.requestId, "again"))
        val ready = ProcessTextReturn.take<String>()!!
        assertEquals("result", ready.result)
        assertEquals("receipt", ready.origin.receipt)
        assertNull("delivered at most once", ProcessTextReturn.take<String>())
    }

    @Test
    fun aNewerRequestSupersedesAnOlderOneAndCancelEndsIt() {
        val first = ProcessTextReturn.begin("pkg", 3, 1, "a")
        val second = ProcessTextReturn.begin("pkg", 3, 1, "b")
        assertFalse(ProcessTextReturn.complete(first.requestId, "late"))
        assertFalse(ProcessTextReturn.hasResult())
        ProcessTextReturn.cancel(first.requestId) // stale cancel: ignored
        assertTrue(ProcessTextReturn.isPending(second.requestId))
        ProcessTextReturn.cancel(second.requestId)
        assertFalse(ProcessTextReturn.isPending(second.requestId))
        assertFalse(ProcessTextReturn.complete(second.requestId, "after cancel"))
    }

    @Test
    fun insertOnlyIntoTheSameUnchangedOrdinaryField() {
        val origin = ProcessTextReturn.begin("pkg", 3, 1, "receipt")
        fun decide(pkg: String? = "pkg", field: Int = 3, type: Int = 1, now: String? = "receipt", ordinary: Boolean = true, result: String = "ok") =
            ProcessTextReturn.decide(origin, result, pkg, field, type, now, ordinary)
        assertEquals(Decision.INSERT, decide())
        assertEquals(Decision.CLIPBOARD, decide(pkg = "other"))
        assertEquals(Decision.CLIPBOARD, decide(field = 4))
        assertEquals(Decision.CLIPBOARD, decide(type = 2))
        assertEquals("text or selection changed", Decision.CLIPBOARD, decide(now = "changed"))
        assertEquals("no readback", Decision.CLIPBOARD, decide(now = null))
        assertEquals("terminal / inline field", Decision.CLIPBOARD, decide(ordinary = false))
        assertEquals(Decision.CLIPBOARD, decide(result = "x".repeat(ProcessTextReturn.MAX_RESULT_LENGTH + 1)))
    }
}
