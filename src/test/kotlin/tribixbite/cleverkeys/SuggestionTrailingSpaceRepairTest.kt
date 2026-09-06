package tribixbite.cleverkeys

import android.content.res.Resources
import android.os.Handler
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * gh #151 residual (reporter follow-up 2026-08-23) — some composing-less editors
 * (browser URL bars, GPTAssist-class fields) DROP the trailing space of the committed
 * `"word "` app-side. Leading spaces survive; only the trailing one is eaten, so the
 * next word runs into the previous one ("example" + "w" → "examplew").
 *
 * A keystroke-time repair cannot reuse the SAS-1 pending-space state: the editor's
 * selection callback after the mangled commit reports exactly stamp−1, which
 * [PredictionContextTracker.onCursorPositionChanged] treats as "cursor moved" and
 * invalidates the SAS-1 state. The fix is NEW state — the trailing-space watch:
 *
 *  1. ARM: after a suggestion commit whose text ended in a space (and only when a
 *     position stamp was obtainable), [PredictionContextTracker.markTrailingSpaceWatch]
 *     records the expected post-commit cursor position and the committed text minus
 *     its trailing space.
 *  2. RESOLVE: the next cursor callback discriminates — landing at the stamp means the
 *     editor kept the space (watch cleared, nothing owed); landing at stamp−1 is the
 *     dropped-space signature (a space is now OWED at that position); anything else
 *     clears the watch.
 *  3. REPAIR: the next single-char ALPHANUMERIC keystroke consumes the owed state
 *     (KeyEventHandler.sendText via IReceiver.takeOwedTrailingSpace) and — after
 *     verifying at use that the text before the cursor still ends with the committed
 *     word and no space (double-space guard) — commits " x" as ONE commitText call
 *     (a separate " " commit would be mangled by the same editors). Any other
 *     keystroke, cursor jump, or field switch clears the state without inserting.
 *
 * RED evidence (2026-09-05, tracker state machine present but SuggestionHandler
 * arming + KeyEventHandler consumption not yet wired):
 * theManglingEditorGetsTheOwedSpaceOnTheNextKeystroke failed with
 * "expected: example w / but was: examplew" — the reporter's exact symptom.
 * Green at HEAD: all cases below.
 *
 * Uses the same InputConnection double as [SuggestionTapPartialReplaceTest]
 * (text buffer + getTextBeforeCursor/deleteSurroundingText/commitText, no composing
 * support), extended with a switchable trailing-space-dropping commitText and an
 * ExtractedText stub so the position stamp is obtainable.
 */
class SuggestionTrailingSpaceRepairTest {

    private val objenesis = ObjenesisStd()

    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var bar: SuggestionBar
    private lateinit var resources: Resources
    private lateinit var config: Config

    /** The editor double's text buffer; cursor is always at its end. */
    private val editorText = StringBuilder()
    private lateinit var ic: InputConnection

    /** When true, the double models the mangling editor: commits lose trailing spaces. */
    private var editorDropsTrailingSpace = false

    /** When false, the editor has no ExtractedText support → no position stamp. */
    private var editorSupportsExtractedText = true

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.auto_space_before_suggestion = true
        config.auto_space_after_suggestion = true
        config.double_space_to_period = false
        config.smart_punctuation = false
        config.primary_language = "en"
        config.swipe_final_autocorrect_enabled = false
        config.on_device_learning_enabled = false
        every { Config.globalConfig() } returns config

        coordinator = mockk(relaxed = true)
        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        resources = mockk(relaxed = true)

        // REAL tracker — the trailing-space watch state machine under test lives here.
        contextTracker = PredictionContextTracker()

