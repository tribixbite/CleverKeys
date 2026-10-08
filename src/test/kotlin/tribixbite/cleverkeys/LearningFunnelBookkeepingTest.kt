package tribixbite.cleverkeys

import android.content.res.Resources
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
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
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import tribixbite.cleverkeys.personalization.PersonalizationEngine
import tribixbite.cleverkeys.ml.SwipeMLData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Learn-funnel BOOKKEEPING (learning-system audit 2026-09-26, W1 / W2 / W5 / W7 / W8): the
 * learned stores must record what the user actually MEANT, not every string the IME briefly
 * put in the editor.
 *
 * # Harness
 *
 * Mock tier, but the parts that decide what gets learned are REAL:
 *  - [SuggestionHandler] — Objenesis-allocated, the production commit/undo/typing bodies
 *    (same allocation pattern as [SwipeAutocapCommitTest]);
 *  - [PredictionContextTracker] — real, so REPLACE / autocorrect tracking is stateful;
 *  - [WordPredictor] — Objenesis-allocated and seeded with a real [ContextModel] over real
 *    [BigramStore]/[TrigramStore] on [InMemoryLearnedStorage] (the substrate
 *    [OnDeviceLearningPrivacyTest] uses), so `addWordToContext` / `rollbackCommittedWord`
 *    run their production bodies and the assertions read real n-gram frequencies. It is
 *    wrapped in a `spyk` ONLY to answer dictionary-membership questions (the add-to-dictionary
 *    prompt), which would otherwise need a loaded dictionary;
 *  - the editor is a tiny fake [InputConnection] over a StringBuilder (cursor at the end).
 *
 * Seams that stay mocks: the personalization engine (recorded via `recordWordTyped`), the
 * selection-adaptation manager (recorded via `recordSelection`), the suggestion bar, the
 * contraction manager and the prediction executor.
 */
class LearningFunnelBookkeepingTest {

    private val objenesis = ObjenesisStd()
    private val scheduler = ScheduledThreadPoolExecutor(1)

    private lateinit var config: Config
    private lateinit var tracker: PredictionContextTracker
    private lateinit var trigramStore: TrigramStore
    private lateinit var bigramStore: BigramStore
    private lateinit var predictor: WordPredictor
    private lateinit var personalization: PersonalizationEngine
    private lateinit var adaptation: UserAdaptationManager
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var bar: SuggestionBar
    private lateinit var inputCoordinator: InputCoordinator
    private lateinit var resources: Resources
    private lateinit var handler: SuggestionHandler

    /** The fake editor's content; the cursor is always at the end. */
    private val editor = StringBuilder()
    private lateinit var ic: InputConnection

    /** Words the handler last pushed to the bar; `getTopSuggestion` answers from it. */
    private var barWords: List<String> = emptyList()

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

        // Config's prefs are @JvmField, so a relaxed mock stores real values here.
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
        config.primary_language = "en"
        config.custom_terminal_packages = emptySet()
        config.swipe_context_rescoring = false
        config.swipe_on_password_fields = false
        config.backspace_undo_swipe = true
        config.backspace_undo_autocorrect = true
        every { Config.globalConfig() } returns config

        tracker = PredictionContextTracker()

        bigramStore = BigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        trigramStore = TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        personalization = mockk(relaxed = true)
        every { personalization.learningLock() } returns Any()
        every { personalization.latestIncrementReceipt() } returns null
        val real = objenesis.newInstance(WordPredictor::class.java)
        real.setField("recentWords", mutableListOf<String>())
        real.setField("config", config)
        real.setField("currentLanguage", "en")
        real.setField("contextModel", ContextModel(bigramStore, trigramStore, "en"))
        real.setField("personalizationEngine", personalization)
        real.setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
        real.setField("languageDetector", null)
        real.setField("multiLanguageManager", null)
        predictor = spyk(real)
        every { predictor.isInDictionary(any()) } returns true
        every { predictor.isInDictionary(any(), any()) } returns true
        every { predictor.isWordDisabled(any()) } returns false
        every { predictor.reset() } just runs

        adaptation = mockk(relaxed = true)
        val dictionary = mockk<DictionaryManager>(relaxed = true)
        every { dictionary.getCurrentLanguage() } returns "en"
        coordinator = mockk(relaxed = true)
        every { coordinator.getWordPredictor() } returns predictor
        every { coordinator.getAdaptationManager() } returns adaptation
        every { coordinator.getDictionaryManager() } returns dictionary

