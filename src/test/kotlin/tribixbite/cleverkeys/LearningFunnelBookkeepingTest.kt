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

    /** The fake editor's content. */
    private val editor = StringBuilder()

    /** The fake editor's cursor; null keeps it at the end of [editor] (most tests). */
    private var cursorAt: Int? = null

    private fun cursor(): Int = (cursorAt ?: editor.length).coerceIn(0, editor.length)
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
        every { bar.setSuggestionsWithScores(any(), any(), any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
        }
        every { bar.getTopSuggestion() } answers { barWords.firstOrNull() }

        inputCoordinator = mockk(relaxed = true)
        every { inputCoordinator.getCurrentSwipeData() } returns null
        resources = mockk(relaxed = true)

        editor.setLength(0)
        cursorAt = null
        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } answers {
            val n = firstArg<Int>()
            val c = cursor()
            editor.substring(maxOf(0, c - n), c)
        }
        every { ic.getTextAfterCursor(any(), any()) } answers {
            val c = cursor()
            editor.substring(c, minOf(editor.length, c + firstArg<Int>()))
        }
        every { ic.commitText(any(), any()) } answers {
            val text = firstArg<CharSequence>()
            val c = cursor()
            editor.insert(c, text)
            cursorAt = cursorAt?.let { it + text.length }
            true
        }
        every { ic.deleteSurroundingText(any(), any()) } answers {
            val c = cursor()
            val start = maxOf(0, c - firstArg<Int>())
            val end = minOf(editor.length, c + secondArg<Int>())
            editor.delete(start, end)
            cursorAt = cursorAt?.let { start }
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
        // Objenesis skips field initializers: the space-commit memo (2026-10-11) must exist.
        handler.setField("autocorrectMemo", tribixbite.cleverkeys.autocorrect.AutocorrectMemo())
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
        every { languages.detectAndSwitch(any(), any(), any()) } returns "en"
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
        every { languages.detectAndSwitch(any(), any(), any()) } returns "es"
        // The switch goes through setLanguage, which re-syncs the manager (already on es: a no-op).
        every { languages.switchLanguage(any()) } returns true
        predictor.setField("multiLanguageManager", languages)

        config.layout_bound_language = "en"
        listOf("we", "all", "really", "like", "cats").forEach { predictor.addWordToContext(it, true) }
        verify(exactly = 0) { languages.detectAndSwitch(any(), any(), any()) }
        assertWithMessage("bound language kept").that(predictor.getField("currentLanguage")).isEqualTo("en")

        config.layout_bound_language = null
        config.active_secondary_language = "es"
        predictor.addWordToContext("dogs", true)
        verify(exactly = 1) { languages.detectAndSwitch(any(), any(), any()) }
        assertWithMessage("unbound: detection switches").that(predictor.getField("currentLanguage")).isEqualTo("es")
    }

    /**
     * 2026-11-25: Auto-Detect is on by default, and its toggle only shows inside Multi-Language,
     * yet with Multi-Language OFF (no secondary language) the fallback detector still ran on
     * every commit and could move the next-word and learned-context models to another language.
     * Without a chosen secondary language there is nothing to detect between.
     */
    @Test
    fun autoDetectionNeedsAChosenSecondaryLanguage() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = null
        val bigrams = mockk<BigramModel>(relaxed = true)
        every { bigrams.isLanguageSupported(any()) } returns true
        predictor.setField("bigramModel", bigrams)
        val detector = mockk<LanguageDetector>()
        every { detector.detectLanguageFromWords(any()) } returns "es"
        predictor.setField("languageDetector", detector)
        listOf("el", "perro", "come", "la", "comida", "con", "su", "familia").forEach {
            predictor.addWordToContext(it, true)
        }
        assertWithMessage("single-language typing keeps its language")
            .that(predictor.getField("currentLanguage")).isEqualTo("en")
    }

    /** It only switches between the primary and the chosen secondary language. */
    @Test
    fun autoDetectionSwitchesOnlyBetweenTheChosenLanguages() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "de"
        val languages = mockk<MultiLanguageManager>(relaxed = true)
        every { languages.detectAndSwitch(any(), any(), any()) } returns "es"
        predictor.setField("multiLanguageManager", languages)
        listOf("we", "all", "really", "like", "cats").forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("a language the user did not choose").that(predictor.getField("currentLanguage")).isEqualTo("en")

        every { languages.detectAndSwitch(any(), any(), any()) } returns "de"
        predictor.addWordToContext("dogs", true)
        assertWithMessage("the chosen secondary").that(predictor.getField("currentLanguage")).isEqualTo("de")
    }

    /**
     * The manager must not switch to a language the user did not choose either (2026-11-25):
     * `detectAndSwitch` switched its own active language first and the predictor refused it
     * after, so on Portuguese text with English + Spanish chosen the manager recorded `pt`
     * while the keyboard stayed English, and the two disagreed from then on.
     */
    @Test
    fun theLanguageManagerStaysOnTheChosenLanguages() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "es"
        val languages = MultiLanguageManager("en")
        predictor.setField("multiLanguageManager", languages)
        listOf("o", "não", "é", "uma", "da", "do", "em", "com", "os", "mais", "não", "é").forEach {
            predictor.addWordToContext(it, true)
        }
        assertWithMessage("the keyboard's language").that(predictor.getField("currentLanguage")).isEqualTo("en")
        assertWithMessage("the manager's language").that(languages.getCurrentLanguage()).isEqualTo("en")
    }

    /**
     * Pre-tag review 2026-11-25 S7, the predictor's half. When the manager decides not to switch
     * (null), the predictor fell through to the legacy detector, which took the best of all six
     * of its languages at a fixed threshold and could switch anyway. The manager's answer stands.
     */
    @Test
    fun theLegacyDetectorDoesNotOverruleTheManager() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "es"
        val languages = mockk<MultiLanguageManager>()
        every { languages.detectAndSwitch(any(), any(), any()) } returns null
        predictor.setField("multiLanguageManager", languages)
        val bigrams = mockk<BigramModel>(relaxed = true)
        every { bigrams.isLanguageSupported(any()) } returns true
        predictor.setField("bigramModel", bigrams)
        val detector = mockk<LanguageDetector>()
        every { detector.detectLanguageFromWords(any()) } returns "es"
        every { detector.detectLanguageFromWordsWithConfidence(any(), any()) } returns
            LanguageDetector.DetectionResult("es", 0.99f)
        predictor.setField("languageDetector", detector)
        listOf("el", "perro", "come", "la", "comida", "con").forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("the manager said no switch").that(predictor.getField("currentLanguage")).isEqualTo("en")
    }

    /**
     * Without a manager, the legacy path follows the same rules: the chosen pair only, the
     * user's sensitivity, and nothing for a pair the detector cannot score (Italian has no
     * detector profile; Settings shows Auto-Detect as unavailable for it).
     */
    @Test
    fun theLegacyDetectorChoosesAmongTheChosenPairAtTheUsersSensitivity() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.language_detection_sensitivity = 0.6f
        val bigrams = mockk<BigramModel>(relaxed = true)
        every { bigrams.isLanguageSupported(any()) } returns true
        predictor.setField("bigramModel", bigrams)
        val detector = mockk<LanguageDetector>()
        predictor.setField("languageDetector", detector)
        val words = listOf("il", "cane", "mangia", "la", "pasta", "con")

        config.active_secondary_language = "it"
        every { detector.detectLanguageFromWords(any()) } returns "it"
        every { detector.detectLanguageFromWordsWithConfidence(any(), any()) } returns
            LanguageDetector.DetectionResult("it", 0.99f)
        words.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("a pair the detector cannot score").that(predictor.getField("currentLanguage")).isEqualTo("en")

        config.active_secondary_language = "es"
        every { detector.detectLanguageFromWords(any()) } returns "es"
        every { detector.detectLanguageFromWordsWithConfidence(any(), any()) } returns
            LanguageDetector.DetectionResult("es", 0.3f)
        predictor.addWordToContext("perro", true)
        assertWithMessage("below the user's sensitivity").that(predictor.getField("currentLanguage")).isEqualTo("en")

        every { detector.detectLanguageFromWordsWithConfidence(any(), any()) } returns
            LanguageDetector.DetectionResult("es", 0.9f)
        predictor.addWordToContext("come", true)
        assertWithMessage("confident, and chosen").that(predictor.getField("currentLanguage")).isEqualTo("es")
        verify { detector.detectLanguageFromWordsWithConfidence(any(), setOf("en", "es")) }
    }

    /**
     * 2026-11-26 (Pixel S7 follow-up, corrected after the Fable audit): a switch the language
     * MANAGER detects moves only the NEXT-WORD language. Its branch first set only the bigram
     * tables' language and the context model, so the new language's `de.cklm` was never loaded;
     * the first fix routed it through [WordPredictor.setLanguage], which also moved the PRIMARY
     * dictionary's state to the detected language while the primary dictionary stayed loaded:
     * German's disabled words replaced English's (an English disabled word was offered and
     * autocorrected again), the user-dictionary observer ran a query on the commit path and then
     * ignored English custom-word changes, and a reload pulled German custom words into the
     * English dictionary. The detected language gets its bigram/context models and its static
     * continuations (loaded off the main thread); disabled words, custom words and the observer
     * stay with the dictionary's language.
     */
    @Test
    fun aDetectedSwitchMovesOnlyTheNextWordLanguage() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "de"
        val ctx = mockk<android.content.Context>(relaxed = true)
        predictor.setField("context", ctx)
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getStringSet(any(), any()) } returns emptySet()
        every { prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey("en"), any()) } returns setOf("colour")
        every { prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey("de"), any()) } returns setOf("doch")
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs
        val bigrams = mockk<BigramModel>(relaxed = true)
        predictor.setField("bigramModel", bigrams)
        val observer = mockk<UserDictionaryObserver>(relaxed = true)
        predictor.setField("dictionaryObserver", observer)
        // English is the loaded dictionary: setLanguage is what a dictionary load calls.
        predictor.setLanguage("en")
        assertWithMessage("English's disabled words").that(predictor.getField("disabledWords") as Set<*>).containsExactly("colour")
        val languages = mockk<MultiLanguageManager>(relaxed = true)
        every { languages.detectAndSwitch(any(), any(), any()) } returns "de"
        predictor.setField("multiLanguageManager", languages)

        listOf("wir", "haben", "das", "schon", "doch").forEach { predictor.addWordToContext(it, true) }

        assertWithMessage("next-word language switched").that(predictor.getField("currentLanguage")).isEqualTo("de")
        verify { bigrams.setLanguage("de") }
        verify { bigrams.loadStaticContinuationsAsync(ctx, "de") }
        assertWithMessage("context model follows").that((predictor.getField("contextModel") as ContextModel).language).isEqualTo("de")
        assertWithMessage("the window that triggered the switch is cleared").that(learnWindow()).isEmpty()
        verify(exactly = 0) { observer.setLanguage("de") }
        assertWithMessage("the primary's disabled words stay").that(predictor.getField("disabledWords") as Set<*>).containsExactly("colour")
        // A Dictionary Manager edit after the switch still reloads the PRIMARY's lists.
        predictor.reloadDisabledWords()
        assertWithMessage("a reload reads the primary's key").that(predictor.getField("disabledWords") as Set<*>).containsExactly("colour")
        verify(exactly = 0) { prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey("de"), any()) }
    }

    // ------------------------------------------- back to the primary (Fable review 2026-11-26)
    //
    // After a detected switch, nothing moved the NEXT-WORD language back to the dictionary's:
    // turning Auto-Detect off, turning Multi-Language off (or removing the secondary language) and
    // switching to a layout bound to the already-loaded primary all left next-word predictions in
    // the secondary language. Each case below starts on English's dictionary with German detected.

    /** English is the loaded dictionary; German was detected and is the next-word language. */
    private fun detectGermanOverEnglish(): BigramModel {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "de"
        config.primary_language = "en"
        config.layout_bound_language = null
        val bigrams = mockk<BigramModel>(relaxed = true)
        predictor.setField("bigramModel", bigrams)
        predictor.setField("dictionaryLanguageOrNull", "en")
        val languages = mockk<MultiLanguageManager>(relaxed = true)
        every { languages.detectAndSwitch(any(), any(), any()) } returns "de"
        predictor.setField("multiLanguageManager", languages)
        listOf("wir", "haben", "das", "schon", "doch").forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("precondition: German detected").that(predictor.getField("currentLanguage")).isEqualTo("de")
        return bigrams
    }

    @Test
    fun turningAutoDetectOffReturnsTheNextWordLanguageToThePrimary() {
        val bigrams = detectGermanOverEnglish()

        config.auto_detect_language = false
        predictor.setConfig(config)

        assertWithMessage("back to the dictionary's language at once, before any commit")
            .that(predictor.getField("currentLanguage")).isEqualTo("en")
        verify { bigrams.setLanguage("en") }
        assertWithMessage("the context model follows")
            .that((predictor.getField("contextModel") as ContextModel).language).isEqualTo("en")
    }

    @Test
    fun turningMultiLanguageOffReturnsTheNextWordLanguageToThePrimary() {
        detectGermanOverEnglish()

        config.active_secondary_language = null
        predictor.addWordToContext("the", true)

        assertWithMessage("no secondary language: the primary's").that(predictor.getField("currentLanguage")).isEqualTo("en")
    }

    @Test
    fun replacingTheSecondaryLanguageReturnsTheNextWordLanguageToThePrimary() {
        detectGermanOverEnglish()

        config.active_secondary_language = "fr"
        predictor.setConfig(config)

        assertWithMessage("German is no longer a chosen language").that(predictor.getField("currentLanguage")).isEqualTo("en")
    }

    @Test
    fun aLayoutBoundToTheLoadedPrimaryReturnsTheNextWordLanguageToIt() {
        detectGermanOverEnglish()

        // Bound to English, which is already loaded: no dictionary load (setLanguage) follows.
        config.layout_bound_language = "en"
        predictor.addWordToContext("the", true)

        assertWithMessage("the bound layout's language").that(predictor.getField("currentLanguage")).isEqualTo("en")
    }

    @Test
    fun whileDetectionStaysOnTheDetectedLanguageIsKept() {
        detectGermanOverEnglish()

        predictor.setConfig(config)
        predictor.addWordToContext("ja", true)

        assertWithMessage("nothing changed: German stays").that(predictor.getField("currentLanguage")).isEqualTo("de")
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
            editor.insert(cursor(), c)
            cursorAt = cursorAt?.plus(1)
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

    /**
     * Saga 2026-11-09: tap `cat`, clear the field app-side (select all + delete collapses the
     * selection to its start, so no cursor sync runs and the tracker still holds `cat`), swipe
     * `kids` → the editor read `" kids "`. The separator is verified at use: the text before the
     * cursor no longer ends in the word, so no space, and the deleted word is not learned.
     */
    @Test
    fun aSwipeAfterTheTypedWordWasDeletedAppSideGetsNoSeparator() {
        editor.append("so ")
        handler.updateContext("so")
        type("cat")
        editor.setLength(0)
        swipe("kids")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("kids ")
        assertWithMessage("the deleted word is not learned").that(bigram("so", "cat")).isEqualTo(0)
        assertWithMessage("nor followed by the swipe").that(bigram("cat", "kids")).isEqualTo(0)
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

    // ================================================================ W2 follow-up (2026-11-26)
    //
    // Maintainer question: "with learning on, are swipe word corrections erroneously getting
    // recorded as n-gram pairs?" One test per route by which a wrong word leaves the editor.

    /** The selection callback a real editor sends after an edit (InputCoordinator.onCursorMoved). */
    private fun cursorSync(field: EditorInfo = textField()) {
        tracker.onCursorPositionChanged(cursor())
        tracker.synchronizeWithCursor(ic, "en", field)
        if (tracker.getCurrentWord().isNotEmpty()) {
            handler.handleCursorSyncPrediction(ic, field)
        } else {
            handler.handleCursorParkPrediction(field, ic)
        }
    }

    /** Ordinary Backspace presses (the #110 whole-word undo off or not applicable). */
    private fun backspaceChars(count: Int, field: EditorInfo = textField()) {
        repeat(count) {
            val c = cursor()
            editor.deleteCharAt(c - 1)
            cursorAt = cursorAt?.minus(1)
            handler.handleBackspace(ic, field)
            cursorSync(field)
        }
    }

    /** A new gesture: InputCoordinator clears the auto-insert tracking when it starts. */
    private fun newSwipe(vararg slate: String) {
        tracker.clearLastAutoInsertedWord()
        swipe(*slate)
    }

    private fun trigram(w1: String, w2: String, w3: String): Float =
        trigramStore.getProbability("en", w1, w2, w3)

    private fun assertOnlyTheCorrectionLearned(prev: String, wrong: String, right: String) {
        assertWithMessage("$prev→$wrong rolled back").that(bigram(prev, wrong)).isEqualTo(0)
        assertWithMessage("no $wrong→$right chain").that(bigram(wrong, right)).isEqualTo(0)
        assertWithMessage("$prev→$right learned once").that(bigram(prev, right)).isEqualTo(1)
    }

    /** Scenario 1: bar alternate over the auto-inserted swipe word, trigram included. */
    @Test
    fun scenario1BarAlternateOverASwipeLeavesNoWrongPairOrTrigram() {
        editor.append("we fix ")
        handler.updateContext("we")
        handler.updateContext("fix")
        swipe("got", "git")
        tap("git")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("we fix git ")
        assertOnlyTheCorrectionLearned("fix", "got", "git")
        assertWithMessage("we fix got").that(trigram("we", "fix", "got")).isEqualTo(0f)
        assertWithMessage("we fix git").that(trigram("we", "fix", "git")).isGreaterThan(0f)
    }

    /** Scenario 2: the #110 whole-word Backspace undo, then the right word swiped. */
    @Test
    fun scenario2BackspaceUndoThenReswipeLearnsOnlyTheCorrection() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        editor.setLength(editor.length - "got ".length)
        handler.onSwipeWordUndone("got", ic)
        newSwipe("git")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix git ")
        assertOnlyTheCorrectionLearned("fix", "got", "git")
    }

    /** Scenario 3: "Backspace undoes swipe" OFF — the word deleted letter by letter, then re-swiped. */
    @Test
    fun scenario3LetterByLetterDeleteThenReswipeLearnsOnlyTheCorrection() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        backspaceChars("got ".length)
        newSwipe("git")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix git ")
        assertOnlyTheCorrectionLearned("fix", "got", "git")
        assertWithMessage("learn window").that(learnWindow()).containsExactly("fix", "git").inOrder()
        assertWithMessage("the rejected word's vocabulary +1 reversed").that(vocabulary.getWordUsage("got")).isNull()
        assertWithMessage("the kept word counted once").that(vocabulary.getWordUsage("git")?.usageCount).isEqualTo(1)
    }

    /** Scenario 3, retyped by tapping instead of swiping. */
    @Test
    fun scenario3LetterByLetterDeleteThenRetypeLearnsOnlyTheCorrection() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        backspaceChars("got ".length)
        type("git ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix git ")
        assertOnlyTheCorrectionLearned("fix", "got", "git")
    }

    /** Scenario 3, part of the word kept: "got" → "go", then "ne" typed onto it. */
    @Test
    fun scenario3PartlyDeletedWordCompletedDifferentlyLearnsTheNewWord() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        backspaceChars(2)
        type("ne ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix gone ")
        assertOnlyTheCorrectionLearned("fix", "got", "gone")
    }

    /** Scenario 3, the editor's own word delete (Ctrl+Backspace / `delete_word`): one edit, no hook. */
    @Test
    fun scenario3CtrlBackspaceThenReswipeLearnsOnlyTheCorrection() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        editor.setLength(editor.length - "got ".length)
        cursorSync()
        newSwipe("git")

        assertOnlyTheCorrectionLearned("fix", "got", "git")
    }

    /** Scenario 3, the keyboard's delete-last-word command (it reports the rejection itself). */
    @Test
    fun scenario3DeleteLastWordThenReswipeLearnsOnlyTheCorrection() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        handler.handleDeleteLastWord(ic, textField())
        cursorSync()
        newSwipe("git")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix git ")
        assertOnlyTheCorrectionLearned("fix", "got", "git")
    }

    /** No false rollback: the swiped word kept, the next word swiped after it. */
    @Test
    fun aKeptSwipeWordFollowedByTheNextWordStaysLearned() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        newSwipe("it")

        assertWithMessage("fix→got kept").that(bigram("fix", "got")).isEqualTo(1)
        assertWithMessage("got→it learned").that(bigram("got", "it")).isEqualTo(1)
    }

    /** No false rollback: only the automatic space deleted, then punctuation. */
    @Test
    fun deletingOnlyTheSpaceAfterASwipeWordKeepsItLearned() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        backspaceChars(1)
        type(". ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix got. ")
        assertWithMessage("fix→got kept").that(bigram("fix", "got")).isEqualTo(1)
        verify(exactly = 0) { personalization.unrecordWordTyped(any()) }
    }

    /** No false rollback: the cursor moved elsewhere and a word was typed there. */
    @Test
    fun aWordTypedElsewhereDoesNotRollTheSwipeWordBack() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        cursorAt = 0
        cursorSync()
        type("so ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("so fix got ")
        assertWithMessage("fix→got kept").that(bigram("fix", "got")).isEqualTo(1)
        verify(exactly = 0) { personalization.unrecordWordTyped(any()) }
    }

    /** Scenario 4: the next word swiped first, then the user goes back and fixes the first one. */
    @Test
    fun scenario4FixingAnEarlierWordAfterTheCursorMovedBackLearnsNoChainedPair() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        newSwipe("it")
        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix got it ")

        cursorAt = "fix got".length
        cursorSync()
        tap("git")

        assertWithMessage("editor").that(editor.toString()).startsWith("fix git")
        assertWithMessage("no it→git (the replacement is not after the newest word)")
            .that(bigram("it", "git")).isEqualTo(0)
        assertWithMessage("no got→git (the replaced word is not before it either)")
            .that(bigram("got", "git")).isEqualTo(0)
        assertWithMessage("it, still in the editor, keeps its learn").that(bigram("got", "it")).isEqualTo(1)
        // TODO(W2 history): fix→got and got→it stay learned. They belong to commits older than
        // the newest receipt, which is the only one the predictor can reverse exactly.
    }

    /**
     * Scenario 4, editor side: the bar tap must not run the swipe REPLACE (delete the newest
     * swipe's length before the cursor) once the cursor has moved away from that swipe.
     */
    @Test
    fun scenario4BarTapAfterTheCursorMovedBackDoesNotDeleteByTheLastSwipesLength() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        newSwipe("something")
        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix got something ")

        cursorAt = "fix got".length
        cursorSync()
        tap("git")

        assertWithMessage("only the word at the cursor is replaced")
            .that(editor.toString().replace("  ", " ")).isEqualTo("fix git something ")
        assertWithMessage("something, still in the editor, keeps its learn").that(bigram("got", "something")).isEqualTo(1)
    }

    /** Scenario 5: undo, re-swipe (the correction candidate), then the next word settles it. */
    @Test
    fun scenario5SettledSwipeCorrectionLeavesOnlyTheCorrection() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        editor.setLength(editor.length - "got ".length)
        handler.onSwipeWordUndone("got", ic)
        newSwipe("git")
        newSwipe("now")

        assertOnlyTheCorrectionLearned("fix", "got", "git")
        assertWithMessage("git→now").that(bigram("git", "now")).isEqualTo(1)
    }

    /** Scenario 6: a typed word autocorrected, then deleted letter by letter and retyped. */
    @Test
    fun scenario6RetypingAnAutocorrectedWordLearnsOnlyTheRetypedWord() {
        config.autocorrect_enabled = true
        every { predictor.autoCorrect(any()) } answers { firstArg() }
        every { predictor.autoCorrect("teh") } returns "the"
        editor.append("fix ")
        handler.updateContext("fix")
        type("teh ")
        assertWithMessage("autocorrect ran").that(editor.toString()).isEqualTo("fix the ")
        backspaceChars("the ".length)
        type("ten ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix ten ")
        assertOnlyTheCorrectionLearned("fix", "the", "ten")
    }

    /**
     * Scenario 7: the wrong word's commit is the one that made Auto-Detect switch the next-word
     * language. Its pair went to the ENGLISH store; the rollback must take it from there.
     */
    @Test
    fun scenario7RollbackAfterADetectedSwitchTargetsTheStoreTheCommitWroteTo() {
        config.auto_detect_language = true
        config.active_secondary_language = "es"
        val languages = mockk<MultiLanguageManager>(relaxed = true)
        every { languages.detectAndSwitch(any(), any(), any()) } returns "es"
        predictor.setField("multiLanguageManager", languages)
        // English is the loaded dictionary (setLanguage, which a real dictionary load calls).
        predictor.setField("dictionaryLanguageOrNull", "en")
        editor.append("we all really like ")
        listOf("we", "all", "really", "like").forEach { handler.updateContext(it) }
        swipe("cats", "cat")
        assertWithMessage("the fifth commit switched").that(predictor.getField("currentLanguage")).isEqualTo("es")
        assertWithMessage("like→cats in the English store").that(bigram("like", "cats")).isEqualTo(1)

        tap("cat")

        assertWithMessage("like→cats rolled back from the English store").that(bigram("like", "cats")).isEqualTo(0)
        verify(exactly = 1) { personalization.unrecordWordTyped("cats") }
    }

    // ---------------------------------------------------- Fable review of a11db8b84 (2026-11-26)

    /** Finding 1: a word inserted BEFORE the newest learned word does not take its place. */
    @Test
    fun aWordInsertedBeforeTheNewestLearnedWordDoesNotRollItBack() {
        val vocabulary = useReceiptVocabulary()
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        cursorAt = "fix ".length
        cursorSync()
        type("really ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix really got ")
        assertWithMessage("fix→got kept").that(bigram("fix", "got")).isEqualTo(1)
        assertWithMessage("got's usage kept").that(vocabulary.getWordUsage("got")?.usageCount).isEqualTo(1)
        assertWithMessage("got kept in the prediction context").that(tracker.getContextWords()).contains("got")
        assertWithMessage("no got→really (really is not after got)").that(bigram("got", "really")).isEqualTo(0)
    }

    /** Finding 2: the first word of a field, then the app clears the field (a WebView send). */
    @Test
    fun aFirstWordFollowedByAnAppClearedFieldIsNotRolledBack() {
        val vocabulary = useReceiptVocabulary()
        type("yes ")
        editor.setLength(0)
        cursorSync()
        type("no ")

        assertWithMessage("yes's usage kept").that(vocabulary.getWordUsage("yes")?.usageCount).isEqualTo(1)
        assertWithMessage("no yes→no across the send").that(bigram("yes", "no")).isEqualTo(0)
    }

    /** Finding 3a: an editor that reads back "" is no evidence against the swipe REPLACE. */
    @Test
    fun aBarAlternateReplacesTheSwipeWhenTheEditorReadsBackNothing() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        every { ic.getTextBeforeCursor(any(), any()) } returns ""
        tap("git")

        assertWithMessage("replaced, not appended").that(editor.toString()).isEqualTo("fix git ")
    }

    /** Finding 3b: an editor that rewrites the case (an all-caps filter) still gets the REPLACE. */
    @Test
    fun aBarAlternateReplacesTheSwipeInAnEditorThatRewritesItsCase() {
        every { ic.commitText(any(), any()) } answers {
            val text = firstArg<CharSequence>().toString().uppercase()
            editor.insert(cursor(), text)
            cursorAt = cursorAt?.let { it + text.length }
            true
        }
        editor.append("FIX ")
        handler.updateContext("fix")
        swipe("got", "git")
        assertWithMessage("the editor shows its own case").that(editor.toString()).isEqualTo("FIX GOT ")
        tap("git")

        assertWithMessage("replaced, not appended").that(editor.toString()).isEqualTo("FIX GIT ")
    }

    /**
     * Finding 4: a run of one word longer than the anchor window: every commit stays learned.
     * (The stores never record a self-pair `la→la`; the vocabulary count is what a false
     * rollback would take.)
     */
    @Test
    fun aLongRunOfTheSameWordKeepsEveryCommit() {
        val vocabulary = useReceiptVocabulary()
        editor.append("we sang ")
        handler.updateContext("we")
        handler.updateContext("sang")
        repeat(30) { type("la ") }

        assertWithMessage("la counted every time").that(vocabulary.getWordUsage("la")?.usageCount).isEqualTo(30)
        assertWithMessage("sang→la kept").that(bigram("sang", "la")).isEqualTo(1)
    }

    /** Finding 5: a closing quote, a bracket or an emoji between two words is not a context break. */
    @Test
    fun aQuoteBracketOrEmojiBetweenWordsKeepsTheLearnWindow() {
        for (between in listOf("\", ", "), ", " \uD83D\uDE00 ")) {
            editor.setLength(0)
            predictor.clearContext()
            editor.append("fix ")
            handler.updateContext("fix")
            swipe("got", "git")
            // The space the swipe added is replaced by the separator run (an app-side edit).
            editor.setLength(editor.length - 1)
            editor.append(between)
            cursorSync()
            newSwipe("it")
            assertWithMessage("got→it learned across '$between'").that(bigram("got", "it")).isAtLeast(1)
            assertWithMessage("the window still holds got before it ('$between')")
                .that(learnWindow().takeLast(2)).containsExactly("got", "it").inOrder()
            bigramStore.clearAll()
        }
    }

    /**
     * Task B (2026-11-26): which secondary Auto-Detect has switched the next-word language to —
     * the list a bar "Add to dictionary" writes to. Null until a detected switch, and again once
     * detection stops applying.
     */
    @Test
    fun theDetectedSecondaryIsReportedOnlyWhileDetectionHasSwitchedToIt() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "es"
        predictor.setField("dictionaryLanguageOrNull", "en")
        val languages = mockk<MultiLanguageManager>(relaxed = true)
        every { languages.detectAndSwitch(any(), any(), any()) } returns null
        predictor.setField("multiLanguageManager", languages)
        listOf("we", "all", "really", "like", "cats").forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("no switch yet").that(predictor.autoDetectedSecondaryLanguage()).isNull()

        every { languages.detectAndSwitch(any(), any(), any()) } returns "es"
        predictor.addWordToContext("perros", true)
        assertWithMessage("switched to the secondary").that(predictor.autoDetectedSecondaryLanguage()).isEqualTo("es")

        config.auto_detect_language = false
        assertWithMessage("detection off").that(predictor.autoDetectedSecondaryLanguage()).isNull()
    }

    /**
     * Fable review of 7d1275a67, finding 6: the detection must be fresh. A detected switch sticks
     * until the next detection run (five words into a window), so short English messages after a
     * Spanish paragraph still read as Spanish. The verdict counts only until the window that
     * produced it ends (a sentence boundary) or the input session changes.
     */
    @Test
    fun aDetectedSecondaryNoLongerCountsAfterTheWindowOrTheSessionEnds() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "es"
        predictor.setField("dictionaryLanguageOrNull", "en")
        val languages = mockk<MultiLanguageManager>(relaxed = true)
        every { languages.detectAndSwitch(any(), any(), any()) } returns "es"
        predictor.setField("multiLanguageManager", languages)
        listOf("el", "perro", "come", "la", "comida").forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("fresh").that(predictor.autoDetectedSecondaryLanguage()).isEqualTo("es")

        predictor.onSentenceBoundary()
        assertWithMessage("the window that detected it ended").that(predictor.autoDetectedSecondaryLanguage()).isNull()

        listOf("y", "el", "gato", "bebe", "agua").forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("a new detection in the new window").that(predictor.autoDetectedSecondaryLanguage()).isEqualTo("es")
        predictor.onInputSessionChanged()
        assertWithMessage("a new input session").that(predictor.autoDetectedSecondaryLanguage()).isNull()
        listOf("el", "perro", "come", "la", "comida").forEach { predictor.addWordToContext(it, true) }
        predictor.clearContext()
        assertWithMessage("the context was cleared").that(predictor.autoDetectedSecondaryLanguage()).isNull()
    }

    // ---------------------------------------------------- Note10+ device run of r20 (2026-11-26)

    /** The legacy detector path the device ran ("Auto-detected language change from en to es"). */
    private fun useRealLegacyDetector() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "es"
        config.language_detection_sensitivity = Defaults.LANGUAGE_DETECTION_SENSITIVITY
        predictor.setField("dictionaryLanguageOrNull", "en")
        val bigrams = mockk<BigramModel>(relaxed = true)
        every { bigrams.isLanguageSupported(any()) } returns true
        predictor.setField("bigramModel", bigrams)
        predictor.setField("languageDetector", LanguageDetector())
        predictor.setField("multiLanguageManager", null)
    }

    private val spanish = "la casa de mi madre es muy bonita y no es para el perro".split(" ")
    private val english = "thank i want to go to the park with you and it is".split(" ")

    /**
     * F2 (Note10+): Spanish detected in a Chrome textarea, then a Samsung Notes field typed in
     * English kept predicting in Spanish (empty bar) until a language change in Settings. A new
     * input session starts next-word in the primary language; detection switches it again from
     * fresh evidence.
     */
    @Test
    fun aNewInputSessionStartsNextWordInThePrimaryLanguage() {
        useRealLegacyDetector()
        spanish.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("Spanish detected").that(predictor.getField("currentLanguage")).isEqualTo("es")

        // CleverKeysService: onFinishInputView → flushLearnedData (clearContext) and
        // onEditorSessionChanged; onStartInputView → onEditorSessionChanged.
        predictor.clearContext()
        handler.onEditorSessionChanged()

        assertWithMessage("the new field starts in the primary").that(predictor.getField("currentLanguage")).isEqualTo("en")
        assertWithMessage("its context model too").that((predictor.getField("contextModel") as ContextModel).language).isEqualTo("en")
        spanish.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("fresh Spanish evidence switches again").that(predictor.getField("currentLanguage")).isEqualTo("es")
    }

    /** F2, second half: inside one field, an English context of 5+ words switches back. */
    @Test
    fun anEnglishContextSwitchesBackInsideOneField() {
        useRealLegacyDetector()
        spanish.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("Spanish detected").that(predictor.getField("currentLanguage")).isEqualTo("es")

        english.forEach { predictor.addWordToContext(it, true) }

        assertWithMessage("English detected again").that(predictor.getField("currentLanguage")).isEqualTo("en")
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
