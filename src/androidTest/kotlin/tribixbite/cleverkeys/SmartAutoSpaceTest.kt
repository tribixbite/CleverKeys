package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SAS-1 (v1.5.0, user-requested): smart auto-space around punctuation.
 *
 * Feature A — no leading auto-space after opening punctuation:
 *   `(` + swipe "word" → `(word ` (was `( word `).
 *
 * Feature B — closing punctuation swallows the AUTOMATIC trailing space:
 *   swipe "word" (commits `word `) then type `.` → `word.` (was `word .`).
 *   Only the automatic space may be swallowed: manually typed spaces,
 *   cursor movement, backspace, and field switches all preserve the space.
 *
 * Harness mirrors Issue151UrlBarSuggestionTapTest (real SuggestionHandler,
 * BaseInputConnection over its internal editable) and additionally wires a
 * real KeyEventHandler whose IReceiver delegates the auto-space pending
 * state to the shared PredictionContextTracker — exactly as
 * KeyEventReceiverBridge does in production. Punctuation is typed through
 * the production key path: KeyEventHandler.key_up → sendText.
 */
@RunWith(AndroidJUnit4::class)
class SmartAutoSpaceTest {

    /**
     * BaseInputConnection(view, fullEditor=true) edits its OWN internal
     * Editable. getExtractedText is overridden (Base returns null) so the
     * position-stamp validation path is exercised like in real editors.
     */
    private enum class CommitBehavior { NORMAL, REJECT, THROW_UNCHANGED, FALSE_AFTER_APPLY, THROW_AFTER_APPLY, PARTIAL, PREFIX_WORD }
    private enum class FinishBehavior { NORMAL, REJECT, THROW, MOVE_CURSOR, MUTATE_TEXT }

    private class TestInputConnection(target: EditText) : BaseInputConnection(target, true) {
        var commitBehavior = CommitBehavior.NORMAL
        var finishBehavior = FinishBehavior.NORMAL
        var commitCalls = 0
        var selectionCallback: ((Int, Int) -> Unit)? = null
        var queueSelectionCallbacks = false
        var refuseSelectionRestore = false
        var fallbackDeleteCalls = 0
        val queuedCallbacks = mutableListOf<Pair<Int, Int>>()
        fun editableText(): String = editable?.toString() ?: ""

        private fun selectionChanged() {
            val ed = editable ?: return
            val event = Selection.getSelectionStart(ed) to Selection.getSelectionEnd(ed)
            if (queueSelectionCallbacks) queuedCallbacks.add(event)
            else selectionCallback?.invoke(event.first, event.second)
        }

        fun deliverQueuedCallbacks() {
            val events = queuedCallbacks.toList()
            queuedCallbacks.clear()
            events.forEach { selectionCallback?.invoke(it.first, it.second) }
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            if (refuseSelectionRestore && start == end &&
                Selection.getSelectionStart(editable) != Selection.getSelectionEnd(editable)) return false
            return super.setSelection(start, end).also { selectionChanged() }
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            fallbackDeleteCalls++
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DEL) fallbackDeleteCalls++
            return super.sendKeyEvent(event)
        }

        override fun finishComposingText(): Boolean {
            when (finishBehavior) {
                FinishBehavior.REJECT -> return false
                FinishBehavior.THROW -> throw IllegalStateException("Cannot finish composition")
                FinishBehavior.MOVE_CURSOR -> setCursor(0)
                FinishBehavior.MUTATE_TEXT -> editable?.insert(0, "!")
                FinishBehavior.NORMAL -> Unit
            }
            return super.finishComposingText().also { selectionChanged() }
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commitCalls++
            when (commitBehavior) {
                CommitBehavior.REJECT -> return false
                CommitBehavior.THROW_UNCHANGED -> throw IllegalStateException("Rejected without mutation")
                else -> Unit
            }
            val inserted = when (commitBehavior) {
                CommitBehavior.PARTIAL -> "'"
                CommitBehavior.PREFIX_WORD -> "bob$text"
                else -> text
            }
            val accepted = super.commitText(inserted, newCursorPosition)
            selectionChanged()
            return when (commitBehavior) {
                CommitBehavior.FALSE_AFTER_APPLY -> false
                CommitBehavior.THROW_AFTER_APPLY -> throw IllegalStateException("Applied before throw")
                else -> accepted
            }
        }

