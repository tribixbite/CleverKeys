package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
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
 * Issue #151: tapping a suggestion in a browser URL bar (inputType
 * TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI) left the typed partial word
 * behind instead of replacing it — typing `exa` and tapping `example`
 * produced `exa example ` (Vanadium, Fennec address bar, etc.).
 *
 * Root cause: PredictionContextTracker.shouldSyncForInputType() skips
 * cursor-sync for URI/email fields, so getCharsToDeleteForPrediction()
 * is always (0,0) there, and the #78 editor-scan fallback was gated on
 * getCurrentWordLength() == 0 — but currentWord IS tracked from typing,
 * so the fallback never fired and nothing was deleted before the commit.
 *
 * Drives the REAL production tap path: SuggestionHandler.onSuggestionSelected
 * with isManualSelection = true, exactly as SuggestionBridge.onSuggestionSelected
 * invokes it when the user taps the suggestion bar. Harness mirrors
 * AutocorrectUrlGuardTest (real BaseInputConnection over its internal editable,
 * keystrokes committed to the editor BEFORE the handler runs).
 */
@RunWith(AndroidJUnit4::class)
class Issue151UrlBarSuggestionTapTest {

    /**
     * BaseInputConnection(view, fullEditor=true) edits its OWN internal
     * Editable — the handler's commitText/deleteSurroundingText/
     * getTextBeforeCursor all operate on that buffer, so assert on it.
     */
    private class TestInputConnection(target: EditText) : BaseInputConnection(target, true) {
        fun editableText(): String = editable?.toString() ?: ""
    }

    private lateinit var context: Context
    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var suggestionHandler: SuggestionHandler
    private lateinit var inputConnection: TestInputConnection
    private lateinit var editText: EditText

    /** Browser URL bar as reported in #151. */
    private val urlBarEditorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        packageName = "app.vanadium.browser"
    }

    /** Plain prose field (control). */
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
        TestConfigHelper.ensureConfigInitialized(context)
        contextTracker = PredictionContextTracker()

        synchronized(Issue151UrlBarSuggestionTapTest::class.java) {
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

        // word_prediction_enabled gates handleRegularTyping (word tracking);
        // autocorrect is irrelevant to the tap path (isManualSelection skips it)
        // but is disabled for determinism. Auto-space prefs pinned to defaults.
        sharedConfig!!.word_prediction_enabled = true
        sharedConfig!!.autocorrect_enabled = false
        sharedConfig!!.swipe_final_autocorrect_enabled = false
        sharedConfig!!.auto_space_before_suggestion = true
        sharedConfig!!.auto_space_after_suggestion = true

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
    private fun type(s: String, editorInfo: EditorInfo) {
        for (c in s) {
            inputConnection.commitText(c.toString(), 1)
            suggestionHandler.handleRegularTyping(c.toString(), inputConnection, editorInfo)
        }
    }

    /** Production suggestion-tap path: SuggestionBridge.onSuggestionSelected →
     *  SuggestionHandler.onSuggestionSelected(word, ic, editorInfo, resources,
     *  isManualSelection = true). */
    private fun tap(word: String, editorInfo: EditorInfo) {
        suggestionHandler.onSuggestionSelected(
            word, inputConnection, editorInfo, context.resources, isManualSelection = true
        )
    }

    private fun editorText() = inputConnection.editableText()

    // ── #151 reproduction: URL bar ───────────────────────────────────────────

    @Test
    fun urlBar_bareToken_tapReplacesWholeToken() {
        // Reported: typing `exa` and tapping `example` produced `exa example `.
        type("exa", urlBarEditorInfo)
        tap("example", urlBarEditorInfo)
        assertEquals(
            "tapped suggestion must replace the typed partial token exactly once",
            "example ", editorText()
        )
    }

    @Test
    fun urlBar_tokenAfterScheme_tapReplacesOnlyThatToken() {
        // URI delimiters (: /) must bound the token; no leading space may be
        // injected after the scheme (" example" would corrupt the URL).
        type("https://exa", urlBarEditorInfo)
        tap("example", urlBarEditorInfo)
        assertEquals(
            "only the partial token after the scheme is replaced, no space injected",
            "https://example ", editorText()
        )
    }

    @Test
    fun urlBar_tokenAfterDot_tapReplacesOnlyThatToken() {
        // TLD completion: "example.co" + tap "com" → "example.com ".
        type("example.co", urlBarEditorInfo)
        tap("com", urlBarEditorInfo)
        assertEquals("example.com ", editorText())
    }

    // ── controls: normal prose fields keep working via cursor-sync ──────────

    @Test
    fun plainField_bareToken_tapReplacesWholeToken() {
        type("hel", plainEditorInfo)
        tap("hello", plainEditorInfo)
        assertEquals("hello ", editorText())
    }

    @Test
    fun plainField_midSentenceToken_tapReplacesOnlyCurrentWord() {
        type("visit exa", plainEditorInfo)
        tap("example", plainEditorInfo)
        assertEquals("visit example ", editorText())
    }
}
