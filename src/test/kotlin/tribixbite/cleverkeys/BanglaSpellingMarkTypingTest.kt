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
import tribixbite.cleverkeys.autocorrect.AutocorrectRanking
import tribixbite.cleverkeys.autocorrect.AutocorrectTuning
import tribixbite.cleverkeys.autocorrect.FrequencyFloor
import tribixbite.cleverkeys.autocorrect.KeyAdjacency
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Typing Bangla words end to end (maintainer approval 2026-11-04; kit
 * `ml/rtx-kits/19-bangla-pack` §3).
 *
 * Mock tier, the harness of [PersianHalfSpaceTypingTest]: REAL [SuggestionHandler] typing and
 * space bodies, [PredictionContextTracker], and a [WordPredictor] over a small `bn` dictionary
 * whose keys are spelled as `build_dictionary.py --keep-script-marks` keeps them (marks in the
 * key). The words are entries of WM Keyboard's `bn.txt`, all unchanged by NFD.
 *
 * Pins: a vowel sign or hasanta typed on a Bengali letter continues the composing word instead
 * of ending it; the bar completes a prefix that ends in a mark; a word is kept as typed on
 * space; cursor-sync composes across marks and keeps them (it stores the raw editor text); a
 * bare mark with no composing base does not join the previous word.
 *
 * Added 2026-11-06: a one-key typo of a marked word gets a typo-tolerant suggestion (the
 * `all { isLetter() }` gate skipped it), a doubled vowel sign collapses and a dropped doubled
 * letter is restored on a marked word, a ZWJ inside a Bengali word composes `র‍্যাব`, and the
 * danda `।` closes the learning window exactly as `.` does.
 *
 * Pure-JVM call-site coverage is [BanglaSpellingMarkBoundaryTest].
 */
class BanglaSpellingMarkTypingTest {

    private val objenesis = ObjenesisStd()
    private val ami = "আমি"
    private val kukur = "কুকুর"
    private val khoma = "ক্ষমা"
    private val rab = "র\u200D্যাব"   // র + ZWJ + ্ + য (ra-phala), া, ব
    private val lolit = "ললিত"         // "graceful": a genuinely doubled letter, then ি

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
        config.primary_language = "bn"
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config

