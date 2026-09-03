package tribixbite.cleverkeys

import android.content.res.Resources
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
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import java.io.File

/**
 * Three release-record rows about what a single TAP on the suggestion bar does.
 *
 * | version | note | anchor |
 * |---|---|---|
 * | v1.2.6 | "Add typed words with a single tap (#42)" | `DictionaryManager.kt#DictionaryManager` |
 * | v1.2.8 | "Single tap to add words (#42)" | same |
 * | v1.2.8 | "Capitalize I words for swipe (#72)" | `SuggestionHandler.kt#SuggestionHandler` |
 *
 * ## Tap-to-add (#42)
 *
 * `SuggestionModelTest` and `PipelineOracleJvmTest` already pin the pure ROUTING decision
 * (`routeSuggestionSelection("exact_add:x")` → `SelectionRoute.ExactAdd("x")`). What no test
 * covered is the EFFECT the note promised: one tap, and the word is both committed to the field
 * and added to the personal dictionary. Those are two different things and either could regress
 * alone, so both are asserted, along with the partial-word deletion that makes the commit a
 * replacement rather than a duplication ("kotl" + tap "+kotlin" must not yield "kotlkotlin ").
 *
 * The entry point driven here is the real public `onSuggestionSelected` — the method the bar's
 * click listener calls — not the private handler, so the routing and the effect are pinned as
 * one path.
 *
 * ## I-word capitalization (#72)
 *
 * `capitalizeIWord` is the transform; it is exercised directly (exact in/out for every member
 * of `I_WORDS`, for near-misses, and with the setting off), and its position in the pipeline is
 * pinned at the source: the swipe auto-insert commits through `onSuggestionSelected`, so the
 * apply site inside that method is what makes the claim "for swipe" true.
 *
 * ## Harness
 *
 * `SuggestionHandler` initialises `Handler(Looper.getMainLooper())` and `MLDataCollector` in
 * field initialisers — android.jar stubs that throw under `runMockTests` — so the instance is
 * Objenesis-allocated and only the fields these paths read are seeded. Same pattern and reason
 * as [ContractionUserWordGuardTest] and [ImeTeardownExecutorShutdownTest].
 */
class SuggestionTapAddAndIWordTest {

    private val objenesis = ObjenesisStd()

    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var dictionary: DictionaryManager
    private lateinit var predictor: WordPredictor
    private lateinit var bar: SuggestionBar
    private lateinit var ic: InputConnection
    private lateinit var resources: Resources
    private lateinit var config: Config

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.autocapitalize_i_words = true
        every { Config.globalConfig() } returns config

        dictionary = mockk(relaxed = true)
        predictor = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        every { coordinator.getDictionaryManager() } returns dictionary
        every { coordinator.getWordPredictor() } returns predictor

        // Relaxed: `getCurrentWord()` defaults to "" (nothing typed yet), which is what most
        // tests want. The few that need a partial word stub it themselves. Note MockK prints a
        // harmless "Failed to set backing field (skipping)" warning when stubbing it — the
        // tracker's backing field is a StringBuilder, not the String the getter returns.
        contextTracker = mockk(relaxed = true)
        every { contextTracker.getLastCommitSource() } returns PredictionSource.UNKNOWN

        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null

