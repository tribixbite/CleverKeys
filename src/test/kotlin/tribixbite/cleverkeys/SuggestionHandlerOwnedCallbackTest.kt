package tribixbite.cleverkeys

import android.os.Handler
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.clearMocks
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Review 2026-10-07, findings 1 and 2: selection callbacks of the IME's OWN editor writes
 * reach [SuggestionHandler.onEditorSelectionChanged] asynchronously (Binder → main looper),
 * often after the code that wrote the text — and after a posted "next loop turn" runnable —
 * has finished. API 24–27 TextView/WebView/Compose editors deliver them later still.
 *
 *  - Finding 2: the suffix-edit callback ledger used to expire on the next main-loop turn,
 *    so a late owned callback (the selected owned space, then the new caret) dropped the
 *    receipt and Backspace no longer undid the suffix.
 *  - Finding 1 (handler side): a commit made of two writes (a typed word's separator " ",
 *    then "want ") reports the intermediate caret first; it dropped the word receipt, so an
 *    "Append 's" right after "I want" (typed "I", swiped "want") was refused.
 *
 * Safety cases pin that a genuine cursor move still invalidates ownership, and that the
 * time bound still expires an allowance whose callbacks never came.
 *
 * Harness: a real [SuggestionHandler] (Objenesis, like [SuggestionTrailingSpaceRepairTest])
 * with the real [PredictionContextTracker], an editable InputConnection double that QUEUES
 * its selection callbacks, and a main Handler that only records posts so the test decides
 * when "the next loop turn" runs. [EditorReadback.capture] is answered from the same buffer
 * because its ExtractedTextRequest() constructor is an android.jar stub in this tier.
 */
class SuggestionHandlerOwnedCallbackTest {
    private val objenesis = ObjenesisStd()
    private lateinit var tracker: PredictionContextTracker
    private lateinit var config: Config
    private lateinit var keyEvents: KeyEventHandler
    private lateinit var ic: InputConnection
    private lateinit var info: EditorInfo
    private lateinit var handler: SuggestionHandler

    private val text = StringBuilder()
    private var selStart = 0
    private var selEnd = 0
    private val queued = ArrayDeque<Pair<Int, Int>>()
    private val posted = mutableListOf<Runnable>()
    private val unowned = mutableListOf<Pair<Int, Int>>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        SystemClock.uptime = 1_000L

        config = mockk(relaxed = true)
        config.primary_language = "en"
        every { config.snapshot } returns testConfigSnapshot()
        tracker = PredictionContextTracker()
        keyEvents = mockk(relaxed = true)
        info = objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.app"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        ic = editorDouble()
        every { keyEvents.isCurrentEditor(any(), any()) } answers { firstArg<InputConnection>() === ic && secondArg<EditorInfo>() === info }

        mockkObject(EditorReadback.Companion)
        every { EditorReadback.capture(any()) } answers { readback() }

