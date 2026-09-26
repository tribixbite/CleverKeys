package tribixbite.cleverkeys

import android.content.res.Resources
import android.os.Handler
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Next-word's two tiers, end to end (maintainer decision 2026-09-26): the STATIC tier — the
 * shipped English context LM — must work with on-device learning OFF; the LEARNED tier still
 * needs the master gate, the context-aware pref and a field that allows personalized learning.
 *
 * # Harness (same shape as [LearningFunnelBookkeepingTest])
 *
 * - [SuggestionHandler] — Objenesis-allocated, the production tap/park/next-word bodies. Its
 *   prediction executor and main-thread handler are replaced by mocks that run the posted
 *   work INLINE, so the whole next-word pass completes inside the call.
 * - [WordPredictor] — Objenesis-allocated with a real [ContextModel] over real n-gram stores
 *   and a real [BigramModel] carrying the SHIPPED `src/main/assets/lm/en.cklm`. Wrapped in a
 *   `spyk` so the test can prove which read paths ran (the learned ones must not, with
 *   learning off) and to answer dictionary membership without loading a dictionary.
 * - The expected static continuations are read from the same [BigramModel], so the test does
 *   not hard-code values that move with every LM rebuild.
 */
class NextWordStaticTierTest {

    private val objenesis = ObjenesisStd()
    private val scheduler = ScheduledThreadPoolExecutor(1)

    private lateinit var config: Config
    private lateinit var tracker: PredictionContextTracker
    private lateinit var bigramStore: BigramStore
    private lateinit var contextModel: ContextModel
    private lateinit var staticModel: BigramModel
    private lateinit var predictor: WordPredictor
    private lateinit var personalization: PersonalizationEngine
    private lateinit var adaptation: UserAdaptationManager
    private lateinit var bar: SuggestionBar
    private lateinit var resources: Resources
    private lateinit var handler: SuggestionHandler

    /** The fake editor's content; the cursor is always at the end. */
    private val editor = StringBuilder()
    private lateinit var ic: InputConnection

    /** Words (and metas) the handler last pushed to the bar. */
    private var barWords: List<String> = emptyList()
    private var barMetas: List<SuggestionMeta> = emptyList()

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
        // The v2.0 fresh-install learning posture: learning OFF. Feature pref at its DEFAULT.
        config.on_device_learning_enabled = false
        config.context_aware_predictions_enabled = true
        config.personalized_learning_enabled = true
        config.next_word_prediction_enabled = Defaults.NEXT_WORD_PREDICTION_ENABLED
        config.word_prediction_enabled = true
        config.auto_space_after_suggestion = true
        config.auto_space_before_suggestion = true
        config.autocapitalize_i_words = true
        config.autocapitalisation = false
        config.autocorrect_enabled = false
        config.swipe_final_autocorrect_enabled = false
        config.swipe_context_rescoring = false
        config.swipe_on_password_fields = false
        // @JvmField strings are null on a relaxed mock; the tap path's cursor sync needs one.
        config.primary_language = "en"
        every { Config.globalConfig() } returns config

        tracker = PredictionContextTracker()

        bigramStore = BigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        val trigramStore = TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        contextModel = ContextModel(bigramStore, trigramStore, "en")
        staticModel = BigramModel().apply {
            installStaticLm("en", StaticContextLm.parse(File("src/main/assets/lm/en.cklm").readBytes()))
        }
        personalization = mockk(relaxed = true)
        val real = objenesis.newInstance(WordPredictor::class.java)
        real.setField("recentWords", mutableListOf<String>())
        real.setField("config", config)
        real.setField("contextModel", contextModel)
        real.setField("bigramModel", staticModel)
        real.setField("personalizationEngine", personalization)
        real.setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
        real.setField("languageDetector", null)
        real.setField("multiLanguageManager", null)
        predictor = spyk(real)
        every { predictor.isInDictionary(any()) } returns true
        every { predictor.isWordDisabled(any()) } returns false
        every { predictor.isInUserVocabulary(any()) } returns true
        every { predictor.reset() } just runs

        adaptation = mockk(relaxed = true)
        val dictionary = mockk<DictionaryManager>(relaxed = true)
        every { dictionary.getCurrentLanguage() } returns "en"
        val coordinator = mockk<PredictionCoordinator>(relaxed = true)
        every { coordinator.getWordPredictor() } returns predictor
        every { coordinator.getAdaptationManager() } returns adaptation
        every { coordinator.getDictionaryManager() } returns dictionary

