package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MockK-based JVM tests for DebugLoggingManager.
 *
 * Tests debug mode state, listener notification, broadcast sending, and close().
 *
 * NOTE: registerDebugModeReceiver/unregisterDebugModeReceiver cannot be tested
 * because they create `object : BroadcastReceiver()` which calls the android.jar
 * stub constructor → RuntimeException("Stub!"). These methods require Robolectric.
 * We test setDebugMode via reflection instead.
 */
class DebugLoggingManagerTest {

    private lateinit var mockContext: Context
    private lateinit var manager: DebugLoggingManager

    private val testPackageName = "tribixbite.cleverkeys"

    @Before
    fun setup() {
        // Mock android.util.Log to prevent "Stub!" exceptions
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockContext = mockk(relaxed = true)
        manager = DebugLoggingManager(mockContext, testPackageName)
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    // =========================================================================
    // Initial state tests
    // =========================================================================

    @Test
    fun `isDebugMode returns false initially`() {
        assertThat(manager.isDebugMode()).isFalse()
    }

    @Test
    fun `getLogFilePath returns correct path`() {
        val path = manager.getLogFilePath()
        assertThat(path).contains("swipe_log.txt")
        assertThat(path).startsWith("/data/")
    }

    // =========================================================================
    // Listener tests (using reflection to call private setDebugMode)
    // =========================================================================

    @Test
    fun `registerDebugModeListener adds listener`() {
        val listener = mockk<DebugLoggingManager.DebugModeListener>(relaxed = true)
        manager.registerDebugModeListener(listener)

        triggerDebugMode(true)

        verify(exactly = 1) { listener.onDebugModeChanged(true) }
    }

    @Test
    fun `registerDebugModeListener is idempotent for same listener`() {
        val listener = mockk<DebugLoggingManager.DebugModeListener>(relaxed = true)
        manager.registerDebugModeListener(listener)
        manager.registerDebugModeListener(listener) // duplicate add

        triggerDebugMode(true)

        // Listener should only be called once (not duplicated)
        verify(exactly = 1) { listener.onDebugModeChanged(true) }
    }

    @Test
    fun `unregisterDebugModeListener removes listener`() {
        val listener = mockk<DebugLoggingManager.DebugModeListener>(relaxed = true)
        manager.registerDebugModeListener(listener)
        manager.unregisterDebugModeListener(listener)

        triggerDebugMode(true)

        // Listener should NOT be called after removal
        verify(exactly = 0) { listener.onDebugModeChanged(any()) }
    }

    @Test
    fun `debug mode toggle notifies listener of disable`() {
        val listener = mockk<DebugLoggingManager.DebugModeListener>(relaxed = true)
        manager.registerDebugModeListener(listener)

        triggerDebugMode(true)
        triggerDebugMode(false)

        verify(ordering = Ordering.ORDERED) {
            listener.onDebugModeChanged(true)
            listener.onDebugModeChanged(false)
        }
    }

    @Test
    fun `multiple listeners all get notified`() {
        val listener1 = mockk<DebugLoggingManager.DebugModeListener>(relaxed = true)
        val listener2 = mockk<DebugLoggingManager.DebugModeListener>(relaxed = true)
        manager.registerDebugModeListener(listener1)
        manager.registerDebugModeListener(listener2)

        triggerDebugMode(true)

        verify(exactly = 1) { listener1.onDebugModeChanged(true) }
        verify(exactly = 1) { listener2.onDebugModeChanged(true) }
    }

    // =========================================================================
    // sendDebugLog tests
    // =========================================================================

    @Test
    fun `sendDebugLog when debug mode off does nothing`() {
        assertThat(manager.isDebugMode()).isFalse()

        manager.sendDebugLog("test message")

        // No broadcast should be sent
        verify(exactly = 0) { mockContext.sendBroadcast(any()) }
    }

    // NOTE: sendDebugLog-sends-broadcast test excluded because Intent(String) constructor
    // is an android.jar stub that throws RuntimeException("Stub!"). mockkConstructor can't
    // intercept android framework class constructors on JVM. Requires Robolectric.

    // =========================================================================
    // close() test
    // =========================================================================

    @Test
    fun `close can be called safely without prior init`() {
        // logWriter is null, close should not throw
        manager.close()
        // Verify no crash — test passes if no exception
    }

    // =========================================================================
    // Helper: trigger debug mode via reflection on private setDebugMode
    // =========================================================================

    /**
     * Invokes the private setDebugMode(Boolean) method via reflection.
     * This avoids the BroadcastReceiver constructor issue that prevents
     * testing through registerDebugModeReceiver.
     */
    private fun triggerDebugMode(enabled: Boolean) {
        every { mockContext.sendBroadcast(any()) } just runs

        val method = DebugLoggingManager::class.java.getDeclaredMethod(
            "setDebugMode", Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(manager, enabled)
    }
}
