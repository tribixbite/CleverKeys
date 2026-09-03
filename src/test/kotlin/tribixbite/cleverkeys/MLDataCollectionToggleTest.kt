package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ml.SwipeMLData
import tribixbite.cleverkeys.ml.SwipeMLDataStore

/**
 * v1.1.71: "Fixed swipe data collection toggle not working".
 *
 * Release-record row: `ml/SwipeMLDataStore.kt#SwipeMLDataStore`, PRESENT-UNTESTED. The claim is
 * a PRIVACY promise — a user who turns swipe-data collection off must have no swipe traces
 * written to the on-device store — and the thing that was broken was the ENFORCEMENT, not the
 * predicate.
 *
 * The predicate itself already had cover: [LearningGate.canCollectSwipeMl] is unit-tested, and
 * `PrivacyManagerTest` pins `canCollectSwipeData()` in both directions. Neither of those says
 * anything about whether a caller consults it. `MLDataCollector.collectAndStoreSwipeData` is
 * the ONLY production path from a swipe selection into `SwipeMLDataStore.storeSwipeData`
 * (`PlaygroundTraceRecorder` is the separately-gated Swipe Playground), so this file pins:
 *
 *  - toggle OFF ⇒ the store is never touched, and the collector reports it stored nothing;
 *  - toggle ON  ⇒ exactly one row is stored, and it carries the right word, source, geometry,
 *    trace and provenance — a gate that stored an EMPTY row would satisfy a bare
 *    "storeSwipeData was called" assertion, so the stored object is inspected;
 *  - the gate is consulted BEFORE anything else, so it also holds when there is no store.
 *
 * ## Why the objects are allocated without their constructors
 *
 * `MLDataCollector`'s constructor calls `PrivacyManager.getInstance(context)`, which reaches
 * SharedPreferences — an android.jar stub that throws under `runMockTests`. Objenesis allocates
 * without it; the two fields the method reads are seeded, and the REAL method body runs.
 */
class MLDataCollectionToggleTest {

    private val objenesis = ObjenesisStd()

    private val screenWidth = 1080
    private val screenHeight = 2400
    private val keyboardHeight = 640

    private lateinit var context: Context
    private lateinit var privacy: PrivacyManager
    private lateinit var store: SwipeMLDataStore

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        // DisplayMetrics is a plain field holder; the android.jar stub constructor throws, so
        // allocate it without one and write the two fields the collector reads.
        val metrics = objenesis.newInstance(DisplayMetrics::class.java).apply {
            widthPixels = screenWidth
            heightPixels = screenHeight
        }
        val resources = mockk<Resources>()
        every { resources.displayMetrics } returns metrics

        context = mockk()
        every { context.resources } returns resources

        privacy = mockk()
        store = mockk(relaxed = true)
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    private fun collector(): MLDataCollector {
        val collector = objenesis.newInstance(MLDataCollector::class.java)
        collector.setField("context", context)
        collector.setField("privacyManager", privacy)
        return collector
    }

    /** A captured swipe: two trace points 50 ms apart, two registered keys, named provenance. */
    private fun capturedSwipe(): SwipeMLData {
        val data = SwipeMLData(
            "captured", "swipe_capture", screenWidth, screenHeight, keyboardHeight, "qwerty", "ctc"
        )
        // Raw px in, normalized out — 108/1080 = 0.1, 240/2400 = 0.1, 540/1080 = 0.5, 1200/2400 = 0.5.
        val t0 = System.currentTimeMillis()
        data.addRawPoint(108f, 240f, t0)
        data.addRawPoint(540f, 1200f, t0 + 50)
        data.addRegisteredKey("h")
        data.addRegisteredKey("i")
        return data
    }

    // ----------------------------------------------------------------- toggle OFF

    @Test
    fun withCollectionDisabledNothingIsStoredAndTheCallerIsTold() {
        every { privacy.canCollectSwipeData() } returns false

        val stored = collector().collectAndStoreSwipeData("hello", capturedSwipe(), keyboardHeight, store)

        assertWithMessage("the collector must report that it stored nothing").that(stored).isFalse()
        // A disabled swipe-data toggle must leave the on-device trace store untouched — this is
        // the whole content of the v1.1.71 privacy promise.
        verify(exactly = 0) { store.storeSwipeData(any()) }
    }

    @Test
    fun theGateIsCheckedBeforeAnythingElseIsTouched() {
        every { privacy.canCollectSwipeData() } returns false

        // Null swipe AND null store: if the gate were checked after the null-guards this would
        // still return false, so the assertion that matters is the ORDER — the gate is asked.
        assertThat(collector().collectAndStoreSwipeData("hello", null, keyboardHeight, null)).isFalse()
        verify(exactly = 1) { privacy.canCollectSwipeData() }
    }