        barWords = emptyList()
        barMetas = emptyList()
        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } answers {
            val i = barWords.indexOf(firstArg<String>())
            if (i >= 0) barMetas.getOrNull(i) else null
        }
        every { bar.setSuggestionsWithScores(any(), any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
            barMetas = thirdArg<List<SuggestionMeta>>().toList()
        }
        every { bar.clearSuggestions() } answers { barWords = emptyList(); barMetas = emptyList() }
        every { bar.getTopSuggestion() } answers { barWords.firstOrNull() }

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
        every { ic.getCursorCapsMode(any()) } returns 0

        val contractions = mockk<ContractionManager>(relaxed = true)
        every { contractions.isKnownContraction(any()) } returns false
        every { contractions.isContractionKey(any()) } returns false
        every { contractions.generatePossessive(any()) } returns null

        // Run the next-word pass inline: executor task, then its main-thread post.
        val tasks = mockk<PredictionTaskRunner>(relaxed = true)
        every { tasks.cancelAndSubmit(any()) } answers { firstArg<Runnable>().run() }
        val main = mockk<Handler>(relaxed = true)
        every { main.post(any()) } answers { firstArg<Runnable>().run(); true }

        handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contextTracker", tracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("contractionManager", contractions)
        handler.setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        handler.setField("predictionTasks", tasks)
        handler.setField("mainHandler", main)
        handler.setField("fieldAllowsPersonalizedLearning", true)
    }

    @After
    fun teardown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
        unmockkAll()
    }

    // ------------------------------------------------------------------ fixtures

    private fun textField(pkg: String = "com.example.notes"): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = pkg
            inputType = InputType.TYPE_CLASS_TEXT
        }

    /** A bar tap (SuggestionBridge passes isManualSelection = true). */
    private fun tap(word: String, field: EditorInfo = textField()) {
        handler.onSuggestionSelected(word, ic, field, resources, isManualSelection = true)
    }

    /** What the shipped model says follows [prev], as next-word would show it (≤ 3, not [prev]). */
    private fun shippedContinuations(prev: String): List<String> =
        staticModel.getPredictions(prev, NextWordPredictor.MAX_SUGGESTIONS)
            .map { it.word.lowercase() }
            .filter { it != prev }

    private fun learnedReadsNeverRan() {
        verify(exactly = 0) { predictor.getNextWordCandidates(any(), any()) }
        verify(exactly = 0) { predictor.getPersonalizationBoostFor(any()) }
        verify(exactly = 0) { predictor.isInUserVocabulary(any()) }
    }

    /** Teach the learned store "want → pizza" well past the confidence floors. */
    private fun seedLearnedPizza() {
        repeat(5) { contextModel.recordSequence(listOf("want", "pizza")) }
    }

    // ============================================================ default + static tier

    @Test
    fun nextWordDefaultsOn() {
        assertWithMessage("Defaults.NEXT_WORD_PREDICTION_ENABLED")
            .that(Defaults.NEXT_WORD_PREDICTION_ENABLED).isTrue()
        // The Config field initialiser and the Settings screen's initial state must come from
        // the same constant (Config's constructor needs Android prefs, so pin the source).
        assertWithMessage("Config field initialiser")
            .that(File("src/main/kotlin/tribixbite/cleverkeys/Config.kt").readText())
            .contains("var next_word_prediction_enabled = Defaults.NEXT_WORD_PREDICTION_ENABLED")
        assertWithMessage("SettingsActivity initial state")
            .that(File("src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt").readText())
            .contains("nextWordPredictionEnabled by mutableStateOf(Defaults.NEXT_WORD_PREDICTION_ENABLED)")
    }

    @Test
    fun staticTierShowsWithLearningOff() {
        val expected = shippedContinuations("want")
        assertWithMessage("the shipped LM has continuations for 'want'").that(expected).isNotEmpty()

        tap("want")

        assertWithMessage("next-word bar with on-device learning OFF")
            .that(barWords).containsExactlyElementsIn(expected).inOrder()
        assertWithMessage("every entry is tagged NEXT_WORD so a tap appends")
            .that(barMetas.map { it.origin }.toSet()).containsExactly(SuggestionOrigin.NEXT_WORD)
        assertWithMessage("the provenance says built-in, not learned")
            .that(barMetas.all { (it.note as? ProvenanceNote.NextWord)?.fromStaticSeed == true }).isTrue()
        learnedReadsNeverRan()
    }

    @Test
    fun learnedTierIsNotReadWithLearningOffEvenWhenItHoldsData() {
        seedLearnedPizza()

        tap("want")

        assertWithMessage("a learned continuation must not surface with learning OFF")
            .that(barWords).doesNotContain("pizza")
        assertWithMessage("the static tier still shows").that(barWords).isNotEmpty()
        learnedReadsNeverRan()
    }

    @Test
    fun learnedTierLeadsWhenLearningIsOn() {
        config.on_device_learning_enabled = true
        seedLearnedPizza()

        tap("want")

        assertWithMessage("learned evidence ranks first, the shipped tier fills the rest")
            .that(barWords.firstOrNull()).isEqualTo("pizza")
        assertWithMessage("static fill after the learned entry")
            .that(barWords.drop(1)).isNotEmpty()
        verify(atLeast = 1) { predictor.getNextWordCandidates(any(), any()) }
    }

    @Test
    fun contextAwareOffClosesOnlyTheLearnedTier() {
        // `context_aware_predictions_enabled` is the LEARNED n-gram pref ("Learn from typing
        // patterns"); it must not take the shipped tier with it.
        config.on_device_learning_enabled = true
        config.context_aware_predictions_enabled = false
        seedLearnedPizza()

        tap("want")

        assertWithMessage("static tier with context-aware off")
            .that(barWords).containsExactlyElementsIn(shippedContinuations("want")).inOrder()
        learnedReadsNeverRan()
    }

    // ============================================================ incognito

    @Test
    fun incognitoFieldGetsTheStaticTierButNeverTheLearnedOne() {
        // Decision: IME_FLAG_NO_PERSONALIZED_LEARNING forbids learning and personalization,
        // not generic suggestions. The shipped tier shows; the learned tier is not even read.
        config.on_device_learning_enabled = true
        handler.setField("fieldAllowsPersonalizedLearning", false)
        seedLearnedPizza()

        tap("want")

        assertWithMessage("incognito: shipped continuations only")
            .that(barWords).containsExactlyElementsIn(shippedContinuations("want")).inOrder()
        assertWithMessage("incognito: learned continuation absent").that(barWords).doesNotContain("pizza")
        learnedReadsNeverRan()
    }

    // ============================================================ exclusions

    @Test
    fun passwordFieldShowsNoNextWord() {
        handler.setField("isPasswordMode", true)
        tap("want")
        assertWithMessage("no next-word in a password field")
            .that(barWords.intersect(shippedContinuations("want").toSet())).isEmpty()
        verify(exactly = 0) { predictor.getStaticNextWordSeed(any(), any()) }
    }

    @Test
    fun termuxShowsNoNextWord() {
        tap("want", textField(pkg = "com.termux"))
        assertWithMessage("no next-word in Termux")
            .that(barWords.intersect(shippedContinuations("want").toSet())).isEmpty()
        verify(exactly = 0) { predictor.getStaticNextWordSeed(any(), any()) }
    }

    @Test
    fun explicitlyDisabledFeatureShowsNothing() {
        // An install with a stored `false` keeps it: the pref, not the default, decides.
        config.next_word_prediction_enabled = false
        tap("want")
        verify(exactly = 0) { predictor.getStaticNextWordSeed(any(), any()) }
    }

    // ============================================================ cursor park

    @Test
    fun cursorParkReadsTheEditorForTheStaticTierWithLearningOff() {
        editor.append("I really want ")

        handler.handleCursorParkPrediction(textField(), ic)

        assertWithMessage("park after 'want' predicts from the editor text")
            .that(barWords).containsExactlyElementsIn(shippedContinuations("want")).inOrder()
        learnedReadsNeverRan()
    }

    // ============================================================ acceptance

    @Test
    fun acceptingAStaticNextWordLearnsNothingWithTheGateOff() {
        tap("want")
        val next = barWords.first()

        tap(next)

        assertWithMessage("the accepted next-word was committed")
            .that(editor.toString()).isEqualTo("want $next ")
        assertWithMessage("no bigram recorded for want → $next")
            .that(bigramStore.getAllBigrams("en", "want")).isEmpty()
        assertWithMessage("n-gram store untouched").that(bigramStore.isDirty()).isFalse()
        verify(exactly = 0) { adaptation.recordSelection(any()) }
        verify(exactly = 0) { personalization.recordWordTyped(any(), any()) }
    }

    // ------------------------------------------------------------------ reflection

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = this.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                f.set(this, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }
}
