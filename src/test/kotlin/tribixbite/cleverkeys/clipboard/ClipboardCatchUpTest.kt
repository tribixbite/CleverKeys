package tribixbite.cleverkeys.clipboard

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.Config
import java.lang.reflect.Field

/**
 * Keyboard-shown clipboard catch-up (Saga report, 2026-10-07: one of four copies made with
 * Chrome's selection-toolbar "Copy" never reached history; the keyboard's own Ctrl+C always did).
 *
 * The platform delivers `onPrimaryClipChanged` to the default IME even while its keyboard is
 * hidden, so a hidden keyboard alone does not lose a copy; a copy made while NO listener is
 * registered (IME process reaped or restarting, another keyboard selected, or registration that
 * bailed at onCreate and was never retried) did — the only other read was at registration. The
 * contract pinned here, for [ClipboardHistoryService.onKeyboardShown]:
 *
 *  1. a clip the service has not observed is recorded when the keyboard is shown;
 *  2. a clip the listener already observed is NOT recorded again (so an entry the user deleted
 *     while it is still on the system clipboard is never resurrected);
 *  3. copying the same text again (a new set event) IS a new observation;
 *  4. clipboard history off: nothing is read or recorded;
 *  5. a skipped clip (IS_SENSITIVE) stays skipped — it is marked observed before filtering;
 *  6. an unregistered listener is (re)registered on show, which performs the read itself.
 *
 * Harness: the real service is allocated without its constructor (its anonymous
 * BroadcastReceiver field cannot be built under android.jar stubs — see PrivateCopyServiceTest),
 * the ClipboardManager is a mock, and the shared store entry [ClipboardHistoryService.addClip]
 * is stubbed on a spy so the test observes exactly what the capture path decided to store.
 */
class ClipboardCatchUpTest {

    private lateinit var cm: ClipboardManager
    private lateinit var context: Context
    private lateinit var config: Config
    private lateinit var service: ClipboardHistoryService

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        setSdkInt(34)

