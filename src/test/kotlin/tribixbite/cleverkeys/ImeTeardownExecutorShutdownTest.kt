package tribixbite.cleverkeys

import android.os.Handler
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.swipe.CtcEngineAdapter
import tribixbite.cleverkeys.swipe.GeometricEngineAdapter

/**
 * Behaviour pins for the LEAVES of the IME teardown chain: what
 * `SuggestionHandler.shutdown()` and `InputCoordinator.shutdown()` actually DO (audit
 * ARC-016).
 *
 * ## What was already covered, and what was not
 *  - `CleanupHandlerTeardownTest` proves `CleanupHandler.cleanup()` CALLS both `shutdown()`
 *    methods, in the right order — but against MOCK coordinators, so it says nothing about
 *    the bodies of those methods.
 *  - `PredictionTaskRunnerTest` proves the executor wrapper itself shuts down and interrupts
 *    in-flight work.
 *
 * The missing link was the delegation between them. Drop `predictionTasks.shutdown()` from
 * `SuggestionHandler.shutdown()` and every existing test stays green while a single-thread
 * decode executor (plus whatever decode it is running) outlives the IME service.
 * `SuggestionHandler.isPredictionExecutorShutdown()` was added to be asserted here and had
 * zero callers until now.
 *
 * ## Why the objects are allocated without their constructors
 * Both classes initialise `Handler(Looper.getMainLooper())` in a field initialiser. Under
 * `runMockTests` those are android.jar stubs: `mockkStatic` handles `Looper.getMainLooper()`,
 * but the `Handler` CONSTRUCTOR still throws `RuntimeException("Stub!")` — MockK cannot
 * intercept framework constructors on the JVM (`mockkConstructor(Handler::class)` was tried
 * and does not prevent the stub body from running; same conclusion as
 * `DebugLoggingManagerTest`). Robolectric is a declared dependency but no test in this repo
 * runs under it — the ARM64 Termux runners are plain `JUnitCore` + android.jar stubs
 * (`runPureTests` / `runMockTests`), with no Robolectric sandbox.
 *
 * So the instances are allocated with Objenesis (no constructor) and ONLY the fields these
 * two methods touch are seeded. That is not a shortcut around the production code — the real
 * `shutdown()` bodies execute, against a real [PredictionTaskRunner]. Every other field is
 * left null, so a future `shutdown()` that reaches for one fails loudly (NPE naming the
 * field) rather than silently drifting out of coverage.
 */
class ImeTeardownExecutorShutdownTest {

    private val objenesis = ObjenesisStd()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ------------------------------------------------------- SuggestionHandler

    @Test
    fun suggestionHandlerShutdownStopsThePredictionExecutorAndClearsMainThreadWork() {
        val mainHandler = mockk<Handler>(relaxed = true)
        val runner = PredictionTaskRunner()
        val handler = objenesis.newInstance(SuggestionHandler::class.java).apply {
            setField("predictionTasks", runner)
            setField("mainHandler", mainHandler)
        }

        assertWithMessage("a live SuggestionHandler must report a LIVE prediction executor")
            .that(handler.isPredictionExecutorShutdown()).isFalse()

        handler.shutdown()

        assertWithMessage(
            "SuggestionHandler.shutdown() must shut the prediction executor down — otherwise " +
                "the single-thread decode executor (and any in-flight decode) outlives the " +
                "IME service that CleanupHandler.cleanup() just tore down"
        ).that(handler.isPredictionExecutorShutdown()).isTrue()
        assertWithMessage("the real runner must be the one that was shut down")
            .that(runner.isShutdown).isTrue()

        // The other half of teardown: a prediction already POSTED to the main thread must not
        // run after the service is gone (it would touch a released suggestion bar).
        verify(exactly = 1) { mainHandler.removeCallbacksAndMessages(null) }
    }

    @Test
    fun suggestionHandlerShutdownIsIdempotent() {
        val runner = PredictionTaskRunner()
        val handler = objenesis.newInstance(SuggestionHandler::class.java).apply {
            setField("predictionTasks", runner)
            setField("mainHandler", mockk<Handler>(relaxed = true))
        }

        // Teardown can legitimately run twice (an early cleanup followed by onDestroy).
        handler.shutdown()
        handler.shutdown()

        assertWithMessage("repeat shutdown must stay shut down, not throw")
            .that(handler.isPredictionExecutorShutdown()).isTrue()
    }

    @Test
    fun suggestionHandlerShutdownDropsWorkSubmittedAfterwards() {
        val runner = PredictionTaskRunner()
        val handler = objenesis.newInstance(SuggestionHandler::class.java).apply {
            setField("predictionTasks", runner)
            setField("mainHandler", mockk<Handler>(relaxed = true))
        }

        handler.shutdown()

        // A late prediction request (a swipe that lands between shutdown and view teardown)
        // must be dropped, not revive a thread or throw RejectedExecutionException.
        var ran = false
        runner.cancelAndSubmit { ran = true }
        Thread.sleep(50)
        assertWithMessage("work submitted after shutdown must be silently dropped")
            .that(ran).isFalse()
    }

    // ------------------------------------------------------- InputCoordinator

    @Test
    fun inputCoordinatorShutdownStopsBothSwipeAdapterThreads() {
        val geometric = mockk<GeometricEngineAdapter>(relaxed = true)
        val ctc = mockk<CtcEngineAdapter>(relaxed = true)
        val coordinator = objenesis.newInstance(InputCoordinator::class.java).apply {
            setField("syncHandler", mockk<Handler>(relaxed = true))
            setField("geometricAdapter", geometric)
            setField("ctcAdapter", ctc)
        }

        coordinator.shutdown()

        // Step 6/8/G5: IC owns the geometric + CTC decode threads. Each adapter holds a
        // native-ish resource (CTC an ORT session), so both must be released on teardown.
        verify(exactly = 1) { geometric.shutdown() }
        verify(exactly = 1) { ctc.shutdown() }
    }

    @Test
    fun inputCoordinatorShutdownToleratesAdaptersThatWereNeverCreated() {
        // The adapters are created lazily on first swipe; teardown before any swipe (the
        // common case for a session that only ever typed) must not NPE.
        val coordinator = objenesis.newInstance(InputCoordinator::class.java).apply {
            setField("syncHandler", mockk<Handler>(relaxed = true))
        }

        coordinator.shutdown()
    }

    // ------------------------------------------------------------------ helper

    /**
     * Write a private field declared on the receiver's own class. Fails with the declaring
     * class's field list when the name is gone, so a rename produces a diagnosis rather than
     * a `NoSuchFieldException` with no context.
     */
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
