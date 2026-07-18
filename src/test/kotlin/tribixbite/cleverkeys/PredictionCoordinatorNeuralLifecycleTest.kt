package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.onnx.SwipePredictorOrchestrator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MockK JVM tests for [PredictionCoordinator]'s neural-engine lifecycle fixes:
 *
 *  - **F2**: the coordinator retains ONE [NeuralSwipeTypingEngine] across init attempts, so its
 *    per-instance retry backoff actually engages in production. A second failed attempt inside
 *    the backoff window must NOT re-drive the underlying orchestrator (the expensive model
 *    load). With the old fresh-engine-per-attempt wiring the backoff reset every attempt → the
 *    model load would run on every swipe.
 *  - **F3**: if [PredictionCoordinator.shutdown] runs before a background init publishes its
 *    engine, the coordinator cleans that engine up (releasing its OrtSessions) instead of
 *    publishing it into a torn-down coordinator (which would leak the native sessions).
 *
 * Seams:
 *  - The coordinator builds `NeuralSwipeTypingEngine(context, config)` directly; the engine's
 *    REAL backoff logic is exercised, but its ONNX-bearing orchestrator is mocked via the same
 *    companion-object seam used by [NeuralSwipeTypingEngineInitRetryTest].
 *  - `Handler(Looper.getMainLooper())` (a coordinator field) is neutralized with mockkStatic +
 *    mockkConstructor so construction succeeds off-device. The deterministic backoff tests run
 *    init INLINE (`setRunInitInlineForTest`) so no Handler.post / thread scheduling is involved;
 *    the shutdown-race test uses the real background thread (with a latch-gated orchestrator).
 */
class PredictionCoordinatorNeuralLifecycleTest {

    private lateinit var context: Context
    private lateinit var config: Config
    private lateinit var predictor: SwipePredictorOrchestrator

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        // No Handler/Looper mocking needed: PredictionCoordinator's mainHandler is lazy and is
        // never materialized here — inline tests dispatch the continuation directly (no post),
        // and the shutdown test clears the pending continuation before the attempt completes.

        context = mockk(relaxed = true)
        config = mockk(relaxed = true)
        // Config exposes these as @JvmField (fields, not getters), so set them directly — an
        // `every { }` stub can't record a field access, and Objenesis-built mocks skip field
        // initializers so the fields would otherwise default to false.
        config.swipe_typing_enabled = true
        config.swipe_debug_detailed_logging = false

        predictor = mockk(relaxed = true)
        mockkObject(SwipePredictorOrchestrator.Companion)
        every { SwipePredictorOrchestrator.getInstance(any()) } returns predictor
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `retained engine backoff suppresses second failed init within window`() {
        // Orchestrator init always fails → engine.initialize() fails; a huge backoff window
        // means the retained engine must suppress a second attempt.
        every { predictor.initialize() } returns false

        val coord = PredictionCoordinator(context, config).apply {
            setRunInitInlineForTest(true)
            setNeuralRetryIntervalForTest(60_000L) // second attempt inside the backoff window
        }

        // First attempt: runs, fails, retains the engine.
        coord.runWhenNeuralEngineReady { }
        assertThat(coord.getNeuralEngine()).isNull()

        // Second attempt within the window: the coordinator sees the retained engine is not yet
        // ready to retry (backoff) and does NOT re-drive the orchestrator.
        coord.runWhenNeuralEngineReady { }
        assertThat(coord.getNeuralEngine()).isNull()

        // Orchestrator init was driven exactly ONCE across both attempts — backoff engaged.
        verify(exactly = 1) { predictor.initialize() }
    }

    @Test
    fun `ensureInitialized respects backoff and does not re-drive full load per swipe`() {
        // Mirrors the persistent-failure swipe path: performSwipeTyping -> ensureInitialized().
        // The retained engine's backoff must keep ensureInitialized() from re-driving the
        // orchestrator load on every call within the window.
        every { predictor.initialize() } returns false

        val coord = PredictionCoordinator(context, config).apply {
            setRunInitInlineForTest(true)
            setNeuralRetryIntervalForTest(60_000L)
        }

        // Prime a first (failed) attempt so a retained engine + backoff state exists.
        coord.runWhenNeuralEngineReady { }
        verify(exactly = 1) { predictor.initialize() }

        // Now hammer ensureInitialized() as repeated swipes would: none may re-drive the load.
        repeat(5) { coord.ensureInitialized() }
        verify(exactly = 1) { predictor.initialize() }
    }

    @Test
    fun `failed init retries and recovers after backoff elapses`() {
        every { predictor.initialize() } returnsMany listOf(false, true)

        val coord = PredictionCoordinator(context, config).apply {
            setRunInitInlineForTest(true)
            setNeuralRetryIntervalForTest(0L) // window immediately elapsed → retry proceeds
        }

        coord.runWhenNeuralEngineReady { }
        assertThat(coord.getNeuralEngine()).isNull() // first attempt failed

        coord.runWhenNeuralEngineReady { }
        assertThat(coord.getNeuralEngine()).isNotNull() // recovered on retry

        verify(exactly = 2) { predictor.initialize() }
    }

    @Test
    fun `shutdown before publish cleans up engine instead of leaking it`() {
        // The orchestrator init blocks until we release it, letting shutdown() run first so the
        // background init reaches its publish decision AFTER isShutdown is set.
        val initEntered = CountDownLatch(1)
        val releaseInit = CountDownLatch(1)
        every { predictor.initialize() } answers {
            initEntered.countDown()
            releaseInit.await(5, TimeUnit.SECONDS)
            true // init "succeeds" — but shutdown has already run, so it must not be published.
        }

        // Real background thread here (NOT inline) so shutdown() can race an in-flight init.
        val coord = PredictionCoordinator(context, config)

        coord.runWhenNeuralEngineReady { /* continuation result irrelevant */ }
        assertThat(initEntered.await(5, TimeUnit.SECONDS)).isTrue()

        // Tear down while the init is mid-flight.
        coord.shutdown()

        // Let the init finish; it must observe isShutdown and clean up rather than publish.
        releaseInit.countDown()

        // Wait for the init thread to reach its post-shutdown branch (cleanup).
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && coord.getNeuralEngine() == null) {
            // Give the init thread a chance to release the monitor and finish.
            Thread.sleep(20)
            if (coord.getAsyncPredictionHandler() != null) break
        }

        // Never published, and the freshly-built engine's ONNX resources were released via
        // engine.cleanup() -> orchestrator.cleanup().
        assertThat(coord.getNeuralEngine()).isNull()
        assertThat(coord.getAsyncPredictionHandler()).isNull()
        verify(atLeast = 1) { predictor.cleanup() }
    }
}
