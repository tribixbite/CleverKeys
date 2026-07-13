package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Autocorrect must NOT rewrite tokens inside URLs, emails, file paths, or other
 * non-prose contexts (reported 2026-07-13: pasted/typed/edited URLs were being
 * "corrected" — e.g. typing `foo.teh ` produced `foo.the `, breaking domains).
 *
 * Drives the REAL SuggestionHandler.handleRegularTyping over a real
 * BaseInputConnection, replicating the production flow where each keystroke is
 * committed to the editor BEFORE the handler runs (so at space time the word
 * and separator are already in the editor, exactly as in CleverKeysService).
 *
 * The prose-control cases pin the inverse: the guard must not suppress normal
 * autocorrect ("visit teh " must still become "visit the ").
 */
@RunWith(AndroidJUnit4::class)
class AutocorrectUrlGuardTest {

    /**
     * BaseInputConnection(view, fullEditor=true) edits its OWN internal
     * Editable, not EditText.text (EditText's real IC is the hidden
     * EditableInputConnection). The handler's commitText/deleteSurroundingText/
     * getTextBeforeCursor all operate on that internal buffer, so the test
     * must assert on it — this subclass just exposes it.
     */
    private class TestInputConnection(target: EditText) : BaseInputConnection(target, true) {
        fun editableText(): String = editable?.toString() ?: ""
    }

    private lateinit var context: Context
    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var suggestionHandler: SuggestionHandler
    private lateinit var inputConnection: TestInputConnection
    private lateinit var editText: EditText

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
        TestConfigHelper.ensureConfigInitialized(context)
        contextTracker = PredictionContextTracker()

        synchronized(AutocorrectUrlGuardTest::class.java) {
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
        assumeNotNull("WordPredictor required", sharedPredictor)

        // Deterministic autocorrect knobs (same as AutocorrectTest).
        // word_prediction_enabled + a SuggestionBar are hard gates at the top of
        // handleRegularTyping — without them the whole typing path early-returns.
        sharedConfig!!.word_prediction_enabled = true
        sharedConfig!!.autocorrect_enabled = true
        sharedConfig!!.autocorrect_min_word_length = 2
        sharedConfig!!.autocorrect_char_match_threshold = 0.65f
        sharedConfig!!.autocorrect_max_length_diff = 2
        sharedConfig!!.autocorrect_confidence_min_frequency = 100
        sharedConfig!!.autocorrect_prefix_length = 0

        val stubReceiver = object : KeyEventHandler.IReceiver {
            override fun handle_event_key(ev: KeyValue.Event) {}
            override fun set_shift_state(state: Boolean, lock: Boolean) {}
            override fun set_compose_pending(pending: Boolean) {}
            override fun selection_state_changed(selectionIsOngoing: Boolean) {}
            override fun getCurrentInputConnection(): InputConnection? = null
            override fun getHandler(): Handler = Handler(Looper.getMainLooper())
            override fun handle_text_typed(text: String) {}
        }
        suggestionHandler = SuggestionHandler(
            context, sharedConfig!!, contextTracker,
            sharedCoordinator!!, sharedContractions!!, KeyEventHandler(stubReceiver)
        )

        // SuggestionBar (a View) + EditText must be created on the main thread.
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

    /** Replicates production keystroke order: commit char to the editor, THEN
     *  run the handler (KeyEventHandler.send_text precedes handle_text_typed). */
    private fun type(s: String) {
        for (c in s) {
            inputConnection.commitText(c.toString(), 1)
            suggestionHandler.handleRegularTyping(c.toString(), inputConnection, null)
        }
    }

    private fun editorText() = inputConnection.editableText()

    // ── non-prose contexts: autocorrect must NOT fire ────────────────────────

    @Test
    fun domainToken_notAutocorrected() {
        type("foo.teh ")
        assertEquals("dotted domain token must not be autocorrected", "foo.teh ", editorText())
    }

    @Test
    fun urlToken_notAutocorrected() {
        type("https://teh.example ")
        assertEquals("URL must not be autocorrected", "https://teh.example ", editorText())
    }

    @Test
    fun emailToken_notAutocorrected() {
        type("user@teh ")
        assertEquals("email must not be autocorrected", "user@teh ", editorText())
    }

    @Test
    fun filePathToken_notAutocorrected() {
        type("/etc/teh ")
        assertEquals("file path must not be autocorrected", "/etc/teh ", editorText())
    }

    @Test
    fun editedPastedUrl_notAutocorrected() {
        // Simulate a pasted URL the user then extends by typing (paste bypasses
        // the typing path; the continuation goes through it).
        inputConnection.commitText("see https://site.example/path?q=", 1)
        type("teh ")
        assertEquals(
            "typed continuation of a pasted URL must not be autocorrected",
            "see https://site.example/path?q=teh ", editorText()
        )
    }

    // ── prose controls: the guard must NOT suppress normal autocorrect ──────

    @Test
    fun proseToken_stillAutocorrected() {
        type("visit teh ")
        assertEquals("normal prose must still autocorrect", "visit the ", editorText())
    }

    @Test
    fun sentenceStartToken_stillAutocorrected() {
        type("teh ")
        assertEquals("sentence-initial typo must still autocorrect", "the ", editorText())
    }
}
