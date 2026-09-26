package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.ContextModel
import tribixbite.cleverkeys.contextaware.TrigramStore
import tribixbite.cleverkeys.ml.SwipeMLData
import tribixbite.cleverkeys.ml.SwipeMLDataStore
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import tribixbite.cleverkeys.personalization.PersonalizationEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The swipe-correction offer wired end to end through the REAL [SuggestionHandler] commit/undo
 * paths (learning-system audit 2026-09-26, Resolution — "I swipe `git` and get `got`").
 *
 * Feature A: a correction of a swipe auto-insert (bar tap over it, or backspace undo followed
 * by the next word) is recorded per language; at [SwipeCorrectionPolicy.OFFER_MIN_CORRECTIONS]
 * the bar offers "Prefer “git” when swiping?", and accepting adds `git` to the personal
 * dictionary — the shipped mechanism the CTC lexicon ranks by. Gates: master learning switch,
 * incognito field, password field.
 *
 * Feature B: the swipe-ML row stored for the corrected swipe is relabelled with the chosen word.
 *
 * Harness: the [LearningFunnelBookkeepingTest] pattern — Objenesis-built handler with a real
 * [PredictionContextTracker], a real-funnel [WordPredictor] spy, a fake editor over a
 * StringBuilder; the correction store is the real [SwipeCorrectionStore] over in-memory storage.
 */
class SwipeCorrectionOfferTest {

    private val objenesis = ObjenesisStd()
    private val scheduler = ScheduledThreadPoolExecutor(1)

    private lateinit var config: Config
    private lateinit var tracker: PredictionContextTracker
    private lateinit var predictor: WordPredictor
    private lateinit var dictionary: DictionaryManager
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var bar: SuggestionBar
    private lateinit var inputCoordinator: InputCoordinator
    private lateinit var resources: Resources
    private lateinit var handler: SuggestionHandler
    private lateinit var store: SwipeCorrectionStore
    private lateinit var mlStore: SwipeMLDataStore

    /** The undo of the last undoable "Added …" confirmation the bar was asked to show. */
    private val undoConfirmation = slot<() -> Unit>()

    private val editor = StringBuilder()
    private lateinit var ic: InputConnection
    private var barWords: List<String> = emptyList()

    private val offer = Suggestion.PreferSwipeWord("git").wire
    private val decline = Suggestion.DeclineSwipePreference("git").wire

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.on_device_learning_enabled = true
        config.context_aware_predictions_enabled = true
        config.personalized_learning_enabled = true
        config.word_prediction_enabled = true
        config.auto_space_after_suggestion = true
        config.auto_space_before_suggestion = true
        config.autocapitalize_i_words = true
        config.autocapitalisation = false
        config.autocorrect_enabled = false
        config.swipe_final_autocorrect_enabled = false
        config.next_word_prediction_enabled = false
        config.swipe_context_rescoring = false
        config.swipe_on_password_fields = false
        config.backspace_undo_swipe = true
        every { Config.globalConfig() } returns config

        tracker = PredictionContextTracker()

        val bigramStore = BigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        val trigramStore = TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        val real = objenesis.newInstance(WordPredictor::class.java)
        real.setField("recentWords", mutableListOf<String>())
        real.setField("config", config)
        real.setField("contextModel", ContextModel(bigramStore, trigramStore, "en"))
        real.setField("personalizationEngine", mockk<PersonalizationEngine>(relaxed = true))
        real.setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
        real.setField("languageDetector", null)
        real.setField("multiLanguageManager", null)
        predictor = spyk(real)
        every { predictor.isInDictionary(any()) } returns true
        every { predictor.isInUserVocabulary(any()) } returns false
        every { predictor.isWordDisabled(any()) } returns false
        every { predictor.reset() } just runs

        dictionary = mockk(relaxed = true)
        every { dictionary.getCurrentLanguage() } returns "en"
        every { dictionary.isUserWordIgnoringCase(any()) } returns false
        mlStore = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        every { coordinator.getWordPredictor() } returns predictor
        every { coordinator.getAdaptationManager() } returns mockk(relaxed = true)
        every { coordinator.getDictionaryManager() } returns dictionary
        every { coordinator.getMlDataStore() } returns mlStore

