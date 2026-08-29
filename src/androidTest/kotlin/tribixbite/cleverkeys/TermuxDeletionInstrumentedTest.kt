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
     * `hello` plus the spaces around it.
     *
     * Count: `"hello".length + 1` for word+trailing space, then `+1` because the Termux branch
     * finds a space immediately before the cursor and counts the leading space too — 7 presses,
     * exactly the length of `" hello "`.
     *
     * TODO(ARC-007 follow-up): the Termux branch inspects `getTextBeforeCursor(1)` BEFORE deleting
     * while the InputConnection branch inspects it AFTER, so the two disagree when the committed
     * word has no leading space (Termux would then send one DEL too many). Not changed here: a
     * swipe commit always inserts the leading space, so the divergence is unreachable on the path
     * that actually runs, and altering the count needs a real-terminal verification run.
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
                7, ic.pressesOf(KeyEvent.KEYCODE_DEL)
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
     * The ordinary "type `wor`, tap `world`" flow. The handler cursor-syncs, finds a 3-character
     * prefix to remove, and in Termux must remove it with three DEL presses.
     */
    @Test
    fun termux_typedPartialDeletion_usesKeyEventsOnly() {
        withEditor("hello wor") {
            // No auto-inserted word and no swipe flag → the typed-partial branch.
            contextTracker.clearLastAutoInsertedWord()
            contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
            contextTracker.setWasLastInputSwipe(false)

            suggestionHandler.onSuggestionSelected(
                "world", ic, termuxEditor, context.resources, isManualSelection = true
            )

            assertEquals(
                "Termux typed-partial must delete 'wor' with DEL key events. Presses: ${ic.keyPresses}",
                3, ic.pressesOf(KeyEvent.KEYCODE_DEL)
            )
            assertEquals(
                "Termux typed-partial must NOT use deleteSurroundingText. Calls: " +
                    "${ic.deleteSurroundingCalls}",
                emptyList<Pair<Int, Int>>(), ic.deleteSurroundingCalls
            )
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
