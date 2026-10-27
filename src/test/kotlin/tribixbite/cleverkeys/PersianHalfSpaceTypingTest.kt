package tribixbite.cleverkeys

import android.os.Handler
import android.os.Trace
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
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.autocorrect.AutocorrectMemo
import tribixbite.cleverkeys.autocorrect.FrequencyFloor
import tribixbite.cleverkeys.autocorrect.KeyAdjacency
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Typing Persian half-space words end to end (decisions (b) and (d), 2026-10-23; research
 * `docs/research/2026-10-21-persian-zwnj.md` §6–§7).
 *
 * Mock tier. REAL: [SuggestionHandler]'s typing and space bodies (Objenesis-allocated, wired like
 * [TypedBarHighlightHandlerTest]), [PredictionContextTracker], and a [WordPredictor] over a small
 * `fa` dictionary (harness of [WordPredictorHalfSpaceTest]); the editor is a StringBuilder,
 * prediction tasks run on demand and main-thread posts run inline.
 *
 * Pins: a half-space typed after an Arabic letter continues the composing word (the bar
 * completes `می‌ش` to `می‌شود`); joined typing is never rewritten on space and the bar offers
 * the half-space spelling without highlighting it; cursor-sync composes across a half-space,
 * including the just-typed edge, but not across a Latin or Sinhala ZWNJ; the learn funnel trims
 * a dangling half-space; the owned-suffix boundary sees a half-space word as one word.
 */
class PersianHalfSpaceTypingTest {

    private val objenesis = ObjenesisStd()
    private val z = "‌"
    private val miShavad = "می${z}شود"

    private lateinit var config: Config
    private lateinit var tracker: PredictionContextTracker
    private lateinit var predictor: WordPredictor
    private lateinit var bar: SuggestionBar
    private lateinit var ic: InputConnection
    private lateinit var handler: SuggestionHandler

    private val editor = StringBuilder()
    private var afterCursor = ""
    private val pendingTasks = ArrayList<Runnable>()

    /** Each bar post: words and the highlighted index. */
    private val posts = ArrayList<Pair<List<String>, Int?>>()

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
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just runs
        every { Trace.endSection() } just runs
        KeyAdjacency.resetLayout()

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.word_prediction_enabled = true
        config.autocorrect_enabled = true
        config.autocapitalize_i_words = true
        config.show_exact_typed_word = false
        config.next_word_prediction_enabled = false
        config.on_device_learning_enabled = false
        config.custom_terminal_packages = emptySet()
        config.primary_language = "fa"
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config

