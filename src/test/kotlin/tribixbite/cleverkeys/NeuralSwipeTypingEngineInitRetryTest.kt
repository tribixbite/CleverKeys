package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.onnx.SwipePredictorOrchestrator

/**
 * MockK JVM tests for [NeuralSwipeTypingEngine]'s retryable initialization
 * (Fix 6). Verifies:
 *  - A failed initialize() is retryable (no longer permanently wedges the engine).
 *  - A later attempt recovers once the predictor succeeds, and success is sticky.
 *  - The backoff interval suppresses rapid retry storms after a failure.
 *  - cleanup() resets init state so a re-init actually re-runs.
 *
 * Seam: NeuralSwipeTypingEngine's constructor calls
 * SwipePredictorOrchestrator.getInstance(context). getInstance is a @JvmStatic
 * function on the companion object of a *class* (not a top-level `object`), so
 * mockkStatic(::getInstance) does NOT intercept the companion dispatch on MockK
 * 1.13.8 — the real body runs and calls context.applicationContext, throwing
 * AbstractMethodError on the relaxed Context mock. The working seam is
 * mockkObject(SwipePredictorOrchestrator.Companion), which proxies the companion
 * instance so the @JvmStatic getInstance is intercepted before its body runs.
 */
class NeuralSwipeTypingEngineInitRetryTest {

    private lateinit var context: Context
    private lateinit var predictor: SwipePredictorOrchestrator
    private lateinit var config: Config

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        context = mockk(relaxed = true)
        config = mockk(relaxed = true)

        // Relaxed predictor mock: initializeAsync() (called from the engine
        // constructor) and all other calls are no-ops unless stubbed per-test.
        predictor = mockk(relaxed = true)

        // Intercept the @JvmStatic factory so the engine wires to our mock. The
        // factory lives on the companion object, so we mock the Companion instance
        // (mockkStatic(::getInstance) does not intercept @JvmStatic companion
        // dispatch on this MockK version, letting the real body run).
        mockkObject(SwipePredictorOrchestrator.Companion)
        every { SwipePredictorOrchestrator.getInstance(any()) } returns predictor
    }

    @After
    fun teardown() {
        unmockkObject(SwipePredictorOrchestrator.Companion)
        unmockkStatic(Log::class)
    }

    /** Build an engine with backoff disabled (retry immediately) unless overridden. */
    private fun newEngine(retryIntervalMs: Long = 0L): NeuralSwipeTypingEngine {
        val engine = NeuralSwipeTypingEngine(context, config)
        engine.initRetryIntervalMs = retryIntervalMs
        return engine
    }

    @Test
    fun `failed init is retryable`() {
        every { predictor.initialize() } returns false

        val engine = newEngine()

        assertThat(engine.initialize()).isFalse()
        assertThat(engine.initialize()).isFalse()

        // Both attempts actually re-ran the predictor init (no permanent wedge).
        verify(exactly = 2) { predictor.initialize() }
        assertThat(engine.getLastError()).isNotNull()
    }

    @Test
    fun `failure then success recovers and success is sticky`() {
        every { predictor.initialize() } returnsMany listOf(false, true)

        val engine = newEngine()

        assertThat(engine.initialize()).isFalse() // 1st: fails
        assertThat(engine.initialize()).isTrue()  // 2nd: succeeds
        // 3rd call must short-circuit on `initialized` and NOT re-init.
        assertThat(engine.initialize()).isTrue()

        verify(exactly = 2) { predictor.initialize() }
        assertThat(engine.getLastError()).isNull()
    }

    @Test
    fun `backoff suppresses rapid retries after failure`() {
        every { predictor.initialize() } returns false

        // Large interval → the second back-to-back attempt is suppressed.
        val engine = newEngine(retryIntervalMs = 60_000L)

        assertThat(engine.initialize()).isFalse()
        assertThat(engine.initialize()).isFalse()

        verify(exactly = 1) { predictor.initialize() }
    }

    @Test
    fun `cleanup resets init state so re-init re-runs`() {
        every { predictor.initialize() } returns true

        val engine = newEngine()

        assertThat(engine.initialize()).isTrue()
        engine.cleanup()
        // After cleanup, initialized is reset → this re-runs the predictor init.
        assertThat(engine.initialize()).isTrue()

        verify(exactly = 2) { predictor.initialize() }
    }

    // --- isReadyToRetryInit() (F2): the side-effect-free predicate PredictionCoordinator uses
    // to decide whether a (re)attempt would actually run vs. be suppressed by the backoff. ---

    @Test
    fun `isReadyToRetryInit true before any attempt`() {
        val engine = newEngine()
        assertThat(engine.isReadyToRetryInit()).isTrue()
    }

    @Test
    fun `isReadyToRetryInit false inside backoff window after failure`() {
        every { predictor.initialize() } returns false
        val engine = newEngine(retryIntervalMs = 60_000L)

        assertThat(engine.initialize()).isFalse()
        // Still inside the (huge) backoff window → not ready to retry, and a call would be
        // suppressed without touching the predictor.
        assertThat(engine.isReadyToRetryInit()).isFalse()
        assertThat(engine.initialize()).isFalse()
        verify(exactly = 1) { predictor.initialize() }
    }

    @Test
    fun `isReadyToRetryInit true once backoff window elapses`() {
        every { predictor.initialize() } returns false
        // Zero interval → the window is immediately elapsed after a failure.
        val engine = newEngine(retryIntervalMs = 0L)

        assertThat(engine.initialize()).isFalse()
        assertThat(engine.isReadyToRetryInit()).isTrue()
    }

    @Test
    fun `isReadyToRetryInit false once initialized`() {
        every { predictor.initialize() } returns true
        val engine = newEngine()

        assertThat(engine.initialize()).isTrue()
        // Already initialized → never a candidate for retry.
        assertThat(engine.isReadyToRetryInit()).isFalse()
    }

    /**
     * F2 core: the retry backoff is state carried by ONE engine instance. This is exactly the
     * property PredictionCoordinator now relies on by retaining a single engine across attempts.
     * If the coordinator (or anything) built a fresh engine per attempt, this shared-state
     * backoff would reset and the second back-to-back attempt would re-run — the very
     * retry-storm the backoff exists to prevent. Here we prove that a SECOND attempt on the SAME
     * instance, within the window, is suppressed.
     */
    @Test
    fun `backoff state persists across attempts on a retained instance`() {
        every { predictor.initialize() } returns false
        val engine = newEngine(retryIntervalMs = 60_000L)

        // Two attempts on the same retained instance within the window.
        assertThat(engine.initialize()).isFalse()
        assertThat(engine.initialize()).isFalse()

        // Only the first actually drove the predictor; the second was skipped by backoff.
        verify(exactly = 1) { predictor.initialize() }
    }
}