        val main = mockk<Handler>(relaxed = true)
        every { main.post(any()) } answers { posted.add(firstArg()); true }
        handler = objenesis.newInstance(SuggestionHandler::class.java).apply {
            setField("contextTracker", tracker)
            setField("predictionCoordinator", mockk<PredictionCoordinator>(relaxed = true))
            setField("suggestionBar", mockk<SuggestionBar>(relaxed = true))
            setField("config", config)
            setField("contractionManager", mockk<ContractionManager>(relaxed = true))
            setField("keyeventhandler", keyEvents)
            setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
            setField("mainHandler", main)
        }
    }

    @After
    fun teardown() { unmockkAll(); SystemClock.uptime = 0L }

    // ------------------------------------------------------------------ editor double

    private fun editorDouble(): InputConnection {
        val c = mockk<InputConnection>(relaxed = true)
        every { c.getTextBeforeCursor(any(), any()) } answers {
            val start = minOf(selStart, selEnd)
            text.substring(maxOf(0, start - firstArg<Int>()), start)
        }
        every { c.getTextAfterCursor(any(), any()) } answers {
            val end = maxOf(selStart, selEnd)
            text.substring(end, minOf(text.length, end + firstArg<Int>()))
        }
        every { c.getSelectedText(any()) } answers {
            if (selStart == selEnd) null else text.substring(minOf(selStart, selEnd), maxOf(selStart, selEnd))
        }
        every { c.finishComposingText() } returns true
        every { c.setSelection(any(), any()) } answers {
            selStart = firstArg(); selEnd = secondArg(); queued.addLast(selStart to selEnd); true
        }
        every { c.commitText(any(), any()) } answers {
            val inserted = firstArg<CharSequence>().toString()
            val start = minOf(selStart, selEnd)
            text.replace(start, maxOf(selStart, selEnd), inserted)
            selStart = start + inserted.length; selEnd = selStart
            queued.addLast(selStart to selEnd); true
        }
        return c
    }

    /** Mirrors EditorReadback.capture's fields over the double's buffer. */
    private fun readback(): EditorReadback {
        val ctor = EditorReadback::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, String::class.java, String::class.java,
        ).apply { isAccessible = true }
        val start = minOf(selStart, selEnd); val end = maxOf(selStart, selEnd)
        return ctor.newInstance(selStart, selEnd,
            text.substring(maxOf(0, start - EditorReadback.GUARD_LENGTH), start),
            text.substring(end, minOf(text.length, end + EditorReadback.GUARD_LENGTH)),
            text.substring(start, end))
    }

    private fun setEditor(content: String, caret: Int = content.length) {
        text.clear(); text.append(content); selStart = caret; selEnd = caret; queued.clear()
    }

    /** The verified-word receipt made after a commit (private; reached by reflection). */
    private fun rememberCommittedWord(word: String, ownsSpace: Boolean) {
        SuggestionHandler::class.java.getDeclaredMethod("rememberVerifiedWord",
            InputConnection::class.java, EditorInfo::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(handler, ic, info, word, ownsSpace, true)
    }

    /** The service's selection route: every queued callback, in order, through the gate. */
    private fun deliverCallbacks() {
        while (queued.isNotEmpty()) {
            val (start, end) = queued.removeFirst()
            handler.onEditorSelectionChanged(start, end) { unowned.add(start to end) }
        }
    }

    private fun runNextLoopTurn() { val due = posted.toList(); posted.clear(); due.forEach { it.run() } }

    private fun Any.setField(name: String, value: Any?) {
        var type: Class<*>? = javaClass
        while (type != null) {
            runCatching { type!!.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true; it.set(this, value); return
            }
            type = type.superclass
        }
        error("no field $name")
    }

    // ------------------------------------------------------------------ finding 2

    @Test
    fun suffixCallbacksDeliveredAfterTheNextLoopTurnKeepSuffixUndo() {
        setEditor("James ")
        rememberCommittedWord("James", ownsSpace = true)
        assertThat(handler.appendSuffix("'s")).isTrue()
        assertThat(text.toString()).isEqualTo("James's ")
        assertThat(queued.toList()).containsExactly(5 to 6, 8 to 8).inOrder()

        runNextLoopTurn() // the posted ledger expiry of the old design runs here
        SystemClock.uptime += 40
        deliverCallbacks() // late, but still this edit's own callbacks

        assertThat(handler.undoSuffix()).isTrue()
        assertThat(text.toString()).isEqualTo("James ")
    }

    @Test
    fun ownedSuffixCallbacksDoNotReachCursorTracking() {
        setEditor("James ")
        rememberCommittedWord("James", ownsSpace = true)
        assertThat(handler.appendSuffix("'s")).isTrue()
        runNextLoopTurn(); SystemClock.uptime += 40
        deliverCallbacks()
        assertThat(unowned).isEmpty()
    }

    @Test
    fun suffixCallbacksLaterThanTheTimeBoundDropOwnership() {
        setEditor("James ")
        rememberCommittedWord("James", ownsSpace = true)
        assertThat(handler.appendSuffix("'s")).isTrue()
        runNextLoopTurn()
        SystemClock.uptime += 60_000
        deliverCallbacks()
        // Conservative: an allowance whose callbacks never came in time expires.
        assertThat(handler.undoSuffix()).isFalse()
        assertThat(text.toString()).isEqualTo("James's ")
    }

    @Test
    fun manualSelectionOfTheOwnedRangeAfterTheEditStillDropsOwnership() {
        setEditor("James ")
        rememberCommittedWord("James", ownsSpace = true)
        assertThat(handler.appendSuffix("'s")).isTrue()
        runNextLoopTurn(); SystemClock.uptime += 40
        deliverCallbacks()
        // The user now selects exactly the range the edit once selected, then returns.
        ic.setSelection(5, 6); deliverCallbacks()
        ic.setSelection(8, 8); deliverCallbacks()
        assertThat(handler.undoSuffix()).isFalse()
        assertThat(text.toString()).isEqualTo("James's ")
    }

    /**
     * Device report 2026-10-07 (Saga, Android 14, Chrome textarea, build bf417bf6): "Append
     * apostrophe" attached, but the next Backspace removed the space and then the apostrophe
     * as two ordinary deletions. Chrome's InputConnection runs on its own thread and reports
     * selections asynchronously: the swiped word's own final caret can still be queued when
     * the command runs, and the command's callbacks follow several loop turns later.
     */
    @Test
    fun apostropheCommandWithChromeStyleLateCallbacksKeepsAtomicUndo() {
        setEditor("parents ")
        rememberCommittedWord("parents", ownsSpace = true)
        queued.addLast(8 to 8) // the word commit's own callback, not yet delivered
        assertThat(handler.appendSuffix("'")).isTrue()
        assertThat(text.toString()).isEqualTo("parents' ")
        assertThat(queued.toList()).containsExactly(8 to 8, 7 to 8, 9 to 9).inOrder()
        runNextLoopTurn(); runNextLoopTurn()
        SystemClock.uptime += 250
        deliverCallbacks()
        assertThat(unowned).isEmpty()
        assertThat(handler.undoSuffix()).isTrue() // one Backspace restores the pre-edit text
        assertThat(text.toString()).isEqualTo("parents ")
    }

    // ------------------------------------------------------------------ finding 1

    @Test
    fun typedPrefixSeparatorCallbackDoesNotDropTheSwipedWordReceipt() {
        setEditor("I")
        // Typed "I", swiped "want": the separator and the word are two editor writes.
        ic.commitText(" ", 1); ic.commitText("want ", 1)
        rememberCommittedWord("want", ownsSpace = true)
        assertThat(queued.toList()).containsExactly(2 to 2, 7 to 7).inOrder()
        SystemClock.uptime += 20
        deliverCallbacks()
        // The intermediate caret is this commit's own; cursor tracking sees only the final one.
        assertThat(unowned).containsExactly(7 to 7)
        assertThat(handler.appendSuffix("'s")).isTrue()
        assertThat(text.toString()).isEqualTo("I want's ")
    }

    @Test
    fun cursorAtTheWordStartIsStillAManualMove() {
        setEditor("Bowie ")
        rememberCommittedWord("Bowie", ownsSpace = true)
        ic.setSelection(0, 0); deliverCallbacks()
        ic.setSelection(6, 6); deliverCallbacks()
        assertThat(handler.appendSuffix("'s")).isFalse()
        assertThat(text.toString()).isEqualTo("Bowie ")
    }

    // ------------------------------------------------------------------ finding 3 (cost)

    /**
     * Every committed word and typed space makes a receipt, and its own final selection
     * callback follows. That callback must not re-read the editor: a later command
     * re-verifies the receipt in full before any edit.
     */
    @Test
    fun plainWordReceiptsFinalCallbackCostsNoEditorRead() {
        setEditor("James ")
        rememberCommittedWord("James", ownsSpace = true)
        queued.addLast(6 to 6)
        clearMocks(EditorReadback.Companion, answers = false)
        deliverCallbacks()
        verify(exactly = 0) { EditorReadback.capture(any()) }
        assertThat(handler.appendSuffix("'s")).isTrue() // still verified at command time
        assertThat(text.toString()).isEqualTo("James's ")
    }

    /** The whole-word check reads from the receipt's own guard text, not another round-trip. */
    @Test
    fun receiptCaptureReusesTheReadbackGuardForTheWholeWordCheck() {
        setEditor("say James ")
        clearMocks(ic, answers = false)
        rememberCommittedWord("James", ownsSpace = true)
        verify(exactly = 0) { ic.getTextBeforeCursor(any(), any()) }
        // Ownership rules are unchanged: a word that continues an earlier one is refused.
        setEditor("bobcat ")
        rememberCommittedWord("cat", ownsSpace = true)
        assertThat(handler.appendSuffix("'s")).isFalse()
    }
}