        tracker = PredictionContextTracker()
        predictor = persianPredictor(
            mapOf(miShavad to 900_000, "کتاب" to 800_000, "شود" to 650_000, "خوب" to 700_000, "میز" to 600_000)
        )
        val coordinator = mockk<PredictionCoordinator>(relaxed = true)
        every { coordinator.getWordPredictor() } returns predictor

        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.setSuggestionsWithScores(any(), any(), any(), any(), any()) } answers {
            posts.add(firstArg<List<String>>().toList() to arg<Int?>(3))
        }

        editor.setLength(0)
        afterCursor = ""
        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } answers {
            editor.substring(maxOf(0, editor.length - firstArg<Int>()))
        }
        every { ic.getTextAfterCursor(any(), any()) } answers { afterCursor.take(firstArg()) }
        every { ic.commitText(any(), any()) } answers { editor.append(firstArg<CharSequence>()); true }
        every { ic.deleteSurroundingText(any(), any()) } answers {
            editor.setLength(maxOf(0, editor.length - firstArg<Int>())); true
        }
        every { ic.getCursorCapsMode(any()) } returns 0

        val contractions = mockk<ContractionManager>(relaxed = true)
        every { contractions.getNonPairedMapping(any()) } returns null
        every { contractions.getPairedContractions(any()) } returns emptyList()

        pendingTasks.clear()
        posts.clear()
        val tasks = mockk<PredictionTaskRunner>(relaxed = true)
        every { tasks.cancelAndSubmit(any()) } answers { pendingTasks.add(firstArg()) }
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
        handler.setField("autocorrectMemo", AutocorrectMemo())
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    /** A real predictor over [words] (binary-dictionary scale) for language `fa`. */
    private fun persianPredictor(words: Map<String, Int>): WordPredictor {
        val p = objenesis.newInstance(WordPredictor::class.java)
        val predictorConfig = objenesis.newInstance(Config::class.java).apply {
            prediction_frequency_scale = Defaults.PREDICTION_FREQUENCY_SCALE
            prediction_context_boost = Defaults.PREDICTION_CONTEXT_BOOST
            context_source = Defaults.CONTEXT_SOURCE
            personalization_weight = Defaults.PERSONALIZATION_WEIGHT
            secondary_prediction_weight = Defaults.SECONDARY_PREDICTION_WEIGHT
            autocorrect_enabled = true
            autocorrect_min_word_length = 2
            autocorrect_char_match_threshold = 0.65f
            autocorrect_max_length_diff = 2
            autocorrect_confidence_min_frequency = FrequencyFloor.SLIDER_MIN
            autocorrect_prefix_length = 0
            swipe_debug_detailed_logging = false
        }
        val dict = HashMap(words)
        val index = HashMap<String, MutableSet<String>>()
        for (w in dict.keys) for (len in 1..minOf(3, w.length)) index.getOrPut(w.substring(0, len)) { HashSet() }.add(w)
        p.setField("dictionary", AtomicReference<MutableMap<String, Int>>(dict))
        p.setField("prefixIndex", AtomicReference<MutableMap<String, MutableSet<String>>>(index))
        p.setField("contractionAliases", emptyMap<String, String>())
        p.setField("customAndUserWords", emptySet<String>())
        p.setField("disabledWords", mutableSetOf<String>())
        p.setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
        p.setField("config", predictorConfig)
        p.setField("cachedMaxFreqForSize", -1)
        p.setField("secondaryIndex", null)
        p.setField("secondaryLanguageCode", "none")
        p.setField("currentLanguage", "fa")
        // Learn-funnel state Objenesis left null (space completion learns the word).
        p.setField("recentWords", mutableListOf<String>())
        return p
    }

    private fun field(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.notes"
            inputType = InputType.TYPE_CLASS_TEXT
        }

    /** Types like the IME does: the character reaches the editor, then the handler sees it. */
    private fun type(text: String, info: EditorInfo) {
        for (c in text) {
            editor.append(c)
            handler.handleRegularTyping(c.toString(), ic, info)
        }
    }

    private fun runPredictions() {
        val queued = pendingTasks.toList()
        pendingTasks.clear()
        queued.forEach { it.run() }
    }

    /** Cursor-sync the tracker against [before]|[after], as onUpdateSelection does. */
    private fun syncAt(before: String, after: String) {
        editor.setLength(0)
        editor.append(before)
        afterCursor = after
        tracker.synchronizeWithCursor(ic, "fa", field())
    }

    // ------------------------------------------------------------------ typing

    @Test
    fun aHalfSpaceAfterAnArabicLetterContinuesTheWord() {
        val info = field()
        type("می", info)
        type(z, info)
        type("شود", info)
        assertWithMessage("one composing word across the half-space").that(tracker.getCurrentWord()).isEqualTo(miShavad)
    }

    @Test
    fun theBarCompletesAHalfSpacePrefix() {
        val info = field()
        type("می${z}ش", info)
        runPredictions()
        assertWithMessage("می‌ش completes to می‌شود").that(posts.last().first).contains(miShavad)
    }

    @Test
    fun joinedTypingIsKeptOnSpaceAndTheHalfSpaceFormIsOnlyOffered() {
        val info = field()
        type("میشود", info)
        runPredictions()
        val post = posts.last()
        assertWithMessage("bar offers the half-space spelling").that(post.first).contains(miShavad)
        assertWithMessage("but does not highlight it as the space commit")
            .that(post.second?.let { post.first[it] }).isNotEqualTo(miShavad)
        type(" ", info)
        assertWithMessage("space keeps what was typed").that(editor.toString()).isEqualTo("میشود ")
    }

    @Test
    fun aHalfSpaceWordIsKeptOnSpace() {
        handler.setField("predictionCoordinator", mockk<PredictionCoordinator>(relaxed = true).also {
            every { it.getWordPredictor() } returns persianPredictor(mapOf("میشود" to 900_000, "کتاب" to 800_000))
        })
        val info = field()
        type("$miShavad ", info)
        assertWithMessage("space keeps the half-space spelling").that(editor.toString()).isEqualTo("$miShavad ")
    }

    // ------------------------------------------------------------------ cursor sync

    @Test
    fun cursorSyncComposesAcrossTheHalfSpace() {
        syncAt("سلام می${z}شو", "د خوب")
        assertWithMessage("prefix across the half-space").that(tracker.getCurrentWord()).isEqualTo("می${z}شو")
        assertWithMessage("suffix").that(tracker.getCurrentWordSuffix()).isEqualTo("د")
        syncAt("سلام می", "${z}شود")
        assertWithMessage("suffix starting with the half-space").that(tracker.getCurrentWordSuffix()).isEqualTo("${z}شود")
    }

    @Test
    fun cursorSyncKeepsAJustTypedHalfSpace() {
        syncAt("سلام می$z", "")
        assertWithMessage("edge half-space after an Arabic letter").that(tracker.getCurrentWord()).isEqualTo("می$z")
    }

    @Test
    fun cursorSyncDoesNotJoinOtherScriptsAcrossZwnj() {
        syncAt("ab${z}cd", "")
        assertWithMessage("Latin ZWNJ is a boundary, as everywhere else").that(tracker.getCurrentWord()).isEqualTo("cd")
        syncAt("ශ${z}ර", "")
        assertWithMessage("Sinhala ZWNJ is a boundary").that(tracker.getCurrentWord()).isEqualTo("ර")
        syncAt("می${z}abc", "")
        assertWithMessage("Arabic/Latin mix is a boundary").that(tracker.getCurrentWord()).isEqualTo("abc")
        syncAt("we don'", "t")
        assertWithMessage("apostrophe at the cursor unchanged").that(tracker.getCurrentWord()).isEqualTo("don'")
    }

    // ------------------------------------------------------------------ learn funnel / suffix ownership

    @Test
    fun aDanglingHalfSpaceIsTrimmedFromTheLearnedToken() {
        assertWithMessage("می‌ + space learns می").that(SuggestionHandler.trimJoiners("می$z")).isEqualTo("می")
        assertWithMessage("inner half-space kept").that(SuggestionHandler.trimJoiners(miShavad)).isEqualTo(miShavad)
        assertWithMessage("apostrophe trimming unchanged").that(SuggestionHandler.trimJoiners("kids'")).isEqualTo("kids")
    }

    @Test
    fun anOwnedSuffixDoesNotSplitAHalfSpaceWord() {
        // "شود" inside "می‌شود" is not a whole word; after a space it is.
        val joined = "می${z}شود"
        assertWithMessage("half-space before the tail continues the word")
            .that(VerifiedSuffixEdit.isWordContinuationAt(joined, 2)).isTrue()
        assertWithMessage("Latin ZWNJ before the tail does not")
            .that(VerifiedSuffixEdit.isWordContinuationAt("ab${z}cd", 2)).isFalse()
        assertWithMessage("apostrophe still continues").that(VerifiedSuffixEdit.isWordContinuationAt("o'cat", 1)).isTrue()
        assertWithMessage("space does not").that(VerifiedSuffixEdit.isWordContinuationAt("a cat", 1)).isFalse()
    }

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