    @Test
    fun aDisabledToggleIsRecheckedOnEveryCallRatherThanCachedAtConstruction() {
        val collector = collector()
        every { privacy.canCollectSwipeData() } returns false
        assertThat(collector.collectAndStoreSwipeData("one", capturedSwipe(), keyboardHeight, store)).isFalse()

        every { privacy.canCollectSwipeData() } returns true
        assertWithMessage(
            "flipping the toggle in Settings must take effect on the very next swipe — a value " +
                "cached in the collector is exactly the 'toggle not working' bug of v1.1.71"
        ).that(collector.collectAndStoreSwipeData("two", capturedSwipe(), keyboardHeight, store)).isTrue()

        every { privacy.canCollectSwipeData() } returns false
        assertThat(collector.collectAndStoreSwipeData("three", capturedSwipe(), keyboardHeight, store)).isFalse()

        verify(exactly = 1) { store.storeSwipeData(any()) }
    }

    // ------------------------------------------------------------------ toggle ON

    @Test
    fun withCollectionEnabledExactlyOneFullyPopulatedRowIsStored() {
        every { privacy.canCollectSwipeData() } returns true
        val captured = slot<SwipeMLData>()
        every { store.storeSwipeData(capture(captured)) } just Runs

        val source = capturedSwipe()
        assertThat(collector().collectAndStoreSwipeData("Hello", source, keyboardHeight, store)).isTrue()

        verify(exactly = 1) { store.storeSwipeData(any()) }
        val row = captured.captured

        assertWithMessage("the selected word is what gets labelled, lowercased by SwipeMLData")
            .that(row.targetWord).isEqualTo("hello")
        assertWithMessage("a row written from a suggestion tap is sourced as such")
            .that(row.collectionSource).isEqualTo("user_selection")
        assertThat(row.screenWidthPx).isEqualTo(screenWidth)
        assertThat(row.screenHeightPx).isEqualTo(screenHeight)
        assertThat(row.keyboardHeightPx).isEqualTo(keyboardHeight)

        assertWithMessage("provenance is CARRIED OVER from the captured trace, not re-derived")
            .that(row.layoutName).isEqualTo("qwerty")
        assertThat(row.engine).isEqualTo("ctc")

        assertWithMessage("every trace point is copied")
            .that(row.getTracePoints()).hasSize(2)
        // Denormalize-then-renormalize must be identity: 0.1 * 1080 = 108, 108 / 1080 = 0.1.
        assertThat(row.getTracePoints()[0].x).isWithin(1e-6f).of(0.1f)
        assertThat(row.getTracePoints()[0].y).isWithin(1e-6f).of(0.1f)
        assertThat(row.getTracePoints()[1].x).isWithin(1e-6f).of(0.5f)
        assertThat(row.getTracePoints()[1].y).isWithin(1e-6f).of(0.5f)

        assertWithMessage("registered keys are copied verbatim and in order")
            .that(row.getRegisteredKeys()).containsExactly("h", "i").inOrder()
    }

    @Test
    fun aRawPrefixedSelectionIsStoredUnderTheBareWord() {
        every { privacy.canCollectSwipeData() } returns true
        val captured = slot<SwipeMLData>()
        every { store.storeSwipeData(capture(captured)) } just Runs

        collector().collectAndStoreSwipeData("raw:kotlin", capturedSwipe(), keyboardHeight, store)

        assertWithMessage("the `raw:` wire prefix is a UI marker, never part of the label")
            .that(captured.captured.targetWord).isEqualTo("kotlin")
    }

    @Test
    fun theInterPointTimingOfTheCapturedTraceSurvivesTheCopy() {
        every { privacy.canCollectSwipeData() } returns true
        val captured = slot<SwipeMLData>()
        every { store.storeSwipeData(capture(captured)) } just Runs

        collector().collectAndStoreSwipeData("hello", capturedSwipe(), keyboardHeight, store)

        // The source's deltas are (1000 - startTs) for the first point and 50 ms for the
        // second. Only the second is a real inter-sample interval, and it is the one a
        // trainer reads; losing it (the pre-fix bug: deltas treated as absolute offsets)
        // would flatten every trace's timing.
        assertWithMessage("the 50 ms gap between the two samples must survive the copy")
            .that(captured.captured.getTracePoints()[1].tDeltaMs).isEqualTo(50L)
    }

    @Test
    fun anEnabledToggleWithNothingCapturedStillStoresNothing() {
        every { privacy.canCollectSwipeData() } returns true

        assertWithMessage("no captured swipe ⇒ nothing to store")
            .that(collector().collectAndStoreSwipeData("hello", null, keyboardHeight, store)).isFalse()
        assertWithMessage("no store ⇒ nothing to store")
            .that(collector().collectAndStoreSwipeData("hello", capturedSwipe(), keyboardHeight, null))
            .isFalse()

        verify(exactly = 0) { store.storeSwipeData(any()) }
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
