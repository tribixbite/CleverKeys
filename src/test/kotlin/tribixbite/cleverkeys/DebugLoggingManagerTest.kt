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
        // DebugLoggingManager's constructor resolves context.filesDir (app-private log
        // path); a relaxed mock returns a File with null path -> NPE. Stub a real temp dir.
        every { mockContext.filesDir } returns java.io.File(System.getProperty("java.io.tmpdir"), "dlm-test").apply { mkdirs() }
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
    // I-8 (comprehensive audit 2026-09-06): the SET_DEBUG_MODE receiver must not be
    // spoofable by third-party apps on API 24-25.
    //
    // A DYNAMICALLY registered receiver without RECEIVER_NOT_EXPORTED receives matching
    // implicit broadcasts from ANY installed app — the old comment's "app-internal
    // broadcast ... not reachable by other apps pre-26" claim is true only for manifest
    // components. A hostile `am broadcast -a tribixbite.cleverkeys.SET_DEBUG_MODE` from
    // another app silently switched on the I-1 recording path with every consent toggle
    // off. The fix registers the pre-26 receiver behind a SIGNATURE-protected permission,
    // so only apps signed with our certificate can deliver the broadcast.
    //
    // These are source/manifest pins: `object : BroadcastReceiver()` calls the
    // android.jar stub constructor (throws "Stub!"), so registerDebugModeReceiver cannot
    // execute in this tier, and framework permission ENFORCEMENT is untestable off-device
    // regardless — what is pinnable is that the permission is declared, signature-level,
    // requested, and actually passed to the pre-26 registration.
    // =========================================================================

    private val managerSource: String by lazy {
        val f = java.io.File("src/main/kotlin/tribixbite/cleverkeys/DebugLoggingManager.kt")
        check(f.isFile) { "${f.path} not found — run with the project root as CWD." }
        f.readText()
    }

    @Test
    fun `pre-26 debug mode receiver is registered behind the signature permission`() {
        val body = managerSource.substringAfter("fun registerDebugModeReceiver(")
            .substringBefore("fun unregisterDebugModeReceiver(")
        assertThat(body).isNotEmpty()
        // The 4-arg (receiver, filter, broadcastPermission, scheduler) form on the else
        // branch — the sender must hold the permission for delivery.
        assertThat(body).contains("registerReceiver(debugModeReceiver, filter, PERMISSION_SET_DEBUG_MODE, null)")
        // And the unguarded 3-arg form must be gone entirely.
        assertThat(body).doesNotContain("registerReceiver(debugModeReceiver, filter)")
    }

    @Test
    fun `the debug mode permission is declared signature-level and requested in the manifest`() {
        val manifest = java.io.File("AndroidManifest.xml").readText()
        // Literal (not the constant) so this test compiles — and stays red — before the fix
        // lands; the source pin above ties the constant name to the registration call.
        val permission = "tribixbite.cleverkeys.permission.SET_DEBUG_MODE"
        assertThat(managerSource).contains("\"$permission\"")
        assertThat(manifest).contains("<permission android:name=\"$permission\" android:protectionLevel=\"signature\"/>")
        assertThat(manifest).contains("<uses-permission android:name=\"$permission\"/>")
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
