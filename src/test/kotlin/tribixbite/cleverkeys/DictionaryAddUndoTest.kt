package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * The tappable "Added “x” to dictionary" confirmation (2026-09-26, user request): every IME path
 * that adds a word to the personal dictionary — the "Add to dictionary?" prompt, the "+word"
 * chip, the autocorrect undo — shows a bar confirmation whose second tap removes the word again
 * (the bar's two-tap state is [UndoableBarMessage], pinned in `UndoableBarMessageTest`).
 *
 * Pinned here, through the REAL [SuggestionHandler.onSuggestionSelected] entry the bar calls:
 *  - the confirmation is undoable only when the add actually INSERTED
 *    ([DictionaryManager.addUserWord] = true) — undo must never delete a word the user had;
 *  - the undo removes the word and refreshes the predictors (so the swipe lexicon memo, keyed on
 *    `custom_words_<lang>` content, follows), and touches no text;
 *  - an undo after the dictionary language changed does nothing (the store is per language);
 *  - typing, backspace, a swipe or another suggestion tap dismisses the confirmation.
 *
 * The swipe "Prefer" offer's undo lives in `SwipeCorrectionOfferTest` (it needs that harness).
 * Harness: Objenesis-built handler, as `SuggestionTapAddAndIWordTest`.
 */
class DictionaryAddUndoTest {

    private val objenesis = ObjenesisStd()

    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var dictionary: DictionaryManager
    private lateinit var predictor: WordPredictor
    private lateinit var bar: SuggestionBar
    private lateinit var ic: InputConnection
    private lateinit var resources: Resources
    private lateinit var config: Config
    private lateinit var context: Context

    private val undo = slot<() -> Unit>()
    private val shownMessage = slot<String>()

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
        config.word_prediction_enabled = true
        every { Config.globalConfig() } returns config

        dictionary = mockk(relaxed = true)
        every { dictionary.getCurrentLanguage() } returns "en"
        every { dictionary.addUserWord(any()) } returns true
        predictor = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        every { coordinator.getDictionaryManager() } returns dictionary
        every { coordinator.getWordPredictor() } returns predictor

        contextTracker = mockk(relaxed = true)
        every { contextTracker.getLastCommitSource() } returns PredictionSource.UNKNOWN

        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.showUndoableMessage(capture(shownMessage), any(), capture(undo)) } just runs

        context = mockk(relaxed = true)
        every { context.getString(any(), *anyVararg()) } answers {
            val id = firstArg<Int>()
            @Suppress("UNCHECKED_CAST")
            val word = (args[1] as Array<Any?>).firstOrNull()
            when (id) {
                R.string.suggestion_added_to_dictionary -> "added:$word"
                R.string.suggestion_removed_from_dictionary -> "removed:$word"
                R.string.suggestion_prefer_when_swiping_added -> "prefers:$word"
                else -> "string:$id"
            }
        }
        every { context.getString(R.string.suggestion_tap_again_to_undo) } returns "tap again"

        ic = mockk(relaxed = true)
        resources = mockk(relaxed = true)
    }

    @After
    fun teardown() = unmockkAll()

    private fun handler(): SuggestionHandler {
        val handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("context", context)
        handler.setField("contextTracker", contextTracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        handler.setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
        return handler
    }

    private fun field(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply { packageName = "com.example.notes" }

    private fun SuggestionHandler.tap(wire: String) = onSuggestionSelected(wire, ic, field(), resources)

    // ------------------------------------------------------------- "Add to dictionary?"

    @Test
    fun theAddPromptConfirmationIsUndoable() {
        handler().tap(Suggestion.AddToDictionary("zebrafish").wire)

        verify(exactly = 1) { bar.showUndoableMessage("added:zebrafish", "tap again", any()) }
        verify(exactly = 0) { bar.showTemporaryMessage(any(), any(), any()) }
    }

    @Test
    fun undoingTheAddPromptRemovesTheWordAndRefreshesThePredictors() {
        handler().tap(Suggestion.AddToDictionary("zebrafish").wire)
        verify(exactly = 1) { coordinator.refreshCustomWords() }

        undo.captured.invoke()

        verify(exactly = 1) { dictionary.removeUserWord("zebrafish") }
        verify(exactly = 2) { coordinator.refreshCustomWords() }
        verify { bar.showTemporaryMessage("removed:zebrafish", any(), clearAfter = true) }
        verify(exactly = 0) { ic.commitText(any(), any()) }
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun anAddThatInsertedNothingIsNotUndoable() {
        // The user already had the word (another casing check passed the prompt, or another
        // writer stored it meanwhile): an undo would delete THEIR word.
        every { dictionary.addUserWord("zebrafish") } returns false

        handler().tap(Suggestion.AddToDictionary("zebrafish").wire)

        verify(exactly = 0) { bar.showUndoableMessage(any(), any(), any()) }
        verify(exactly = 1) { bar.showTemporaryMessage("added:zebrafish", any(), clearAfter = true) }
        verify(exactly = 0) { dictionary.removeUserWord(any()) }
    }

    @Test
    fun anUndoAfterTheLanguageChangedDoesNothing() {
        handler().tap(Suggestion.AddToDictionary("zebrafish").wire)
        every { dictionary.getCurrentLanguage() } returns "de"

        undo.captured.invoke()

        verify(exactly = 0) { dictionary.removeUserWord(any()) }
    }

    // ------------------------------------------------------------- "+word" chip

    @Test
    fun undoingAnExactAddRemovesTheWordButLeavesTheText() {
        every { contextTracker.getCurrentWord() } returns "kotl"
        handler().tap(Suggestion.ExactAdd("kotlin").wire)
        verify(exactly = 1) { ic.commitText("kotlin ", 1) }
        assertThat(shownMessage.captured).isEqualTo("added:kotlin")

        undo.captured.invoke()

        verify(exactly = 1) { dictionary.removeUserWord("kotlin") }
        // The committed word stays — only the dictionary entry is taken back.
        verify(exactly = 1) { ic.commitText(any(), any()) }
        verify(exactly = 1) { ic.deleteSurroundingText(any(), any()) }
    }

    // ------------------------------------------------------------- autocorrect undo

    @Test
    fun theAutocorrectUndoAddIsUndoableToo() {
        every { contextTracker.getLastCommitSource() } returns PredictionSource.AUTOCORRECT
        every { contextTracker.getLastAutocorrectOriginalWord() } returns "teh"
        every { contextTracker.getLastAutoInsertedWord() } returns "the"

        handler().tap("teh")
        verify(exactly = 1) { dictionary.addUserWord("teh") }
        assertThat(shownMessage.captured).isEqualTo("added:teh")

        undo.captured.invoke()
        verify(exactly = 1) { dictionary.removeUserWord("teh") }
    }

    // ------------------------------------------------------------- dismissal

    @Test
    fun typingALetterDismissesTheConfirmation() {
        handler().handleRegularTyping("a", ic, field())
        verify(exactly = 1) { bar.dismissUndoableMessage() }
    }

    @Test
    fun typingASpaceOrPunctuationDismissesTheConfirmation() {
        val handler = handler()
        handler.handleRegularTyping(" ", ic, field())
        handler.handleRegularTyping(".", ic, field())
        verify(exactly = 2) { bar.dismissUndoableMessage() }
    }

    @Test
    fun backspaceDismissesTheConfirmation() {
        handler().handleBackspace()
        verify(exactly = 1) { bar.dismissUndoableMessage() }
    }

    @Test
    fun anotherSuggestionTapDismissesTheConfirmation() {
        runCatching { handler().tap("hello") }
        verify(atLeast = 1) { bar.dismissUndoableMessage() }
    }

    // ------------------------------------------------------------------ reflection

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val f = cls.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                f.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError("field '$name' not found on ${javaClass.simpleName}")
    }
}