        cm = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.packageName } returns "tribixbite.cleverkeys"
        config = mockk(relaxed = true)
        config.clipboard_history_enabled = true
        config.clipboard_exclude_password_managers = false
        config.clipboard_respect_sensitive_flag = true
        setGlobalConfig(config)

        val raw = allocate(ClipboardHistoryService::class.java)
        setField(raw, "_cm", cm)
        setField(raw, "_context", context)
        setBooleanField(raw, "_isListenerRegistered", true)
        service = spyk(raw)
        every { service.addClip(any()) } just Runs
    }

    @After
    fun tearDown() {
        setGlobalConfig(null)
        setSdkInt(0)
        unmockkAll()
    }

    private fun clip(text: String, setAt: Long, sensitive: Boolean = false): ClipData {
        val item = mockk<ClipData.Item>(relaxed = true) {
            every { this@mockk.text } returns text
            every { uri } returns null
        }
        val extras = mockk<PersistableBundle>(relaxed = true) {
            every { getBoolean("android.content.extra.IS_SENSITIVE", false) } returns sensitive
        }
        val description = mockk<ClipDescription>(relaxed = true) {
            every { timestamp } returns setAt
            every { this@mockk.extras } returns extras
        }
        return mockk(relaxed = true) {
            every { itemCount } returns 1
            every { getItemAt(0) } returns item
            every { this@mockk.description } returns description
        }
    }

    private fun listenerFires() = service.SystemListener().onPrimaryClipChanged()

    @Test
    fun copyTheListenerMissed_isRecordedWhenTheKeyboardShows() {
        every { cm.primaryClip } returns clip("from chrome toolbar", 1_000)
        service.onKeyboardShown()
        verify(exactly = 1) { service.addClip("from chrome toolbar") }
    }

    @Test
    fun clipAlreadyObservedByTheListener_isNotRecordedAgain() {
        every { cm.primaryClip } returns clip("seen", 1_000)
        listenerFires()
        service.onKeyboardShown()
        service.onKeyboardShown()
        verify(exactly = 1) { service.addClip("seen") }
    }

    @Test
    fun sameTextCopiedAgain_isANewObservation() {
        every { cm.primaryClip } returns clip("again", 1_000)
        listenerFires()
        every { cm.primaryClip } returns clip("again", 2_000)
        service.onKeyboardShown()
        verify(exactly = 2) { service.addClip("again") }
    }

    @Test
    fun historyDisabled_readsAndRecordsNothing() {
        config.clipboard_history_enabled = false
        every { cm.primaryClip } returns clip("off", 1_000)
        service.onKeyboardShown()
        verify(exactly = 0) { cm.primaryClip }
        verify(exactly = 0) { service.addClip(any()) }
    }

    @Test
    fun sensitiveClip_isSkippedAndStaysSkipped() {
        every { cm.primaryClip } returns clip("hunter2", 1_000, sensitive = true)
        listenerFires()
        service.onKeyboardShown()
        verify(exactly = 0) { service.addClip(any()) }
    }

    /**
     * A copy made in a password manager is skipped WITHOUT reading its content; it must still be
     * marked observed (from the description's set time) so that showing the keyboard later in an
     * ordinary app does not record the secret through the catch-up.
     */
    @Test
    fun passwordManagerCopy_isNotRecordedByALaterCatchUp() {
        config.clipboard_exclude_password_managers = true
        val usage = mockk<UsageStatsManager>()
        every { context.getSystemService(Context.USAGE_STATS_SERVICE) } returns usage
        fun foreground(pkg: String) {
            val row = mockk<UsageStats>()
            every { row.packageName } returns pkg
            every { row.lastTimeUsed } returns System.currentTimeMillis()
            every { usage.queryUsageStats(any(), any(), any()) } returns listOf(row)
        }
        val secret = clip("correct-horse", 5_000)
        every { cm.primaryClip } returns secret
        every { cm.primaryClipDescription } returns secret.description

        foreground("com.x8bit.bitwarden")
        listenerFires()
        verify(exactly = 0) { cm.primaryClip }

        foreground("com.android.chrome")
        service.onKeyboardShown()
        verify(exactly = 0) { service.addClip(any()) }
    }

    @Test
    fun emptyClipboard_recordsNothing() {
        every { cm.primaryClip } returns null
        service.onKeyboardShown()
        verify(exactly = 0) { service.addClip(any()) }
    }

    @Test
    fun unregisteredListener_isRegisteredOnShowAndReadsTheCurrentClip() {
        setBooleanField(service, "_isListenerRegistered", false)
        mockkStatic(Settings.Secure::class)
        every { Settings.Secure.getString(any(), Settings.Secure.DEFAULT_INPUT_METHOD) } returns
            "tribixbite.cleverkeys/.CleverKeysService"
        every { cm.primaryClip } returns clip("copied while unregistered", 1_000)
        service.onKeyboardShown()
        verify(exactly = 1) { cm.addPrimaryClipChangedListener(any()) }
        verify(exactly = 1) { service.addClip("copied while unregistered") }
    }

    // ------------------------------------------------------------------ pure helper

    @Test
    fun pureDecision() {
        val a = ClipboardCatchUp.fingerprint(1_000, listOf("x"))
        assertTrue(ClipboardCatchUp.shouldRecord(true, a, null))
        assertFalse(ClipboardCatchUp.shouldRecord(true, a, ClipboardCatchUp.fingerprint(1_000, listOf("x"))))
        assertTrue(ClipboardCatchUp.shouldRecord(true, a, ClipboardCatchUp.fingerprint(999, listOf("x"))))
        assertFalse(ClipboardCatchUp.shouldRecord(false, a, null))
        assertFalse(ClipboardCatchUp.shouldRecord(true, null, a))
        // API 24-25: no set timestamp, the content still distinguishes clips.
        assertNotEquals(ClipboardCatchUp.fingerprint(0, listOf("x")), ClipboardCatchUp.fingerprint(0, listOf("y")))
        // With a set time the identity needs no content (password-manager path reads none).
        assertEquals(ClipboardCatchUp.fingerprint(1_000, emptyList()), ClipboardCatchUp.fingerprint(1_000, listOf("x")))
    }

    // ------------------------------------------------------------------ harness

    private fun setGlobalConfig(value: Config?) {
        val companion = Config::class.java.getDeclaredField("Companion").get(null)
        val field = try {
            companion.javaClass.getDeclaredField("_globalConfig")
        } catch (_: NoSuchFieldException) {
            Config::class.java.getDeclaredField("_globalConfig")
        }
        field.isAccessible = true
        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) field.set(null, value) else field.set(companion, value)
    }

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

    private fun declared(target: Any, name: String): Field {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name) } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        throw NoSuchFieldException(name)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val u = unsafe()
        val offset = u.javaClass.getMethod("objectFieldOffset", Field::class.java)
            .invoke(u, declared(target, name)) as Long
        u.javaClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(u, target, offset, value)
    }

    private fun setBooleanField(target: Any, name: String, value: Boolean) {
        val u = unsafe()
        val offset = u.javaClass.getMethod("objectFieldOffset", Field::class.java)
            .invoke(u, declared(target, name)) as Long
        u.javaClass.getMethod("putBoolean", Any::class.java, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(u, target, offset, value)
    }

    /** Build.VERSION.SDK_INT via Unsafe (same idiom as ClipboardLockedStartupTest). */
    private fun setSdkInt(sdkInt: Int) {
        val u = unsafe()
        val field = android.os.Build.VERSION::class.java.getField("SDK_INT")
        val base = u.javaClass.getMethod("staticFieldBase", Field::class.java).invoke(u, field)
        val offset = u.javaClass.getMethod("staticFieldOffset", Field::class.java).invoke(u, field) as Long
        u.javaClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(u, base, offset, sdkInt)
    }
}