        ic = mockk(relaxed = true)
        resources = mockk(relaxed = true)
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
        return handler
    }

    /** An EditorInfo naming [pkg]; the stub's constructor throws, so allocate without it. */
    private fun editorInfo(pkg: String?): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply { packageName = pkg }

    private fun SuggestionHandler.capitalizeI(word: String): String =
        SuggestionHandler::class.java
            .getDeclaredMethod("capitalizeIWord", String::class.java)
            .apply { isAccessible = true }
            .invoke(this, word) as String

    // ------------------------------------------------- #42: "+word" adds AND commits

    @Test
    fun tappingTheExactWordChipCommitsItAndAddsItToTheDictionary() {
        every { contextTracker.getCurrentWord() } returns "kotl"

        val returned = handler().onSuggestionSelected(
            "exact_add:kotlin", ic, editorInfo("com.example.notes"), resources
        )

        assertWithMessage("the special-suggestion route commits itself and returns no word")
            .that(returned).isNull()

        // Both halves of the promise, in the order the field requires: the partial word the
        // user typed is deleted first, then the full word lands with its trailing space.
        verifyOrder {
            ic.deleteSurroundingText(4, 0)
            ic.commitText("kotlin ", 1)
        }
        verify(exactly = 1) { dictionary.addUserWord("kotlin") }
        // Without the refresh the word is stored but invisible to predictions until restart.
        verify(exactly = 1) { coordinator.refreshCustomWords() }
    }

    @Test
    fun theWordIsAddedExactlyAsTypedNotLowercased() {
        every { contextTracker.getCurrentWord() } returns "McKenna"

        handler().onSuggestionSelected(
            "exact_add:McKenna", ic, editorInfo("com.example.notes"), resources
        )

        // #42 exists so a user can keep a spelling the dictionary rejects — folding its case
        // would defeat the feature for exactly the proper nouns it is used for.
        verify(exactly = 1) { dictionary.addUserWord("McKenna") }
        verify(exactly = 1) { ic.commitText("McKenna ", 1) }
    }

    @Test
    fun withNothingTypedYetNoDeletionIsIssued() {
        // getCurrentWord() is "" by default — nothing typed.
        handler().onSuggestionSelected(
            "exact_add:kotlin", ic, editorInfo("com.example.notes"), resources
        )

        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
        verify(exactly = 1) { ic.commitText("kotlin ", 1) }
        verify(exactly = 1) { dictionary.addUserWord("kotlin") }
    }

    @Test
    fun inTermuxTheDeletionUsesKeyEventsInsteadOfDeleteSurroundingText() {
        val keyEvents = mockk<KeyEventHandler>(relaxed = true)
        every { contextTracker.getCurrentWord() } returns "abc"

        val handler = handler()
        handler.setField("keyeventhandler", keyEvents)
        handler.onSuggestionSelected("exact_add:abcd", ic, editorInfo(SuggestionHandler.TERMUX_PACKAGE), resources)

        // Termux's terminal ignores deleteSurroundingText; the app-specific path is one
        // KEYCODE_DEL per typed character.
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
        verify(exactly = 3) { keyEvents.send_key_down_up(android.view.KeyEvent.KEYCODE_DEL, 0) }
        verify(exactly = 1) { ic.commitText("abcd ", 1) }
        verify(exactly = 1) { dictionary.addUserWord("abcd") }
    }

    @Test
    fun tappingTheAddToDictionaryPromptAddsWithoutRewritingTheField() {
        val returned = handler().onSuggestionSelected(
            "dict_add:zebrafish", ic, editorInfo("com.example.notes"), resources
        )

        assertThat(returned).isNull()
        verify(exactly = 1) { dictionary.addUserWord("zebrafish") }
        verify(exactly = 1) { coordinator.refreshCustomWords() }
        // The prompt appears AFTER the word was already committed, so touching the text again
        // would duplicate or truncate it.
        verify(exactly = 0) { ic.commitText(any(), any()) }
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun anEmptyPayloadOnEitherAddRouteIsIgnored() {
        val handler = handler()
        handler.onSuggestionSelected("exact_add:", ic, editorInfo("com.example.notes"), resources)
        handler.onSuggestionSelected("dict_add:", ic, editorInfo("com.example.notes"), resources)

        verify(exactly = 0) { dictionary.addUserWord(any()) }
        verify(exactly = 0) { ic.commitText(any(), any()) }
    }

    @Test
    fun anOrdinaryWordTapNeverAddsToTheDictionary() {
        // The negative control for #42: only the "+word" chip adds. If a plain suggestion tap
        // also added, every accepted prediction would silently enter the personal dictionary.
        every { contextTracker.getCurrentWord() } returns "hell"
        val handler = handler()

        runCatching {
            handler.onSuggestionSelected("hello", ic, editorInfo("com.example.notes"), resources)
        }

        verify(exactly = 0) { dictionary.addUserWord(any()) }
    }

    // ---------------------------------------------------------- #72: I-word capitalization

    @Test
    fun everyIFormIsCapitalizedAndNothingElseIs() {
        val handler = handler()

        assertThat(handler.capitalizeI("i")).isEqualTo("I")
        assertThat(handler.capitalizeI("i'm")).isEqualTo("I'm")
        assertThat(handler.capitalizeI("i'll")).isEqualTo("I'll")
        assertThat(handler.capitalizeI("i'd")).isEqualTo("I'd")
        assertThat(handler.capitalizeI("i've")).isEqualTo("I've")

        assertWithMessage("only the FIRST letter changes — `i'll` must not become `I'LL`")
            .that(handler.capitalizeI("i'll")).isEqualTo("I'll")

        // Words that merely start with `i` are ordinary words and must be left alone.
        for (word in listOf("island", "it", "in", "ill", "id", "ive", "im", "internet")) {
            assertWithMessage("`$word` is not an I-form and must pass through unchanged")
                .that(handler.capitalizeI(word)).isEqualTo(word)
        }
    }

    @Test
    fun anAlreadyCapitalizedIFormIsUnchanged() {
        val handler = handler()
        assertThat(handler.capitalizeI("I")).isEqualTo("I")
        assertThat(handler.capitalizeI("I'm")).isEqualTo("I'm")
        assertWithMessage("the membership test is case-insensitive, the rewrite is idempotent")
            .that(handler.capitalizeI("I'VE")).isEqualTo("I'VE")
    }

    @Test
    fun withTheSettingOffNothingIsCapitalized() {
        config.autocapitalize_i_words = false
        val handler = handler()

        assertWithMessage(
            "the setting is read from globalConfig on EVERY call (v1.2.8) so a Settings change " +
                "takes effect without restarting the keyboard"
        ).that(handler.capitalizeI("i")).isEqualTo("i")
        assertThat(handler.capitalizeI("i'm")).isEqualTo("i'm")

        config.autocapitalize_i_words = true
        assertThat(handler.capitalizeI("i")).isEqualTo("I")
    }

    @Test
    fun anEmptyWordIsSurvivable() {
        assertThat(handler().capitalizeI("")).isEqualTo("")
    }

    /**
     * The claim is "capitalize I words FOR SWIPE". A swipe's top prediction is auto-inserted by
     * `handleSwipePredictionResults` calling `onSuggestionSelected` — the one commit engine —
     * so the transform being applied inside that method is what makes the swipe case true. A
     * refactor that moved the call out of the commit path would leave every assertion above
     * green while a swiped "i" was again committed lowercase.
     */
    @Test
    fun theCommitEngineTheSwipeAutoInsertUsesAppliesTheTransform() {
        val source = File("src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt")
        assertWithMessage("expected ${source.path} (run from project root)")
            .that(source.isFile).isTrue()
        val text = source.readText()

        val swipeResults = text.substringAfter("fun handleSwipePredictionResults(")
            .substringBefore("fun onSuggestionSelected(")
        assertWithMessage("handleSwipePredictionResults was renamed — re-point this guard")
            .that(swipeResults).isNotEmpty()
        assertWithMessage(
            "the swipe auto-insert must route through onSuggestionSelected; a private commit " +
                "shortcut would bypass I-word capitalization (and autocorrect, and tracking)"
        ).that(swipeResults).contains("onSuggestionSelected(")

        val commit = text.substringAfter("fun onSuggestionSelected(")
            .substringBefore("private fun handleSpecialSuggestion")
        assertWithMessage(
            "onSuggestionSelected must apply capitalizeIWord to the word it is about to commit"
        ).that(commit).contains("processedWord = capitalizeIWord(processedWord)")
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
