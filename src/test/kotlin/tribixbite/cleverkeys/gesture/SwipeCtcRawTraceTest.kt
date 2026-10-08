package tribixbite.cleverkeys

import android.graphics.PointF
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcFeaturizer
import kotlin.math.abs

/**
 * Short-word CTC fix (2026-10-07, `docs/eval/2026-10-07-short-word-ctc.md` §5): the CTC
 * engine featurizes the recognizer's UNSMOOTHED samples plus exactly one sample at finger
 * lift, while the geometric engine keeps the smoothed path.
 *
 * Drives the real [ImprovedSwipeGestureRecognizer] touch-stream sequence
 * (startSwipe / addPoint / recordLift / endSwipe) on a deterministic injected clock with the
 * production smoothing window (3) and noise threshold (1.26 px). The fixture is the
 * investigation's `ad` case: a straight swipe from `a` to `d` that ENDS WITH A 200 ms STOP
 * on `d`. Every stationary sample during the stop moves < 1.26 px and is dropped as noise,
 * so before the fix the stop was invisible to the encoder (no lift sample) and the endpoint
 * lagged one smoothing window behind the finger — the encoder then reads `as`.
 */
class SwipeCtcRawTraceTest {

    private var now = 0L
    private val keyA = letterKey('a')
    private val keyS = letterKey('s')
    private val keyD = letterKey('d')

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        mockkObject(Config.Companion)
        val config = mockk<Config>(relaxed = true)
        // Production defaults for the two pre-processing stages under test.
        config.swipe_smoothing_window = Defaults.SWIPE_SMOOTHING_WINDOW
        config.swipe_noise_threshold = Defaults.SWIPE_NOISE_THRESHOLD
        // Candidacy gates kept real but satisfiable by the fixture.
        config.swipe_min_distance = 30f
        config.swipe_min_key_distance = 20f
        config.swipe_min_dwell_time = 0L
        config.swipe_high_velocity_threshold = 1_000_000f
        every { Config.globalConfig() } returns config
    }

    @After
    fun tearDown() = unmockkAll()

    private fun letterKey(c: Char): KeyboardData.Key =
        KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.makeCharKey(c))

    /** Key centres on a 100 px pitch: a = 50, s = 150, d = 250 (y = 100). */
    private fun keyAt(x: Float) = when {
        x < 100f -> keyA
        x < 200f -> keyS
        else -> keyD
    }

    /** Number of samples the recognizer ACCEPTS for [swipeAToD] (start + 20 moves). */
    private val acceptedSamples = 21

    /**
     * a-centre to d-centre in 20 steps of 10 px every 8 ms (t = 0..160), then a 200 ms stop
     * on `d` with sub-threshold jitter every 8 ms (t = 168..352; the callers lift at
     * t = 360). Returns the recognizer before lift.
     */
    private fun swipeAToD(): ImprovedSwipeGestureRecognizer {
        now = 0L
        val recognizer = ImprovedSwipeGestureRecognizer(clock = { now })
        recognizer.startSwipe(50f, 100f, keyA)
        for (i in 1..20) {
            now += 8
            val x = 50f + 10f * i
            recognizer.addPoint(x, 100f, keyAt(x))
        }
        val jitter = floatArrayOf(0.3f, -0.4f, 0.2f, 0.5f, -0.2f)
        for (i in 0 until 24) {
            now += 8
            recognizer.addPoint(250f + jitter[i % 5], 100f - jitter[(i + 2) % 5], keyD)
        }
        return recognizer
    }

    /** Letter-box normalization of the fixture (1000 x 300 px) for the featurizer. */
    private fun featurize(points: List<PointF>, timestamps: List<Long>): FloatArray =
        CtcFeaturizer.featurize(
            DoubleArray(points.size) { points[it].x / 1000.0 },
            DoubleArray(points.size) { points[it].y / 300.0 },
            DoubleArray(points.size) { timestamps[it].toDouble() },
        )

    @Test
    fun `a stop on d reaches the CTC featurizer at d, not lagged`() {
        val recognizer = swipeAToD()
        now += 8 // ACTION_UP at t = 360: a 200 ms stop after the last move (t = 160)
        recognizer.recordLift(250.2f, 100.1f)
        val result = recognizer.endSwipe()

        val trace = assertNotNull(result.rawTrace)
        assertWithMessage("the finger is ON d when it stops; the CTC trace must end there")
            .that(abs(trace.points[trace.points.size - 2].x - 250f)).isLessThan(0.01f)
        assertThat(abs(trace.points.last().x - 250.2f)).isLessThan(0.01f)
        assertWithMessage("the lift sample carries the stop's duration")
            .that(trace.timestamps.last()).isEqualTo(360L)

        // What the encoder actually sees: the 200 ms stop fills the tail of the 64 columns.
        val features = featurize(trace.points, trace.timestamps)
        val atD = (0 until CtcFeaturizer.RESAMPLE_LENGTH).count { abs(features[it] - 0.25f) < 0.001f }
        assertWithMessage("columns at d's centre (stop = 200 of 360 ms ≈ 35 of 64 columns)")
            .that(atD).isAtLeast(30)
        assertThat(abs(features[CtcFeaturizer.RESAMPLE_LENGTH - 1] - 0.25f)).isLessThan(0.001f)
    }

    /**
     * Typing audit (2026-10-08): a 2 s hold on `d` before lift. Unclamped, the lift sample
     * carried 2000 of 2160 ms and the featurizer's 64 TIME-resampled columns were ~59 copies
     * of `d` — the a→d stroke shrank to a handful of columns. The lift gap is clamped to
     * 500 ms, so the stroke keeps a share of the timeline comparable to the 200 ms `ad` stop.
     */
    @Test
    fun `a two second hold before lift is clamped and the stroke keeps its shape`() {
        val recognizer = swipeAToD()
        now += 1_848 // ACTION_UP at t = 2200: a 2 s hold on d after the last accepted sample (t = 160)
        recognizer.recordLift(250.2f, 100.1f)
        val trace = assertNotNull(recognizer.endSwipe().rawTrace)

        assertWithMessage("the lift gap is clamped to MAX_LIFT_GAP_MS")
            .that(trace.timestamps.last() - trace.timestamps[trace.timestamps.size - 2])
            .isEqualTo(tribixbite.cleverkeys.gesture.ContinuousSwipe.MAX_LIFT_GAP_MS)
        assertThat(abs(trace.points.last().x - 250.2f)).isLessThan(0.01f)

        val features = featurize(trace.points, trace.timestamps)
        val stroke = (0 until CtcFeaturizer.RESAMPLE_LENGTH).count { features[it] < 0.249f }
        assertWithMessage("columns on the a→d stroke (160 of 660 ms ≈ 15 of 64)")
            .that(stroke).isAtLeast(12)
        assertThat(abs(features[CtcFeaturizer.RESAMPLE_LENGTH - 1] - 0.25f)).isLessThan(0.001f)
    }

    @Test
    fun `the lift sample is present exactly once`() {
        val recognizer = swipeAToD()
        now += 4
        recognizer.recordLift(249f, 100f)
        now += 4
        recognizer.recordLift(250.2f, 100.1f) // a later report replaces, never adds
        val trace = assertNotNull(recognizer.endSwipe().rawTrace)
        assertThat(trace.points).hasSize(acceptedSamples + 1)
        assertThat(trace.timestamps.count { it > 160L }).isEqualTo(1)
        assertThat(trace.timestamps.last()).isEqualTo(360L)
    }

    @Test
    fun `no lift sample without a lift or when it is not later than the last sample`() {
        val withoutLift = swipeAToD()
        assertThat(assertNotNull(withoutLift.endSwipe().rawTrace).points).hasSize(acceptedSamples)

        // A lift in the same millisecond as the last accepted sample adds no duplicate.
        now = 0L
        val recognizer = ImprovedSwipeGestureRecognizer(clock = { now })
        recognizer.startSwipe(50f, 100f, keyA)
        for (i in 1..20) { now += 8; val x = 50f + 10f * i; recognizer.addPoint(x, 100f, keyAt(x)) }
        recognizer.recordLift(250f, 100f)
        val trace = assertNotNull(recognizer.endSwipe().rawTrace)
        assertThat(trace.points).hasSize(acceptedSamples)
        assertThat(trace.timestamps.zipWithNext().all { (a, b) -> b > a }).isTrue()
    }

    @Test
    fun `a lift without an active path is ignored`() {
        now = 0L
        val recognizer = ImprovedSwipeGestureRecognizer(clock = { now })
        recognizer.recordLift(10f, 10f)
        assertThat(recognizer.liftSample()).isNull()
        assertThat(recognizer.ctcTrace()).isNull()
    }

    @Test
    fun `the geometric engine keeps the smoothed path`() {
        val recognizer = swipeAToD()
        now += 8
        recognizer.recordLift(250.2f, 100.1f)
        val smoothedBefore = recognizer.getSwipePath()
        val result = recognizer.endSwipe()

        val path = assertNotNull(result.path)
        assertThat(path).hasSize(acceptedSamples)
        assertThat(path.map { it.x to it.y }).isEqualTo(smoothedBefore.map { it.x to it.y })
        assertThat(result.timestamps).hasSize(acceptedSamples)
        assertWithMessage("trailing 3-point average of 230/240/250 — the lag CTC no longer sees")
            .that(abs(path.last().x - 240f)).isLessThan(0.01f)
        // The raw trace is a separate snapshot: identical sample times, unsmoothed positions.
        val trace = assertNotNull(result.rawTrace)
        assertThat(trace.timestamps.take(acceptedSamples)).isEqualTo(result.timestamps)
        assertThat(trace.points.take(acceptedSamples).map { it.x })
            .isEqualTo((0..20).map { 50f + 10f * it })
    }

    @Test
    fun `reset clears the lift`() {
        val recognizer = swipeAToD()
        now += 8
        recognizer.recordLift(250f, 100f)
        recognizer.reset()
        assertThat(recognizer.liftSample()).isNull()
    }

    @Test
    fun `the dispatcher hands CTC the raw trace and geometric the smoothed path`() {
        // Source pin (the coordinator needs a live IME to drive): the ONLY decodeAsync call
        // featurizes the raw trace, and every geometric hand-off keeps swipePath/timestamps.
        val source = java.io.File("src/main/kotlin/tribixbite/cleverkeys/InputCoordinator.kt").readText()
        val ctcBody = source.substringAfter("private fun performCtcSwipeTyping(")
            .substringBefore("\n    private fun ").substringBefore("\n    fun ")
        assertThat(ctcBody).contains("ctcTrace?.points ?: swipePath, ctcTrace?.timestamps ?: timestamps")
        val geometricCalls = Regex("""(?<!fun )performGeometricSwipeTyping\(\s*([^)]*)\)""").findAll(source).toList()
        assertThat(geometricCalls).isNotEmpty()
        for (call in geometricCalls) {
            assertWithMessage("geometric must keep the smoothed path: ${call.value}")
                .that(call.groupValues[1].replace(Regex("\\s+"), " "))
                .startsWith("swipedKeys, swipePath, timestamps,")
        }
        // Pointers: every word-swipe end in onTouchUp records the lift first.
        val pointers = java.io.File("src/main/kotlin/tribixbite/cleverkeys/Pointers.kt").readText()
        val up = pointers.substringAfter("fun onTouchUp(").substringBefore("\n    }\n")
        assertThat(Regex("""_handler\.onSwipeEnd\(""").findAll(up).count()).isEqualTo(1)
        assertThat(up).contains("_swipeRecognizer.recordLift(")
    }

    @Test
    fun `RawSwipeTrace copies its input and rejects malformed input`() {
        val points = mutableListOf(PointF(1f, 2f), PointF(3f, 4f))
        val times = mutableListOf(10L, 20L)
        val trace = assertNotNull(RawSwipeTrace.withLift(points, times, null))
        points[0].x = 99f; points.clear(); times.clear()
        assertThat(trace.points.map { it.x }).containsExactly(1f, 3f).inOrder()
        assertThat(trace.timestamps).containsExactly(10L, 20L).inOrder()
        assertThat(RawSwipeTrace.withLift(emptyList(), emptyList(), null)).isNull()
        assertThat(RawSwipeTrace.withLift(listOf(PointF(0f, 0f)), listOf(1L, 2L), null)).isNull()
        val nanLift = RawSwipeTrace.withLift(listOf(PointF(0f, 0f)), listOf(1L),
            tribixbite.cleverkeys.gesture.ContinuousSwipe.Sample(Float.NaN, 0f, 5L))
        assertThat(assertNotNull(nanLift).points).hasSize(1)
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        assertThat(value).isNotNull()
        return value!!
    }
}
