package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ARC-007 / WP9 R-1 step 7 — the dedicated Termux instrumented test the deferred decision owed.
 *
 * The decision (recorded in [SuggestionHandler]'s `isTermuxEditor` KDoc) is **KEEP the key-event
 * branches**: a terminal has no editable text buffer, so `deleteSurroundingText` is best-effort at
 * best over a pty-backed InputConnection, while `KEYCODE_DEL` / Ctrl+W are the terminal's own
 * vocabulary. A decision without a test is just a comment, so this pins the branch:
 *
 *  - in `com.termux`, every deletion path emits KEY EVENTS and calls `deleteSurroundingText` ZERO
 *    times;
 *  - in an ordinary app, the SAME paths with the SAME state call `deleteSurroundingText` and emit
 *    no deletion key events.
 *
 * The control half is what gives the Termux half meaning: it proves the editor package is the
 * discriminator rather than something in the harness.
 *
 * Deliberately built WITHOUT a [WordPredictor] — `isManualSelection = true` skips final
 * autocorrect, so no dictionary is needed and the test cannot flake on a small-heap OOM. It also
 * touches no user dictionary: the paths that call `addUserWord` (exact-add, autocorrect-undo) are
 * excluded on purpose, since a test must not leave the device's personal dictionary dirty.
 */
@RunWith(AndroidJUnit4::class)
class TermuxDeletionInstrumentedTest {

    /**
     * Records what an IME did to the editor.
     *
     * Extends [BaseInputConnection] over a real [EditText] so the reads the production code makes
     * (`getTextBeforeCursor` for the auto-space and word-boundary logic) return real text.
     * `deleteSurroundingText` and `commitText` delegate so the buffer stays consistent;
     * `sendKeyEvent` does NOT delegate — a detached EditText has no terminal on the far end, and
     * the assertion is about which vocabulary the IME chose, not about what a pty would do with it.
     */
    private class RecordingInputConnection(private val editText: EditText) :
        BaseInputConnection(editText, true) {
        /** Key codes of ACTION_DOWN events only — one entry per logical key press. */
        val keyPresses = mutableListOf<Pair<Int, Int>>()
        val deleteSurroundingCalls = mutableListOf<Pair<Int, Int>>()
        val commits = mutableListOf<String>()

        /**
         * CRITICAL (first ew-cli run, 2026-08-29): a directly-constructed [BaseInputConnection]
         * edits its OWN empty fake editable — the EditText's `setText` content was invisible to
         * every `getTextBeforeCursor`/cursor-sync read, so the typed-partial branch found no
         * prefix (0 deletions) and the leading-space probe read an empty buffer (6-vs-7 REPLACE
         * count). Returning the EditText's real editable makes reads AND
         * `deleteSurroundingText` operate on the text the tests seeded.
         */
        override fun getEditable(): android.text.Editable = editText.text

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                keyPresses.add(event.keyCode to event.metaState)
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deleteSurroundingCalls.add(beforeLength to afterLength)
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            commits.add(text.toString())
            return super.commitText(text, newCursorPosition)
        }

        fun pressesOf(keyCode: Int): Int = keyPresses.count { it.first == keyCode }
    }

    private lateinit var context: Context
    private lateinit var config: Config
    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var suggestionHandler: SuggestionHandler
    private lateinit var ic: RecordingInputConnection

    private val termuxEditor = EditorInfo().apply {
        packageName = SuggestionHandler.TERMUX_PACKAGE
        inputType = InputType.TYPE_CLASS_TEXT
    }
    private val ordinaryEditor = EditorInfo().apply {
        packageName = "com.example.notes"
        inputType = InputType.TYPE_CLASS_TEXT
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        config = Config.globalConfig()
        contextTracker = PredictionContextTracker()
    }

    /**
     * Builds the handler and a recording connection over an editor holding [text] with the cursor
     * at the end, then runs [body] on the main thread (the EditText's editable is main-thread
     * property, and the production code commits text into it).
     */
    private fun withEditor(text: String, body: () -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val editText = EditText(context).apply {
                    setText(text)
                    setSelection(text.length)
                }
                ic = RecordingInputConnection(editText)

                // The KeyEventHandler routes send_key_down_up through the receiver's
                // InputConnection — which is how a Termux branch's DEL reaches the editor.
                val receiver = object : KeyEventHandler.IReceiver {
                    override fun handle_event_key(ev: KeyValue.Event) {}
                    override fun set_shift_state(state: Boolean, lock: Boolean) {}
                    override fun set_compose_pending(pending: Boolean) {}
                    override fun selection_state_changed(selectionIsOngoing: Boolean) {}
                    override fun getCurrentInputConnection(): InputConnection = ic
                    override fun getHandler(): Handler = Handler(Looper.getMainLooper())
                    override fun handle_text_typed(text: String) {}
                }

                suggestionHandler = SuggestionHandler(
                    context, config, contextTracker,
                    PredictionCoordinator(context, config),
                    ContractionManager(context), KeyEventHandler(receiver)
                )
                body()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        assertTrue("main-thread body must complete", latch.await(15, TimeUnit.SECONDS))
        failure?.let { throw it }
    }

    // ===========================================================================================
    // (a) Suggestion REPLACE deletion — the swipe-auto-inserted word is removed before the tap
    // ===========================================================================================

    /**
     * `note hello ` was auto-inserted by a swipe; tapping a different suggestion must remove
     * `hello` and its trailing space with DEL key events.
     *
     * Count: this fixture uses legacy tracking (no recorded commit text), so
     * [SmartAutoSpace.swipeReplacement] plans `"hello".length + 1` = 6 presses (word + trailing
     * space). Until 691aa8cf the Termux branch read the character before the cursor BEFORE
     * deleting and added a 7th press for it, but that character is the swipe's own trailing
     * space, so the extra press erased the space before the word (the divergence this test's old
     * TODO described; fixed for RustDesk, 2026-10-25). The space before `hello` now stays, and the
     * tap's leading-space rule sees it and adds none.
     */
    @Test
    fun termux_replaceDeletion_usesKeyEventsOnly() {
        withEditor("note hello ") {
            contextTracker.setLastAutoInsertedWord("hello")
            contextTracker.setLastCommitSource(PredictionSource.SWIPE)

            suggestionHandler.onSuggestionSelected(
                "world", ic, termuxEditor, context.resources, isManualSelection = true
            )

            assertEquals(
                "Termux REPLACE must delete with DEL key events. Presses: ${ic.keyPresses}",
                6, ic.pressesOf(KeyEvent.KEYCODE_DEL)
            )
            assertEquals(
                "Termux REPLACE must NOT use deleteSurroundingText. Calls: ${ic.deleteSurroundingCalls}",
                emptyList<Pair<Int, Int>>(), ic.deleteSurroundingCalls
            )
        }
    }

    /** Control: the identical state in an ordinary app takes the InputConnection branch. */
    @Test
    fun ordinaryApp_replaceDeletion_usesInputConnectionOnly() {
        withEditor("note hello ") {
            contextTracker.setLastAutoInsertedWord("hello")
            contextTracker.setLastCommitSource(PredictionSource.SWIPE)

            suggestionHandler.onSuggestionSelected(
                "world", ic, ordinaryEditor, context.resources, isManualSelection = true
            )

            assertTrue(
                "ordinary REPLACE must use deleteSurroundingText. Calls: ${ic.deleteSurroundingCalls}",
                ic.deleteSurroundingCalls.isNotEmpty()
            )
            assertEquals(
                "ordinary REPLACE must send no DEL key events. Presses: ${ic.keyPresses}",
                0, ic.pressesOf(KeyEvent.KEYCODE_DEL)
            )
        }
    }

    // ===========================================================================================
    // (b) Typed-partial deletion — the partial word is removed before the tapped suggestion lands
    // ===========================================================================================

    /**
     * The ordinary "type `wor`, tap `world`" flow. In Termux the tap deletes NOTHING
     * (2026-10-15, [TerminalSuggestionPolicy]): the IME cannot verify that the tracked letters
     * are still on the line (Termux's toolbar keys and CTRL change it unseen), so it appends
     * only the remainder `ld ` after the tracked `wor`.
     */
    @Test
    fun termux_typedPartialTap_appendsWithoutDeleting() {
        withEditor("hello wor") {
            // No auto-inserted word and no swipe flag → the typed-partial branch.
            contextTracker.clearLastAutoInsertedWord()
            contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
            contextTracker.setWasLastInputSwipe(false)
            // A terminal's only record of the word is the keystroke tracker.
            contextTracker.appendToCurrentWord("wor")

            suggestionHandler.onSuggestionSelected(
                "world", ic, termuxEditor, context.resources, isManualSelection = true
            )

            assertEquals(
                "Termux typed-partial tap must send no DEL. Presses: ${ic.keyPresses}",
                0, ic.pressesOf(KeyEvent.KEYCODE_DEL)
            )
            assertEquals(
                "Termux typed-partial must NOT use deleteSurroundingText. Calls: " +
                    "${ic.deleteSurroundingCalls}",
                emptyList<Pair<Int, Int>>(), ic.deleteSurroundingCalls
            )
            // The trailing space follows the user's auto-space preference.
            assertEquals("Termux tap appends only the remainder", "ld", ic.commits.single().trimEnd())
        }
    }

    /** Control: same state, ordinary app → one `deleteSurroundingText(3, 0)`, no key events. */
    @Test
    fun ordinaryApp_typedPartialDeletion_usesInputConnectionOnly() {
        withEditor("hello wor") {
            contextTracker.clearLastAutoInsertedWord()
            contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
            contextTracker.setWasLastInputSwipe(false)

            suggestionHandler.onSuggestionSelected(
                "world", ic, ordinaryEditor, context.resources, isManualSelection = true
            )

            assertTrue(
                "ordinary typed-partial must delete via InputConnection. Calls: " +
                    "${ic.deleteSurroundingCalls}",
                ic.deleteSurroundingCalls.contains(3 to 0)
            )
            assertEquals(
                "ordinary typed-partial must send no DEL key events. Presses: ${ic.keyPresses}",
                0, ic.pressesOf(KeyEvent.KEYCODE_DEL)
            )
        }
    }

    // ===========================================================================================
    // (c) Delete-last-word — the fifth key-event branch (Ctrl+W, the terminal's kill-word)
    // ===========================================================================================

    @Test
    fun termux_deleteLastWord_sendsCtrlW() {
        withEditor("hello world") {
            suggestionHandler.handleDeleteLastWord(ic, termuxEditor)

            val ctrlW = ic.keyPresses.filter { it.first == KeyEvent.KEYCODE_W }
            assertEquals(
                "Termux delete-last-word must send exactly one Ctrl+W. Presses: ${ic.keyPresses}",
                1, ctrlW.size
            )
            assertTrue(
                "the W press must carry the CTRL modifier. Got meta=${ctrlW.first().second}",
                (ctrlW.first().second and KeyEvent.META_CTRL_ON) != 0
            )
            assertEquals(
                "Termux delete-last-word must NOT use deleteSurroundingText. Calls: " +
                    "${ic.deleteSurroundingCalls}",
                emptyList<Pair<Int, Int>>(), ic.deleteSurroundingCalls
            )
        }
    }

    /** Control: an ordinary app removes "world" with `deleteSurroundingText(5, 0)`. */
    @Test
    fun ordinaryApp_deleteLastWord_usesInputConnection() {
        withEditor("hello world") {
            suggestionHandler.handleDeleteLastWord(ic, ordinaryEditor)

            assertEquals(
                "ordinary delete-last-word must remove the last word via InputConnection. " +
                    "Calls: ${ic.deleteSurroundingCalls}",
                listOf(5 to 0), ic.deleteSurroundingCalls
            )
            assertEquals(
                "ordinary delete-last-word must send no key events. Presses: ${ic.keyPresses}",
                0, ic.keyPresses.size
            )
        }
    }

    // ===========================================================================================
    // Detection point
    // ===========================================================================================

    /**
     * Every branch above turns on ONE package comparison. Pinning the constant keeps a rename of
     * the Termux package from silently disabling all five branches with every test still green.
     */
    @Test
    fun termuxPackageConstantIsTheRealPackageName() {
        assertEquals("com.termux", SuggestionHandler.TERMUX_PACKAGE)
    }
}
