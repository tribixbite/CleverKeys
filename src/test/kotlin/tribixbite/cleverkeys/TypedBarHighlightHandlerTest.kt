package tribixbite.cleverkeys

import android.os.Handler
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.autocorrect.AutocorrectMemo

/**
 * Bar highlight == space commit, end to end through [SuggestionHandler] (Pixel report
 * 2026-10-11: typing `Helo` highlighted `Hello`, space committed `Help`).
 *
 * Mock tier. REAL: the handler's typing/space bodies (Objenesis-allocated), the tracker, the
 * shared resolver/planner/memo. The predictor is a mock whose bar ranks `hello` first and whose
 * `autoCorrect` answers `help`, exactly the reported disagreement; the editor is a StringBuilder.
 * Prediction tasks are queued and run on demand, and the main-thread post runs inline.
 *
 * Pins: the bar post highlights the space commit in slot 0; space writes that same word and
 * reuses the bar's `autoCorrect` sweep (one call, not two); no replacement is highlighted with
 * autocorrect off, in a terminal, or in a URL token; a word typed on after the bar computed is
 * recomputed at space, never served the stale correction.
 */
class TypedBarHighlightHandlerTest {

    private val objenesis = ObjenesisStd()

    private lateinit var config: Config
    private lateinit var tracker: PredictionContextTracker
    private lateinit var predictor: WordPredictor
    private lateinit var bar: SuggestionBar
    private lateinit var ic: InputConnection
    private lateinit var handler: SuggestionHandler

    private val editor = StringBuilder()
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

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.word_prediction_enabled = true
        config.autocorrect_enabled = true
        config.autocapitalize_i_words = true
        config.show_exact_typed_word = false
        config.next_word_prediction_enabled = false
        config.on_device_learning_enabled = false
        config.custom_terminal_packages = emptySet()
        config.primary_language = "en"
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config

        tracker = PredictionContextTracker()

        predictor = mockk(relaxed = true)
        every { predictor.predictWordsWithContext(any(), any()) } answers {
            when (firstArg<String>()) {
                "helo" -> WordPredictor.PredictionResult(listOf("hello", "helot", "help"), listOf(90, 50, 40))
                "Helo" -> WordPredictor.PredictionResult(listOf("hello", "helot"), listOf(90, 50))
                else -> WordPredictor.PredictionResult(listOf("${firstArg<String>()}s"), listOf(10))
            }
        }
        every { predictor.autoCorrect(any()) } answers {
            when (firstArg<String>()) { "helo" -> "help"; "Helo" -> "Help"; "helop" -> "helps"; else -> firstArg() }
        }
        every { predictor.isInDictionary(any()) } returns true
        every { predictor.isInDictionary(any(), any()) } returns true
        val coordinator = mockk<PredictionCoordinator>(relaxed = true)
        every { coordinator.getWordPredictor() } returns predictor

        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.setSuggestionsWithScores(any(), any(), any(), any(), any()) } answers {
            posts.add(firstArg<List<String>>().toList() to arg<Int?>(3))
        }

        editor.setLength(0)
        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } answers {
            editor.substring(maxOf(0, editor.length - firstArg<Int>()))
        }
        every { ic.getTextAfterCursor(any(), any()) } returns ""
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

    private fun field(pkg: String = "com.example.notes"): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = pkg
            inputType = InputType.TYPE_CLASS_TEXT
        }

    /** Types like the IME does: the letter reaches the editor, then the handler sees it. */
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

    private fun lastPost() = posts.last()
    private fun highlighted(post: Pair<List<String>, Int?>) = post.second?.let { post.first[it] }

    // ------------------------------------------------------------------ the reported case

    @Test
    fun heloHighlightsHelpAndSpaceCommitsHelp() {
        val info = field()
        type("helo", info)
        runPredictions()
        assertWithMessage("bar highlights what space commits").that(highlighted(lastPost())).isEqualTo("help")
        assertWithMessage("in the first slot").that(lastPost().second).isEqualTo(0)
        assertWithMessage("ranker's other entries kept").that(lastPost().first).containsExactly("help", "hello", "helot").inOrder()

        type(" ", info)
        assertWithMessage("space commits the highlighted word").that(editor.toString()).isEqualTo("help ")
        verify(exactly = 1) { predictor.autoCorrect("helo") }
    }

    @Test
    fun titleCaseIsShownAsCommitted() {
        val info = field()
        type("Helo", info)
        runPredictions()
        assertWithMessage("absent target inserted in committed casing").that(highlighted(lastPost())).isEqualTo("Help")
        type(" ", info)
        assertWithMessage("space commits it").that(editor.toString()).isEqualTo("Help ")
    }

    // ------------------------------------------------------------------ no replacement highlighted

    @Test
    fun autocorrectOffHighlightsNoReplacement() {
        config.autocorrect_enabled = false
        val info = field()
        type("helo", info)
        runPredictions()
        assertWithMessage("no entry highlighted").that(lastPost().second).isNull()
        assertWithMessage("bar order untouched").that(lastPost().first).containsExactly("hello", "helot", "help").inOrder()
        type(" ", info)
        assertWithMessage("space keeps the typed word").that(editor.toString()).isEqualTo("helo ")
    }

    @Test
    fun terminalHighlightsNoReplacement() {
        val info = field("com.termux")
        type("helo", info)
        runPredictions()
        assertWithMessage("no entry highlighted in a terminal").that(lastPost().second).isNull()
        verify(exactly = 0) { predictor.autoCorrect(any()) }
    }

    @Test
    fun urlTokenHighlightsNoReplacement() {
        val info = field()
        editor.append("foo.")
        type("helo", info)
        runPredictions()
        assertWithMessage("no replacement highlighted inside a URL token").that(lastPost().second).isNull()
        type(" ", info)
        assertWithMessage("space keeps the token").that(editor.toString()).isEqualTo("foo.helo ")
    }

    @Test
    fun aValidTypedWordIsTheOnlyHighlight() {
        every { predictor.predictWordsWithContext("help", any()) } returns
            WordPredictor.PredictionResult(listOf("helps", "help", "helped"), listOf(90, 80, 70))
        val info = field()
        type("help", info)
        runPredictions()
        assertWithMessage("the typed word, where the ranker put it").that(lastPost().second).isEqualTo(1)
        assertWithMessage("bar order untouched").that(lastPost().first).containsExactly("helps", "help", "helped").inOrder()
    }

    @Test
    fun aPrefixHighlightsNoCompletion() {
        val info = field()
        type("hel", info)
        runPredictions()
        assertWithMessage("completion 'hels' is not what space commits").that(lastPost().second).isNull()
    }

    // ------------------------------------------------------------------ single source of truth

    @Test
    fun aWordTypedOnAfterTheBarIsRecomputedAtSpace() {
        val info = field()
        type("helo", info)
        runPredictions()
        assertWithMessage("bar for 'helo'").that(highlighted(lastPost())).isEqualTo("help")
        // The user types on and presses space before the next prediction runs.
        type("p", info)
        type(" ", info)
        assertWithMessage("space corrects the word actually typed").that(editor.toString()).isEqualTo("helps ")
        verify(exactly = 1) { predictor.autoCorrect("helop") }
    }

    @Test
    fun spaceWithoutABarStillCorrects() {
        val info = field()
        type("helo", info) // predictions never ran: the memo is cold
        type(" ", info)
        assertWithMessage("space computes the correction itself").that(editor.toString()).isEqualTo("help ")
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
