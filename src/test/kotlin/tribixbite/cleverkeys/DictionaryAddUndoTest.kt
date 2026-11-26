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
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.notes"
            // An ordinary text field: an unset inputType is 0 = TYPE_NULL, a blind-field signal since 2026-10-13.
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

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

    // ------------------------------------------- casing of the stored word (2026-11-25)
    //
    // Device report (Saga, Note10+, Pixel; release r16): a word typed at a field or sentence start
    // and then added was stored as `Grüezi` / `Parcero` because its capital came from
    // auto-capitalisation, and every later swipe or tap wrote it capitalised mid-sentence. Rule
    // ([UserWordCasing]): a word whose only capital is its first letter, sitting where
    // auto-capitalisation capitalises, is stored in lowercase; anywhere else the user's capital
    // is kept. The editor text before the cursor is the evidence, read after the word is in it.

    /** An ordinary sentence-capitalising field with auto-capitalisation on. */
    private fun capField(flags: Int = android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES): EditorInfo =
        field().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or flags }

    private fun SuggestionHandler.tapIn(wire: String, info: EditorInfo) =
        onSuggestionSelected(wire, ic, info, resources)

    private fun editorShows(textBeforeCursor: String) {
        every { ic.getTextBeforeCursor(any(), any()) } returns textBeforeCursor
    }

    @Test
    fun aFieldStartWordAddedFromThePromptIsStoredLowercase() {
        config.autocapitalisation = true
        editorShows("Grüezi ")

        handler().tapIn(Suggestion.AddToDictionary("Grüezi").wire, capField())

        verify(exactly = 1) { dictionary.addUserWord("grüezi") }
        verify(exactly = 0) { dictionary.addUserWord("Grüezi") }
        // The confirmation names what was stored, and its undo removes exactly that.
        assertThat(shownMessage.captured).isEqualTo("added:grüezi")
        undo.captured.invoke()
        verify(exactly = 1) { dictionary.removeUserWord("grüezi") }
    }

    @Test
    fun aWordAfterASentenceEndIsStoredLowercase() {
        config.autocapitalisation = true
        editorShows("Wir sind da. Grüezi ")

        handler().tapIn(Suggestion.AddToDictionary("Grüezi").wire, capField())

        verify(exactly = 1) { dictionary.addUserWord("grüezi") }
    }

    @Test
    fun aWordCapitalisedMidSentenceKeepsItsCapital() {
        // The user's own Shift (a name, a German noun): auto-capitalisation did not act there.
        config.autocapitalisation = true
        editorShows("und dann sagte er Grüezi ")

        handler().tapIn(Suggestion.AddToDictionary("Grüezi").wire, capField())

        verify(exactly = 1) { dictionary.addUserWord("Grüezi") }
        assertThat(shownMessage.captured).isEqualTo("added:Grüezi")
    }

    @Test
    fun withAutoCapitalisationOffTheCapitalIsTheUsers() {
        config.autocapitalisation = false
        editorShows("Grüezi ")

        handler().tapIn(Suggestion.AddToDictionary("Grüezi").wire, capField())

        verify(exactly = 1) { dictionary.addUserWord("Grüezi") }
    }

    @Test
    fun aFieldThatAsksForNoCapitalsKeepsTheUsersCapital() {
        config.autocapitalisation = true
        editorShows("Grüezi ")

        handler().tapIn(Suggestion.AddToDictionary("Grüezi").wire, capField(flags = 0))

        verify(exactly = 1) { dictionary.addUserWord("Grüezi") }
    }

    @Test
    fun aWordsCapitalisingFieldCapitalisesEveryWordSoAnyPositionIsLowercased() {
        config.autocapitalisation = true
        editorShows("Hans Muster ")

        handler().tapIn(
            Suggestion.AddToDictionary("Muster").wire,
            capField(flags = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        )

        verify(exactly = 1) { dictionary.addUserWord("muster") }
    }

    @Test
    fun internalCapitalsAndAllCapsAreKeptAtASentenceStart() {
        config.autocapitalisation = true
        val handler = handler()

        editorShows("McDonald ")
        handler.tapIn(Suggestion.AddToDictionary("McDonald").wire, capField())
        editorShows("NASA ")
        handler.tapIn(Suggestion.AddToDictionary("NASA").wire, capField())

        verify(exactly = 1) { dictionary.addUserWord("McDonald") }
        verify(exactly = 1) { dictionary.addUserWord("NASA") }
    }

    @Test
    fun theAutocorrectUndoAddStoresASentenceStartWordLowercase() {
        // `parcero` typed at a field start, auto-capitalised to `Parcero`, autocorrected to
        // `Parcel`; tapping the original restores `Parcero` in the field and adds `parcero`.
        config.autocapitalisation = true
        every { contextTracker.getLastCommitSource() } returns PredictionSource.AUTOCORRECT
        every { contextTracker.getLastAutocorrectOriginalWord() } returns "Parcero"
        every { contextTracker.getLastAutoInsertedWord() } returns "Parcel"
        editorShows("Parcero ")

        handler().tapIn("Parcero", capField())

        verify(exactly = 1) { ic.commitText("Parcero ", 1) }
        verify(exactly = 1) { dictionary.addUserWord("parcero") }
        assertThat(shownMessage.captured).isEqualTo("added:parcero")
        undo.captured.invoke()
        verify(exactly = 1) { dictionary.removeUserWord("parcero") }
    }

    @Test
    fun theExactAddChipStoresASentenceStartWordLowercase() {
        config.autocapitalisation = true
        every { contextTracker.getCurrentWord() } returns "Kotl"
        editorShows("Kotlin ")

        handler().tapIn(Suggestion.ExactAdd("Kotlin").wire, capField())

        // The field keeps the capital the user saw; only the stored form is folded.
        verify(exactly = 1) { ic.commitText("Kotlin ", 1) }
        verify(exactly = 1) { dictionary.addUserWord("kotlin") }
    }

    @Test
    fun aWordTheEditorDoesNotShowIsStoredAsGiven() {
        // No evidence of where the word sits (the read failed or the text moved on): keep it.
        config.autocapitalisation = true
        editorShows("something else entirely ")

        handler().tapIn(Suggestion.AddToDictionary("Grüezi").wire, capField())

        verify(exactly = 1) { dictionary.addUserWord("Grüezi") }
    }

    @Test
    fun theSentenceStartTestFollowsTheEditorsCapsModeRules() {
        val s = UserWordCasing.AutoCapMode.SENTENCES
        assertThat(UserWordCasing.autoCapitalisesAfter("", s)).isTrue()
        assertThat(UserWordCasing.autoCapitalisesAfter("Hallo\n", s)).isTrue()
        assertThat(UserWordCasing.autoCapitalisesAfter("Really? ", s)).isTrue()
        assertThat(UserWordCasing.autoCapitalisesAfter("He said. \"", s)).isTrue()
        assertThat(UserWordCasing.autoCapitalisesAfter("(done.) ", s)).isTrue()
        // A dotted abbreviation does not end a sentence; nor does a period with no space after it.
        assertThat(UserWordCasing.autoCapitalisesAfter("see e.g. ", s)).isFalse()
        assertThat(UserWordCasing.autoCapitalisesAfter("end.", s)).isFalse()
        assertThat(UserWordCasing.autoCapitalisesAfter("und ", s)).isFalse()
        assertThat(UserWordCasing.autoCapitalisesAfter("und ", UserWordCasing.AutoCapMode.WORDS)).isTrue()
        assertThat(UserWordCasing.autoCapitalisesAfter("", UserWordCasing.AutoCapMode.NONE)).isFalse()
    }

    @Test
    fun theStoredFormNeedsTheWordStandingAloneAndAKnownStart() {
        val s = UserWordCasing.AutoCapMode.SENTENCES
        val de = java.util.Locale.GERMAN
        // Only a whole-word match counts; a longer word containing it is no evidence.
        assertThat(UserWordCasing.storageForm("Grüezi", "Grüezimann ", s, false, de)).isEqualTo("Grüezi")
        // The read window cut the text before the word: its context is unknown.
        assertThat(UserWordCasing.storageForm("Grüezi", "Grüezi ", s, true, de)).isEqualTo("Grüezi")
        assertThat(UserWordCasing.storageForm("Grüezi", null, s, false, de)).isEqualTo("Grüezi")
        // Single letters and digits after the capital are not the auto-capital shape.
        assertThat(UserWordCasing.hasAutoCapitalShape("I")).isFalse()
        assertThat(UserWordCasing.hasAutoCapitalShape("A1")).isFalse()
        assertThat(UserWordCasing.hasAutoCapitalShape("iPhone")).isFalse()
        assertThat(UserWordCasing.hasAutoCapitalShape("Ärger")).isTrue()
        // The dictionary language's case mapping: Turkish dotted İ lowercases to a plain i.
        assertThat(
            UserWordCasing.storageForm("İstanbullu", "İstanbullu ", s, false, java.util.Locale.forLanguageTag("tr"))
        ).isEqualTo("istanbullu")
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
