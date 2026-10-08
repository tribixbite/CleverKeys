package tribixbite.cleverkeys

import android.content.res.Resources
import android.graphics.PointF
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.gesture.ContinuousSwipe
import tribixbite.cleverkeys.swipe.CtcEngineAdapter
import tribixbite.cleverkeys.swipe.GeometricEngineAdapter
import tribixbite.cleverkeys.swipe.SwipeEngineRouter
import java.lang.reflect.Field

/**
 * Which touch trace each swipe engine receives from [InputCoordinator.handleSwipeTyping]
 * (short-word fix, 2026-10-07; typing audit, 2026-10-08).
 *
 * The CTC encoder was trained on RAW samples, so it gets the unsmoothed trace plus the lift
 * sample ([RawSwipeTrace]) when the gesture layer supplied one, and the smoothed path otherwise.
 * The geometric engine was tuned on the recognizer's SMOOTHED path and always gets that — on its
 * own route and on every CTC fall-through. The ML capture records both.
 *
 * Behavioural: the real dispatcher runs against fake adapters that only record their inputs.
 * This replaces a source pin that grepped InputCoordinator for the argument text.
 * [InputCoordinator] is allocated without its constructor (it builds a main-looper Handler).
 */
class InputCoordinatorEngineInputTest {

    private lateinit var coordinator: InputCoordinator
    private lateinit var config: Config
    private lateinit var ctc: CtcEngineAdapter
    private lateinit var geometric: GeometricEngineAdapter
    private lateinit var view: Keyboard2View
    private val resources = mockk<Resources>(relaxed = true)

    private val ctcPoints = slot<List<PointF>>()
    private val ctcTimes = slot<List<Long>>()
    private val geoPoints = slot<List<PointF>>()
    private val geoTimes = slot<List<Long>>()

    /** The recognizer's smoothed path (what geometric must see). */
    private val smoothed = listOf(PointF(50f, 100f), PointF(140f, 100f), PointF(240f, 100f))
    private val smoothedTimes = listOf(0L, 80L, 160L)

    /** The unsmoothed trace plus a lift sample (what CTC must see). */
    private val raw = requireNotNull(
        RawSwipeTrace.withLift(
            listOf(PointF(50f, 100f), PointF(150f, 100f), PointF(250f, 100f)),
            listOf(0L, 80L, 160L),
            ContinuousSwipe.Sample(250.2f, 100.1f, 360L),
        )
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        config = mockk(relaxed = true)
        config.swipe_typing_enabled = true
        config.primary_language = "en"
        config.swipe_engine_mode = "ctc"
        config.active_secondary_language = null

        view = mockk(relaxed = true)
        // A Latin layout: the real router sends it to CTC under the "ctc" mode.
        val latin = mockk<KeyboardData>(relaxed = true)
        every { latin.script } returns "latin"
        every { latin.name } returns "latn_qwerty_us"
        every { view.getKeyboard() } returns latin
        every { view.geometryParams() } returns mockk(relaxed = true)
        every { view.width } returns 1000
        every { view.height } returns 300
        mockkObject(KeyboardGeometry)
        every { KeyboardGeometry.computeKeyRects(any<KeyboardData>(), any()) } returns emptyList()

        mockkObject(CtcEngineAdapter.Companion)
        every { CtcEngineAdapter.supportsLanguage(any()) } returns true
        ctc = mockk(relaxed = true)
        every { ctc.isModelPermanentlyUnavailable(any()) } returns false
        every { ctc.hasLexiconSource(any()) } returns true
        every { ctc.supportsLayout(any(), any(), any(), any(), any()) } returns true
        every {
            ctc.decodeAsync(any(), any(), any(), any(), capture(ctcPoints), capture(ctcTimes), any(), any(), any(), any())
        } returns Unit
        geometric = mockk(relaxed = true)
        every {
            geometric.decodeAsync(any(), any(), any(), any(), capture(geoPoints), capture(geoTimes), any(), any())
        } returns Unit

        val predictions = mockk<PredictionCoordinator>(relaxed = true)
        every { predictions.getDictionaryManager() } returns null

        coordinator = allocate(InputCoordinator::class.java)
        setField(coordinator, "config", config)
        setField(coordinator, "contextTracker", PredictionContextTracker())
        setField(coordinator, "predictionCoordinator", predictions)
        setField(coordinator, "keyboardViewProvider", { view })
        setField(coordinator, "ctcAdapter", ctc)
        setField(coordinator, "geometricAdapter", geometric)
    }