        editorDropsTrailingSpace = false
        editorSupportsExtractedText = true
        editorText.clear()
        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } answers {
            editorText.takeLast(firstArg<Int>()).toString()
        }
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        every { ic.getCursorCapsMode(any()) } returns 0
        every { ic.deleteSurroundingText(any(), any()) } answers {
            val before = firstArg<Int>()
            editorText.setLength((editorText.length - before).coerceAtLeast(0))
            true
        }
        every { ic.commitText(any(), any()) } answers {
            var committed = firstArg<CharSequence>().toString()
            if (editorDropsTrailingSpace) {
                // The reporter's editor class: the trailing space of a commit is
                // silently dropped app-side; leading/inner spaces survive.
                committed = committed.trimEnd(' ')
            }
            editorText.append(committed)
            true
        }
        // The position stamp source (SAS-1 currentCursorPosition — normally
        // getExtractedText, whose ExtractedTextRequest() constructor is an android.jar
        // stub here): cursor at buffer end, or -1 for the no-ExtractedText editor class.
        mockkObject(PredictionContextTracker.Companion)
        every { PredictionContextTracker.currentCursorPosition(any()) } answers {
            if (editorSupportsExtractedText) editorText.length else -1
        }
        every { ic.setComposingRegion(any(), any()) } returns false
        every { ic.setComposingText(any(), any()) } returns false
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    private fun handler(): SuggestionHandler {
        val handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contextTracker", contextTracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("contractionManager", mockk<ContractionManager>(relaxed = true))
        handler.setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        handler.setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
        return handler
    }

    private fun keyEventHandler(): KeyEventHandler {
        val keh = objenesis.newInstance(KeyEventHandler::class.java)
        keh.setField("recv", receiver())
        keh.setField("autocap", mockk<Autocapitalisation>(relaxed = true))
        return keh
    }

    /** IReceiver wired to the REAL tracker — mirrors KeyEventReceiverBridge's delegation. */
    private fun receiver(): KeyEventHandler.IReceiver = object : KeyEventHandler.IReceiver {
        override fun handle_event_key(ev: KeyValue.Event) {}
        override fun set_shift_state(state: Boolean, lock: Boolean) {}
        override fun set_compose_pending(pending: Boolean) {}
        override fun selection_state_changed(selectionIsOngoing: Boolean) {}
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getHandler(): Handler = mockk(relaxed = true)
        override fun handle_text_typed(text: String) {}
        override fun wasLastSpaceAutoInserted(): Boolean = contextTracker.lastSpaceWasAutoInserted
        override fun setLastSpaceAutoInserted(value: Boolean) {
            if (value) contextTracker.lastSpaceWasAutoInserted = true
            else contextTracker.invalidateAutoSpacePending()
        }
        override fun getAutoSpaceStampedPosition(): Int = contextTracker.autoSpaceStampedPosition
        override fun takeOwedTrailingSpace(): String? = contextTracker.takeOwedTrailingSpace()
    }

    private fun editorInfo(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.app"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }

    /** Types one key through KeyEventHandler's real sendText path (private → reflection). */
    private fun type(keh: KeyEventHandler, text: String) {
        val m = KeyEventHandler::class.java.getDeclaredMethod(
            "sendText", CharSequence::class.java, Boolean::class.javaPrimitiveType
        )
        m.isAccessible = true
        m.invoke(keh, text, false)
    }

    /**
     * The shared scenario head: the user typed "exa" (already in the editor), taps
     * "example". The commit is "example " (8 chars); the pre-commit cursor is 0 after
     * partial deletion, so the stamp is 8 and the dropped-space cursor lands at 7.
     */
    private fun tapExampleSuggestion() {
        handler().onSuggestionSelected(
            "example", ic, editorInfo(), resources, isManualSelection = true
        )
    }

    // -------------------------------------------------- the residual: mangling editor

    @Test
    fun theManglingEditorGetsTheOwedSpaceOnTheNextKeystroke() {
        editorDropsTrailingSpace = true
        editorText.append("exa")

        tapExampleSuggestion()
        assertThat(editorText.toString()).isEqualTo("example") // editor ate the space

        // The editor's selection callback after the mangled commit: stamp−1.
        contextTracker.onCursorPositionChanged(editorText.length)

        type(keyEventHandler(), "w")

        assertWithMessage(
            "gh #151 residual: the editor dropped the committed trailing space, so the " +
                "next alphanumeric keystroke must insert the owed space first"
        ).that(editorText.toString()).isEqualTo("example w")
    }

    @Test
    fun theRepairSpaceRidesInTheSameCommitAsTheKeystroke() {
        // A separate commitText(" ") would be mangled by the same editors (it ends in a
        // space) — the repair must arrive as one " w" commit whose last char is the letter.
        editorDropsTrailingSpace = true
        editorText.append("exa")
        tapExampleSuggestion()
        contextTracker.onCursorPositionChanged(editorText.length)

        type(keyEventHandler(), "w")

        verify(exactly = 1) { ic.commitText(" w", any()) }
        verify(exactly = 0) { ic.commitText(" ", any()) }
    }

    // ------------------------------------------------ control: well-behaved editor

    @Test
    fun aWellBehavedEditorNeverGetsADoubleSpace() {
        editorDropsTrailingSpace = false
        editorText.append("exa")

        tapExampleSuggestion()
        assertThat(editorText.toString()).isEqualTo("example ") // space kept

        // The commit's own selection callback: exactly the stamp → nothing owed.
        contextTracker.onCursorPositionChanged(editorText.length)

        type(keyEventHandler(), "w")

        assertThat(editorText.toString()).isEqualTo("example w")
        // The keystroke commit must be the bare char — no owed-space prefix.
        verify(exactly = 0) { ic.commitText(match { it.startsWith(" ") }, any()) }
    }

    // ------------------------------------------------------- state-clearing cases

    @Test
    fun aNonAlphanumericKeystrokeClearsTheOwedStateWithoutInserting() {
        editorDropsTrailingSpace = true
        editorText.append("exa")
        tapExampleSuggestion()
        contextTracker.onCursorPositionChanged(editorText.length)

        type(keyEventHandler(), ".")
        assertThat(editorText.toString()).isEqualTo("example.")

        // The owed state was consumed by the punctuation keystroke — a later letter
        // must NOT get a stale repair space.
        type(keyEventHandler(), "w")
        assertThat(editorText.toString()).isEqualTo("example.w")
    }

    @Test
    fun aCursorJumpAwayFromTheSignatureClearsTheOwedState() {
        editorDropsTrailingSpace = true
        editorText.append("exa")
        tapExampleSuggestion()
        contextTracker.onCursorPositionChanged(editorText.length) // owed armed at 7
        contextTracker.onCursorPositionChanged(2)                 // user tapped elsewhere

        assertThat(contextTracker.takeOwedTrailingSpace()).isNull()
    }

    @Test
    fun aFieldSwitchClearsTheOwedState() {
        editorDropsTrailingSpace = true
        editorText.append("exa")
        tapExampleSuggestion()
        contextTracker.onCursorPositionChanged(editorText.length)

        contextTracker.clearAll() // field switch

        type(keyEventHandler(), "w")
        assertThat(editorText.toString()).isEqualTo("examplew")
    }

    @Test
    fun withoutAPositionStampThereIsNoRepair() {
        // Editor without ExtractedText support: the dropped-space signature cannot be
        // discriminated from a cursor move, so the watch is never armed (documented
        // degradation — no repair, but also no spurious spaces).
        editorSupportsExtractedText = false
        editorDropsTrailingSpace = true
        editorText.append("exa")
        tapExampleSuggestion()
        contextTracker.onCursorPositionChanged(editorText.length)

        type(keyEventHandler(), "w")
        assertThat(editorText.toString()).isEqualTo("examplew")
    }

    @Test
    fun theVerifyAtUseGuardBlocksTheRepairWhenTheEditorTextChanged() {
        editorDropsTrailingSpace = true
        editorText.append("exa")
        tapExampleSuggestion()
        contextTracker.onCursorPositionChanged(editorText.length) // owed armed

        // The editor rewrote its content out from under us (URL bars do) — the text
        // before the cursor no longer ends with the committed word, so no insert.
        editorText.setLength(0)
        editorText.append("other")

        type(keyEventHandler(), "w")
        assertThat(editorText.toString()).isEqualTo("otherw")
    }

    @Test
    fun anIntermediateDeletionCallbackDoesNotKillTheWatch() {
        // Device-observed (Pixel 8 Pro Chrome omnibox, 2026-09-05): the partial-word
        // deleteSurroundingText's own selection callback (pos=0) is delivered AFTER the
        // commit, before the dropped-space callback. The watch must survive a small
        // number of such stale intermediate positions or the repair never arms.
        editorDropsTrailingSpace = true
        editorText.append("exa")
        tapExampleSuggestion()

        contextTracker.onCursorPositionChanged(0) // stale: the deletion's callback
        contextTracker.onCursorPositionChanged(7) // stamp−1 → owed

        type(keyEventHandler(), "w")
        assertWithMessage(
            "the stale pre-commit deletion callback must not kill the trailing-space watch"
        ).that(editorText.toString()).isEqualTo("example w")
    }

    @Test
    fun theWatchExpiresAfterTheUnmatchedCallbackBudget() {
        // A genuine cursor jump produces a stream of unrelated positions — after the
        // budget is exhausted the watch must die so a much later coincidental stamp−1
        // position can't arm a spurious repair.
        editorDropsTrailingSpace = true
        editorText.append("exa")
        tapExampleSuggestion()

        contextTracker.onCursorPositionChanged(3)
        contextTracker.onCursorPositionChanged(1)
        contextTracker.onCursorPositionChanged(5)
        contextTracker.onCursorPositionChanged(7) // stamp−1, but the watch is spent

        assertThat(contextTracker.takeOwedTrailingSpace()).isNull()
    }

    @Test
    fun aLateKeptSpaceCallbackDisarmsTheOwedState() {
        // Some editors report an intermediate stamp−1 then apply the space and report
        // the stamp. The second callback (≠ owed position) must disarm the owed state
        // so the next keystroke can't double-space.
        editorDropsTrailingSpace = true
        editorText.append("exa")
        tapExampleSuggestion()
        contextTracker.onCursorPositionChanged(7) // stamp−1 → owed armed
        contextTracker.onCursorPositionChanged(8) // editor applied the space after all

        assertThat(contextTracker.takeOwedTrailingSpace()).isNull()
    }

    // ------------------------------------------------------------------ reflection

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName} — it was renamed or removed; " +
                "declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }
}