        barWords = emptyList()
        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.setSuggestionsWithScores(any(), any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
        }
        every { bar.setSuggestionsWithScores(any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
        }
        every { bar.clearSuggestions() } answers { barWords = emptyList() }
        every { bar.getTopSuggestion() } answers { barWords.firstOrNull() }
        undoConfirmation.clear()
        every { bar.showUndoableMessage(any(), any(), capture(undoConfirmation)) } just runs

        inputCoordinator = mockk(relaxed = true)
        every { inputCoordinator.getCurrentSwipeData() } returns null
        resources = mockk(relaxed = true)

        editor.setLength(0)
        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } answers {
            val n = firstArg<Int>()
            editor.substring(maxOf(0, editor.length - n))
        }
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        every { ic.commitText(any(), any()) } answers {
            editor.append(firstArg<CharSequence>())
            true
        }
        every { ic.deleteSurroundingText(any(), any()) } answers {
            editor.setLength(maxOf(0, editor.length - firstArg<Int>()))
            true
        }
        every { ic.getCursorCapsMode(any()) } returns 0

        val contractions = mockk<ContractionManager>(relaxed = true)
        every { contractions.isKnownContraction(any()) } returns false
        every { contractions.isContractionKey(any()) } returns false
        every { contractions.generatePossessive(any()) } returns null

        val context = mockk<Context>(relaxed = true)
        every { context.getString(any(), *anyVararg()) } returns "added"

        store = SwipeCorrectionStore(InMemoryLearnedStorage())

        handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("context", context)
        handler.setField("contextTracker", tracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("contractionManager", contractions)
        handler.setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        handler.setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
        handler.setField("fieldAllowsPersonalizedLearning", true)
        handler.setField("swipeCorrectionStore", store)
    }

