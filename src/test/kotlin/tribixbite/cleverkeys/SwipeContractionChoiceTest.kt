package tribixbite.cleverkeys

import android.content.res.Resources
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
import java.util.concurrent.ConcurrentHashMap

/**
 * The `its` / `it's` context chooser on the REAL swipe path
 * ([SuggestionHandler.handleSwipePredictionResults]), shipped after round 3 passed
 * (`docs/eval/2026-10-07-apostrophe-context.md` §8). The decision rule itself is pinned by
 * `swipe.ContractionContextChooserTest`; this class pins the wiring: the swap reaches the bar
 * and the auto-inserted word, and every no-action rule the wiring owns holds (password field,
 * non-English language, sentence start, user word in slot 0).
 *
 * The LM is a hand-built CKLM installed on the [BigramModel] singleton (the handler reads it
 * through `BigramModel.getInstance`); [teardown] restores whatever `en` model was there before,
 * so no fixture leaks into other classes in the same JVM.
 *
 * Harness: mock tier, Objenesis-allocated SuggestionHandler, as [SwipeAutocapCommitTest].
 */
class SwipeContractionChoiceTest {

    private val objenesis = ObjenesisStd()

    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var dictionary: DictionaryManager
    private lateinit var predictor: WordPredictor
    private lateinit var bar: SuggestionBar
    private lateinit var ic: InputConnection
    private lateinit var resources: Resources
    private lateinit var config: Config
    private lateinit var inputCoordinator: InputCoordinator

    /** The words the handler last pushed to the bar; getTopSuggestion answers from it. */
    private var barWords: List<String> = emptyList()

    /** The `en` LM installed before this test, restored afterwards (null = none). */
    private var priorEnglishLm: StaticContextLm? = null

    /** "of its" is a stored pair; "of it's" is not, so only backoff speaks for it. */
    private val lm: StaticContextLm = StaticContextLm.parse(
        StaticContextLmFixtures.encode(
            unigrams = mapOf("of" to 0.02, "its" to 0.002, "it's" to 0.004, "the" to 0.05),
            table = mapOf("of" to mapOf("its" to 0.02, "the" to 0.3), "the" to mapOf("it's" to 0.0001)),
        )
    )

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
        config.auto_space_after_suggestion = true
        every { Config.globalConfig() } returns config

        dictionary = mockk(relaxed = true)
        every { dictionary.getCurrentLanguage() } returns "en"
        every { dictionary.isUserWordIgnoringCase(any()) } returns false
        predictor = mockk(relaxed = true)
        every { predictor.applyUserWordCaseToList(any()) } answers { firstArg() }
        coordinator = mockk(relaxed = true)
        every { coordinator.getDictionaryManager() } returns dictionary
        every { coordinator.getWordPredictor() } returns predictor

        contextTracker = mockk(relaxed = true)
        every { contextTracker.getLastCommitSource() } returns PredictionSource.UNKNOWN
        every { contextTracker.getLastAutoInsertedWord() } returns null
        every { contextTracker.wasLastInputSwipe() } returns true
        every { contextTracker.getCurrentWordLength() } returns 0
        every { contextTracker.getCurrentWord() } returns ""
        every { contextTracker.shouldSyncForInputType(any()) } returns true

        barWords = emptyList()
        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.setSuggestionsWithScores(any(), any(), any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
        }
        every { bar.getTopSuggestion() } answers { barWords.firstOrNull() }

        inputCoordinator = mockk(relaxed = true)
        every { inputCoordinator.getCurrentSwipeData() } returns null

        ic = mockk(relaxed = true)
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        every { ic.getCursorCapsMode(any()) } returns 0
        resources = mockk(relaxed = true)

