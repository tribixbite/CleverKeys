package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.clipboard.sanitize.systemClipboardRewrite

/**
 * #156 THE load-bearing regression test (design §5.3 / §8): the private-copy path stores privately
 * and never touches the OS clipboard.
 *
 * HARNESS NOTE: the full [ClipboardHistoryService] constructor initializes an anonymous
 * `BroadcastReceiver` field, whose android.jar stub super-constructor throws "Stub!" — and MockK
 * cannot intercept a superclass constructor of an anonymous inner class. So a JVM MockK test cannot
 * construct the real service. We therefore pin the invariant at the two reachable seams:
 *
 *   1. [ClipboardHistoryService.privateCopy] (the static entry point used by BOTH entry points)
 *      delegates ONLY to [ClipboardHistoryService.addPrivateClip] and returns false for empty text.
 *      Crucially it does NOT call any OS-clipboard API on that path (verified against a spy).
 *   2. [systemClipboardRewrite] — the SOLE `setPrimaryClip` decision in the store core — returns
 *      null (⇒ no OS write) unless explicitly enabled. The private path passes `rewriteOsClipboard
 *      = false`, so this is never even reached; this unit assertion documents/locks the gate.
 *
 * The end-to-end DB-insert + `verify(exactly=0){ setPrimaryClip }` across the *constructed* service
 * is exercised in the instrumented PrivateCopyProcessTextActivityTest / a service androidTest, where
 * the real Android runtime has no android.jar stubs.
 */
class PrivateCopyServiceTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        // android.jar stubs report Build.VERSION.SDK_INT = 0, which makes get_service() bail via its
        // `SDK_INT <= 11` guard. Force a modern API (same Unsafe trick as VibratorCompatTest).
        setSdkInt(34)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Seam 1: static privateCopy delegates only to addPrivateClip, no OS write ──────────

    @Test
    fun privateCopy_delegatesToAddPrivateClip_withTextAndProvenance() {
        val service = mockk<ClipboardHistoryService>(relaxed = true)
        val ctx = mockk<Context>(relaxed = true)
        mockkObject(ClipboardHistoryService.Companion)
        every { ClipboardHistoryService.get_service(any()) } returns service

        val stored = ClipboardHistoryService.privateCopy(ctx, "secret token", "com.bank.app")

        assertTrue(stored)
        // SECURITY: the ONLY store call on the private path is addPrivateClip(text, source).
        verify(exactly = 1) { service.addPrivateClip("secret token", "com.bank.app") }
        // addClip (the OS-listener, setPrimaryClip-capable path) is NEVER used by private copy.
        verify(exactly = 0) { service.addClip(any()) }
    }

    @Test
    fun privateCopy_nullProvenance_stillDelegates() {
        val service = mockk<ClipboardHistoryService>(relaxed = true)
        val ctx = mockk<Context>(relaxed = true)
        mockkObject(ClipboardHistoryService.Companion)
        every { ClipboardHistoryService.get_service(any()) } returns service

        assertTrue(ClipboardHistoryService.privateCopy(ctx, "note", null))
        verify(exactly = 1) { service.addPrivateClip("note", null) }
    }

    @Test
    fun privateCopy_emptyOrNullText_rejectedWithoutTouchingService() {
        val service = mockk<ClipboardHistoryService>(relaxed = true)
        val ctx = mockk<Context>(relaxed = true)
        mockkObject(ClipboardHistoryService.Companion)
        every { ClipboardHistoryService.get_service(any()) } returns service

        assertFalse(ClipboardHistoryService.privateCopy(ctx, "", "com.x"))
        assertFalse(ClipboardHistoryService.privateCopy(ctx, null, "com.x"))
        // Empty text short-circuits BEFORE resolving/using the service — no store, no OS write.
        verify(exactly = 0) { service.addPrivateClip(any(), any()) }
        verify(exactly = 0) { ClipboardHistoryService.get_service(any()) }
    }

    @Test
    fun privateCopy_serviceUnavailable_returnsFalse() {
        val ctx = mockk<Context>(relaxed = true)
        mockkObject(ClipboardHistoryService.Companion)
        every { ClipboardHistoryService.get_service(any()) } returns null
        assertFalse(ClipboardHistoryService.privateCopy(ctx, "text", "com.x"))
    }

    // ── Seam 2: the sole setPrimaryClip decision is gated OFF unless explicitly enabled ───

    @Test
    fun systemClipboardRewrite_returnsNull_whenDisabled_soNoOsWrite() {
        // The private path passes rewriteOsClipboard=false and NEVER calls this. Even if it did,
        // `enabled=false` ⇒ null ⇒ no rewriteSystemClipboard/setPrimaryClip. Locks the gate.
        assertNull(systemClipboardRewrite(original = "https://x.com?utm=1", processed = "https://x.com", enabled = false))
    }

    @Test
    fun systemClipboardRewrite_returnsNull_whenContentUnchanged() {
        // Even enabled, an unchanged clip ⇒ null ⇒ no OS write (idempotency guard).
        assertNull(systemClipboardRewrite(original = "plain text", processed = "plain text", enabled = true))
    }

    @Test
    fun systemClipboardRewrite_returnsProcessed_onlyWhenEnabledAndChanged() {
        // Positive control: the OS clipboard is written ONLY on the sanitize path (never private).
        assertEquals("https://x.com", systemClipboardRewrite("https://x.com?utm=1", "https://x.com", enabled = true))
    }

    // ── tiny asserts (dependency-light) ──────────────────────────────────────────────────
    private fun assertTrue(b: Boolean) { if (!b) throw AssertionError("expected true") }
    private fun assertFalse(b: Boolean) { if (b) throw AssertionError("expected false") }
    private fun assertNull(o: Any?) { if (o != null) throw AssertionError("expected null, was $o") }
    private fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("expected $expected but was $actual")
    }

    /** Set Build.VERSION.SDK_INT via sun.misc.Unsafe (Java 17+ removed Field.modifiers access). */
    private fun setSdkInt(sdkInt: Int) {
        try {
            val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val unsafeClass = unsafe.javaClass
            val field = android.os.Build.VERSION::class.java.getField("SDK_INT")
            val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field)
            val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field) as Long
            unsafeClass.getMethod("putInt", Object::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(unsafe, base, offset, sdkInt)
        } catch (_: Exception) { /* falls back to android.jar default (0) */ }
    }
}