        fun setCursor(position: Int) {
            editable?.let { Selection.setSelection(it, position) }
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            val et = ExtractedText()
            val ed = editable
            et.text = ed?.toString() ?: ""
            et.startOffset = 0
            val start = ed?.let { Selection.getSelectionStart(it) } ?: 0
            val end = ed?.let { Selection.getSelectionEnd(it) } ?: 0
            et.selectionStart = if (start >= 0) start else (ed?.length ?: 0)
            et.selectionEnd = if (end >= 0) end else (ed?.length ?: 0)
            return et
        }
    }

    private lateinit var context: Context
    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var suggestionHandler: SuggestionHandler
    private lateinit var keyEventHandler: KeyEventHandler
    private lateinit var inputConnection: TestInputConnection
    private lateinit var editText: EditText

    private val plainEditorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "com.example.notes"
    }

    companion object {
        private var sharedPredictor: WordPredictor? = null
        private var sharedConfig: Config? = null
        private var sharedCoordinator: PredictionCoordinator? = null
        private var sharedContractions: ContractionManager? = null
        @Volatile private var initAttempted = false
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Config must initialize", TestConfigHelper.ensureConfigInitialized(context))
        contextTracker = PredictionContextTracker()

        synchronized(SmartAutoSpaceTest::class.java) {
            if (!initAttempted) {
                initAttempted = true
                try {
                    sharedConfig = Config.globalConfig()
                    sharedContractions = ContractionManager(context).also { it.loadMappings() }
                    sharedPredictor = WordPredictor().apply {
                        setContext(context)
                        setConfig(sharedConfig!!)
                        loadDictionary(context, "en")
                    }
                    sharedCoordinator = PredictionCoordinator(context, sharedConfig!!)
                    PredictionCoordinator::class.java.getDeclaredField("wordPredictor").apply {
                        isAccessible = true
                        set(sharedCoordinator, sharedPredictor)
                    }
                } catch (e: OutOfMemoryError) {
                    sharedPredictor = null
                }
            }
        }
        org.junit.Assert.assertNotNull("WordPredictor must initialize; OOM is a test failure", sharedPredictor)

        // Pin every pref this feature interacts with to a deterministic state.
        sharedConfig!!.word_prediction_enabled = true
        sharedConfig!!.autocorrect_enabled = false
        sharedConfig!!.swipe_final_autocorrect_enabled = false
        sharedConfig!!.auto_space_before_suggestion = true
        sharedConfig!!.auto_space_after_suggestion = true
        sharedConfig!!.smart_punctuation = true
        sharedConfig!!.double_space_to_period = false
        sharedConfig!!.backspace_undo_swipe = false
        sharedConfig!!.backspace_undo_autocorrect = false
        sharedConfig!!.termux_mode_enabled = false

        // Receiver mirrors KeyEventReceiverBridge: auto-space pending state
        // lives on the shared PredictionContextTracker.
        val receiver = object : KeyEventHandler.IReceiver {
            override fun handle_event_key(ev: KeyValue.Event) {}
            override fun set_shift_state(state: Boolean, lock: Boolean) {}
            override fun set_compose_pending(pending: Boolean) {}
            override fun selection_state_changed(selectionIsOngoing: Boolean) {}
            override fun getCurrentEditorInfo(): EditorInfo = plainEditorInfo
            override fun getContext(): Context = context
            override fun getCurrentInputConnection(): InputConnection? = inputConnection
            override fun getHandler(): Handler = Handler(Looper.getMainLooper())
            override fun handle_text_typed(text: String) {}
            override fun getLastAutoInsertedWord(): String? = contextTracker.getLastAutoInsertedWord()
            override fun clearSwipeUndoState() = contextTracker.clearLastAutoInsertedWord()
            override fun getLastAutocorrectOriginalWord(): String? = contextTracker.getLastAutocorrectOriginalWord()
            override fun wasLastSpaceAutoInserted(): Boolean =
                contextTracker.lastSpaceWasAutoInserted
            override fun setLastSpaceAutoInserted(value: Boolean) {
                if (value) {
                    contextTracker.lastSpaceWasAutoInserted = true
                } else {
                    contextTracker.invalidateAutoSpacePending()
                }
            }
            override fun getAutoSpaceStampedPosition(): Int =
                contextTracker.autoSpaceStampedPosition
            override fun markAutoSpacePending(expectedCursorPosition: Int) =
                contextTracker.markAutoSpacePending(expectedCursorPosition)
        }
        keyEventHandler = KeyEventHandler(receiver)
        suggestionHandler = SuggestionHandler(
            context, sharedConfig!!, contextTracker,
            sharedCoordinator!!, sharedContractions!!, keyEventHandler
        )

        keyEventHandler.learningHooks = suggestionHandler

        val latch = CountDownLatch(1)
        val editHolder = arrayOfNulls<EditText>(1)
        val barHolder = arrayOfNulls<SuggestionBar>(1)
        Handler(Looper.getMainLooper()).post {
            barHolder[0] = SuggestionBar(context)
            editHolder[0] = EditText(context)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        suggestionHandler.setSuggestionBar(barHolder[0]!!)
        editText = editHolder[0]!!
        inputConnection = TestInputConnection(editText)
    }

    /** Production swipe auto-insert path: wasLastInputSwipe → onSuggestionSelected. */
    private fun swipe(word: String) {
        contextTracker.setWasLastInputSwipe(true)
        suggestionHandler.onSuggestionSelected(
            word, inputConnection, plainEditorInfo, context.resources, isManualSelection = false
        )
    }

    /** Production suggestion-tap path (shares the same auto-space mechanism). */
    private fun tap(word: String) {
        suggestionHandler.onSuggestionSelected(
            word, inputConnection, plainEditorInfo, context.resources, isManualSelection = true
        )
    }

    /** Production key path for typed characters: key_up → sendText. */
    private fun press(c: Char) {
        keyEventHandler.key_up(KeyValue.makeCharKey(c), Pointers.Modifiers.EMPTY, false)
    }

    private fun pressBackspace() {
        keyEventHandler.key_up(
            KeyValue.getKeyByName("backspace"), Pointers.Modifiers.EMPTY, false
        )
    }

    private fun editorText() = inputConnection.editableText()

    @Test fun possessiveCommandAttachesAndUndoOnlyRemovesSuffix() {
        swipe("James")
        assertTrue(keyEventHandler.execute_suffix("'s"))
        assertEquals("James's ", editorText())
        pressBackspace()
        assertEquals("James ", editorText())
        assertEquals(listOf("james"), contextTracker.getContextWords())
    }
    @Test fun suffixUndoRestoresSwipeSourceAndSecondBackspaceDeletesBase() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sharedConfig!!.backspace_undo_swipe = true
            val view = Keyboard2View(context)
            val coordinator = InputCoordinator(context, sharedConfig!!, contextTracker, sharedCoordinator!!,
                SuggestionBar(context), { view }).apply { setSwipeResultDelegate(suggestionHandler) }
            try {
                // Use the outer production results boundary: it stamps SWIPE after
                // onSuggestionSelected has captured the verified word receipt.
                contextTracker.setWasLastInputSwipe(true)
                coordinator.handlePredictionResults(listOf("James"), listOf(100), inputConnection,
                    plainEditorInfo, context.resources)
                assertEquals(PredictionSource.SWIPE, contextTracker.getLastCommitSource())
                val original = sharedPredictor!!.latestLearningCommit()
                assertTrue(keyEventHandler.execute_suffix("'s"))
                val suffixed = sharedPredictor!!.latestLearningCommit()
                assertTrue(original !== suffixed)
                pressBackspace()
                assertEquals("James ", editorText())
                assertEquals("James", contextTracker.getLastAutoInsertedWord())
                assertEquals(PredictionSource.SWIPE, contextTracker.getLastCommitSource())
                val restored = sharedPredictor!!.latestLearningCommit()
                assertTrue(suffixed !== restored)
                assertFalse(sharedPredictor!!.canReplaceLearningCommit(original!!))
                assertFalse(sharedPredictor!!.canReplaceLearningCommit(suffixed!!))
                pressBackspace()
                assertEquals("", editorText())
                assertTrue(contextTracker.getContextWords().isEmpty())
            } finally {
                sharedConfig!!.backspace_undo_swipe = false
                coordinator.shutdown()
            }
        }
    }

    @Test fun manualSuggestionSuffixUndoDoesNotAcquireWholeSwipeUndo() {
        tap("James")
        assertTrue(keyEventHandler.execute_suffix("'s"))
        pressBackspace()
        assertEquals("James ", editorText())
        assertEquals(null, contextTracker.getLastAutoInsertedWord())
        assertFalse(suggestionHandler.undoSuffix())
    }

    @Test fun typedBoundarySuffixUndoDoesNotAcquireWholeSwipeUndo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (c in "James ") {
                val text = c.toString()
                inputConnection.commitText(text, 1)
                suggestionHandler.handleRegularTyping(text, inputConnection, plainEditorInfo)
            }
            assertTrue(keyEventHandler.execute_suffix("'s"))
            assertEquals("James's ", editorText())
            pressBackspace()
            assertEquals("James ", editorText())
            assertEquals(null, contextTracker.getLastAutoInsertedWord())
            assertFalse(suggestionHandler.undoSuffix())
        }
    }

    @Test fun suffixFinishesComposingBaseBeforeSpacelessInsertion() {
        assertComposingBasePreserved(automaticSpace = false)
    }

    @Test fun suffixFinishesComposingBaseBeforeSelectingAutomaticSpace() {
        assertComposingBasePreserved(automaticSpace = true)
    }

    private fun assertComposingBasePreserved(automaticSpace: Boolean) {
        sharedConfig!!.auto_space_after_suggestion = automaticSpace
        try {
            swipe("James")
            assertTrue(inputConnection.setComposingRegion(0, 5))
            assertEquals(0, BaseInputConnection.getComposingSpanStart(requireNotNull(inputConnection.editable)))
            assertTrue(keyEventHandler.execute_suffix("'s"))
            assertEquals("James's" + if (automaticSpace) " " else "", editorText())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(requireNotNull(inputConnection.editable)))
            pressBackspace()
            assertEquals("James" + if (automaticSpace) " " else "", editorText())
        } finally { sharedConfig!!.auto_space_after_suggestion = true }
    }

    @Test fun rejectedFinishCompositionPreventsSuffixWrite() {
        assertFinishRefusesSuffixWrite(FinishBehavior.REJECT, "James ")
    }

    @Test fun throwingFinishCompositionPreventsSuffixWrite() {
        assertFinishRefusesSuffixWrite(FinishBehavior.THROW, "James ")
    }

    @Test fun finishCompositionChangingCursorPreventsSuffixWrite() {
        assertFinishRefusesSuffixWrite(FinishBehavior.MOVE_CURSOR, "James ")
    }

    @Test fun finishCompositionChangingTextPreventsSuffixWrite() {
        assertFinishRefusesSuffixWrite(FinishBehavior.MUTATE_TEXT, "!James ")
    }

    private fun assertFinishRefusesSuffixWrite(behavior: FinishBehavior, expectedText: String) {
        swipe("James")
        assertTrue(inputConnection.setComposingRegion(0, 5))
        inputConnection.commitCalls = 0
        inputConnection.finishBehavior = behavior
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals(0, inputConnection.commitCalls)
        assertEquals(expectedText, editorText())
        inputConnection.finishBehavior = FinishBehavior.NORMAL
        assertFalse(keyEventHandler.execute_suffix("'s"))
    }

    @Test fun verifiedContinuousSeparatorPreservesLearningAndAllowsSuffix() {
        sharedConfig!!.auto_space_after_suggestion = false
        try {
            swipe("James")
            val handle = sharedPredictor!!.latestLearningCommit()
            val words = contextTracker.getContextWords()
            assertTrue(inputConnection.commitText(" ", 1))
            assertTrue(suggestionHandler.onContinuousSeparatorAccepted(inputConnection, plainEditorInfo, "James"))
            assertTrue(handle === sharedPredictor!!.latestLearningCommit())
            assertEquals(words, contextTracker.getContextWords())
            assertTrue(contextTracker.lastSpaceWasAutoInserted)
            assertEquals(6, contextTracker.autoSpaceStampedPosition)
            assertTrue(keyEventHandler.execute_suffix("'s"))
            assertEquals("James's ", editorText())
            pressBackspace()
            assertEquals("James ", editorText())
            assertEquals("James", contextTracker.getLastAutoInsertedWord())
        } finally { sharedConfig!!.auto_space_after_suggestion = true }
    }

    @Test fun continuousSeparatorSynchronousCallbacksPreserveSuffixOwnership() {
        assertContinuousSeparatorCallbacks(queue = false)
    }

    @Test fun continuousSeparatorQueuedCallbacksPreserveSuffixOwnership() {
        assertContinuousSeparatorCallbacks(queue = true)
    }

    private fun assertContinuousSeparatorCallbacks(queue: Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sharedConfig!!.auto_space_after_suggestion = false
            try {
                swipe("James")
                val handle = sharedPredictor!!.latestLearningCommit()
                inputConnection.selectionCallback = { start, end -> suggestionHandler.validateWordReceiptSelection(start, end) }
                inputConnection.queueSelectionCallbacks = queue
                assertTrue(inputConnection.setComposingRegion(0, 5))
                assertTrue(suggestionHandler.appendContinuousSeparator(inputConnection, plainEditorInfo, "James") { true })
                if (queue) {
                    assertEquals(listOf(5 to 5, 6 to 6), inputConnection.queuedCallbacks)
                    inputConnection.deliverQueuedCallbacks()
                }
                assertEquals("James ", editorText())
                assertTrue(handle === sharedPredictor!!.latestLearningCommit())
                assertTrue(keyEventHandler.execute_suffix("'s"))
                if (queue) inputConnection.deliverQueuedCallbacks()
                pressBackspace()
                if (queue) inputConnection.deliverQueuedCallbacks()
                assertEquals("James ", editorText())
                assertEquals(PredictionSource.SWIPE, contextTracker.getLastCommitSource())
            } finally { sharedConfig!!.auto_space_after_suggestion = true }
        }
    }

    @Test fun continuousSeparatorRejectedWriteDropsOwnershipWithoutRelearning() {
        sharedConfig!!.auto_space_after_suggestion = false
        try {
            swipe("James")
            val handle = sharedPredictor!!.latestLearningCommit()
            inputConnection.commitBehavior = CommitBehavior.REJECT
            assertFalse(suggestionHandler.appendContinuousSeparator(inputConnection, plainEditorInfo, "James") { true })
            assertEquals("James", editorText())
            assertTrue(handle === sharedPredictor!!.latestLearningCommit())
            inputConnection.commitBehavior = CommitBehavior.NORMAL
            assertFalse(keyEventHandler.execute_suffix("'s"))
        } finally { sharedConfig!!.auto_space_after_suggestion = true }
    }

    @Test fun continuousSeparatorRechecksSessionAfterFinishingComposition() {
        sharedConfig!!.auto_space_after_suggestion = false
        try {
            swipe("James")
            var current = true
            inputConnection.selectionCallback = { _, _ -> current = false }
            inputConnection.commitCalls = 0
            assertFalse(suggestionHandler.appendContinuousSeparator(inputConnection, plainEditorInfo, "James") { current })
            assertEquals(0, inputConnection.commitCalls)
            assertEquals("James", editorText())
        } finally { sharedConfig!!.auto_space_after_suggestion = true }
    }

    @Test fun continuousSeparatorReceiptRejectsExtraTextAndSessionChange() {
        sharedConfig!!.auto_space_after_suggestion = false
        try {
            swipe("James")
            inputConnection.commitText("  ", 1)
            assertFalse(suggestionHandler.onContinuousSeparatorAccepted(inputConnection, plainEditorInfo, "James"))
            suggestionHandler.onEditorSessionChanged()
            assertFalse(suggestionHandler.onContinuousSeparatorAccepted(inputConnection, plainEditorInfo, "James"))
            assertFalse(keyEventHandler.execute_suffix("'s"))
        } finally { sharedConfig!!.auto_space_after_suggestion = true }
    }

    @Test fun synchronousOwnedSelectionCallbacksPreserveSuffixUndo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            swipe("James")
            inputConnection.selectionCallback = { start, end -> suggestionHandler.validateWordReceiptSelection(start, end) }
            assertTrue(keyEventHandler.execute_suffix("'s"))
            pressBackspace()
            assertEquals("James ", editorText())
        }
    }

    @Test fun queuedOwnedSelectionCallbacksPreserveSuffixUndo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            swipe("James")
            inputConnection.selectionCallback = { start, end -> suggestionHandler.validateWordReceiptSelection(start, end) }
            inputConnection.queueSelectionCallbacks = true
            assertTrue(keyEventHandler.execute_suffix("'s"))
            assertEquals(listOf(6 to 6, 5 to 6, 8 to 8), inputConnection.queuedCallbacks)
            inputConnection.deliverQueuedCallbacks()
            pressBackspace()
            inputConnection.deliverQueuedCallbacks()
            assertEquals("James ", editorText())
        }
    }

    /** Real downstream consumers behind the same gate used by CleverKeysService. */
    private class SelectionRoute(
        val view: Keyboard2View,
        val coordinator: InputCoordinator,
        val uiSelections: MutableList<Pair<Int, Int>>,
    )

    private fun wireSelectionRoute(initialReportedPosition: Int = 0): SelectionRoute {
        val view = Keyboard2View(context)
        val coordinator = InputCoordinator(context, sharedConfig!!, contextTracker, sharedCoordinator!!,
            SuggestionBar(context), { view }).apply { setCursorSyncDelegate(suggestionHandler) }
        val uiSelections = mutableListOf<Pair<Int, Int>>()
        var previous = initialReportedPosition to initialReportedPosition
        inputConnection.selectionCallback = { start, end ->
            // UI is unconditional, just as in the service; the production gate
            // alone decides whether manual cursor consumers see this callback.
            keyEventHandler.selection_updated(previous.first, start)
            if ((previous.first == previous.second) != (start == end)) view.set_selection_state(start != end)
            uiSelections.add(start to end)
            suggestionHandler.onEditorSelectionChanged(start, end) {
                view.onContinuousSelectionChanged(start, end)
                if (start == end && previous.first != start) {
                    coordinator.onCursorMoved(start, inputConnection, "en", plainEditorInfo)
                }
            }
            previous = start to end
        }
        return SelectionRoute(view, coordinator, uiSelections)
    }

    private fun setContinuousExpected(view: Keyboard2View, expected: EditorReadback) {
        Keyboard2View::class.java.getDeclaredField("continuousExpected").apply {
            isAccessible = true; set(view, expected)
        }
    }

    private fun continuousExpected(view: Keyboard2View): Any? =
        Keyboard2View::class.java.getDeclaredField("continuousExpected").run {
            isAccessible = true; get(view)
        }

    @Test fun queuedSuffixCallbacksPreserveAutomaticSpaceThroughCursorCoordinator() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            swipe("James")
            // The editor's previous reported position can lag the current buffer;
            // its original-caret callback must not erase the later suffix stamp.
            val route = wireSelectionRoute(initialReportedPosition = 0)
            try {
                inputConnection.queueSelectionCallbacks = true
                assertTrue(keyEventHandler.execute_suffix("'s"))
                inputConnection.deliverQueuedCallbacks()
                assertEquals(listOf(6 to 6, 5 to 6, 8 to 8), route.uiSelections)
                assertTrue(contextTracker.lastSpaceWasAutoInserted)
                assertEquals(8, contextTracker.autoSpaceStampedPosition)
                inputConnection.queueSelectionCallbacks = false
                press('.')
                // Closing sentence punctuation re-adds its configured trailing space.
                assertEquals("James's. ", editorText())
            } finally { route.coordinator.shutdown(); route.view.cancelContinuousSwipe() }
        }
    }

    @Test fun queuedContinuousSeparatorCallbacksPreserveActualViewPhraseState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sharedConfig!!.auto_space_after_suggestion = false
            swipe("James")
            val route = wireSelectionRoute(initialReportedPosition = 5)
            try {
                inputConnection.queueSelectionCallbacks = true
                assertTrue(suggestionHandler.appendContinuousSeparator(inputConnection, plainEditorInfo, "James") { true })
                val expected = requireNotNull(EditorReadback.capture(inputConnection))
                // The view publishes this final receipt after separator acceptance.
                setContinuousExpected(route.view, expected)
                inputConnection.deliverQueuedCallbacks()
                assertEquals(listOf(5 to 5, 6 to 6), route.uiSelections)
                assertTrue(expected === continuousExpected(route.view))
                assertTrue(contextTracker.lastSpaceWasAutoInserted)
                assertEquals(6, contextTracker.autoSpaceStampedPosition)
                assertTrue(keyEventHandler.execute_suffix("'s"))
                assertEquals("James's ", editorText())
            } finally {
                sharedConfig!!.auto_space_after_suggestion = true
                route.coordinator.shutdown(); route.view.cancelContinuousSwipe()
            }
        }
    }

    @Test fun manualSelectionStillCancelsActualViewAndInvalidatesSuffixAndSpace() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            swipe("James")
            assertTrue(keyEventHandler.execute_suffix("'s"))
            val route = wireSelectionRoute(initialReportedPosition = 8)
            try {
                setContinuousExpected(route.view, requireNotNull(EditorReadback.capture(inputConnection)))
                inputConnection.setSelection(0, 0)
                assertEquals(null, continuousExpected(route.view))
                assertFalse(contextTracker.lastSpaceWasAutoInserted)
                inputConnection.setSelection(8, 8)
                assertEquals(listOf(0 to 0, 8 to 8), route.uiSelections)
                assertFalse(suggestionHandler.undoSuffix())
                assertEquals("James's ", editorText())
            } finally { route.coordinator.shutdown(); route.view.cancelContinuousSwipe() }
        }
    }

    @Test fun manualOwnedTailSelectionIsNotAnOwnedCallback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            swipe("James")
            inputConnection.selectionCallback = { start, end -> suggestionHandler.validateWordReceiptSelection(start, end) }
            assertTrue(keyEventHandler.execute_suffix("'s"))
            inputConnection.setSelection(5, 6)
            inputConnection.setSelection(8, 8)
            assertFalse(suggestionHandler.undoSuffix())
            assertEquals("James's ", editorText())
        }
    }

    @Test fun suffixedCursorAwayAndBackCannotReviveUndo() {
        swipe("James")
        assertTrue(keyEventHandler.execute_suffix("'s"))
        inputConnection.setCursor(0)
        suggestionHandler.validateWordReceiptSelection(0, 0)
        inputConnection.setCursor(8)
        assertFalse(suggestionHandler.undoSuffix())
        assertEquals("James's ", editorText())
    }

    @Test fun staleSuffixUndoConsumesBackspaceWithoutDeletingAnotherCharacter() {
        swipe("James")
        assertTrue(keyEventHandler.execute_suffix("'s"))
        inputConnection.setCursor(0) // No callback: the undo path must discover the stale editor.
        assertTrue(suggestionHandler.undoSuffix())
        assertEquals("James's ", editorText())
    }

    @Test fun rejectedSuffixUndoConsumesRealBackspaceWithoutFallbackDeletion() {
        swipe("James")
        assertTrue(keyEventHandler.execute_suffix("'s"))
        inputConnection.commitBehavior = CommitBehavior.REJECT
        inputConnection.fallbackDeleteCalls = 0
        pressBackspace()
        assertEquals("James's ", editorText())
        assertEquals(0, inputConnection.fallbackDeleteCalls)
        val state = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
        assertEquals(8, state.selectionStart); assertEquals(8, state.selectionEnd)
    }

    @Test fun reentrantSessionChangeDuringSuffixEditDoesNotRepublishOldReceipt() {
        swipe("James")
        inputConnection.selectionCallback = { _, _ -> suggestionHandler.onEditorSessionChanged() }
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals("James ", editorText())
        inputConnection.selectionCallback = null
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertFalse(suggestionHandler.undoSuffix())
    }

    @Test fun editorPrefixTransformationCannotAuthorizeSuffixOfLargerWord() {
        inputConnection.commitBehavior = CommitBehavior.PREFIX_WORD
        swipe("cat")
        inputConnection.commitBehavior = CommitBehavior.NORMAL
        assertEquals("bobcat ", editorText())
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals("bobcat ", editorText())
    }

    @Test fun rejectedSuffixCommitRestoresOriginalCaretAndInvalidatesOwnership() {
        assertRejectedSuffixLeavesOriginalCaret(CommitBehavior.REJECT)
    }

    @Test fun throwingUnchangedSuffixCommitRestoresOriginalCaret() {
        assertRejectedSuffixLeavesOriginalCaret(CommitBehavior.THROW_UNCHANGED)
    }

    private fun assertRejectedSuffixLeavesOriginalCaret(behavior: CommitBehavior) {
        swipe("James")
        inputConnection.commitBehavior = behavior
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals("James ", editorText())
        val state = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
        assertEquals(6, state.selectionStart); assertEquals(6, state.selectionEnd)
        inputConnection.commitBehavior = CommitBehavior.NORMAL
        assertFalse(keyEventHandler.execute_suffix("'s"))
        inputConnection.commitText("next", 1)
        assertEquals("James next", editorText())
    }

    @Test fun failedCaretRestoreDoesNotAttemptCompensatingTextWrite() {
        swipe("James")
        inputConnection.commitBehavior = CommitBehavior.REJECT
        inputConnection.refuseSelectionRestore = true
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals("James ", editorText())
        val state = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
        assertEquals(5, state.selectionStart); assertEquals(6, state.selectionEnd)
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
        assertFalse(suggestionHandler.undoSuffix())
    }

    @Test fun partiallyAppliedSuffixDoesNotGainOwnershipOrCompensateText() {
        swipe("James")
        inputConnection.commitBehavior = CommitBehavior.PARTIAL
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals("James'", editorText())
        assertFalse(suggestionHandler.undoSuffix())
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
    }

    @Test fun falseAfterVerifiedSuffixApplicationIsAcceptedOnce() {
        assertAppliedSuffixRecognized(CommitBehavior.FALSE_AFTER_APPLY)
    }

    @Test fun throwAfterVerifiedSuffixApplicationIsAcceptedOnce() {
        assertAppliedSuffixRecognized(CommitBehavior.THROW_AFTER_APPLY)
    }

    private fun assertAppliedSuffixRecognized(behavior: CommitBehavior) {
        swipe("James")
        inputConnection.commitBehavior = behavior
        assertTrue(keyEventHandler.execute_suffix("'s"))
        assertEquals("James's ", editorText())
        inputConnection.commitBehavior = CommitBehavior.NORMAL
        pressBackspace()
        assertEquals("James ", editorText())
    }

    @Test fun apostropheCommandPreservesPluralStem() {
        swipe("parents")
        assertTrue(keyEventHandler.execute_suffix("'"))
        assertEquals("parents' ", editorText())
        assertFalse(keyEventHandler.execute_suffix("'s"))
        pressBackspace()
        assertEquals("parents ", editorText())
    }
    @Test fun suffixWithoutAutomaticSpaceStaysSpaceless() {
        sharedConfig!!.auto_space_after_suggestion = false
        try {
            swipe("Bowie")
            assertTrue(keyEventHandler.execute_suffix("'s"))
            assertEquals("Bowie's", editorText())
            pressBackspace(); assertEquals("Bowie", editorText())
        } finally { sharedConfig!!.auto_space_after_suggestion = true }
    }
    @Test fun cursorAwayAndBackCannotReviveSuffixOwnership() {
        swipe("Bowie")
        inputConnection.setCursor(0)
        suggestionHandler.validateWordReceiptSelection(0, 0)
        inputConnection.setCursor(6)
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals("Bowie ", editorText())
    }
    @Test fun selectedRangeRefusesSuffixWithoutDeletingText() {
        swipe("Bowie")
        inputConnection.setSelection(0, 5)
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals("Bowie ", editorText())
    }
    @Test fun sessionChangeRefusesSuffix() {
        swipe("Bowie")
        suggestionHandler.onEditorSessionChanged()
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals("Bowie ", editorText())
    }
    @Test fun literalApostropheInvalidatesExplicitSuffixReceipt() {
        swipe("Bowie")
        press('\'')
        assertFalse(keyEventHandler.execute_suffix("'s"))
        assertEquals("Bowie'", editorText())
    }

    @Test
    fun rejectedSuggestionCommitDoesNotOwnAnAutomaticSpaceOrContextWord() {
        val rejected = object : InputConnectionWrapper(inputConnection, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = false
        }
        contextTracker.setWasLastInputSwipe(true)
        assertEquals(null, suggestionHandler.onSuggestionSelected(
            "Bowie", rejected, plainEditorInfo, context.resources, isManualSelection = false
        ))
        assertEquals("", editorText())
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
        assertEquals(-1, contextTracker.autoSpaceStampedPosition)
        assertTrue(contextTracker.getContextWords().isEmpty())
        assertEquals(PredictionSource.UNKNOWN, contextTracker.getLastCommitSource())
    }

    @Test
    fun throwingSuggestionCommitDoesNotOwnAnAutomaticSpaceOrContextWord() {
        val rejected = object : InputConnectionWrapper(inputConnection, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                throw IllegalStateException("Editor unavailable")
        }
        contextTracker.setWasLastInputSwipe(true)
        assertEquals(null, suggestionHandler.onSuggestionSelected(
            "Bowie", rejected, plainEditorInfo, context.resources, isManualSelection = false
        ))
        assertEquals("", editorText())
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
        assertTrue(contextTracker.getContextWords().isEmpty())
        assertEquals(PredictionSource.UNKNOWN, contextTracker.getLastCommitSource())
    }

    @Test
    fun curlyApostropheAttachesToOwnedSpaceAndFollowingSTypesNormally() {
        swipe("Bowie")
        press('’')
        press('s')
        assertEquals("Bowie’s", editorText())
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
    }

    @Test
    fun curlyApostropheDoesNotConsumeAManualSpace() {
        inputConnection.commitText("Bowie ", 1)
        press('’')
        assertEquals("Bowie ’", editorText())
    }

    @Test
    fun apostropheReplacesSelectedTextWithoutReclaimingSpaceBeforeSelection() {
        swipe("Bowie")
        // Simulate an editor-side edit without cursor callbacks, then select the added
        // text. Its start equals the old space stamp but its end is different.
        inputConnection.commitText("abc", 1)
        assertTrue(inputConnection.setSelection(6, 9))
        val extracted = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
        assertEquals(6, extracted.selectionStart)
        assertEquals(9, extracted.selectionEnd)
        press('\'')
        assertEquals("Bowie '", editorText())
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
    }

    // ── Feature A: no leading auto-space after opening punctuation ──────────

    @Test
    fun openParen_swipe_noLeadingSpace() {
        inputConnection.commitText("(", 1)
        swipe("word")
        assertEquals("(word ", editorText())
    }

    @Test
    fun openBracketBrace_swipe_noLeadingSpace() {
        inputConnection.commitText("[", 1)
        swipe("word")
        assertEquals("[word ", editorText())
    }

    @Test
    fun straightDoubleQuote_atFieldStart_swipe_noLeadingSpace() {
        inputConnection.commitText("\"", 1)
        swipe("word")
        assertEquals("\"word ", editorText())
    }

    @Test
    fun straightDoubleQuote_afterSpace_swipe_noLeadingSpace() {
        inputConnection.commitText("He said \"", 1)
        swipe("word")
        assertEquals("He said \"word ", editorText())
    }

    @Test
    fun curlyOpeningSingleQuote_swipe_noLeadingSpace() {
        inputConnection.commitText("‘", 1)
        swipe("word")
        assertEquals("‘word ", editorText())
    }

    @Test
    fun curlyOpeningDoubleQuote_swipe_noLeadingSpace() {
        inputConnection.commitText("she wrote “", 1)
        swipe("word")
        assertEquals("she wrote “word ", editorText())
    }

    @Test
    fun invertedQuestionAndExclamation_swipe_noLeadingSpace() {
        inputConnection.commitText("¿", 1)
        swipe("qué")
        assertEquals("¿qué ", editorText())
    }

    @Test
    fun apostropheAfterLetter_possessive_swipe_keepsLeadingSpace() {
        // `'` right after a letter is a possessive/closing quote, NOT an opener
        inputConnection.commitText("kids'", 1)
        swipe("toys")
        assertEquals("kids' toys ", editorText())
    }

    @Test
    fun openParen_tapPath_noLeadingSpace() {
        // Suggestion taps share the same auto-space mechanism as swipes
        inputConnection.commitText("(", 1)
        tap("word")
        assertEquals("(word ", editorText())
    }

    // ── Feature B: closing punctuation swallows the AUTOMATIC space ─────────

    @Test
    fun period_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        assertEquals("word ", editorText())
        press('.')
        // Sentence-ending punctuation re-adds a trailing space (v1.2.8 autocap)
        assertEquals("word. ", editorText())
    }

    @Test
    fun closeParen_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        press(')')
        assertEquals("word)", editorText())
    }

    @Test
    fun comma_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        press(',')
        assertEquals("word,", editorText())
    }

    @Test
    fun curlyClosingQuote_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        press('”')
        assertEquals("word”", editorText())
    }

    @Test
    fun ellipsis_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        press('…')
        assertEquals("word…", editorText())
    }

    @Test
    fun apostrophe_afterSwipeAutoSpace_swallowsForPossessive() {
        // swipe "kids" → `kids `; typing ' must yield `kids'` (→ kids's/kids')
        swipe("kids")
        press('\'')
        assertEquals("kids'", editorText())
    }

    @Test
    fun straightDoubleQuote_withUnmatchedOpenQuote_swallowsAutoSpace() {
        // One prior `"` → parity says this is a CLOSING quote → swallow
        inputConnection.commitText("\"", 1)
        swipe("word")
        assertEquals("\"word ", editorText())
        press('"')
        assertEquals("\"word\"", editorText())
    }

    @Test
    fun straightDoubleQuote_noOpenQuote_isOpening_keepsAutoSpace() {
        // No prior `"` → parity says OPENING quote → the space must stay
        swipe("said")
        press('"')
        assertEquals("said \"", editorText())
    }

    @Test
    fun period_afterTapCommit_swallowsAutoSpace() {
        // Taps share the mechanism: type partial, tap suggestion, then punctuation
        inputConnection.commitText("hel", 1)
        contextTracker.appendToCurrentWord("hel")
        tap("hello")
        assertEquals("hello ", editorText())
        press('.')
        assertEquals("hello. ", editorText())
    }

    // ── Manual space must NEVER be eaten ─────────────────────────────────────

    @Test
    fun manuallyTypedSpace_period_keepsSpace() {
        for (c in "word") press(c)
        press(' ')
        press('.')
        assertEquals("word .", editorText())
    }

    @Test
    fun manualSpaceAfterSwipe_period_keepsManualSpace() {
        // swipe → `word `, user types their own space, then `.`:
        // the typed space invalidated the pending state, nothing is swallowed
        swipe("word")
        press(' ')
        press('.')
        assertEquals("word  .", editorText())
    }

    // ── Invalidation ─────────────────────────────────────────────────────────

    @Test
    fun cursorMovedAwayFromStamp_period_keepsSpace() {
        // "one " typed manually, "two " auto-committed at the end, then the
        // cursor jumps back to just after the manual space at position 4.
        // Prev char IS a space but the position stamp no longer matches —
        // that manual space must not be swallowed.
        inputConnection.commitText("one ", 1)
        swipe("two")
        assertEquals("one two ", editorText())
        inputConnection.setCursor(4)
        press('.')
        assertEquals("one .two ", editorText())
    }

    @Test
    fun cursorMoveInvalidation_viaTrackerHook() {
        // InputCoordinator.onCursorMoved feeds onCursorPositionChanged: the
        // commit's own selection callback (== stamp) keeps the pending state,
        // any other position kills it.
        swipe("word")
        assertTrue(contextTracker.lastSpaceWasAutoInserted)
        val stamp = contextTracker.autoSpaceStampedPosition
        assertEquals(5, stamp)
        contextTracker.onCursorPositionChanged(stamp) // commit's own update
        assertTrue(contextTracker.lastSpaceWasAutoInserted)
        contextTracker.onCursorPositionChanged(2)     // user moved the cursor
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
        press('.')
        assertEquals("word .", editorText())
    }

    @Test
    fun backspace_invalidatesPendingAutoSpace() {
        swipe("word")
        assertTrue(contextTracker.lastSpaceWasAutoInserted)
        pressBackspace()
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
    }

    @Test
    fun fieldSwitch_clearAll_invalidatesPendingAutoSpace() {
        // onFinishInputView → contextTracker.clearAll()
        swipe("word")
        assertTrue(contextTracker.lastSpaceWasAutoInserted)
        contextTracker.clearAll()
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
        assertEquals(-1, contextTracker.autoSpaceStampedPosition)
        press('.')
        assertEquals("word .", editorText())
    }

    @Test
    fun secondPunctuation_chainsWithExactlyOneDeletionEach() {
        // `.` swallows the swipe's auto-space and re-adds one (stamped again);
        // `)` swallows only that re-added space. Never a double deletion.
        swipe("word")
        press('.')
        assertEquals("word. ", editorText())
        press(')')
        assertEquals("word.)", editorText())
    }

    @Test
    fun otherCharInput_invalidates_soLaterPunctuationKeepsManualSpace() {
        // swipe → letter typed (invalidates) → manual space → period keeps it
        swipe("word")
        press('s')
        press(' ')
        press('.')
        assertEquals("word s .", editorText())
    }

    // ── Plain regression: normal word-space-word flow unchanged ─────────────

    @Test
    fun consecutiveSwipes_singleSpaceBetweenWords() {
        swipe("hello")
        swipe("world")
        assertEquals("hello world ", editorText())
    }

    @Test
    fun typedPartial_tapReplacement_unchanged() {
        // Issue #151-adjacent control: tap replaces the typed partial
        inputConnection.commitText("hel", 1)
        contextTracker.appendToCurrentWord("hel")
        tap("hello")
        assertEquals("hello ", editorText())
    }
    @Test fun continuousReadbackRejectsTransformedTextBeforeLearning() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sharedConfig!!.on_device_learning_enabled = true
            sharedConfig!!.context_aware_predictions_enabled = true
            inputConnection.commitText("alpha ", 1)
            val previous = sharedPredictor!!.latestLearningCommit()
            val guard = EditorCommitGuard(requireNotNull(EditorReadback.capture(inputConnection))) { true }
            inputConnection.commitBehavior = CommitBehavior.PREFIX_WORD
            contextTracker.setWasLastInputSwipe(true)
            val committed = suggestionHandler.onSuggestionSelected("cat", inputConnection,
                plainEditorInfo, context.resources, commitGuard = guard)
            org.junit.Assert.assertNull(committed)
            assertEquals("alpha bobcat ", inputConnection.editableText())
            org.junit.Assert.assertSame(previous, sharedPredictor!!.latestLearningCommit())
            org.junit.Assert.assertNull(contextTracker.getLastAutoInsertedWord())
            assertFalse(contextTracker.lastSpaceWasAutoInserted)
        }
    }
    @Test fun continuousReadbackAcceptsExactWordThenCreatesLearningReceipt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sharedConfig!!.on_device_learning_enabled = true
            sharedConfig!!.context_aware_predictions_enabled = true
            inputConnection.commitText("alpha ", 1)
            val guard = EditorCommitGuard(requireNotNull(EditorReadback.capture(inputConnection))) { true }
            contextTracker.setWasLastInputSwipe(true)
            assertEquals("cat", suggestionHandler.onSuggestionSelected("cat", inputConnection,
                plainEditorInfo, context.resources, commitGuard = guard))
            assertEquals("alpha cat ", inputConnection.editableText())
            org.junit.Assert.assertNotNull(sharedPredictor!!.latestLearningCommit())
        }
    }

    @Test fun oneLetterContinuousRouteUsesSharedCommitAndRespectsIPreference() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sharedConfig!!.swipe_typing_enabled = true
            sharedConfig!!.primary_language = "en"
            val layout = requireNotNull(tribixbite.cleverkeys.prefs.LayoutsPreference.layoutOfString(context.resources, "latn_qwerty_us"))
            val view = Keyboard2View(context).apply { setKeyboard(layout) }
            val coordinator = InputCoordinator(context, sharedConfig!!, contextTracker, sharedCoordinator!!,
                SuggestionBar(context), { view }).apply { setSwipeResultDelegate(suggestionHandler) }
            try {
            val key = layout.rows.flatMap { it.keys }.first { tribixbite.cleverkeys.swipe.KeyLetter.centreLetterOf(it.keys[0]) == 'i' }
            inputConnection.commitText("prefix ", 1)
            for (capitalize in listOf(false, true)) {
                sharedConfig!!.autocapitalize_i_words = capitalize
                var completed: String? = null
                val guard = EditorCommitGuard(requireNotNull(EditorReadback.capture(inputConnection))) { true }
                coordinator.handleSwipeTyping(listOf(key), listOf(android.graphics.PointF(1f, 1f)), listOf(1000L),
                    inputConnection, plainEditorInfo, context.resources, control = InputCoordinator.SwipeCommitControl(
                        { true }, { completed = it }, commitGuard = guard))
                assertEquals(if (capitalize) "I" else "i", completed)
            }
            assertEquals("prefix i I ", inputConnection.editableText())
            } finally { coordinator.shutdown() }
        }
    }

}
