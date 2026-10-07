package tribixbite.cleverkeys

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.util.Log
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
import tribixbite.cleverkeys.gesture.ContinuousSelectionGate
import tribixbite.cleverkeys.gesture.ContinuousSwipe
import tribixbite.cleverkeys.gesture.ContinuousSwipeQueue
import tribixbite.cleverkeys.a11y.KeyboardGeometry

/**
 * Review 2026-10-07, findings 5 and 6: continuous-swipe session lifecycle in the real
 * [Keyboard2View].
 *
 *  - Finding 5: after lift the final segment's decode is still in flight; the next
 *    touch-down or a typed key ("." right after lifting) cancelled it, and the queue's
 *    late `complete(null)` was swallowed — the last word vanished without feedback.
 *  - Finding 6: with the opt-in enabled, any cancellation (second finger, layout reset,
 *    an app-side selection change) discarded the WHOLE gesture even when no spacebar
 *    boundary had been crossed; with the feature off the same swipe commits its word.
 *
 * Harness: the [Keyboard2ViewCustomSwipeDispatchTest] idiom — a real view allocated without
 * its constructor (Objenesis) and spied; fields that the constructor would initialize are
 * set explicitly.
 */
class Keyboard2ViewContinuousLifecycleTest {
    private val objenesis = ObjenesisStd()
    private lateinit var view: Keyboard2View
    private lateinit var service: CleverKeysService
    private val key: KeyboardData.Key = objenesis.newInstance(KeyboardData.Key::class.java)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        service = mockk(relaxed = true)
        view = spyk(objenesis.newInstance(Keyboard2View::class.java))
        every { view.context } returns mockk<Context>(relaxed = true)
        every { view.invalidate() } just runs
        view.setField("_keyboard2", service)
        view.setField("continuousGate", ContinuousSelectionGate())
        view.setField("continuousTimer", mockk<Handler>(relaxed = true))
    }

    @After
    fun teardown() = unmockkAll()

    private fun recognizerWithWord(): ImprovedSwipeGestureRecognizer = mockk(relaxed = true) {
        every { isSwipeTyping() } returns true
        every { endSwipe() } returns SwipeResult(listOf(key, key), listOf(PointF(1f, 1f), PointF(9f, 1f)),
            listOf(1L, 2L), 8f, true)
    }

    /** A segmenter mid-gesture: letters seen, optionally one deliberate space boundary. */
    private fun segmenter(boundary: Boolean): ContinuousSwipe<KeyboardData.Key> {
        val swipe = ContinuousSwipe<KeyboardData.Key>({}, {})
        swipe.sample(1f, 1f, 1001, 1, key, false)
        if (boundary) {
            swipe.sample(5f, 5f, 1002, 2, null, true)
            swipe.dwell(2 + ContinuousSwipe.DWELL_MS)
            swipe.sample(9f, 1f, 1400, 400, key, false)
        }
        return swipe
    }

    // ------------------------------------------------------------------ finding 6

    @Test
    fun cancellationWithoutABoundaryStillCommitsTheSingleWord() {
        view.setField("continuousSegmenter", segmenter(boundary = false))
        view.cancelContinuousSwipe() // second finger / app selection change / reset
        view.onSwipeEnd(recognizerWithWord())
        verify(exactly = 1) { service.handleSwipeTyping(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun cancellationAfterABoundaryStillDiscardsTheRestOfThePhrase() {
        view.setField("continuousSegmenter", segmenter(boundary = true))
        view.cancelContinuousSwipe()
        view.onSwipeEnd(recognizerWithWord())
        verify(exactly = 0) { service.handleSwipeTyping(any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------ finding 5

    @Test
    fun newInputWhileTheFinalSegmentDecodesGivesFeedback() {
        // Lifted: the segmenter is gone, the final segment's decode has not completed.
        val queue = ContinuousSwipeQueue<Int>({ true }, { _, _, _ -> }, {})
        queue.enqueue(1)
        view.setField("continuousQueue", queue)
        view.cancelContinuousSwipe() // KeyboardReceiver.handle_text_typed / next touch-down
        verify(exactly = 1) { service.showSuggestionBarMessage(any(), any()) }
    }

    @Test
    fun newInputAfterThePhraseFinishedIsSilent() {
        val queue = ContinuousSwipeQueue<Int>({ true }, { _, _, done -> done(true) }, {})
        queue.enqueue(1)
        view.setField("continuousQueue", queue)
        view.cancelContinuousSwipe()
        verify(exactly = 0) { service.showSuggestionBarMessage(any(), any()) }
    }

    // ------------------------------------------------------------------ finding 3 (cost)

    private fun charKey(c: Char) = KeyboardData.Key(
        listOf(KeyValue.makeCharKey(c), null, null, null, null, null, null, null, null), null, 0, 1f, 0f, null)

    private fun layoutWith(vararg keys: KeyboardData.Key): KeyboardData {
        val ctor = KeyboardData::class.java.declaredConstructors.first { it.parameterCount == 12 }
        ctor.isAccessible = true
        return ctor.newInstance(listOf(KeyboardData.Row(keys.toList(), 1f, 0f)), keys.size.toFloat(), 1f,
            null, null, null, "test", false, false, false, false, null) as KeyboardData
    }

    private fun collapsedReadback(): EditorReadback =
        EditorReadback::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(3, 3, "say", "", "")

    /** Arms a continuous-enabled touch-down on a two-key row: letter `a` and SPACE. */
    private fun armContinuousStart(): Pair<KeyboardData.Key, ImprovedSwipeGestureRecognizer> {
        val ic = mockk<InputConnection>(relaxed = true)
        every { service.currentInputConnection } returns ic
        every { service.currentInputEditorInfo } returns objenesis.newInstance(EditorInfo::class.java).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val snap = testConfigSnapshot(continuous_swipe_enabled = true)
        val config = mockk<Config>(relaxed = true)
        every { config.snapshot } returns snap
        config.setField("handler", mockk<Config.IKeyEventHandler>(relaxed = true) { every { canUseEditorActions() } returns true })
        view.setField("_config", config)
        val letter = charKey('a')
        view.setField("_keyboard", layoutWith(letter, charKey(' ')))
        view.setField("_tc", objenesis.newInstance(Theme.Computed::class.java).apply { setField("row_height", 100f) })
        view.setField("_keyWidth", 100f)
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns mockk(relaxed = true)
        mockkObject(EditorReadback.Companion)
        every { EditorReadback.capture(any()) } answers { collapsedReadback() }
        mockkObject(KeyboardGeometry)
        return letter to mockk(relaxed = true)
    }

    @Test
    fun touchDownReadsNoEditorStateAndReusesSpaceGeometry() {
        val (letter, recognizer) = armContinuousStart()
        val snap = testConfigSnapshot(continuous_swipe_enabled = true)
        view.onSwipeStart(10f, 50f, letter, snap, recognizer)
        view.onSwipeStart(10f, 50f, letter, snap, recognizer)
        verify(exactly = 0) { EditorReadback.capture(any()) }
        verify(exactly = 1) { KeyboardGeometry.computeKeyRects(any<KeyboardData>(), any()) }
    }

    @Test
    fun firstBoundaryReadsTheBaselineAndDispatchesTheSegment() {
        val (letter, recognizer) = armContinuousStart()
        view.onSwipeStart(10f, 50f, letter, testConfigSnapshot(continuous_swipe_enabled = true), recognizer)
        @Suppress("UNCHECKED_CAST")
        val segmenter = Keyboard2View::class.java.getDeclaredField("continuousSegmenter")
            .apply { isAccessible = true }.get(view) as ContinuousSwipe<KeyboardData.Key>
        segmenter.sample(20f, 50f, 2_000, 1_000, letter, false)
        segmenter.sample(150f, 50f, 2_010, 1_010, null, true)
        segmenter.dwell(1_010 + ContinuousSwipe.DWELL_MS)
        verify(atLeast = 1) { EditorReadback.capture(any()) }
        verify(exactly = 1) { service.handleSwipeTyping(any(), any(), any(), any(), any(), any()) }
    }

    private fun Any.setField(name: String, value: Any?) {
        var type: Class<*>? = javaClass
        while (type != null) {
            runCatching { type!!.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true; it.set(this, value); return
            }
            type = type.superclass
        }
        error("no field $name")
    }
}
