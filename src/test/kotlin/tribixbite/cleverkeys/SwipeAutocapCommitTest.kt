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

/**
 * Proper-noun casing on swipe (device-confirmed gap): a swiped "bowie" committed lowercase
 * even at a sentence start.
 *
 * Tap typing is capitalized there because [Autocapitalisation] latches the SHIFT fake pointer
 * and each tapped key is shifted. The swipe path instead snapshots the shift latch at swipe
 * START (Pointers.onTouchDown → recognizer) — a snapshot that goes stale whenever autocap's
 * delayed (50ms) latch, a suggestion-commit the autocap cursor tracker never saw, or a
 * selection-update race un-latches shift around the gesture. The fix applies the SAME decision
 * the tap path's Autocapitalisation uses, but at COMMIT time: when neither shift nor caps lock
 * was latched, `Autocapitalisation.shouldCapitalizeAtCursor` (autocap setting + the field's
 * CAP flags + `getCursorCapsMode`) decides whether the swiped slate is capitalized.
 *
 * Deliberately NOT proper-noun dictionary casing — that is a different feature; this only
 * mirrors what tap typing would have produced at the same cursor.
 *
 * Harness: mock tier, Objenesis-allocated SuggestionHandler with only the fields the swipe
 * path reads seeded — same pattern as [SuggestionTapAddAndIWordTest].
 */
class SwipeAutocapCommitTest {

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
        config.autocapitalisation = true
        config.auto_space_after_suggestion = true
        every { Config.globalConfig() } returns config

        dictionary = mockk(relaxed = true)
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
        every { bar.setSuggestionsWithScores(any(), any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
        }
        every { bar.getTopSuggestion() } answers { barWords.firstOrNull() }

        inputCoordinator = mockk(relaxed = true)
        every { inputCoordinator.getCurrentSwipeData() } returns null

        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } returns ""
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        resources = mockk(relaxed = true)
    }

    @After
    fun teardown() = unmockkAll()

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

    /** A sentence-capitalizing text field (what a notes/message body declares). */
    private fun capSentencesField(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.notes"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

    /** A plain text field that declares NO capitalization behavior at all. */
    private fun noCapsField(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.notes"
            inputType = InputType.TYPE_CLASS_TEXT
        }

    private fun swipe(
        editorInfo: EditorInfo,
        shiftActive: Boolean = false,
        shiftLocked: Boolean = false,
    ) {
        handler().handleSwipePredictionResults(
            listOf("bowie", "bowls"), listOf(100, 90), ic, editorInfo, resources,
            shiftActive, shiftLocked, inputCoordinator
        )
    }

    // ------------------------------------------------------------------ the gap

    @Test
    fun aSentenceStartSwipeCommitIsCapitalized() {
        // The editor reports "capitalize here" — exactly what autocap consults for taps.
        every { ic.getCursorCapsMode(any()) } returns InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        swipe(capSentencesField())

        verify { ic.commitText("Bowie ", 1) }
        assertWithMessage("the bar must show the same casing the commit used")
            .that(barWords.first()).isEqualTo("Bowie")
    }

    @Test
    fun midSentenceTheSwipeCommitStaysLowercase() {
        every { ic.getCursorCapsMode(any()) } returns 0

        swipe(capSentencesField())

        verify { ic.commitText("bowie ", 1) }
    }

    @Test
    fun withAutocapDisabledTheSentenceStartSwipeStaysLowercase() {
        config.autocapitalisation = false
        every { ic.getCursorCapsMode(any()) } returns InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        swipe(capSentencesField())

        verify { ic.commitText("bowie ", 1) }
    }

    @Test
    fun aFieldWithoutCapFlagsIsNeverQueriedAndStaysLowercase() {
        // Mirrors Autocapitalisation.started(): capsMode == 0 disables the feature outright,
        // so a misbehaving editor's getCursorCapsMode can never flip the casing.
        swipe(noCapsField())

        verify(exactly = 0) { ic.getCursorCapsMode(any()) }
        verify { ic.commitText("bowie ", 1) }
    }

    // ------------------------------------------------- explicit shift still wins

    @Test
    fun capsLockStillUppercasesTheWholeWord() {
        every { ic.getCursorCapsMode(any()) } returns 0

        swipe(capSentencesField(), shiftLocked = true)

        verify { ic.commitText("BOWIE ", 1) }
    }

    @Test
    fun aLatchedShiftStillCapitalizesWithoutConsultingTheCursor() {
        swipe(capSentencesField(), shiftActive = true)

        verify { ic.commitText("Bowie ", 1) }
    }

    // ------------------------------------------------------------------ reflection

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