    @After
    fun teardown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
        unmockkAll()
    }

    // ------------------------------------------------------------------ fixtures

    private fun textField(): EditorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
        packageName = "com.example.notes"
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun passwordField(): EditorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
        packageName = "com.example.bank"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun type(text: String, field: EditorInfo = textField()) {
        for (c in text) {
            editor.append(c)
            handler.handleRegularTyping(c.toString(), ic, field)
        }
    }

    private fun swipe(vararg slate: String, field: EditorInfo = textField()) {
        tracker.setWasLastInputSwipe(true)
        handler.handleSwipePredictionResults(
            slate.toList(), slate.indices.map { 100 - it }, ic, field, resources,
            false, false, inputCoordinator
        )
    }

    private fun tap(word: String, field: EditorInfo = textField()) {
        handler.onSuggestionSelected(word, ic, field, resources, isManualSelection = true)
    }

    /** KeyEventHandler.handleBackspaceUndoSwipe: delete "word " and report it. */
    private fun backspaceUndo(word: String) {
        check(editor.endsWith("$word ")) { "editor '$editor' does not end with the swiped word" }
        editor.setLength(editor.length - word.length - 1)
        handler.onSwipeWordUndone(word, ic)
    }

    /** The audit's scenario: swipe → "got" auto-inserted → tap "git". */
    private fun correctGotToGitFromTheBar() {
        swipe("got", "git", "hit")
        tap("git")
    }

    // ================================================================ Feature A — recording

    @Test
    fun aBarTapOverTheAutoInsertIsRecordedAsACorrection() {
        editor.append("fix ")
        correctGotToGitFromTheBar()

        assertThat(editor.toString()).isEqualTo("fix git ")
        assertThat(store.correctionCount("en", "git")).isEqualTo(1)
        assertThat(store.pairCount("en", "got", "git")).isEqualTo(1)
        assertWithMessage("one correction does not offer yet").that(barWords).doesNotContain(offer)
    }

    @Test
    fun backspaceUndoThenTypingTheWordIsRecorded() {
        editor.append("fix ")
        swipe("got", "for")
        backspaceUndo("got")
        type("git ")

        assertThat(editor.toString()).isEqualTo("fix git ")
        assertThat(store.correctionCount("en", "git")).isEqualTo(1)
        assertThat(store.pairCount("en", "got", "git")).isEqualTo(1)
    }

    @Test
    fun backspaceUndoThenReSwipingTheWordIsRecordedOnceTheUserMovesOn() {
        editor.append("fix ")
        swipe("got", "for")
        backspaceUndo("got")
        swipe("git", "got")
        assertWithMessage("the re-swipe alone is only a candidate")
            .that(store.correctionCount("en", "git")).isEqualTo(0)

        type("now ")

        assertThat(store.pairCount("en", "got", "git")).isEqualTo(1)
    }

    @Test
    fun aChangedMindRetypeIsNotRecorded() {
        editor.append("say ")
        swipe("hello", "help")
        backspaceUndo("hello")
        type("hi ")

        assertThat(store.correctionCount("en", "hi")).isEqualTo(0)
    }

    @Test
    fun aWordTypedAfterTheCursorMovedAwayIsNotRecorded() {
        editor.append("fix ")
        swipe("got", "for")
        backspaceUndo("got")
        // The user taps elsewhere and types there: the editor no longer continues the undo point.
        editor.append("and then ")
        type("git ")

        assertThat(store.correctionCount("en", "git")).isEqualTo(0)
    }

    @Test
    fun aTypoIsNotRecorded() {
        every { predictor.isInDictionary("gxt") } returns false
        editor.append("fix ")
        swipe("got", "gxt")
        tap("gxt")

        assertThat(store.correctionCount("en", "gxt")).isEqualTo(0)
    }

    @Test
    fun keepingTheSwipeRecordsNothing() {
        editor.append("fix ")
        swipe("git", "got")
        type("now ")

        assertThat(store.trackedWordCount("en")).isEqualTo(0)
    }

    // ================================================================ Feature A — gates

    @Test
    fun theMasterLearningSwitchOffRecordsNothing() {
        config.on_device_learning_enabled = false
        correctGotToGitFromTheBar()
        correctGotToGitFromTheBar()

        assertThat(store.trackedWordCount("en")).isEqualTo(0)
        assertThat(barWords).doesNotContain(offer)
    }

    @Test
    fun anIncognitoFieldRecordsNothing() {
        handler.setField("fieldAllowsPersonalizedLearning", false)
        editor.append("fix ")
        swipe("got", "for")
        backspaceUndo("got")
        type("git ")
        correctGotToGitFromTheBar()

        assertThat(store.trackedWordCount("en")).isEqualTo(0)
    }

    @Test
    fun aPasswordFieldRecordsNothing() {
        config.swipe_on_password_fields = true
        swipe("got", "git", field = passwordField())
        tap("git", field = passwordField())

        assertThat(store.trackedWordCount("en")).isEqualTo(0)
    }

    @Test
    fun theMasterSwitchOffAlsoSuppressesTheOfferForExistingCounts() {
        store.recordCorrection("en", "git", listOf("got"))
        store.recordCorrection("en", "git", listOf("got"))
        config.on_device_learning_enabled = false
        correctGotToGitFromTheBar()

        assertThat(barWords).doesNotContain(offer)
    }

    // ================================================================ Feature A — the offer

    @Test
    fun theSecondCorrectionOffersToPreferTheWord() {
        editor.append("fix ")
        correctGotToGitFromTheBar()
        type("and ")
        correctGotToGitFromTheBar()

        assertThat(store.correctionCount("en", "git")).isEqualTo(2)
        assertThat(barWords).containsExactly(offer, decline).inOrder()
    }

    @Test
    fun acceptingTheOfferAddsTheWordToThePersonalDictionary() {
        correctGotToGitFromTheBar()
        type("and ")
        correctGotToGitFromTheBar()
        val before = editor.toString()

        tap(offer)

        verify(exactly = 1) { dictionary.addUserWord("git") }
        verify { coordinator.refreshCustomWords() }
        assertWithMessage("accepting does not touch the text").that(editor.toString()).isEqualTo(before)
        assertWithMessage("the counts are superseded by the dictionary entry")
            .that(store.correctionCount("en", "git")).isEqualTo(0)
        verify { bar.showTemporaryMessage(any(), any(), clearAfter = true) }
    }

    @Test
    fun decliningIsRememberedAndNeverAskedAgain() {
        correctGotToGitFromTheBar()
        type("and ")
        correctGotToGitFromTheBar()

        tap(decline)
        assertThat(store.isDeclined("en", "git")).isTrue()
        verify(exactly = 0) { dictionary.addUserWord(any()) }

        type("so ")
        correctGotToGitFromTheBar()
        type("and ")
        correctGotToGitFromTheBar()
        assertThat(barWords).doesNotContain(offer)
    }

    @Test
    fun aWordAlreadyInThePersonalDictionaryIsNotOffered() {
        every { dictionary.isUserWordIgnoringCase("git") } returns true
        correctGotToGitFromTheBar()
        type("and ")
        correctGotToGitFromTheBar()

        assertThat(barWords).doesNotContain(offer)
    }

    @Test
    fun theOfferSurvivesTheCursorParkThatFollowsTheCommit() {
        correctGotToGitFromTheBar()
        type("and ")
        correctGotToGitFromTheBar()

        // InputCoordinator's debounced cursor sync lands after the commit with no word at the cursor.
        handler.handleCursorParkPrediction(textField(), ic)

        assertThat(barWords).containsExactly(offer, decline).inOrder()
    }

    @Test
    fun anUndoResolvedByTypingOffersToo() {
        editor.append("fix ")
        correctGotToGitFromTheBar()
        type("and ")
        swipe("got", "for")
        backspaceUndo("got")
        type("git ")

        assertThat(barWords).containsExactly(offer, decline).inOrder()
    }

    // ================================================================ undoing an accepted offer

    @Test
    fun theAcceptConfirmationUndoesTheAddAndTheWordCanBeOfferedAgain() {
        every { dictionary.addUserWord("git") } returns true
        correctGotToGitFromTheBar()
        type("and ")
        correctGotToGitFromTheBar()
        tap(offer)
        verify(exactly = 0) { bar.showTemporaryMessage(any(), any(), any()) }

        undoConfirmation.captured.invoke()

        verify(exactly = 1) { dictionary.removeUserWord("git") }
        verify(exactly = 2) { coordinator.refreshCustomWords() }
        assertWithMessage("undo is not a decline — the word stays offerable")
            .that(store.isDeclined("en", "git")).isFalse()
        assertWithMessage("the counts restart: the offer needs a fresh pattern, not one more slip")
            .that(store.correctionCount("en", "git")).isEqualTo(0)

        type("so ")
        correctGotToGitFromTheBar()
        assertThat(barWords).doesNotContain(offer)
        type("and ")
        correctGotToGitFromTheBar()
        assertThat(barWords).containsExactly(offer, decline).inOrder()
    }

    @Test
    fun anAcceptThatInsertedNothingIsNotUndoable() {
        // Relaxed mock default: addUserWord → false (the word was already stored).
        correctGotToGitFromTheBar()
        type("and ")
        correctGotToGitFromTheBar()
        tap(offer)

        verify(exactly = 0) { bar.showUndoableMessage(any(), any(), any()) }
        verify(exactly = 0) { dictionary.removeUserWord(any()) }
    }

    // ================================================================ deferred offers (task 2)

    /** One correction toward `git` already on record, so the next one reaches the threshold. */
    private fun oneCorrectionOnRecord() {
        store.recordCorrection("en", "git", listOf("got"))
    }

    /** swipe `got` → backspace undo → re-swipe `git` (a candidate, not yet settled). */
    private fun undoThenReSwipeGit() {
        editor.append("fix ")
        swipe("got", "for")
        backspaceUndo("got")
        swipe("git", "got")
    }

    @Test
    fun aReSwipeSettledByTheNextSwipeOffersAtTheNextWordCompletion() {
        oneCorrectionOnRecord()
        undoThenReSwipeGit()
        swipe("now", "how")

        assertThat(store.correctionCount("en", "git")).isEqualTo(2)
        assertWithMessage("the bar holds the new swipe's alternates — not the moment to offer")
            .that(barWords).doesNotContain(offer)

        type("so ")
        assertThat(barWords).containsExactly(offer, decline).inOrder()
    }

    @Test
    fun anEnterSettledCorrectionOffersWhenTheBarNextIdles() {
        oneCorrectionOnRecord()
        undoThenReSwipeGit()
        handler.onEditorWordBoundary(ic)
        assertThat(store.correctionCount("en", "git")).isEqualTo(2)
        assertThat(barWords).doesNotContain(offer)

        // After Enter the cursor parks on the new line (or the sent field's empty start).
        handler.handleCursorParkPrediction(textField(), ic)
        assertThat(barWords).containsExactly(offer, decline).inOrder()
    }

    @Test
    fun aFieldExitSettledCorrectionOffersInTheNextField() {
        oneCorrectionOnRecord()
        undoThenReSwipeGit()
        handler.flushTypedWordOnFinishInput(ic)
        assertThat(store.correctionCount("en", "git")).isEqualTo(2)

        editor.setLength(0)
        type("ok ")
        assertThat(barWords).containsExactly(offer, decline).inOrder()
    }

    @Test
    fun aDeferredOfferIsShownOnlyOnce() {
        oneCorrectionOnRecord()
        undoThenReSwipeGit()
        handler.onEditorWordBoundary(ic)
        type("so ")
        assertThat(barWords).contains(offer)

        type("then ")
        assertThat(barWords).doesNotContain(offer)
    }

    @Test
    fun aDeferredOfferIsDroppedWhenTheWordWasAddedMeanwhile() {
        oneCorrectionOnRecord()
        undoThenReSwipeGit()
        handler.onEditorWordBoundary(ic)
        every { dictionary.isUserWordIgnoringCase("git") } returns true

        type("so ")
        assertThat(barWords).doesNotContain(offer)
    }

    @Test
    fun aDeferredOfferIsDroppedWhenTheCountsWereErased() {
        oneCorrectionOnRecord()
        undoThenReSwipeGit()
        handler.onEditorWordBoundary(ic)
        store.clearAll() // Privacy → forget learned data

        type("so ")
        assertThat(barWords).doesNotContain(offer)
    }

    @Test
    fun aDeferredOfferWaitsForItsOwnLanguage() {
        oneCorrectionOnRecord()
        undoThenReSwipeGit()
        handler.onEditorWordBoundary(ic)

        every { dictionary.getCurrentLanguage() } returns "de"
        type("so ")
        assertThat(barWords).doesNotContain(offer)

        every { dictionary.getCurrentLanguage() } returns "en"
        type("and ")
        assertThat(barWords).containsExactly(offer, decline).inOrder()
    }

    // ================================================================ contraction case

    @Test
    fun aPromotedContractionCorrectedToItsBaseCountsTowardTheBase() {
        // CTC promoted "i'd" over "id" (ContractionOverlay); the user wanted "id".
        editor.append("my ")
        swipe("I'd", "id")
        tap("id")
        type("is ")
        swipe("I'd", "id")
        tap("id")

        assertThat(store.pairCount("en", "i'd", "id")).isEqualTo(2)
        assertThat(barWords).containsExactly(
            Suggestion.PreferSwipeWord("id").wire, Suggestion.DeclineSwipePreference("id").wire
        ).inOrder()
    }

    // ================================================================ Feature B — ML relabel

    @Test
    fun theCorrectedSwipesMlRowIsRelabelled() {
        val capture = SwipeMLData("", "swipe_capture", 1080, 2400, 900, "qwerty", "ctc")
        every { inputCoordinator.getCurrentSwipeData() } returns capture
        val collector = mockk<MLDataCollector>()
        every { collector.collectAndStoreSwipeData(any(), any(), any(), any(), any()) } answers {
            arg<((String) -> Unit)?>(4)?.invoke("trace-got")
            true
        }
        handler.setField("mlDataCollector", collector)

        editor.append("fix ")
        correctGotToGitFromTheBar()

        verify(exactly = 1) { mlStore.relabelSwipe("trace-got", "git") }
    }

    @Test
    fun anUncorrectedSwipesRowIsLeftAlone() {
        val capture = SwipeMLData("", "swipe_capture", 1080, 2400, 900, "qwerty", "ctc")
        every { inputCoordinator.getCurrentSwipeData() } returns capture
        val collector = mockk<MLDataCollector>()
        every { collector.collectAndStoreSwipeData(any(), any(), any(), any(), any()) } answers {
            arg<((String) -> Unit)?>(4)?.invoke("trace-git")
            true
        }
        handler.setField("mlDataCollector", collector)

        swipe("git", "got")
        type("now ")

        verify(exactly = 0) { mlStore.relabelSwipe(any(), any()) }
    }

    // ------------------------------------------------------------------ reflection

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
        throw AssertionError("field '$name' not found on ${javaClass.simpleName}")
    }
}