        tracker = PredictionContextTracker()
        predictor = banglaPredictor(
            mapOf(
                ami to 900_000, "আমার" to 850_000, kukur to 600_000, khoma to 500_000, "কথা" to 700_000,
                rab to 400_000, lolit to 300_000,
            )
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

    /** A real predictor over [words] (binary-dictionary scale) for language `bn`. */
    private fun banglaPredictor(words: Map<String, Int>): WordPredictor {
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
        p.setField("currentLanguage", "bn")
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
        tracker.synchronizeWithCursor(ic, "bn", field())
    }

    // ------------------------------------------------------------------ typing

    @Test
    fun aVowelSignOnALetterContinuesTheWord() {
        type(ami, field())
        assertWithMessage("আম + ি is one composing word").that(tracker.getCurrentWord()).isEqualTo(ami)
    }

    @Test
    fun aHasantaConjunctContinuesTheWord() {
        type(khoma, field())
        assertWithMessage("ক + ্ + ষ ... is one composing word").that(tracker.getCurrentWord()).isEqualTo(khoma)
    }

    @Test
    fun theBarCompletesAPrefixEndingInAMark() {
        type("কু", field())
        runPredictions()
        assertWithMessage("কু completes to কুকুর").that(posts.last().first).contains(kukur)
        type("কু", field()) // কুকু: still one word, still completing
        runPredictions()
        assertWithMessage("কুকু completes to কুকুর").that(posts.last().first).contains(kukur)
        assertWithMessage("composing word").that(tracker.getCurrentWord()).isEqualTo("কুকু")
    }

    @Test
    fun theBarCompletesAPrefixEndingInAHasanta() {
        type("ক্", field())
        runPredictions()
        assertWithMessage("ক্ completes to ক্ষমা").that(posts.last().first).contains(khoma)
    }

    @Test
    fun aWordIsKeptAsTypedOnSpace() {
        type("$ami $kukur ", field())
        assertWithMessage("space keeps both words").that(editor.toString()).isEqualTo("$ami $kukur ")
    }

    @Test
    fun aMarkWithoutAComposingBaseDoesNotJoinThePreviousWord() {
        type("$ami ", field())
        type("ি", field())
        assertWithMessage("nothing composes from a bare mark").that(tracker.getCurrentWord()).isEmpty()
    }

    // ------------------------------------------------------------------ cursor sync

    @Test
    fun cursorSyncComposesAcrossMarksAndKeepsThem() {
        syncAt("$ami কুকু", "র")
        assertWithMessage("prefix keeps both u-signs").that(tracker.getCurrentWord()).isEqualTo("কুকু")
        assertWithMessage("suffix").that(tracker.getCurrentWordSuffix()).isEqualTo("র")
        syncAt("$ami ক্ষ", "মা")
        assertWithMessage("prefix keeps the hasanta").that(tracker.getCurrentWord()).isEqualTo("ক্ষ")
        syncAt("আম", "ি")
        assertWithMessage("a sign right after the cursor is the word's suffix")
            .that(tracker.getCurrentWordSuffix()).isEqualTo("ি")
    }

    // ------------------------------------------------------------------ typo tolerance (2026-11-06)

    @Test
    fun aOneLetterTypoOfAMarkedWordGetsAFuzzySuggestion() {
        type("কুকুল", field()) // ল for র: a substitution in the last letter
        runPredictions()
        assertWithMessage("কুকুল offers কুকুর").that(posts.last().first).contains(kukur)
    }

    @Test
    fun aMarkTypoIsAnOrdinaryEdit() {
        type("কাকুর", field()) // া for ু: the vowel sign is a character like any other
        runPredictions()
        assertWithMessage("কাকুর offers কুকুর").that(posts.last().first).contains(kukur)
    }

    @Test
    fun aDoubledVowelSignCollapses() {
        assertWithMessage("আমিি → আমি (key repeat on the sign)").that(predictor.autoCorrect("আমিি")).isEqualTo(ami)
    }

    @Test
    fun aDroppedDoubledLetterIsRestoredInAMarkedWord() {
        val band = AutocorrectTuning.DEFAULT.copy(ranking = AutocorrectRanking.BAND)
        assertWithMessage("লিত → ললিত (step 1.45, band ranking)").that(predictor.autoCorrect("লিত", band)).isEqualTo(lolit)
    }

    // ------------------------------------------------------------------ ZWJ (2026-11-06)

    @Test
    fun aZwjInsideABengaliWordContinuesIt() {
        type(rab, field())
        assertWithMessage("র + ZWJ + ্ + য + া + ব is one composing word").that(tracker.getCurrentWord()).isEqualTo(rab)
        type(" ", field())
        assertWithMessage("space keeps it as typed").that(editor.toString()).isEqualTo("$rab ")
        assertWithMessage("learned whole").that(recentWords()).containsExactly(rab)
    }

    @Test
    fun aZwjAfterALatinLetterStillEndsTheWord() {
        type("ab\u200D", field())
        assertWithMessage("nothing composes after a Latin ZWJ").that(tracker.getCurrentWord()).isEmpty()
    }

    // ------------------------------------------------------------------ danda (2026-11-06)

    @Test
    fun theDandaClosesTheLearningWindow() {
        type("$ami $kukur।", field())
        assertWithMessage("no bigram spans the danda: the window is empty").that(recentWords()).isEmpty()
    }

    @Test
    fun aCommaDoesNotCloseTheLearningWindow() {
        type("$ami $kukur,", field())
        assertWithMessage("control: both words stay in the window").that(recentWords()).containsExactly(ami, kukur).inOrder()
    }

    @Suppress("UNCHECKED_CAST")
    private fun recentWords(): List<String> {
        val f = WordPredictor::class.java.getDeclaredField("recentWords")
        f.isAccessible = true
        return (f.get(predictor) as List<String>).toList()
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
