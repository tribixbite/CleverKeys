package tribixbite.cleverkeys

import android.content.res.Resources
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
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

    /**
     * The caret the editor last reported (onUpdateSelection's old position): kept current by the
     * keyboard's own commits and deletions through [ic]; edits made straight on [editor] (typed
     * letters, a hand deletion, an app-side change) are reported by the next [cursorSync].
     */
    private var reportedCaret = -1
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
        every { bar.clearSuggestions() } answers { barWords = emptyList() }

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
            reportedCaret = cursor()
            true
        }
        every { ic.deleteSurroundingText(any(), any()) } answers {
            val c = cursor()
            val start = maxOf(0, c - firstArg<Int>())
            val end = minOf(editor.length, c + secondArg<Int>())
            editor.delete(start, end)
            cursorAt = cursorAt?.let { start }
            reportedCaret = cursor()
            true
        }
        every { ic.getCursorCapsMode(any()) } returns 0
        // The end-of-field proof (an empty after-cursor read alone proves nothing) reads the
        // editor's extracted text; ExtractedText(Request) are android.jar stubs here.
        every { ic.getExtractedText(any(), any()) } answers {
            objenesis.newInstance(ExtractedText::class.java).apply {
                text = editor.toString()
                startOffset = 0
                selectionStart = cursor()
                selectionEnd = cursor()
            }
        }
        EditorSelectionReader.newExtractedTextRequest = { objenesis.newInstance(ExtractedTextRequest::class.java) }
        EditorSelectionReader.platformSdk = { 0 }

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

    /**
     * Production's only Auto-Detect path, the predictor's own [LanguageDetector] (audit B2,
     * 2026-11-26: the MultiLanguageManager branch was never built in production and is gone),
     * answering [language] at [confidence]; every language has static next-word data.
     */
    private fun detects(language: String, confidence: Float = 0.99f): LanguageDetector {
        val bigrams = predictor.getField("bigramModel") as BigramModel?
            ?: mockk<BigramModel>(relaxed = true).also { predictor.setField("bigramModel", it) }
        every { bigrams.isLanguageSupported(any()) } returns true
        val detector = mockk<LanguageDetector>()
        every { detector.detectLanguageFromWordsWithConfidence(any(), any()) } returns
            LanguageDetector.DetectionResult(language, confidence)
        predictor.setField("languageDetector", detector)
        return detector
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
        config.active_secondary_language = "es"
        // Detection confirming the active language is not a change to this predictor's en store.
        detects("en")
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
        val detector = detects("es")

        config.layout_bound_language = "en"
        listOf("we", "all", "really", "like", "cats").forEach { predictor.addWordToContext(it, true) }
        verify(exactly = 0) { detector.detectLanguageFromWordsWithConfidence(any(), any()) }
        assertWithMessage("bound language kept").that(predictor.getField("currentLanguage")).isEqualTo("en")

        config.layout_bound_language = null
        config.active_secondary_language = "es"
        predictor.addWordToContext("dogs", true)
        verify(exactly = 1) { detector.detectLanguageFromWordsWithConfidence(any(), any()) }
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
        val detector = detects("es")
        listOf("we", "all", "really", "like", "cats").forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("a language the user did not choose").that(predictor.getField("currentLanguage")).isEqualTo("en")

        every { detector.detectLanguageFromWordsWithConfidence(any(), any()) } returns
            LanguageDetector.DetectionResult("de", 0.99f)
        predictor.addWordToContext("dogs", true)
        assertWithMessage("the chosen secondary").that(predictor.getField("currentLanguage")).isEqualTo("de")
    }

    /**
     * Name kept for docs/RELEASE_RECORD.md (the v2.0.0 row "the language manager stays on the
     * chosen languages" anchors it). The manager is gone (audit B2, 2026-11-26); the claim it
     * carried is that Portuguese text with English + Spanish chosen never moves the keyboard to
     * Portuguese, which the production detector now pins: it scores the chosen pair only.
     */
    @Test
    fun theLanguageManagerStaysOnTheChosenLanguages() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "es"
        config.language_detection_sensitivity = Defaults.LANGUAGE_DETECTION_SENSITIVITY
        detects("en") // installs a bigram model that supports every language
        predictor.setField("languageDetector", LanguageDetector())
        listOf("o", "não", "é", "uma", "da", "do", "em", "com", "os", "mais", "não", "é").forEach {
            predictor.addWordToContext(it, true)
        }
        assertWithMessage("never a language outside the chosen pair")
            .that(predictor.getField("currentLanguage")).isAnyOf("en", "es")
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
        detects("de")

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
        detects("de")
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
        EditorSelectionReader.newExtractedTextRequest = { ExtractedTextRequest() }
        EditorSelectionReader.platformSdk = { android.os.Build.VERSION.SDK_INT }
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
        // CleverKeysService.onUpdateSelection: an edit that shortened the newest learned word
        // takes its learning back at once (Saga F5), before the cursor bookkeeping below.
        val old = if (reportedCaret >= 0) reportedCaret else cursor()
        handler.onUnownedSelectionUpdate(old, old, cursor(), cursor(), ic)
        reportedCaret = cursor()
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
        detects("es")
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
        val detector = detects("en")
        listOf("we", "all", "really", "like", "cats").forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("no switch yet").that(predictor.autoDetectedSecondaryLanguage()).isNull()

        every { detector.detectLanguageFromWordsWithConfidence(any(), any()) } returns
            LanguageDetector.DetectionResult("es", 0.99f)
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
        detects("es")
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
        handler.onInputFieldStarted(chromeField(), restarting = false)
        spanish.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("Spanish detected").that(predictor.getField("currentLanguage")).isEqualTo("es")

        // CleverKeysService: onFinishInputView → onEditorSessionChanged and flushLearnedData
        // (clearContext); onStartInputView of ANOTHER field → onInputFieldStarted.
        handler.onEditorSessionChanged()
        predictor.clearContext()
        handler.onInputFieldStarted(textField(), restarting = false)

        assertWithMessage("the new field starts in the primary").that(predictor.getField("currentLanguage")).isEqualTo("en")
        assertWithMessage("its context model too").that((predictor.getField("contextModel") as ContextModel).language).isEqualTo("en")
        spanish.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("fresh Spanish evidence switches again").that(predictor.getField("currentLanguage")).isEqualTo("es")
    }

    private fun chromeField(): EditorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
        packageName = "com.android.chrome"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    /**
     * Fable review of 251e9c152, finding 1: only a NEW field resets the detected language. A
     * restart (rotation, a WebView restarting input), a hide and re-show of the same field and a
     * dictation write each wiped a correct detection, and Spanish then needed five new words.
     */
    @Test
    fun theSameFieldRestartedReshownOrDictatedIntoKeepsTheDetectedLanguage() {
        // Fable review 2026-11-27 (#3): with the editor passed in, and EMPTY, as a chat box is
        // after sending (the calls without one could not see an empty-field rule).
        useRealLegacyDetector()
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        spanish.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("Spanish detected").that(predictor.getField("currentLanguage")).isEqualTo("es")
        val window = learnWindow()

        handler.onInputStarted(restarting = true)
        handler.onInputFieldStarted(chromeField(), restarting = true, ic)
        assertWithMessage("a restart").that(predictor.getField("currentLanguage")).isEqualTo("es")
        handler.onEditorSessionChanged()
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        assertWithMessage("hidden and shown again").that(predictor.getField("currentLanguage")).isEqualTo("es")
        handler.onEditorSessionChanged() // a dictation write (CleverKeysService onExternalEdit)
        assertWithMessage("a dictation write").that(predictor.getField("currentLanguage")).isEqualTo("es")
        assertWithMessage("the window survives").that(learnWindow()).isEqualTo(window)
    }

    /**
     * Finding 1, the finish order: the last, unfinished word of the field is learned in the
     * detected language's store with its window, not after a reset into the primary's.
     */
    @Test
    fun leavingTheFieldLearnsTheLastWordInTheDetectedLanguage() {
        useRealLegacyDetector()
        handler.onInputFieldStarted(chromeField(), restarting = false)
        spanish.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("Spanish detected").that(predictor.getField("currentLanguage")).isEqualTo("es")
        editor.append("el perro ")
        type("gato")

        // CleverKeysService.onFinishInputView: onEditorSessionChanged, then the flush.
        handler.onEditorSessionChanged()
        handler.flushTypedWordOnFinishInput(ic)

        assertWithMessage("perro→gato in the Spanish store")
            .that(bigramStore.getAllBigrams("es", "perro").map { it.word2 }).contains("gato")
        assertWithMessage("nothing in the English store").that(bigram("perro", "gato")).isEqualTo(0)
    }

    /**
     * Finding 2, then audit B6 (2026-11-26): a switch back to the primary below the user's
     * sensitivity needs two detection windows that favour it WITHOUT sharing evidence: the second
     * counts only once [WordPredictor.DETECTION_MIN_WORDS] new words came after the first. Two
     * consecutive commits (windows sharing all but one word) were close to one decision. And
     * while a weak return is under way the secondary's verdict is no longer fresh, so a bar add
     * goes to the primary's list.
     */
    @Test
    fun aWeakSwitchBackNeedsTwoWindowsWithoutSharedWords() {
        useReceiptVocabulary()
        config.auto_detect_language = true
        config.active_secondary_language = "es"
        config.language_detection_sensitivity = Defaults.LANGUAGE_DETECTION_SENSITIVITY
        predictor.setField("dictionaryLanguageOrNull", "en")
        val detector = detects("es", 0.9f)
        fun lang() = predictor.getField("currentLanguage")
        fun weakEnglish() = every { detector.detectLanguageFromWordsWithConfidence(any(), any()) } returns
            LanguageDetector.DetectionResult("en", 0.3f)

        (1..5).forEach { predictor.addWordToContext("w$it", true) }
        assertWithMessage("switched").that(lang()).isEqualTo("es")
        assertWithMessage("fresh").that(predictor.autoDetectedSecondaryLanguage()).isEqualTo("es")
        weakEnglish()
        (6..10).forEach { predictor.addWordToContext("w$it", true) }
        assertWithMessage("one window for the primary").that(lang()).isEqualTo("es")
        assertWithMessage("a weak return under way: not fresh").that(predictor.autoDetectedSecondaryLanguage()).isNull()
        predictor.addWordToContext("w11", true)
        assertWithMessage("the next commit's window shares four words").that(lang()).isEqualTo("es")
        (12..14).forEach { predictor.addWordToContext("w$it", true) }
        assertWithMessage("still within the first window's words").that(lang()).isEqualTo("es")
        predictor.addWordToContext("w15", true)
        assertWithMessage("a second window of new words").that(lang()).isEqualTo("en")
    }

    /**
     * Audit B1 (2026-11-26): detection needed five words in the LEARN window, which every
     * sentence boundary empties, so after a Spanish sentence short English ones ("Ok. See you
     * soon. Thanks!") never ran detection and the field stayed Spanish. The detection window
     * keeps the field's last words across sentence boundaries.
     */
    @Test
    fun shortEnglishSentencesAfterASpanishOneReturnToThePrimary() {
        useRealLegacyDetector()
        spanish.forEach { predictor.addWordToContext(it, true) }
        predictor.onSentenceBoundary()
        assertWithMessage("Spanish detected").that(predictor.getField("currentLanguage")).isEqualTo("es")

        for (sentence in listOf("ok", "see you soon", "thanks", "i am on my way", "talk to you later", "bye now")) {
            sentence.split(" ").forEach { predictor.addWordToContext(it, true) }
            predictor.onSentenceBoundary()
        }

        assertWithMessage("English again").that(predictor.getField("currentLanguage")).isEqualTo("en")
    }

    /**
     * Audit B3 (2026-11-26): the detected next-word language's static seed was respelled by the
     * PRIMARY's Swiss-spelling flag. With English primary and German detected (Swiss spelling on)
     * the seed offered `straße` while German's own lexicon held `strasse`, and next-word dropped it.
     */
    @Test
    fun theDetectedLanguagesSeedIsSpelledLikeItsOwnLexicon() {
        val ctx = mockk<android.content.Context>(relaxed = true)
        predictor.setField("context", ctx)
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(LanguagePrefs.GERMAN_SWISS_SPELLING, any()) } returns true
        every { prefs.all } returns mapOf(LanguagePrefs.GERMAN_SWISS_SPELLING to true)
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs
        val bigrams = mockk<BigramModel>(relaxed = true)
        every { bigrams.getPredictions("die", any()) } returns listOf(StaticBigramSeed.Continuation("straße", 1f))
        predictor.setField("bigramModel", bigrams)
        predictor.setField("dictionaryLanguageOrNull", "en")
        predictor.setField("currentLanguage", "de")
        predictor.setField("primarySwissSpelling", false)

        assertWithMessage("German's Swiss spelling").that(predictor.getStaticNextWordSeed(listOf("die"), 5).map { it.word })
            .containsExactly("strasse")
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

    /**
     * F4 (Note10+): "count tem " autocorrected to "ten", the first Backspace restored "tem ", the
     * user deleted it by hand and typed "them all ". The restored word was learned without a
     * placement, so its vocabulary count and its prediction-context entry stayed.
     */
    @Test
    fun aWordRestoredByTheAutocorrectUndoAndThenDeletedLeavesNoLearning() {
        val vocabulary = useReceiptVocabulary()
        config.autocorrect_enabled = true
        every { predictor.autoCorrect(any()) } answers { firstArg() }
        every { predictor.autoCorrect("tem") } returns "ten"
        editor.append("count ")
        handler.updateContext("count")
        type("tem ")
        assertWithMessage("autocorrect ran").that(editor.toString()).isEqualTo("count ten ")

        // KeyEventHandler.handleBackspaceUndoAutocorrect: "ten " → "tem ", reported with the editor.
        editor.setLength(editor.length - "ten ".length)
        editor.append("tem ")
        handler.onAutocorrectUndone("ten", "tem", originalCompleted = true, ic = ic)
        handler.handleBackspace(ic, textField())
        cursorSync()
        backspaceChars("tem ".length)
        type("them all ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("count them all ")
        assertWithMessage("tem's usage reversed").that(vocabulary.getWordUsage("tem")).isNull()
        assertWithMessage("ten's usage reversed").that(vocabulary.getWordUsage("ten")).isNull()
        assertWithMessage("count→them").that(bigram("count", "them")).isEqualTo(1)
        assertWithMessage("no tem→them").that(bigram("tem", "them")).isEqualTo(0)
        assertWithMessage("them→all").that(bigram("them", "all")).isEqualTo(1)
        assertWithMessage("tem left the prediction context").that(tracker.getContextWords()).doesNotContain("tem")
        assertWithMessage("learn window").that(learnWindow()).containsExactly("count", "them", "all").inOrder()
    }

    // ---------------------------------------------------- Saga device run of r20 (2026-11-26)

    /** F4 on the Saga: the wrong PAIRS stayed ("red → wirld", "wirld → wield"), red → wield missing. */
    @Test
    fun aRestoredAutocorrectOriginalDeletedByHandLeavesNoPair() {
        val vocabulary = useReceiptVocabulary()
        config.autocorrect_enabled = true
        every { predictor.autoCorrect(any()) } answers { firstArg() }
        every { predictor.autoCorrect("wirld") } returns "world"
        editor.append("red ")
        handler.updateContext("red")
        type("wirld ")
        assertWithMessage("autocorrect ran").that(editor.toString()).isEqualTo("red world ")
        editor.setLength(editor.length - "world ".length)
        editor.append("wirld ")
        handler.onAutocorrectUndone("world", "wirld", originalCompleted = true, ic = ic)
        handler.handleBackspace(ic, textField())
        cursorSync()
        backspaceChars("wirld ".length)
        type("wield sword ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("red wield sword ")
        assertWithMessage("red→wield").that(bigram("red", "wield")).isEqualTo(1)
        assertWithMessage("no red→wirld").that(bigram("red", "wirld")).isEqualTo(0)
        assertWithMessage("no wirld→wield").that(bigram("wirld", "wield")).isEqualTo(0)
        assertWithMessage("no red→world").that(bigram("red", "world")).isEqualTo(0)
        assertWithMessage("wield→sword").that(bigram("wield", "sword")).isEqualTo(1)
        assertWithMessage("wirld's usage reversed").that(vocabulary.getWordUsage("wirld")).isNull()
    }

    /** F5: deleted letter by letter ("Backspace undoes swipe" off), then the field is left. */
    @Test
    fun aSwipedWordDeletedLetterByLetterIsRolledBackBeforeTheFieldIsLeft() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        backspaceChars("got ".length)
        handler.flushTypedWordOnFinishInput(ic)
        handler.onEditorSessionChanged()

        assertWithMessage("fix→got rolled back").that(bigram("fix", "got")).isEqualTo(0)
        assertWithMessage("got's usage reversed").that(vocabulary.getWordUsage("got")).isNull()
        assertWithMessage("got left the prediction context").that(tracker.getContextWords()).doesNotContain("got")
    }

    /** F5: Ctrl+Backspace (one editor edit), then the field is left; its alternates leave the bar. */
    @Test
    fun aSwipedWordRemovedWithCtrlBackspaceIsRolledBackAndStopsOwningTheBar() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        editor.append("set ")
        handler.updateContext("set")
        swipe("got", "hot", "for")
        editor.setLength(editor.length - "got ".length)
        cursorSync()

        assertWithMessage("set→got rolled back at the edit").that(bigram("set", "got")).isEqualTo(0)
        assertWithMessage("got's usage reversed").that(vocabulary.getWordUsage("got")).isNull()
        assertWithMessage("no swipe word left to replace or undo").that(tracker.getLastAutoInsertedWord()).isNull()
        // InputCoordinator keeps the bar while the last commit is a swipe: "got hot for" stayed.
        assertWithMessage("the bar no longer belongs to the swipe")
            .that(tracker.getLastCommitSource()).isNotEqualTo(PredictionSource.SWIPE)
    }

    /** F5: a partly deleted word that is retyped is learned again, once, at its commit. */
    @Test
    fun aPartlyDeletedWordThatIsRetypedIsLearnedOnce() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        backspaceChars(2)
        assertWithMessage("rolled back as soon as the word was shortened").that(bigram("fix", "got")).isEqualTo(0)
        type("t ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix got ")
        assertWithMessage("fix→got learned once").that(bigram("fix", "got")).isEqualTo(1)
        assertWithMessage("got counted once").that(vocabulary.getWordUsage("got")?.usageCount).isEqualTo(1)
    }

    /** F5 guard: moving the cursor into the word and back is not an edit. */
    @Test
    fun aCursorMoveIntoTheNewestLearnedWordDoesNotRollItBack() {
        val vocabulary = useReceiptVocabulary()
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        cursorAt = "fix go".length
        cursorSync()
        cursorAt = "fix ".length
        cursorSync()
        cursorAt = null
        cursorSync()

        assertWithMessage("fix→got kept").that(bigram("fix", "got")).isEqualTo(1)
        assertWithMessage("got's usage kept").that(vocabulary.getWordUsage("got")?.usageCount).isEqualTo(1)
    }

    /** Backspace whose selection update arrives before the 100 ms debounced cursor sync runs. */
    private fun backspaceBeforeTheCursorSync(count: Int) {
        repeat(count) {
            val c = cursor()
            editor.deleteCharAt(c - 1)
            cursorAt = cursorAt?.minus(1)
            handler.handleBackspace(ic, textField())
            handler.onUnownedSelectionUpdate(c, c, c - 1, c - 1, ic)
            reportedCaret = c - 1
        }
    }

    /** Fable review of 91307aab4, finding 3: `got` → `go` → `t` + space before the sync. */
    @Test
    fun aWordRetypedBeforeTheCursorSyncIsLearnedWhole() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        backspaceBeforeTheCursorSync(2)
        type("t ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix got ")
        assertWithMessage("fix→got learned again").that(bigram("fix", "got")).isEqualTo(1)
        assertWithMessage("no fragment t").that(vocabulary.getWordUsage("t")).isNull()
        assertWithMessage("no fix→t").that(bigram("fix", "t")).isEqualTo(0)
    }

    /** Finding 3: `got` → `go`, then a space: the editor's word `go` is what is learned. */
    @Test
    fun aShortenedWordEndedWithASpaceIsLearnedAsTheEditorShowsIt() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        backspaceBeforeTheCursorSync(2)
        type(" ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix go ")
        assertWithMessage("fix→go").that(bigram("fix", "go")).isEqualTo(1)
        assertWithMessage("go counted").that(vocabulary.getWordUsage("go")?.usageCount).isEqualTo(1)
        assertWithMessage("got not").that(vocabulary.getWordUsage("got")).isNull()
    }

    /**
     * Finding 4: an editor that answers "" after the cursor everywhere makes a caret move into
     * the word look like a deletion. An empty after-read is only trusted once the editor shows
     * the caret at the end of the field.
     */
    @Test
    fun aCaretMoveInAnEditorThatReadsNothingAfterTheCursorIsNotADeletion() {
        val vocabulary = useReceiptVocabulary()
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        cursorAt = "fix go".length
        cursorSync()

        assertWithMessage("fix→got kept").that(bigram("fix", "got")).isEqualTo(1)
        assertWithMessage("got's usage kept").that(vocabulary.getWordUsage("got")?.usageCount).isEqualTo(1)
        // A real deletion at the end of that field is still taken back.
        cursorAt = null
        backspaceChars(2)
        assertWithMessage("a deletion at the field end").that(bigram("fix", "got")).isEqualTo(0)
    }

    /**
     * F6 (Saga): a word disabled in Dictionary Manager was still swiped in, because the disabled
     * sets reloaded only at the next tap prediction. The preference change reloads both
     * languages' sets at once, and [Predictor.isWordDisabled] with a language reads them.
     */
    @Test
    fun aDisabledWordsChangeReachesBothLanguagesAtOnce() {
        val ctx = mockk<android.content.Context>(relaxed = true)
        predictor.setField("context", ctx)
        val stored = mutableMapOf<String, Set<String>>()
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getStringSet(any(), any()) } answers {
            (stored[firstArg<String>()] ?: secondArg<Set<String>?>())?.let { HashSet(it) }
        }
        every { prefs.getString(any(), any()) } answers { secondArg() }
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs
        predictor.setField("bigramModel", mockk<BigramModel>(relaxed = true))
        predictor.setField("dictionaryObserver", mockk<UserDictionaryObserver>(relaxed = true))
        predictor.setLanguage("en")
        predictor.setField("secondaryLanguageCode", "es")
        predictor.setField("customAndUserWords", emptySet<String>())
        // The harness stubs isWordDisabled to false for the other tests; this one reads the sets.
        every { predictor.isWordDisabled(any()) } answers { callOriginal() }
        assertWithMessage("not yet").that(predictor.isWordDisabled("you")).isFalse()

        stored[LanguagePreferenceKeys.disabledWordsKey("en")] = setOf("you")
        stored[LanguagePreferenceKeys.disabledWordsKey("es")] = setOf("que")
        predictor.reloadDisabledWords()

        assertWithMessage("the primary's list").that(predictor.isWordDisabled("you")).isTrue()
        assertWithMessage("the primary's list, by language").that(predictor.isWordDisabled("you", "en")).isTrue()
        assertWithMessage("the secondary's list").that(predictor.isWordDisabled("que", "es")).isTrue()
        assertWithMessage("not the primary's").that(predictor.isWordDisabled("que", "en")).isFalse()
    }

    // ---------------------------------------------------- Fable audit A of today's fixes (2026-11-26)

    /** A selection the user deletes or types over: the editor collapses it, then reports it. */
    private fun replaceSelection(start: Int, end: Int, typed: String = "") {
        editor.replace(start, end, typed)
        cursorAt = start + typed.length
        if (typed.isNotEmpty()) handler.handleRegularTyping(typed, ic, textField())
        // CleverKeysService.onUpdateSelection with the editor's own numbers, e.g. (4,7)→(4,4).
        handler.onUnownedSelectionUpdate(start, end, cursor(), cursor(), ic)
        reportedCaret = cursor()
        tracker.synchronizeWithCursor(ic, "en", textField())
    }

    /** A1: the app restarts input (rotation, WebView) between the swipe and its deletion. */
    @Test
    fun anInputRestartKeepsTheDeletionRollbackWorking() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        handler.onEditorSessionChanged()
        handler.onInputFieldStarted(textField(), restarting = true)
        backspaceChars("got ".length)
        newSwipe("git")

        assertOnlyTheCorrectionLearned("fix", "got", "git")
        assertWithMessage("no fix got git trigram").that(trigram("fix", "got", "git")).isEqualTo(0f)
    }

    /** A1: a dictation write between two typed words; the second does not pair with the first. */
    @Test
    fun aDictationWriteBetweenTwoWordsClosesTheLearnWindow() {
        type("fix ")
        editor.append("the build is green ")
        handler.onEditorSessionChanged() // CleverKeysService onExternalEdit
        type("today ")

        assertWithMessage("no fix→today across the dictated text").that(bigram("fix", "today")).isEqualTo(0)
    }

    /** A2: the swiped word selected and deleted ((4,7)→(4,4)); the space after it stays. */
    @Test
    fun aSelectedAndDeletedWordIsRolledBackAtTheEdit() {
        val vocabulary = useReceiptVocabulary()
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        replaceSelection("fix ".length, "fix got".length)

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix  ")
        assertWithMessage("fix→got rolled back").that(bigram("fix", "got")).isEqualTo(0)
        assertWithMessage("got's usage reversed").that(vocabulary.getWordUsage("got")).isNull()
    }

    /** A2: the swiped word selected and typed over, then completed: only the correction is learned. */
    @Test
    fun aSelectedWordTypedOverLearnsOnlyTheNewWord() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        replaceSelection("fix ".length, "fix got".length, "g")
        type("it ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix git  ")
        assertOnlyTheCorrectionLearned("fix", "got", "git")
    }

    /** A3: the field's first word deleted with the keyboard's Backspace is taken back. */
    @Test
    fun theFieldsFirstWordDeletedWithBackspaceIsRolledBack() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        swipe("got", "git")
        assertWithMessage("editor").that(editor.toString()).isEqualTo("got ")
        backspaceChars("got ".length)

        assertWithMessage("got's usage reversed").that(vocabulary.getWordUsage("got")).isNull()
    }

    /** A4: the receipt expired (a store was reset) while the proof holds: no wrong→new pair. */
    @Test
    fun aRefusedRollbackStillKeepsTheRemovedWordOutOfThePairs() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        trigramStore.clearAll()
        backspaceChars("got ".length)
        newSwipe("git")

        assertWithMessage("no got→git").that(bigram("got", "git")).isEqualTo(0)
        assertWithMessage("got left the prediction context").that(tracker.getContextWords()).doesNotContain("got")
    }

    /** A5: sentence punctuation ended the receipt; the word is then deleted by hand. */
    @Test
    fun aWordDeletedAfterItsSentenceEndedIsRolledBack() {
        config.backspace_undo_swipe = false
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        type(".")
        backspaceChars(editor.length - "fix ".length)

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix ")
        assertWithMessage("fix→got rolled back").that(bigram("fix", "got")).isEqualTo(0)
    }

    /**
     * The debounced cursor sync as InputCoordinator.onCursorMoved runs it: a word at the caret
     * gets its predictions; an empty prefix gets the park pass, EXCEPT while the last commit is a
     * swipe or an autocorrect undo is offered: then the bar is kept for their alternates.
     */
    private fun inputCoordinatorSync(field: EditorInfo = textField()) {
        val old = if (reportedCaret >= 0) reportedCaret else cursor()
        handler.onUnownedSelectionUpdate(old, old, cursor(), cursor(), ic)
        reportedCaret = cursor()
        tracker.onCursorPositionChanged(cursor())
        tracker.synchronizeWithCursor(ic, "en", field)
        when {
            tracker.getCurrentWord().isNotEmpty() -> handler.handleCursorSyncPrediction(ic, field)
            tracker.getLastAutocorrectOriginalWord() == null &&
                tracker.getLastCommitSource() != PredictionSource.SWIPE -> handler.handleCursorParkPrediction(field, ic)
            else -> Unit // the bar is kept
        }
    }

    /**
     * Note10+ r21 case 7 (2026-11-26): swipe `fix got something`, put the caret at the start of
     * `got`, tap a bar entry. The bar still held the LAST swipe's alternates (the empty prefix at
     * a word start keeps the bar while the last commit is a swipe), and tapping `someone` gave
     * `fix someone something`: a stale alternate replaced the word under the caret.
     */
    @Test
    fun aBarTapAfterTheCaretMovedToAnotherWordNeverUsesTheLastSwipesAlternates() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        newSwipe("something", "somethin", "someone")
        assertWithMessage("precondition: the swipe's alternates").that(barWords).contains("someone")

        cursorAt = "fix ".length
        inputCoordinatorSync()
        assertWithMessage("the last swipe's alternates left the bar").that(barWords).doesNotContain("someone")
        assertWithMessage("no REPLACE target left for a later tap")
            .that(tracker.getLastAutoInsertedWord()).isNull()
    }

    /** Case 7, the race: the tap arrives before the caret's selection update. */
    @Test
    fun aTapOnTheLastSwipesAlternateWithTheCaretElsewhereIsRefused() {
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git")
        newSwipe("something", "somethin", "someone")
        cursorAt = "fix ".length
        tracker.synchronizeWithCursor(ic, "en", textField())

        tap("someone")

        assertWithMessage("nothing replaced").that(editor.toString()).isEqualTo("fix got something ")
        assertWithMessage("got keeps its pair").that(bigram("fix", "got")).isEqualTo(1)
    }

    // ---------------------------------------------------- Pixel r22 R3 (2026-11-27): the real lifecycle

    /** The production PredictionCoordinator's flush, over this harness's predictor. */
    private fun realCoordinator(): PredictionCoordinator =
        objenesis.newInstance(PredictionCoordinator::class.java).also {
            it.setField("wordPredictor", predictor)
            it.setField("adaptationManager", adaptation)
        }

    /**
     * CleverKeysService.onFinishInputView then onStartInputView, in the service's order: what a
     * rotation (the activity is recreated) and Back + a tap on the field (hide, show) both run.
     */
    private fun hideAndShowKeyboard(
        field: EditorInfo = textField(), restarting: Boolean = false, newInput: Boolean = false,
    ) {
        val coordinator = realCoordinator()
        // onFinishInputView
        handler.onEditorSessionChanged()
        handler.flushTypedWordOnFinishInput(ic)
        tracker.clearAll()
        coordinator.flushLearnedData()
        // onStartInput: another editor was bound (a new tab, another field, a recreated window), or
        // the IME's own configuration change restarted the input (a rotation). Back + a tap on the
        // same field has none.
        if (newInput) handler.onInputStarted(restarting = false)
        if (restarting) handler.onInputStarted(restarting = true)
        // onStartInputView, with the editor as it now reads
        handler.onEditorSessionChanged()
        handler.onInputFieldStarted(field, restarting, ic)
        handler.setFieldPersonalizedLearningAllowed(LearningGate.fieldAllowsPersonalizedLearning(field.imeOptions))
    }

    /** A WebView restarting its input (no onFinishInputView): onStartInput + onStartInputView, restarting. */
    private fun webViewRestart(field: EditorInfo) {
        handler.onInputStarted(restarting = true)
        handler.onEditorSessionChanged()
        handler.onInputFieldStarted(field, restarting = true, ic)
    }

    /**
     * Pixel r22 R3: `fix ` + swipe `toy`, rotate (or Back, tap the field), four Backspaces, swipe
     * `try`. The deletion was seen ("LEARN REMOVED") but `fix → toy` and toy's +1 stayed, and no
     * `fix → try` was learned: the hide's flushLearnedData cleared the learn window, which took
     * the receipt and the one-slot record with it, so the rollback undid nothing and then closed
     * the window. 23622f144's test modelled the restart without onFinishInputView.
     */
    @Test
    fun aDeletionAfterTheKeyboardWasHiddenAndShownIsRolledBack() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        handler.onInputFieldStarted(textField(), restarting = false)
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("toy", "try")
        hideAndShowKeyboard()
        backspaceChars("toy ".length)
        newSwipe("try")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix try ")
        assertWithMessage("fix→toy reversed").that(bigram("fix", "toy")).isEqualTo(0)
        assertWithMessage("toy's +1 reversed").that(vocabulary.getWordUsage("toy")).isNull()
        assertWithMessage("fix→try learned").that(bigram("fix", "try")).isEqualTo(1)
    }

    /** Saga r22 R3: Chrome restarts its input on a rotation; the same deletion after it. */
    @Test
    fun aDeletionAfterARotationThatRestartsTheInputIsRolledBack() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        editor.append("fix ")
        handler.updateContext("fix")
        swipe("got", "git", field = chromeField())
        hideAndShowKeyboard(field = chromeField(), restarting = true)
        backspaceChars("got ".length, field = chromeField())
        newSwipe("something")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix something ")
        assertWithMessage("fix→got reversed").that(bigram("fix", "got")).isEqualTo(0)
        assertWithMessage("got's +1 reversed").that(vocabulary.getWordUsage("got")).isNull()
        assertWithMessage("fix→something learned").that(bigram("fix", "something")).isEqualTo(1)
    }

    /** R3 guard: another field still starts a new learn window (H1: no pair across fields). */
    @Test
    fun aNewFieldStillStartsANewLearnWindow() {
        handler.onInputFieldStarted(textField(), restarting = false)
        type("fix ")
        hideAndShowKeyboard(field = chromeField())
        editor.setLength(0)
        type("today ")

        assertWithMessage("no fix→today across fields").that(bigram("fix", "today")).isEqualTo(0)
    }

    /** R3 guard: two fields that look alike (WebView textareas share an id): no pair across them. */
    @Test
    fun aLookAlikeFieldDoesNotPairWithThePreviousOne() {
        handler.onInputFieldStarted(textField(), restarting = false)
        type("fix ")
        editor.setLength(0)
        hideAndShowKeyboard(newInput = true)
        type("today ")

        assertWithMessage("no fix→today: today does not follow fix in this editor").that(bigram("fix", "today")).isEqualTo(0)
    }

    /**
     * Note10+ r22: after Spanish was detected, a brand-new Chrome tab predicted Spanish from its
     * first letter. Every Chrome textarea reports the same package / field id / input type, so
     * the new tab looked like the same field. An EMPTY editor at a (non-restart) start is a new
     * field.
     */
    @Test
    fun aNewEmptyChromeTextareaStartsInThePrimaryLanguage() {
        useRealLegacyDetector()
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        editor.append(spanish.joinToString(" ") + " ")
        spanish.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("Spanish detected").that(predictor.getField("currentLanguage")).isEqualTo("es")

        editor.setLength(0) // the new tab's textarea
        hideAndShowKeyboard(field = chromeField(), newInput = true)

        assertWithMessage("the new textarea starts in the primary").that(predictor.getField("currentLanguage")).isEqualTo("en")
    }

    /** The other direction: the same textarea, with its text, re-shown or restarted keeps Spanish. */
    @Test
    fun theSameChromeTextareaReshownOrRestartedKeepsTheDetectedLanguage() {
        useRealLegacyDetector()
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        editor.append(spanish.joinToString(" ") + " ")
        spanish.forEach { predictor.addWordToContext(it, true) }

        hideAndShowKeyboard(field = chromeField())
        assertWithMessage("re-shown").that(predictor.getField("currentLanguage")).isEqualTo("es")
        hideAndShowKeyboard(field = chromeField(), restarting = true)
        assertWithMessage("restarted").that(predictor.getField("currentLanguage")).isEqualTo("es")
        webViewRestart(chromeField())
        assertWithMessage("a WebView restart").that(predictor.getField("currentLanguage")).isEqualTo("es")
    }

    /**
     * Saga r22: type `so `, swipe `well`, Backspace (the #110 undo removes `well `), three more
     * Backspaces (the field is empty), swipe `fix` → `so → fix` was learned and `so` kept its +1.
     * After the undo the learn window ends with `so` again, but `so`'s place in the editor was no
     * longer watched, so deleting it went unseen. Chosen: its +1 is reversed too, because the
     * same editor proof that guards every other rollback shows it gone.
     */
    @Test
    fun aWordDeletedAfterASwipeUndoDoesNotPairWithTheNextOne() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = true
        type("so ")
        swipe("well", "will")
        // KeyEventHandler.handleBackspaceUndoSwipe: deletes "well " and reports it; handle_backspace follows.
        val c = cursor()
        editor.setLength(editor.length - "well ".length)
        handler.onSwipeWordUndone("well", ic)
        handler.handleBackspace(ic, textField())
        handler.onUnownedSelectionUpdate(c, c, cursor(), cursor(), ic)
        reportedCaret = cursor()
        backspaceChars("so ".length)
        newSwipe("fix")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("fix ")
        assertWithMessage("no so→fix").that(bigram("so", "fix")).isEqualTo(0)
        assertWithMessage("so's +1 reversed").that(vocabulary.getWordUsage("so")).isNull()
        assertWithMessage("well's +1 reversed").that(vocabulary.getWordUsage("well")).isNull()
    }

    /**
     * A start whose EditorInfo puts the caret inside text is the same field even when the
     * connection reads back empty at that moment (a WebView that has not synced its text yet):
     * only an empty field with the caret at its start counts as a new one.
     */
    @Test
    fun aStartWithTheCaretInsideTextIsNotANewFieldEvenIfTheReadsAreEmpty() {
        useRealLegacyDetector()
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        editor.append(spanish.joinToString(" ") + " ")
        spanish.forEach { predictor.addWordToContext(it, true) }
        val caret = editor.length

        editor.setLength(0) // the connection's reads, not the field
        hideAndShowKeyboard(field = chromeField().apply { initialSelStart = caret; initialSelEnd = caret }, newInput = true)

        assertWithMessage("kept").that(predictor.getField("currentLanguage")).isEqualTo("es")
    }

    /**
     * Pixel r22 (b): `fix ` + swipe `toy`, then Backspace past `toy ` into `fix` (`fi` left) and
     * type `lip`: `fix → filip` was learned. Once `toy` was rolled back the learn window ended
     * with `fix` again, but nothing watched `fix`'s place, so shortening it went unseen.
     */
    @Test
    fun deletingIntoThePreviousWordTakesItsLearningBackToo() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        type("fix ")
        swipe("toy", "try")
        backspaceChars("toy ".length + "x ".length)
        type("lip ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("filip ")
        assertWithMessage("fix→toy reversed").that(bigram("fix", "toy")).isEqualTo(0)
        assertWithMessage("no fix→filip").that(bigram("fix", "filip")).isEqualTo(0)
        assertWithMessage("fix's +1 reversed").that(vocabulary.getWordUsage("fix")).isNull()
        assertWithMessage("toy's +1 reversed").that(vocabulary.getWordUsage("toy")).isNull()
    }

    // ------------------------------------------- Fable review 2026-11-27 of 8d42e6fbc / b2e554f8c

    /**
     * #1 (HIGH, privacy): Chrome tab A `my diagnosis is`, hide; tab B (the same package / field id
     * / input type) holds a draft ending in `is`. `great` learned `diagnosis is great`: the only
     * check left was that the new text ends with the window's last word.
     */
    @Test
    fun aLookAlikeFieldEndingInTheSameWordDoesNotContinueTheWindow() {
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        type("my diagnosis is ", chromeField())
        editor.setLength(0)
        editor.append("draft is ")
        hideAndShowKeyboard(field = chromeField().apply { initialSelStart = 9; initialSelEnd = 9 }, newInput = true)
        type("great ", chromeField())

        assertWithMessage("no diagnosis is great").that(trigram("diagnosis", "is", "great")).isEqualTo(0f)
        assertWithMessage("no is→great from tab A's is").that(bigram("is", "great")).isEqualTo(0)
    }

    /** #1: the same, when the new editor cannot be read: nothing proves the window is its own. */
    @Test
    fun anUnreadableLookAlikeFieldDoesNotContinueTheWindow() {
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        type("my diagnosis is ", chromeField())
        editor.setLength(0)
        editor.append("draft is ")
        every { ic.getTextBeforeCursor(any(), any()) } returns null
        every { ic.getExtractedText(any(), any()) } returns null
        hideAndShowKeyboard(field = chromeField(), newInput = true)
        type("great ", chromeField())

        assertWithMessage("no diagnosis is great").that(trigram("diagnosis", "is", "great")).isEqualTo(0f)
        assertWithMessage("no is→great").that(bigram("is", "great")).isEqualTo(0)
    }

    /**
     * #1: an incognito word never enters the learn window, so it cannot become the context of a
     * word typed in a normal tab that looks the same (`IME_FLAG_NO_PERSONALIZED_LEARNING` was
     * not part of the field identity either).
     */
    @Test
    fun anIncognitoWordNeverBecomesContextInANormalTab() {
        val incognito = chromeField().apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }
        handler.onInputFieldStarted(incognito, restarting = false, ic)
        handler.setFieldPersonalizedLearningAllowed(false)
        type("my secret ", incognito)
        assertWithMessage("no incognito word in the learn window").that(learnWindow()).isEmpty()

        editor.setLength(0)
        editor.append("notes my secret ")
        hideAndShowKeyboard(field = chromeField().apply { initialSelStart = 16; initialSelEnd = 16 })
        type("plan ", chromeField())

        assertWithMessage("no secret→plan").that(bigram("secret", "plan")).isEqualTo(0)
        assertWithMessage("no my secret plan").that(trigram("my", "secret", "plan")).isEqualTo(0f)
    }

    /** #1: an incognito tab's detected language does not carry into a normal tab that looks the same. */
    @Test
    fun anIncognitoTabsDetectedLanguageDoesNotCarryIntoANormalTab() {
        useRealLegacyDetector()
        val incognito = chromeField().apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }
        handler.onInputFieldStarted(incognito, restarting = false, ic)
        handler.setFieldPersonalizedLearningAllowed(false)
        editor.append(spanish.joinToString(" ") + " ")
        spanish.forEach { predictor.addWordToContext(it, false) }
        assertWithMessage("Spanish detected").that(predictor.getField("currentLanguage")).isEqualTo("es")

        editor.setLength(0)
        editor.append("hello there ")
        hideAndShowKeyboard(field = chromeField().apply { initialSelStart = 12; initialSelEnd = 12 }, newInput = true)

        assertWithMessage("the normal tab starts in the primary").that(predictor.getField("currentLanguage")).isEqualTo("en")
    }

    /** #2: learning off, `my pin is`, learning turned on, `long`: `pin is long` was stored. */
    @Test
    fun turningLearningOnDoesNotPairWithWordsTypedWhileItWasOff() {
        config.on_device_learning_enabled = false
        type("my pin is ")
        config.on_device_learning_enabled = true
        type("long ")

        assertWithMessage("no pin is long").that(trigram("pin", "is", "long")).isEqualTo(0f)
        assertWithMessage("no is→long").that(bigram("is", "long")).isEqualTo(0)
    }

    /** #2, reverse: deleting a word committed while learning was off decrements nothing. */
    @Test
    fun deletingAWordTypedWhileLearningWasOffTakesNothingBack() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        type("my word ")
        config.on_device_learning_enabled = false
        type("my word ")
        config.on_device_learning_enabled = true
        reportedCaret = cursor()
        backspaceChars("word ".length)
        type("x ")

        assertWithMessage("my→word kept").that(bigram("my", "word")).isEqualTo(1)
        assertWithMessage("word's count kept").that(vocabulary.getWordUsage("word")?.usageCount).isEqualTo(1)
    }

    /**
     * #4: a restored previous placement kept `editChecksOff` from a caret move made while it was
     * the newest word, so deleting into `fix` went unseen again and the commit-time check closed
     * the window: `a` → `filip` was not learned.
     */
    @Test
    fun aRestoredPreviousWordIsWatchedEvenAfterTheCaretLeftItOnce() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        type("a fix ")
        reportedCaret = cursor()
        cursorAt = 1
        cursorSync() // the caret into `a`: fix's region is left once
        cursorAt = null
        cursorSync() // and back to the end
        swipe("toy", "try")
        backspaceChars("toy ".length + "x ".length)
        type("lip ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("a filip ")
        assertWithMessage("no fix→filip").that(bigram("fix", "filip")).isEqualTo(0)
        assertWithMessage("fix's +1 reversed").that(vocabulary.getWordUsage("fix")).isNull()
        assertWithMessage("a→filip learned").that(bigram("a", "filip")).isEqualTo(1)
    }

    /** #5: `a fix toy`, delete to `a fi`, retype `x `: `a → fix` was reversed and never relearned. */
    @Test
    fun retypingThePreviousWordRelearnsItsPair() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        type("a fix ")
        swipe("toy", "try")
        backspaceChars("toy ".length + "x ".length)
        type("x ")

        assertWithMessage("editor").that(editor.toString()).isEqualTo("a fix ")
        assertWithMessage("fix→toy reversed").that(bigram("fix", "toy")).isEqualTo(0)
        assertWithMessage("toy's +1 reversed").that(vocabulary.getWordUsage("toy")).isNull()
        assertWithMessage("a→fix relearned").that(bigram("a", "fix")).isEqualTo(1)
        assertWithMessage("fix counted once").that(vocabulary.getWordUsage("fix")?.usageCount).isEqualTo(1)
    }

    /** #5: after `fix toy.` (the sentence ended), deleting back through `fix` kept fix's +1. */
    @Test
    fun deletingBackThroughAnEndedSentenceTakesBothWordsBack() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        type("fix ")
        swipe("toy", "try")
        type(".")
        reportedCaret = cursor()
        backspaceChars(editor.length)

        assertWithMessage("editor").that(editor.toString()).isEmpty()
        assertWithMessage("fix→toy reversed").that(bigram("fix", "toy")).isEqualTo(0)
        assertWithMessage("toy's +1 reversed").that(vocabulary.getWordUsage("toy")).isNull()
        assertWithMessage("fix's +1 reversed").that(vocabulary.getWordUsage("fix")).isNull()
    }

    /** Fable #7: a WebView input restart (no onFinishInputView) keeps the deletion rollback working. */
    @Test
    fun aDeletionAfterAWebViewInputRestartIsRolledBack() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        type("fix ", chromeField())
        swipe("got", "git", field = chromeField())
        webViewRestart(chromeField())
        backspaceChars("got ".length, field = chromeField())
        newSwipe("something")

        assertWithMessage("fix→got reversed").that(bigram("fix", "got")).isEqualTo(0)
        assertWithMessage("got's +1 reversed").that(vocabulary.getWordUsage("got")).isNull()
        assertWithMessage("fix→something learned").that(bigram("fix", "something")).isEqualTo(1)
    }

    // ------------------------------------------------- Fable re-check 2026-11-27 of 75674eb83

    /** A Chrome `<input type=text>`: the same package and field id as a textarea, another input type. */
    private fun chromeTextInput(): EditorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
        packageName = "com.android.chrome"
        inputType = InputType.TYPE_CLASS_TEXT
    }

    /**
     * #1: Chrome (and Compose, Flutter) move between the fields of one host view with
     * `imm.restartInput`, which arrives as a restart. Textarea A `my diagnosis is`, then the
     * page's second textarea, ending in `is`: `great` learned `diagnosis is great`.
     */
    @Test
    fun aRestartIntoAnotherTextareaEndingInTheSameWordDoesNotContinueTheWindow() {
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        type("my diagnosis is ", chromeField())
        editor.setLength(0)
        editor.append("draft is ")
        webViewRestart(chromeField())
        type("great ", chromeField())

        assertWithMessage("no diagnosis is great").that(trigram("diagnosis", "is", "great")).isEqualTo(0f)
        assertWithMessage("no is→great").that(bigram("is", "great")).isEqualTo(0)
    }

    /** #1: the same restart into a textarea that cannot be read. */
    @Test
    fun aRestartIntoAnUnreadableTextareaDoesNotContinueTheWindow() {
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        type("my diagnosis is ", chromeField())
        editor.setLength(0)
        editor.append("draft is ")
        every { ic.getTextBeforeCursor(any(), any()) } returns null
        every { ic.getExtractedText(any(), any()) } returns null
        webViewRestart(chromeField())
        type("great ", chromeField())

        assertWithMessage("no diagnosis is great").that(trigram("diagnosis", "is", "great")).isEqualTo(0f)
        assertWithMessage("no is→great").that(bigram("is", "great")).isEqualTo(0)
    }

    /** #1: a restart into another kind of field (a text input after a textarea) is a new field. */
    @Test
    fun aRestartIntoAnotherKindOfFieldIsANewField() {
        useRealLegacyDetector()
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        editor.append(spanish.joinToString(" ") + " ")
        spanish.forEach { predictor.addWordToContext(it, true) }
        assertWithMessage("Spanish detected").that(predictor.getField("currentLanguage")).isEqualTo("es")

        editor.setLength(0)
        editor.append("hello there ")
        webViewRestart(chromeTextInput())

        assertWithMessage("the text input starts in the primary").that(predictor.getField("currentLanguage")).isEqualTo("en")
        assertWithMessage("its learn window is empty").that(learnWindow()).isEmpty()
    }

    /**
     * #2: a failed field proof closed the window but kept both commit records from field A, so
     * B's first commit moved A's `is` record to the prior slot and rolling B's word back revived
     * it: a rollback of `is` then took A's `diagnosis → is` back.
     */
    @Test
    fun aFailedFieldProofDropsTheOldFieldsCommitRecords() {
        val vocabulary = useReceiptVocabulary()
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        type("my diagnosis is ", chromeField())
        editor.setLength(0)
        editor.append("draft is ")
        hideAndShowKeyboard(field = chromeField().apply { initialSelStart = 9; initialSelEnd = 9 }, newInput = true)
        type("great ", chromeField())
        predictor.rollbackCommittedWord("great", true)
        predictor.rollbackCommittedWord("is", true)

        assertWithMessage("A's diagnosis→is kept").that(bigram("diagnosis", "is")).isEqualTo(1)
        assertWithMessage("A's is kept its count").that(vocabulary.getWordUsage("is")?.usageCount).isEqualTo(1)
    }

    /**
     * #3: in a look-alike field, a keyboard deletion that empties it took back field A's first
     * word: the blank-anchor path for the keyboard's own deletions runs before any field proof.
     */
    @Test
    fun aKeyboardDeletionInALookAlikeFieldDoesNotUnlearnTheOldFieldsFirstWord() {
        val vocabulary = useReceiptVocabulary()
        config.backspace_undo_swipe = false
        handler.onInputFieldStarted(chromeField(), restarting = false, ic)
        type("fix ", chromeField())
        editor.setLength(0)
        editor.append("fix ")
        hideAndShowKeyboard(field = chromeField().apply { initialSelStart = 4; initialSelEnd = 4 }, newInput = true)
        reportedCaret = cursor()
        backspaceChars("x ".length, field = chromeField())

        assertWithMessage("A's fix kept its count").that(vocabulary.getWordUsage("fix")?.usageCount).isEqualTo(1)
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