    @After
    fun tearDown() = unmockkAll()

    /** Selects the engine through the real router: the "geometric" mode or the default "ctc". */
    private fun route(engine: SwipeEngineRouter.Engine) {
        config.swipe_engine_mode = if (engine == SwipeEngineRouter.Engine.GEOMETRIC) "geometric" else "ctc"
        assertThat(SwipeEngineRouter.route(view.getKeyboard(), SwipeEngineRouter.Mode.fromPref(config.swipe_engine_mode)))
            .isEqualTo(engine)
    }

    private fun swipe(trace: RawSwipeTrace?) = coordinator.handleSwipeTyping(
        swipedKeys = emptyList(), swipePath = smoothed, timestamps = smoothedTimes,
        ic = null, editorInfo = null, resources = resources, ctcTrace = trace,
    )

    @Test
    fun `CTC decodes the raw trace with its lift sample`() {
        route(SwipeEngineRouter.Engine.CTC)
        swipe(raw)

        assertThat(ctcPoints.captured.map { it.x to it.y }).isEqualTo(raw.points.map { it.x to it.y })
        assertThat(ctcTimes.captured).isEqualTo(raw.timestamps)
        verify(exactly = 0) { geometric.decodeAsync(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `CTC falls back to the smoothed path when no raw trace was supplied`() {
        route(SwipeEngineRouter.Engine.CTC)
        swipe(null)

        assertThat(ctcPoints.captured).isSameInstanceAs(smoothed)
        assertThat(ctcTimes.captured).isSameInstanceAs(smoothedTimes)
    }

    @Test
    fun `geometric gets the smoothed path even when a raw trace exists`() {
        route(SwipeEngineRouter.Engine.GEOMETRIC)
        swipe(raw)

        assertThat(geoPoints.captured).isSameInstanceAs(smoothed)
        assertThat(geoTimes.captured).isSameInstanceAs(smoothedTimes)
        verify(exactly = 0) { ctc.decodeAsync(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a CTC fall-through to geometric hands it the smoothed path`() {
        route(SwipeEngineRouter.Engine.CTC)
        every { ctc.supportsLayout(any(), any(), any(), any(), any()) } returns false
        swipe(raw)

        assertWithMessage("letter-incomplete layout: geometric serves it, on the smoothed path")
            .that(geoPoints.captured).isSameInstanceAs(smoothed)
        verify(exactly = 0) { ctc.decodeAsync(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /** The ML capture keeps the smoothed path and adds the raw CTC trace (both engines). */
    @Test
    fun `the ML capture records the raw trace beside the smoothed path`() {
        for (engine in listOf(SwipeEngineRouter.Engine.CTC, SwipeEngineRouter.Engine.GEOMETRIC)) {
            route(engine)
            swipe(raw)
            val capture = requireNotNull(coordinator.getCurrentSwipeData()) { "$engine" }
            assertWithMessage("$engine smoothed").that(capture.getTracePoints()).hasSize(smoothed.size)
            assertWithMessage("$engine raw").that(capture.getRawTracePoints()).hasSize(raw.points.size)

            swipe(null)
            assertWithMessage("$engine without a raw trace")
                .that(requireNotNull(coordinator.getCurrentSwipeData()).getRawTracePoints()).isNull()
        }
    }

    // ------------------------------------------------------------------ harness

    private fun unsafe(): Any {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val u = unsafe()
        return u.javaClass.getMethod("allocateInstance", Class::class.java).invoke(u, type) as T
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val u = unsafe()
        val field: Field = target.javaClass.getDeclaredField(name)
        val offset = u.javaClass.getMethod("objectFieldOffset", Field::class.java).invoke(u, field) as Long
        u.javaClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(u, target, offset, value)
    }
}
