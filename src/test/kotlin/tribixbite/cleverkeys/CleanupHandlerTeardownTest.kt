package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MockK-based JVM tests for [CleanupHandler] teardown ordering.
 *
 * Verifies that the executor-owning coordinators (InputCoordinator, SuggestionHandler) are shut
 * down BEFORE the PredictionCoordinator — so no in-flight prediction task references engines that
 * PredictionCoordinator.shutdown() is about to release — and that null coordinators are tolerated.
 *
 * Mirrors the MockK static-Log setup used by AutocapitalisationTest / DebugLoggingManagerTest.
 */
class CleanupHandlerTeardownTest {

    private lateinit var mockContext: Context
    private lateinit var mockConfigManager: ConfigurationManager
    private lateinit var mockClipboardManager: ClipboardManager
    private lateinit var mockPredictionCoordinator: PredictionCoordinator
    private lateinit var mockDebugLoggingManager: DebugLoggingManager
    private lateinit var mockInputCoordinator: InputCoordinator
    private lateinit var mockSuggestionHandler: SuggestionHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        // ClipboardHistoryService.on_shutdown() is called in cleanup(); mock it to keep the
        // test isolated from real service state. on_shutdown() is a @JvmStatic function on the
        // companion object of a *class* (not a top-level `object`), so mockkStatic(::class) does
        // NOT intercept the companion dispatch on MockK 1.13.8 — the every{} block then sees a
        // real (non-mock) call and fails with "Missing mocked calls". Mocking the Companion
        // instance proxies the @JvmStatic method so the stub takes effect.
        mockkObject(ClipboardHistoryService.Companion)
        every { ClipboardHistoryService.on_shutdown() } just Runs

        mockContext = mockk(relaxed = true)
        mockConfigManager = mockk(relaxed = true)
        mockClipboardManager = mockk(relaxed = true)
        mockPredictionCoordinator = mockk(relaxed = true)
        mockDebugLoggingManager = mockk(relaxed = true)
        mockInputCoordinator = mockk(relaxed = true)
        mockSuggestionHandler = mockk(relaxed = true)

        // cleanup() opens with `configManager?.getFoldStateTracker()?.close()`. getFoldStateTracker()
        // is declared non-null, so a relaxed mock would auto-return a FoldStateTracker child mock —
        // but MockK cannot mock FoldStateTracker on this classpath at all: byte-buddy must retransform
        // the class to intercept close(), and doing so resolves its field type
        // androidx.window.java.layout.WindowInfoTrackerCallbackAdapter, which ships only inside an AAR
        // and is absent from the pure-JVM mock classpath (NoClassDefFoundError, real close() body runs).
        // Force the getter to yield a genuine null so the null-safe production chain
        // (`getFoldStateTracker()?.close()`) short-circuits — a valid "no fold tracker" teardown
        // state that keeps the androidx.window runtime out of the test while leaving the
        // shutdown-ordering assertions intact. This avoids MockK ever proxying FoldStateTracker,
        // which is impossible here: byte-buddy must retransform the class to mock it, and that
        // resolves its field type androidx.window.java.layout.WindowInfoTrackerCallbackAdapter — a
        // class shipped only inside an AAR and absent from the pure-JVM mock classpath. A direct
        // `null as FoldStateTracker` cast throws NPE (the non-null cast intrinsic), so the null is
        // laundered through an unbounded generic (forceNull), whose `null as T` is an *unchecked*
        // cast that carries no runtime null assertion. MockK returns this raw null from the getter.
        every { mockConfigManager.getFoldStateTracker() } returns forceNull()
    }

    /**
     * Produce a genuine `null` typed as any [T] without triggering Kotlin's non-null cast
     * intrinsic. `null as T` for an *unbounded* type parameter compiles to an unchecked cast
     * (no `Intrinsics.checkNotNull`), so the null survives to the call site — unlike
     * `null as FoldStateTracker`, which inserts a runtime assertion that throws NPE.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> forceNull(): T = null as T

    @After
    fun teardown() {
        unmockkStatic(Log::class)
        unmockkObject(ClipboardHistoryService.Companion)
    }

    @Test
    fun cleanupShutsDownExecutorOwnersBeforePredictionCoordinator() {
        val handler = CleanupHandler.create(
            mockContext,
            mockConfigManager,
            mockClipboardManager,
            mockPredictionCoordinator,
            mockDebugLoggingManager,
            mockInputCoordinator,
            mockSuggestionHandler
        )

        handler.cleanup()

        // Executor owners must be torn down before the prediction coordinator releases engines.
        verifyOrder {
            mockInputCoordinator.shutdown()
            mockSuggestionHandler.shutdown()
            mockPredictionCoordinator.shutdown()
        }
    }

    @Test
    fun cleanupWithNullCoordinatorsDoesNotThrow() {
        // The two new coordinator params default to null (older call sites / early teardown).
        val handler = CleanupHandler.create(
            mockContext,
            mockConfigManager,
            mockClipboardManager,
            mockPredictionCoordinator,
            mockDebugLoggingManager
        )

        // Must not NPE despite the null coordinators.
        handler.cleanup()

        verify { mockPredictionCoordinator.shutdown() }
    }
}
