package tribixbite.cleverkeys

import android.content.res.Resources
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * A swiped word, or a tap that replaces one, is committed WITH its alternatives as a
 * SuggestionSpan so the editor's own Replace popup (and replaceTextMenu) offer them
 * (docs/research/2026-10-15-ime-editor-actions.md §1.2: CleverKeys attached no spans, so
 * Replace only ever collapsed the selection). Never in password, terminal-style or
 * no-suggestion fields.
 *
 * Drives the real `SuggestionHandler.onSuggestionSelected` against an editor double;
 * [SuggestionSpanCommit.decorate] (SpannableString is an android.jar stub here) is replaced by
 * a marker that records the plan, so what is pinned is WHICH text reaches commitText.
 */
class SuggestionSpanCommitTest {

    private val objenesis = ObjenesisStd()
    private val editorText = StringBuilder()
    private val committed = mutableListOf<CharSequence>()
    private val plans = mutableListOf<SuggestionSpanPolicy.Plan>()

    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var bar: SuggestionBar
    private lateinit var config: Config
    private lateinit var ic: InputConnection

    /** What decorate returns: the same characters, tagged with its plan. */
    private class Decorated(val text: String, val plan: SuggestionSpanPolicy.Plan) : CharSequence by text {
        override fun toString() = text
    }

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
        config.primary_language = "en"
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config

        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.getCurrentSuggestions() } returns emptyList()

        contextTracker = mockk(relaxed = true)
        every { contextTracker.getLastCommitSource() } returns PredictionSource.UNKNOWN
        every { contextTracker.getLastAutoInsertedWord() } returns null
        every { contextTracker.wasLastInputSwipe() } returns true
        every { contextTracker.shouldSyncForInputType(any()) } returns true
        every { contextTracker.getCharsToDeleteForPrediction() } returns Pair(0, 0)
        every { contextTracker.getCurrentWord() } returns ""

        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } answers { editorText.takeLast(firstArg<Int>()).toString() }
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        every { ic.deleteSurroundingText(any(), any()) } answers {
            editorText.setLength((editorText.length - firstArg<Int>()).coerceAtLeast(0)); true
        }
        every { ic.commitText(any(), any()) } answers {
            committed += firstArg<CharSequence>(); editorText.append(firstArg<CharSequence>()); true
        }

        mockkObject(SuggestionSpanCommit)
        every { SuggestionSpanCommit.decorate(any(), any(), any()) } answers {
            plans += secondArg<SuggestionSpanPolicy.Plan>()
            Decorated(firstArg(), secondArg())
        }
    }

    @After
    fun teardown() = unmockkAll()

    private fun handler(): SuggestionHandler = objenesis.newInstance(SuggestionHandler::class.java).apply {
        setField("contextTracker", contextTracker)
        setField("predictionCoordinator", mockk<PredictionCoordinator>(relaxed = true))
        setField("suggestionBar", bar)
        setField("config", config)
        setField("contractionManager", mockk<ContractionManager>(relaxed = true))
        setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
    }

    private fun editor(type: Int, pkg: String = "com.example.notes"): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply { packageName = pkg; inputType = type }

    private val plain = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL

    @Test
    fun aSwipeCommitCarriesTheOtherCandidatesOverTheWordOnly() {
        handler().onSuggestionSelected(
            "hello", ic, editor(plain), mockk<Resources>(relaxed = true),
            isManualSelection = false, alternatives = listOf("hello", "hallo", "help"),
        )
        assertThat(editorText.toString()).isEqualTo("hello ")
        assertThat(plans).containsExactly(SuggestionSpanPolicy.Plan(0, 5, listOf("hallo", "help")))
        assertThat(committed.single()).isInstanceOf(Decorated::class.java)
    }

    @Test
    fun aTapThatReplacesASwipedWordOffersTheSwipedWordAndTheBar() {
        editorText.append("helo ")
        every { contextTracker.wasLastInputSwipe() } returns false
        every { contextTracker.getLastAutoInsertedWord() } returns "helo"
        every { contextTracker.getLastCommitSource() } returns PredictionSource.SWIPE
        every { bar.getCurrentSuggestions() } returns listOf("helo", "hello", "help")
        handler().onSuggestionSelected("hello", ic, editor(plain), mockk(relaxed = true), isManualSelection = true)
        assertThat(editorText.toString()).isEqualTo("hello ")
        assertThat(plans.single().suggestions).containsExactly("helo", "help").inOrder()
    }

    @Test
    fun noSpanInPasswordTerminalBlindOrNoSuggestionFields() {
        val cases = listOf(
            editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            editor(plain or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS),
            editor(plain, pkg = "com.termux"),
            editor(InputType.TYPE_NULL, pkg = "com.carriez.flutter_hbb"),
        )
        for (info in cases) {
            editorText.clear(); committed.clear()
            handler().onSuggestionSelected(
                "hello", ic, info, mockk(relaxed = true),
                isManualSelection = false, alternatives = listOf("hallo", "help"),
            )
            assertThat(committed.none { it is Decorated }).isTrue()
        }
        assertThat(plans).isEmpty()
    }

    @Test
    fun withoutAlternativesThePlainTextIsCommitted() {
        handler().onSuggestionSelected("hello", ic, editor(plain), mockk(relaxed = true), isManualSelection = false)
        assertThat(plans).isEmpty()
        assertThat(committed.single()).isInstanceOf(String::class.java)
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError("field $name not found on ${javaClass.name}")
    }
}