        val shared = BigramModel.getInstance(null)
        priorEnglishLm = shared.staticLmFor("en")
        shared.installStaticLm("en", lm)
    }

    @After
    fun teardown() {
        val prior = priorEnglishLm
        if (prior != null) {
            BigramModel.getInstance(null).installStaticLm("en", prior)
        } else {
            staticLms().remove("en")
        }
        unmockkAll()
    }

    // ------------------------------------------------------------------ fixtures

    private fun handler(): SuggestionHandler {
        val handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contextTracker", contextTracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("contractionManager", mockk<ContractionManager>(relaxed = true))
        handler.setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        handler.setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
        return handler
    }

    private fun field(inputType: Int = InputType.TYPE_CLASS_TEXT): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.notes"
            this.inputType = inputType
        }

    /** Swipe the `its` surface with the overlay's slate (it's ahead) after [before]. */
    private fun swipeIts(before: String, editorInfo: EditorInfo = field()) {
        every { ic.getTextBeforeCursor(any(), any()) } returns before
        handler().handleSwipePredictionResults(
            listOf("it's", "its", "is"), listOf(120, 110, 90), ic, editorInfo, resources,
            false, false, inputCoordinator
        )
    }

    // ------------------------------------------------------------------ the swap

    @Test
    fun afterAListedPreviousWordItsTakesSlotZeroAndIsCommitted() {
        swipeIts("one of ")

        assertWithMessage("bar order after \"of\"").that(barWords.take(3))
            .containsExactly("its", "it's", "is").inOrder()
        verify { ic.commitText("its ", 1) }
    }

    // ------------------------------------------------------------------ no-action rules

    @Test
    fun atASentenceStartTheOverlayOrderStands() {
        swipeIts("Look at that. ")

        assertWithMessage("no previous word in the segment").that(barWords.take(2))
            .containsExactly("it's", "its").inOrder()
        verify { ic.commitText("it's ", 1) }
    }

    @Test
    fun aPreviousWordWithoutAStoredItsPairChangesNothing() {
        // "the" names it's (backoff for its): LISTED evidence for its is absent.
        swipeIts("look at the ")

        assertWithMessage("LISTED evidence is required").that(barWords.take(2))
            .containsExactly("it's", "its").inOrder()
    }

    @Test
    fun aNonEnglishDictionaryLanguageChangesNothing() {
        every { dictionary.getCurrentLanguage() } returns "fr"

        swipeIts("one of ")

        assertWithMessage("English only").that(barWords.take(2))
            .containsExactly("it's", "its").inOrder()
    }

    @Test
    fun aUserWordInSlotZeroIsKept() {
        every { dictionary.isUserWordIgnoringCase("it's") } returns true

        swipeIts("one of ")

        assertWithMessage("the user's word wins").that(barWords.take(2))
            .containsExactly("it's", "its").inOrder()
    }

    @Test
    fun aPasswordFieldIsNeverReordered() {
        config.swipe_on_password_fields = true

        swipeIts("one of ", field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))

        assertWithMessage("password slate untouched").that(barWords.take(2))
            .containsExactly("it's", "its").inOrder()
    }

    @Test
    fun aDualLanguageSlateWithMixedLabelsIsLeftAlone() {
        every { ic.getTextBeforeCursor(any(), any()) } returns "one of "
        handler().handleSwipePredictionResults(
            listOf("it's", "its"), listOf(120, 110), ic, field(), resources,
            false, false, inputCoordinator, languages = listOf("en", "fr")
        )

        assertWithMessage("slots from two languages").that(barWords.take(2))
            .containsExactly("it's", "its").inOrder()
    }

    // ------------------------------------------------------------------ reflection

    @Suppress("UNCHECKED_CAST")
    private fun staticLms(): ConcurrentHashMap<String, StaticContextLm> {
        val f = BigramModel::class.java.getDeclaredField("staticLms")
        f.isAccessible = true
        return f.get(BigramModel.getInstance(null)) as ConcurrentHashMap<String, StaticContextLm>
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName} — it was renamed or removed; " +
                "declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }
}