        barWords = emptyList()
        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.setSuggestionsWithScores(any(), any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
        }
        every { bar.getTopSuggestion() } answers { barWords.firstOrNull() }

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
            val before = firstArg<Int>()
            editor.setLength(maxOf(0, editor.length - before))
            true
        }
        every { ic.getCursorCapsMode(any()) } returns 0

        val contractions = mockk<ContractionManager>(relaxed = true)
        every { contractions.isKnownContraction(any()) } returns false
        every { contractions.isContractionKey(any()) } returns false
        every { contractions.generatePossessive(any()) } returns null

        handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contextTracker", tracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("contractionManager", contractions)
        handler.setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        handler.setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
        // Objenesis leaves booleans false; production's default for an ordinary field is true.
        handler.setField("fieldAllowsPersonalizedLearning", true)
    }

    /** Real vocabulary + engine for receipt tests; no production preferences are touched. */
    private fun useReceiptVocabulary(): tribixbite.cleverkeys.personalization.UserVocabulary {
        val vocabulary = tribixbite.cleverkeys.personalization.UserVocabulary(
            InMemoryLearnedStorage(), 60_000, 120_000, scheduler
        )
        vocabulary.setField("lastCleanup", System.currentTimeMillis())
        val engine = objenesis.newInstance(PersonalizationEngine::class.java)
        engine.setField("vocabulary", vocabulary)
        engine.setField("enabled", true)
        engine.setField("prefs", mockk<android.content.SharedPreferences>(relaxed = true))
        predictor.setField("personalizationEngine", engine)
        config.learning_aggression = "BALANCED"
        return vocabulary
    }

    private fun replaceReceipt(commit: LearningCommit, word: String): LearningCommit =
        (predictor.replaceLearningCommit(commit, word, true) as LearningCommitReplacement.Applied).commit

    @Test
    fun exactReceiptReplacesSuffixThenUndoesWithoutLearningAnExtraWord() {
        val vocabulary = useReceiptVocabulary()
        listOf("we", "like", "bowie").forEach { predictor.addWordToContext(it, true) }
        val base = requireNotNull(predictor.latestLearningCommit())
        val suffix = replaceReceipt(base, "bowie's")
        assertWithMessage("base pair removed").that(bigram("like", "bowie")).isEqualTo(0)
        assertWithMessage("suffix pair once").that(bigram("like", "bowie's")).isEqualTo(1)
        assertWithMessage("no base to suffix pair").that(bigram("bowie", "bowie's")).isEqualTo(0)
        assertWithMessage("base usage removed").that(vocabulary.getWordUsage("bowie")).isNull()
        assertWithMessage("suffix usage once").that(vocabulary.getWordUsage("bowie's")?.usageCount).isEqualTo(1)
        assertWithMessage("original trigram removed").that(trigramStore.getProbability("en", "we", "like", "bowie")).isEqualTo(0f)
        val restored = replaceReceipt(suffix, "bowie")
        assertWithMessage("base restored once").that(bigram("like", "bowie")).isEqualTo(1)
        assertWithMessage("suffix removed").that(bigram("like", "bowie's")).isEqualTo(0)
        assertWithMessage("window restored").that(learnWindow()).containsExactly("we", "like", "bowie").inOrder()
        assertWithMessage("fresh identity after undo").that(restored).isNotSameInstanceAs(base)
        assertWithMessage("consumed original cannot run again").that(predictor.replaceLearningCommit(base, "wrong", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
        assertWithMessage("consumed suffix cannot run again").that(predictor.replaceLearningCommit(suffix, "wrong", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
        assertWithMessage("restored usage once").that(vocabulary.getWordUsage("bowie")?.usageCount).isEqualTo(1)
    }

    @Test
    fun exactReceiptNeverFallsBackToAnOlderIdenticalWord() {
        val vocabulary = useReceiptVocabulary()
        predictor.addWordToContext("cat", true)
        val old = requireNotNull(predictor.latestLearningCommit())
        predictor.addWordToContext("cat", true)
        val latest = requireNotNull(predictor.latestLearningCommit())
        assertWithMessage("old same spelling rejected").that(predictor.replaceLearningCommit(old, "cat's", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
        replaceReceipt(latest, "cat's")
        assertWithMessage("older cat usage retained").that(vocabulary.getWordUsage("cat")?.usageCount).isEqualTo(1)
        assertWithMessage("only latest replaced").that(learnWindow()).containsExactly("cat", "cat's").inOrder()
    }

    @Test
    fun exactReceiptRejectsStoreResetBeforeTouchingOtherStores() {
        val vocabulary = useReceiptVocabulary()
        listOf("we", "like", "cats").forEach { predictor.addWordToContext(it, true) }
        val receipt = requireNotNull(predictor.latestLearningCommit())
        trigramStore.clearAll()
        assertWithMessage("preflight invalidated").that(predictor.canReplaceLearningCommit(receipt, true)).isFalse()
        assertWithMessage("replacement invalidated").that(predictor.replaceLearningCommit(receipt, "cats'", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
        assertWithMessage("bigram untouched").that(bigram("like", "cats")).isEqualTo(1)
        assertWithMessage("vocabulary untouched").that(vocabulary.getWordUsage("cats")?.usageCount).isEqualTo(1)
        assertWithMessage("forgotten trigram not recreated").that(trigramStore.getTotalTrigramCount("en")).isEqualTo(0)
    }

    @Test
    fun exactReceiptRejectsVocabularyForgetAndSameWordRecreation() {
        val vocabulary = useReceiptVocabulary()
        predictor.addWordToContext("cat", true)
        val receipt = requireNotNull(predictor.latestLearningCommit())
        vocabulary.clearAll()
        vocabulary.recordWordUsage("cat")
        assertWithMessage("recreated word is a different observation").that(predictor.replaceLearningCommit(receipt, "cat's", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
        assertWithMessage("new observation retained").that(vocabulary.getWordUsage("cat")?.usageCount).isEqualTo(1)
    }

    @Test
    fun exactReceiptExpiresAcrossMutableConfigGateChanges() {
        useReceiptVocabulary()
        predictor.addWordToContext("cat", true)
        val receipt = requireNotNull(predictor.latestLearningCommit())
        config.on_device_learning_enabled = false
        predictor.setConfig(config)
        config.on_device_learning_enabled = true
        predictor.setConfig(config)
        assertWithMessage("same Config instance does not revive receipt").that(predictor.replaceLearningCommit(receipt, "cat's", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
    }

    @Test
    fun exactReceiptExpiresAsSoonAsConfiguredLanguageChangesBeforeDictionaryReload() {
        useReceiptVocabulary()
        predictor.addWordToContext("cat", true)
        val receipt = requireNotNull(predictor.latestLearningCommit())
        // The async loader has not called setLanguage yet: currentLanguage is still en.
        config.primary_language = "fr"
        predictor.setConfig(config)
        config.primary_language = "en"
        predictor.setConfig(config)
        assertWithMessage("pre-reload language round trip cannot revive handle")
            .that(predictor.replaceLearningCommit(receipt, "cat's", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
    }

    @Test
    fun exactReceiptSurvivesDetectionReturningTheAlreadyActiveStoreLanguage() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        val languages = mockk<MultiLanguageManager>()
        // The manager can catch up to the predictor's language after a separate dictionary
        // switch. Reporting en is not a change to this predictor's en context/store.
        every { languages.detectAndSwitch(any(), any()) } returns "en"
        predictor.setField("multiLanguageManager", languages)
        listOf("we", "all", "really", "like", "cats").forEach { predictor.addWordToContext(it, true) }
        val receipt = requireNotNull(predictor.latestLearningCommit())
        assertWithMessage("same language detection keeps the context")
            .that(learnWindow()).containsExactly("we", "all", "really", "like", "cats").inOrder()
        replaceReceipt(receipt, "cats'")
        assertWithMessage("suffix uses retained previous word").that(bigram("like", "cats'")).isEqualTo(1)
    }

    /**
     * GH #186/#61 gate (audit gap 2026-10-08): while the current layout carries a language
     * binding, auto-detection must not run — it would switch the n-gram/context models away
     * from the bound language mid-sentence. Unbound, the same five words do reach detection.
     */
    @Test
    fun autoDetectionIsSkippedWhileTheLayoutBindsALanguage() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        val languages = mockk<MultiLanguageManager>()
        every { languages.detectAndSwitch(any(), any()) } returns "es"
        predictor.setField("multiLanguageManager", languages)

        config.layout_bound_language = "en"
        listOf("we", "all", "really", "like", "cats").forEach { predictor.addWordToContext(it, true) }
        verify(exactly = 0) { languages.detectAndSwitch(any(), any()) }
        assertWithMessage("bound language kept").that(predictor.getField("currentLanguage")).isEqualTo("en")

        config.layout_bound_language = null
        predictor.addWordToContext("dogs", true)
        verify(exactly = 1) { languages.detectAndSwitch(any(), any()) }
        assertWithMessage("unbound: detection switches").that(predictor.getField("currentLanguage")).isEqualTo("es")
    }

    @Test
    fun exactReceiptPreflightPermissionMismatchPermanentlyExpiresIt() {
        val vocabulary = useReceiptVocabulary()
        predictor.addWordToContext("cat", true)
        val receipt = requireNotNull(predictor.latestLearningCommit())
        assertWithMessage("incognito preflight rejected").that(predictor.canReplaceLearningCommit(receipt, false)).isFalse()
        assertWithMessage("allowed again cannot revive it").that(predictor.canReplaceLearningCommit(receipt, true)).isFalse()
        assertWithMessage("replacement also rejected").that(predictor.replaceLearningCommit(receipt, "cat's", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
        assertWithMessage("original usage untouched").that(vocabulary.getWordUsage("cat")?.usageCount).isEqualTo(1)
    }

    @Test
    fun exactReceiptExpiresAcrossLanguageAndContextBoundaries() {
        useReceiptVocabulary()
        predictor.addWordToContext("cat", true)
        val languageReceipt = requireNotNull(predictor.latestLearningCommit())
        predictor.setLanguage("fr")
        predictor.setLanguage("en")
        assertWithMessage("language round trip invalidates").that(predictor.replaceLearningCommit(languageReceipt, "cat's", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
        predictor.addWordToContext("cat", true)
        val sessionReceipt = requireNotNull(predictor.latestLearningCommit())
        predictor.clearContext()
        predictor.addWordToContext("cat", true)
        assertWithMessage("new session identical word invalidates").that(predictor.replaceLearningCommit(sessionReceipt, "cat's", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
    }

    @Test
    fun exactReceiptSupportsContextOnlyReplacementWithoutStoreReads() {
        config.on_device_learning_enabled = false
        predictor.addWordToContext("cat", true)
        val receipt = requireNotNull(predictor.latestLearningCommit())
        replaceReceipt(receipt, "cat's")
        assertWithMessage("context updated with learning off").that(learnWindow()).containsExactly("cat's")
        verify(exactly = 0) { personalization.receiptVersion() }
        verify(exactly = 0) { personalization.recordWordTyped(any(), any()) }
        assertWithMessage("no ngrams").that(bigramStore.getTotalBigramCount("en")).isEqualTo(0)
    }

    @Test
    fun exactReceiptRejectsChangedFieldPermissionWithoutMutation() {
        val vocabulary = useReceiptVocabulary()
        predictor.addWordToContext("cat", true)
        val receipt = requireNotNull(predictor.latestLearningCommit())
        assertWithMessage("incognito change invalidates").that(predictor.replaceLearningCommit(receipt, "cat's", false))
            .isEqualTo(LearningCommitReplacement.Invalidated)
        assertWithMessage("permission restored cannot revive rejected handle")
            .that(predictor.replaceLearningCommit(receipt, "cat's", true))
            .isEqualTo(LearningCommitReplacement.Invalidated)
        assertWithMessage("original usage unchanged").that(vocabulary.getWordUsage("cat")?.usageCount).isEqualTo(1)
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

    /** Production order: KeyEventHandler commits the char, THEN reports it as typed. */
    private fun type(text: String, field: EditorInfo = textField()) {
        for (c in text) {
            editor.append(c)
            handler.handleRegularTyping(c.toString(), ic, field)
        }
    }

    /**
     * Like [type], but lets the 100 ms cursor-sync debounce elapse after every key, the way
     * typing slower than ~10 keys/s behaves on a device: InputCoordinator re-reads the word at
     * the cursor from the editor and routes to the sync (word) or park (no word) entry.
     */
    private fun typeWithCursorSync(text: String) {
        for (c in text) {
            editor.append(c)
            handler.handleRegularTyping(c.toString(), ic, textField())
            tracker.synchronizeWithCursor(ic, "en", textField())
            if (tracker.getCurrentWord().isNotEmpty()) {
                handler.handleCursorSyncPrediction()
            } else {
                handler.handleCursorParkPrediction(textField(), ic)
            }
        }
    }

    /** Swipe decode → auto-insert of the top candidate (InputCoordinator sets the swipe flag). */
    private fun swipe(vararg slate: String, field: EditorInfo = textField()) {
        tracker.setWasLastInputSwipe(true)
        handler.handleSwipePredictionResults(
            slate.toList(), slate.indices.map { 100 - it }, ic, field, resources,
            false, false, inputCoordinator
        )
    }

    /** A bar tap (SuggestionBridge passes isManualSelection = true). */
    private fun tap(word: String, field: EditorInfo = textField()) {
        handler.onSuggestionSelected(word, ic, field, resources, isManualSelection = true)
    }

    /** Learned bigram frequency of (w1 → w2) in the real store; 0 when absent. */
    private fun bigram(w1: String, w2: String): Int =
        bigramStore.getAllBigrams("en", w1).firstOrNull { it.word2 == w2 }?.frequency ?: 0

    private fun learnWindow(): List<String> = predictor.getRecentWords()

    /** A decoder result is only an offer until the editor acknowledges insertion. */
    private fun assertRejectedSwipe(connection: InputConnection?) {
        editor.append("fix ")
        handler.updateContext("fix")
        val collector = mockk<MLDataCollector>(relaxed = true)
        handler.setField("mlDataCollector", collector)
        every { inputCoordinator.getCurrentSwipeData() } returns mockk<SwipeMLData>(relaxed = true)
        tracker.setWasLastInputSwipe(true)
        handler.handleSwipePredictionResults(
            listOf("got", "git"), listOf(100, 90), connection, textField(), resources,
            false, false, inputCoordinator
        )

        assertWithMessage("a rejected result must leave correction candidates available")
            .that(barWords).containsAtLeast("got", "git").inOrder()
        assertWithMessage("a rejected word cannot own a later replacement")
            .that(tracker.getLastAutoInsertedWord()).isNull()
        assertWithMessage("no successful commit provenance")
            .that(tracker.getLastCommitSource()).isEqualTo(PredictionSource.UNKNOWN)
        assertWithMessage("no automatic-space ownership").that(tracker.lastSpaceWasAutoInserted).isFalse()
        assertWithMessage("selection callbacks must not be awaited").that(tracker.expectingSelectionUpdate).isFalse()
        assertWithMessage("no rejected word in prediction context")
            .that(tracker.getContextWords()).doesNotContain("got")
        assertWithMessage("no rejected word in learning window").that(learnWindow()).containsExactly("fix")
        assertWithMessage("no rejected bigram increment").that(bigram("fix", "got")).isEqualTo(0)
        verify(exactly = 0) { personalization.recordWordTyped("got", any()) }
        verify(exactly = 0) { adaptation.recordSelection(any()) }
        verify(exactly = 0) { collector.collectAndStoreSwipeData(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { inputCoordinator.resetSwipeData() }
        verify(exactly = 0) { inputCoordinator.triggerSwipeCompleteHaptic() }
        assertWithMessage("no swipe-correction record for a rejected write")
            .that(handler.javaClass.getDeclaredField("swipeCorrectionTracker").apply { isAccessible = true }.get(handler))
            .isNull()
    }

    @Test
    fun rejectedSwipeCommitDoesNotLearnOwnOrCapturePrediction() {
        every { ic.commitText(any(), any()) } returns false
        assertRejectedSwipe(ic)
        assertWithMessage("rejected editor content").that(editor.toString()).isEqualTo("fix ")
    }

    @Test
    fun throwingSwipeCommitDoesNotLearnOwnOrCapturePrediction() {
        every { ic.commitText(any(), any()) } throws IllegalStateException("Editor unavailable")
        assertRejectedSwipe(ic)
        assertWithMessage("throwing editor content").that(editor.toString()).isEqualTo("fix ")
    }

    @Test
    fun missingSwipeConnectionDoesNotLearnOwnOrCapturePrediction() {
        assertRejectedSwipe(null)
    }

    @Test
    fun rejectedManualCandidateDoesNotLearnSelection() {
        every { ic.commitText(any(), any()) } returns false
        val committed = handler.onSuggestionSelected("git", ic, textField(), resources, true)
        assertWithMessage("rejected candidate result").that(committed).isNull()
        assertWithMessage("no rejected candidate context").that(learnWindow()).isEmpty()
        verify(exactly = 0) { adaptation.recordSelection(any()) }
        verify(exactly = 0) { personalization.recordWordTyped(any(), any()) }
    }

    @Test
    fun rejectedTypedSeparatorAbortsSwipeWithoutCompletingTypedWord() {
        type("kids'toy")
        every { ic.commitText(" ", 1) } returns false
        swipe("toys")
        assertWithMessage("separator rejection must not append the prediction")
            .that(editor.toString()).isEqualTo("kids'toy")
        assertWithMessage("no completed typed word without its accepted separator")
            .that(learnWindow()).isEmpty()
        assertWithMessage("no uncommitted swipe ownership").that(tracker.getLastAutoInsertedWord()).isNull()
        verify(exactly = 0) { personalization.recordWordTyped(any(), any()) }
    }

    // ================================================================ W1

    @Test
    fun swipeAutoInsertIsNotRecordedAsAUserSelection() {
        editor.append("fix ")
        swipe("got", "git")

        assertWithMessage("the auto-insert must still reach the editor")
            .that(editor.toString()).isEqualTo("fix got ")
        verify(exactly = 0) { adaptation.recordSelection(any()) }
    }

    @Test
    fun aManualBarTapIsRecordedAsASelection() {
        editor.append("fix ")
        tap("git")

        assertWithMessage("manual selection must actually reach the editor")
            .that(editor.toString()).isEqualTo("fix git ")
        verify(exactly = 1) { adaptation.recordSelection("git") }
    }

    @Test
    fun acknowledgedCandidateReturnsItsExactInsertedCapitalization() {
        type("Bo")
        val committed = handler.onSuggestionSelected("bowie", ic, textField(), resources, true)
        assertWithMessage("acknowledged spelling").that(committed).isEqualTo("Bowie")
        assertWithMessage("editor spelling").that(editor.toString()).isEqualTo("Bowie ")
        assertWithMessage("whole normalized word learned once").that(learnWindow()).containsExactly("bowie")
        verify(exactly = 1) { adaptation.recordSelection("Bowie") }
    }

    // ================================================================ W2 (bar REPLACE)

    /**
     * The audit's `git` scenario end to end: swipe → `got` auto-inserted → user taps `git`.
     * Required: context LM holds prev→git (+1) and neither got→git nor prev→got; selection
     * adaptation saw only `git`.
     */
    @Test
    fun replacingAnAutoInsertedSwipeWordRollsTheRejectedWordOutOfTheContextLm() {
        editor.append("fix ")
        handler.updateContext("fix")

        swipe("got", "git")
        tap("git")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix git ")
        assertWithMessage("fix→git must be learned once").that(bigram("fix", "git")).isEqualTo(1)
        assertWithMessage("the rejected fix→got must be rolled back").that(bigram("fix", "got")).isEqualTo(0)
        assertWithMessage("the replacement must not chain onto the rejected word")
            .that(bigram("got", "git")).isEqualTo(0)
        assertWithMessage("learn window").that(learnWindow()).containsExactly("fix", "git").inOrder()
        verify(exactly = 1) { personalization.recordWordTyped("git", any()) }
        verify(exactly = 0) { adaptation.recordSelection("got") }
        verify(exactly = 1) { adaptation.recordSelection("git") }
    }

    // ================================================================ W5 (typed word terminated by a swipe)

    @Test
    fun aTypedWordTerminatedByASwipeIsLearnedBeforeTheSwipedWord() {
        editor.append("so ")
        handler.updateContext("so")
        type("we")
        swipe("think")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("so we think ")
        assertWithMessage("so→we").that(bigram("so", "we")).isEqualTo(1)
        assertWithMessage("we→think").that(bigram("we", "think")).isEqualTo(1)
    }

    // ================================================================ W7

    @Test
    fun aTapTypedContractionIsLearnedAsOneTokenNeverAsFragments() {
        editor.append("i ")
        handler.updateContext("i")
        type("don't ")

        assertWithMessage("no fragment bigram don→t").that(bigram("don", "t")).isEqualTo(0)
        assertWithMessage("no fragment bigram i→don").that(bigram("i", "don")).isEqualTo(0)
        assertWithMessage("the whole token follows the context").that(bigram("i", "don't")).isEqualTo(1)
        verify(exactly = 0) { personalization.recordWordTyped("don", any()) }
        verify(exactly = 0) { personalization.recordWordTyped("t", any()) }
        verify(exactly = 1) { personalization.recordWordTyped("don't", any()) }
    }

    /**
     * Against a real, non-empty lexicon that — like the shipped `en_enhanced.json` — has no
     * hyphenated entries (review of 59bd4159: the previous version passed only because the
     * predictor had no lexicon, so the typo policy failed open and accepted everything).
     */
    @Test
    fun aHyphenatedWordIsLearnedWhole() {
        seedLexicon("a", "co", "op")
        editor.append("a ")
        handler.updateContext("a")
        type("co-op ")

        assertWithMessage("a→co-op").that(bigram("a", "co-op")).isEqualTo(1)
        assertWithMessage("no fragment co→op").that(bigram("co", "op")).isEqualTo(0)
        verify(exactly = 0) { personalization.recordWordTyped("co", any()) }
        verify(exactly = 0) { personalization.recordWordTyped("op", any()) }

        // The lexicon is live: a compound with a misspelled part is still held back.
        type("co-pq ")
        assertWithMessage("co-op→co-pq (typo part)").that(bigram("co-op", "co-pq")).isEqualTo(0)
    }

    @Test
    fun aTrailingPossessiveApostropheLearnsTheStem() {
        editor.append("the ")
        handler.updateContext("the")
        type("kids' ")

        assertWithMessage("the→kids").that(bigram("the", "kids")).isEqualTo(1)
        verify(exactly = 1) { personalization.recordWordTyped("kids", any()) }
    }

    @Test
    fun aSlowlyTypedContractionMergedByCursorSyncIsStillLearnedOnce() {
        // Typing slower than the 100 ms sync debounce: the tracker is re-read from the editor
        // after every key, so after "t" it holds "don't" — the stem must not be glued twice.
        editor.append("i ")
        handler.updateContext("i")
        typeWithCursorSync("don't ")

        assertWithMessage("i→don't").that(bigram("i", "don't")).isEqualTo(1)
        assertWithMessage("no double-glued token").that(bigram("i", "don'don't")).isEqualTo(0)
        verify(exactly = 1) { personalization.recordWordTyped("don't", any()) }
        verify(exactly = 0) { personalization.recordWordTyped("don", any()) }
    }

    // ================================================================ W2 (backspace undos)

    @Test
    fun backspaceUndoOfASwipeRollsTheWordBack() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")

        // KeyEventHandler.handleBackspaceUndoSwipe deletes "got " and reports it.
        editor.setLength(editor.length - "got ".length)
        handler.onSwipeWordUndone("got", ic)

        assertWithMessage("fix→got rolled back").that(bigram("fix", "got")).isEqualTo(0)
        assertWithMessage("learn window").that(learnWindow()).containsExactly("fix")
    }

    /**
     * Review of 59bd4159 (LOW): swipe "got", type "." (a sentence boundary clears the learn
     * window), backspace twice — the #110 swipe undo still fires (the editor again ends with
     * the swiped word), but the rollback found an empty window and did nothing, so fix→got
     * and the vocabulary +1 stayed while the tracker dropped the word. The undo keeps working
     * and now rolls the learning back from a one-slot record of the last commit.
     */
    @Test
    fun backspaceUndoOfASwipeAfterASentenceBoundaryStillRollsTheWordBack() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        assertWithMessage("fix→got learned by the auto-insert").that(bigram("fix", "got")).isEqualTo(1)

        type(".")
        assertWithMessage("the period closed the learn window").that(learnWindow()).isEmpty()

        // Backspace 1 deletes "."; backspace 2 is KeyEventHandler's swipe undo, which deletes
        // "got " and reports the rejected word.
        editor.setLength(editor.length - 1)
        handler.handleBackspace()
        editor.setLength(editor.length - "got ".length)
        handler.onSwipeWordUndone("got", ic)

        assertWithMessage("fix→got rolled back").that(bigram("fix", "got")).isEqualTo(0)
        verify(exactly = 1) { personalization.unrecordWordTyped("got") }

        // A second report of the same word (nothing left to undo) must not decrement again.
        handler.onSwipeWordUndone("got", ic)
        verify(exactly = 1) { personalization.unrecordWordTyped("got") }
    }

    @Test
    fun backspaceUndoOfAnAutocorrectLearnsTheOriginalInstead() {
        config.autocorrect_enabled = true
        every { predictor.autoCorrect(any()) } answers { firstArg() }
        every { predictor.autoCorrect("teh") } returns "the"
        editor.append("fix ")
        handler.updateContext("fix")
        type("teh ")
        assertWithMessage("autocorrect ran").that(editor.toString()).isEqualTo("fix the ")
        assertWithMessage("fix→the learned by the correction").that(bigram("fix", "the")).isEqualTo(1)

        // KeyEventHandler.handleBackspaceUndoAutocorrect restores "teh " and reports it.
        editor.setLength(editor.length - "the ".length)
        editor.append("teh ")
        handler.onAutocorrectUndone("the", "teh", originalCompleted = true)

        assertWithMessage("fix→the rolled back").that(bigram("fix", "the")).isEqualTo(0)
        assertWithMessage("fix→teh learned").that(bigram("fix", "teh")).isEqualTo(1)
        assertWithMessage("learn window").that(learnWindow()).containsExactly("fix", "teh").inOrder()
    }

    @Test
    fun anAutocorrectUndoThatLeavesTheWordOpenDoesNotLearnItYet() {
        editor.append("fix ")
        handler.updateContext("fix")
        handler.updateContext("the")

        handler.onAutocorrectUndone("the", "teh", originalCompleted = false)

        assertWithMessage("fix→the rolled back").that(bigram("fix", "the")).isEqualTo(0)
        assertWithMessage("teh not learned while still being typed").that(bigram("fix", "teh")).isEqualTo(0)
    }

    // ================================================================ W5 (Enter / action / field switch)

    @Test
    fun enterLearnsTheTypedWordAndClosesTheWindow() {
        editor.append("see ")
        handler.updateContext("see")
        type("you")

        handler.onEditorWordBoundary(ic)

        assertWithMessage("see→you").that(bigram("see", "you")).isEqualTo(1)
        verify(exactly = 1) { personalization.recordWordTyped("you", any()) }
        assertWithMessage("a newline/send is a context boundary").that(learnWindow()).isEmpty()

        // Leaving the field right after must not learn it a second time.
        handler.flushTypedWordOnFinishInput(ic)
        verify(exactly = 1) { personalization.recordWordTyped("you", any()) }
    }

    @Test
    fun enterAfterASpaceCompletedWordDoesNotDoubleLearn() {
        type("you ")
        handler.onEditorWordBoundary(ic)

        verify(exactly = 1) { personalization.recordWordTyped("you", any()) }
    }

    @Test
    fun leavingTheFieldLearnsTheTypedWordOnce() {
        editor.append("see ")
        handler.updateContext("see")
        typeWithCursorSync("later")

        handler.flushTypedWordOnFinishInput(ic)
        handler.flushTypedWordOnFinishInput(ic)

        assertWithMessage("see→later").that(bigram("see", "later")).isEqualTo(1)
        verify(exactly = 1) { personalization.recordWordTyped("later", any()) }
    }

    @Test
    fun enterAfterATrailingApostropheLearnsTheStem() {
        editor.append("the ")
        handler.updateContext("the")
        type("kids'")

        handler.onEditorWordBoundary(ic)

        assertWithMessage("the→kids").that(bigram("the", "kids")).isEqualTo(1)
    }

    @Test
    fun theFlushRespectsTheIncognitoFieldAndTheMasterGate() {
        handler.setField("fieldAllowsPersonalizedLearning", false)
        editor.append("see ")
        handler.updateContext("see")
        type("secret")
        handler.onEditorWordBoundary(ic)

        handler.setField("fieldAllowsPersonalizedLearning", true)
        config.on_device_learning_enabled = false
        type("private")
        handler.flushTypedWordOnFinishInput(ic)

        verify(exactly = 0) { personalization.recordWordTyped(any(), any()) }
        assertWithMessage("no bigram learned").that(bigramStore.getTotalBigramCount("en")).isEqualTo(0)
    }

    /**
     * Review of 59bd4159 (LOW): the space-completion branch learned whatever the tracker held,
     * including a word cursor-sync merely re-read from the editor. Type "hello", leave the
     * field (flushed and learned), come back with the cursor at "hello|", press space: the
     * word was learned a second time.
     */
    @Test
    fun aFlushedWordReSyncedByTheCursorIsNotLearnedAgainBySpace() {
        editor.append("see ")
        handler.updateContext("see")
        type("hello")
        handler.flushTypedWordOnFinishInput(ic)
        verify(exactly = 1) { personalization.recordWordTyped("hello", any()) }

        // Back in the field: cursor-sync re-reads "hello" at the cursor; the user presses space.
        tracker.synchronizeWithCursor(ic, "en", textField())
        handler.handleCursorSyncPrediction()
        type(" ")

        verify(exactly = 1) { personalization.recordWordTyped("hello", any()) }
        assertWithMessage("see→hello").that(bigram("see", "hello")).isEqualTo(1)
    }

    @Test
    fun aParkedOnWordTheUserExtendsIsLearnedAsTheExtendedWord() {
        editor.append("the cat")
        tracker.synchronizeWithCursor(ic, "en", textField())
        handler.handleCursorSyncPrediction()
        type("s ")

        verify(exactly = 1) { personalization.recordWordTyped("cats", any()) }
        verify(exactly = 0) { personalization.recordWordTyped("cat", any()) }
    }

    @Test
    fun aWordTheCursorMovedAwayFromIsNotFlushed() {
        editor.append("hello world")
        type("hel")
        // The user taps into "wor|ld": cursor-sync re-reads the editor at the new position.
        val elsewhere = mockk<InputConnection>(relaxed = true)
        every { elsewhere.getTextBeforeCursor(any(), any()) } returns "hello wor"
        every { elsewhere.getTextAfterCursor(any(), any()) } returns "ld"
        tracker.synchronizeWithCursor(elsewhere)
        handler.handleCursorSyncPrediction()

        handler.onEditorWordBoundary(elsewhere)

        verify(exactly = 0) { personalization.recordWordTyped(any(), any()) }
    }

    // ================================================================ W8

    @Test
    fun swipingIntoAPasswordFieldNeverLearns() {
        config.swipe_on_password_fields = true
        handler.setPasswordMode(true)

        swipe("hunter", "hunted", field = passwordField())

        assertWithMessage("the swipe still commits (user opted in)")
            .that(editor.toString()).isEqualTo("hunter ")
        assertWithMessage("learn window").that(learnWindow()).isEmpty()
        assertWithMessage("session context").that(tracker.getContextWords()).isEmpty()
        verify(exactly = 0) { personalization.recordWordTyped(any(), any()) }
        verify(exactly = 0) { adaptation.recordSelection(any()) }
    }

    @Test
    fun aPasswordFieldDetectedOnlyFromTheEditorAlsoNeverLearns() {
        // Before onStartInputView sets the tracked mode, the live EditorInfo is the only signal.
        config.swipe_on_password_fields = true

        swipe("hunter", "hunted", field = passwordField())
        tap("hunted", field = passwordField())

        assertWithMessage("learn window").that(learnWindow()).isEmpty()
        verify(exactly = 0) { personalization.recordWordTyped(any(), any()) }
        verify(exactly = 0) { adaptation.recordSelection(any()) }
    }

    // ------------------------------------------------------------------ reflection

    /**
     * Give the predictor a real, NON-EMPTY lexicon so its [LearnableWordPolicy] judges words.
     * Without this the Objenesis-allocated predictor has no dictionary, and the production
     * policy fails OPEN (learns everything) — right for the tests that pin WHICH commits reach
     * the funnel, but vacuous for a test about which words the funnel accepts. Seeds the
     * spy's own fields (spyk copies the delegate's state at creation) and drops the lazily
     * built policy so it is rebuilt over this lexicon.
     */
    private fun seedLexicon(vararg words: String) {
        predictor.setField(
            "dictionary",
            java.util.concurrent.atomic.AtomicReference<MutableMap<String, Int>>(
                words.associateWith { 200 }.toMutableMap()
            )
        )
        predictor.setField("customAndUserWords", emptySet<String>())
        predictor.setField("learnableWordPolicyCache", null)
    }

    private fun Any.getField(name: String): Any? {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredFields.firstOrNull { it.name == name }?.let { it.isAccessible = true; return it.get(this) }
            cls = cls.superclass
        }
        throw AssertionError("field '$name' not found on ${javaClass.simpleName}")
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
        throw AssertionError("field '$name' not found on ${javaClass.simpleName}")
    }
}
