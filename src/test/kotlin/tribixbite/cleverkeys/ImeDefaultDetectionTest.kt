package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins v1.0.4's "Better keyboard visibility detection" — [IMEStatusHelper], the helper the
 * IME uses to decide whether CleverKeys is the system's selected keyboard and whether to
 * nag the user about it.
 *
 * Two behaviours matter to users and had nothing pinning them:
 *
 *  1. **Detection is an EXACT match** on `"<package>/<serviceClass>"` against
 *     `Settings.Secure.DEFAULT_INPUT_METHOD`. Substring matching would report the debug
 *     build (`tribixbite.cleverkeys.debug/…`) as "we are the default", and would also
 *     mis-detect any other keyboard whose id happens to embed ours.
 *  2. **The prompt fires at most once per BOOT, never when we ARE the default, and never
 *     once the user says "don't ask again"** (I-7, maintainer decision 2026-09-08). The
 *     toast is a 5-second, unmissable interruption over whatever the user is typing into,
 *     so the guard must survive process death within a boot: the helper persists the BOOT
 *     INSTANT (currentTimeMillis − elapsedRealtime) it last prompted in, and treats two
 *     instants within a small tolerance as the same boot. A new boot yields a new instant
 *     and prompts again; `ime_default_prompt_enabled=false` (written by the Settings
 *     reminder switch) suppresses the prompt permanently.
 *
 * Mock tier (android.jar stubs + MockK): `Settings.Secure.getString` is a static framework
 * call and `Toast`/`Handler` are framework types, so this cannot run in `runPureTests`.
 * Run with `scripts/gradle-guard.sh runMockTests -PtestClass=ImeDefaultDetectionTest`.
 */
class ImeDefaultDetectionTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var handler: Handler

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(Settings.Secure::class)
        // The boot-instant computation needs a stable elapsed-realtime. The SDK
        // stub's method is NATIVE (UnsatisfiedLinkError on the JVM, and MockK
        // cannot instrument natives), so the functional shadow in
        // src/test/kotlin/android/os/SystemClock.kt pins the uptime; the boot
        // instant itself still tracks the real wall clock (see bootInstantNow).
        SystemClock.elapsed = ELAPSED_MS

        context = mockk(relaxed = true)
        every { context.contentResolver } returns mockk(relaxed = true)
        every { context.getSystemService(Context.INPUT_METHOD_SERVICE) } returns
            mockk<InputMethodManager>(relaxed = true)

        editor = mockk(relaxed = true)
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        prefs = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        // Baseline: prompting enabled (the honest default) and never prompted.
        every { prefs.getBoolean(PROMPT_ENABLED_KEY, any()) } returns true
        every { prefs.getLong(LAST_BOOT_KEY, any()) } returns 0L

        handler = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        SystemClock.elapsed = 0L
        unmockkStatic(Settings.Secure::class)
        unmockkStatic(Log::class)
    }

    /**
     * The boot instant the production code derives: wall clock minus uptime.
     * elapsedRealtime is pinned to [ELAPSED_MS], so this is deterministic up
     * to the few milliseconds a test takes — far inside the helper's same-boot
     * tolerance.
     */
    private fun bootInstantNow(): Long = System.currentTimeMillis() - ELAPSED_MS

    private fun systemDefaultIme(value: String?) {
        every {
            Settings.Secure.getString(any(), Settings.Secure.DEFAULT_INPUT_METHOD)
        } returns value
    }

    // =========================================================================
    // isDefaultIME — exact-match detection
    // =========================================================================

    @Test
    fun `isDefaultIME is true only for our exact component id`() {
        systemDefaultIme("$PACKAGE/$SERVICE")
        assertWithMessage("the system's selected IME is literally ours")
            .that(IMEStatusHelper.isDefaultIME(context, PACKAGE, SERVICE)).isTrue()
    }

    @Test
    fun `isDefaultIME is false for another keyboard`() {
        systemDefaultIme("com.example.other/com.example.other.OtherService")
        assertWithMessage("another IME is selected, so we are not the default")
            .that(IMEStatusHelper.isDefaultIME(context, PACKAGE, SERVICE)).isFalse()
    }

    @Test
    fun `isDefaultIME is false for a package that merely contains ours`() {
        // The debug variant ships with applicationIdSuffix '.debug' and coexists with the
        // release build. A substring check would make each report the other as default.
        systemDefaultIme("$PACKAGE.debug/$SERVICE")
        assertWithMessage("'$PACKAGE.debug' is a DIFFERENT app; detection must be exact")
            .that(IMEStatusHelper.isDefaultIME(context, PACKAGE, SERVICE)).isFalse()
    }

    @Test
    fun `isDefaultIME is false for our package with a different service class`() {
        systemDefaultIme("$PACKAGE/tribixbite.cleverkeys.SomeOtherService")
        assertWithMessage("the component id includes the service class, not just the package")
            .that(IMEStatusHelper.isDefaultIME(context, PACKAGE, SERVICE)).isFalse()
    }

    @Test
    fun `isDefaultIME is false when no default IME is recorded`() {
        systemDefaultIme(null)
        assertWithMessage("a null DEFAULT_INPUT_METHOD must not be treated as a match")
            .that(IMEStatusHelper.isDefaultIME(context, PACKAGE, SERVICE)).isFalse()
    }

    @Test
    fun `isDefaultIME returns false instead of propagating a settings failure`() {
        every {
            Settings.Secure.getString(any(), Settings.Secure.DEFAULT_INPUT_METHOD)
        } throws SecurityException("settings unreadable")
        assertWithMessage("a settings read failure must not crash the IME at startup")
            .that(IMEStatusHelper.isDefaultIME(context, PACKAGE, SERVICE)).isFalse()
    }

    // =========================================================================
    // checkAndPromptDefaultIME — the once-per-BOOT nag (I-7, decided 2026-09-08)
    // =========================================================================

    @Test
    fun `prompt is scheduled and this boot's instant is recorded when we are not the default`() {
        systemDefaultIme("com.example.other/com.example.other.OtherService")

        val delay = slot<Long>()
        every { handler.postDelayed(any(), capture(delay)) } returns true

        IMEStatusHelper.checkAndPromptDefaultIME(context, handler, prefs, PACKAGE, SERVICE)

        verify(exactly = 1) { handler.postDelayed(any(), any()) }
        assertWithMessage("the toast is delayed so it lands after the IME window settles")
            .that(delay.captured).isEqualTo(2000L)
        // The once-per-boot guard must survive process death within the boot,
        // so what is persisted is the BOOT INSTANT, not a boolean.
        val recorded = slot<Long>()
        verify(exactly = 1) { editor.putLong(LAST_BOOT_KEY, capture(recorded)) }
        assertWithMessage("the recorded value must be this boot's instant (now − elapsedRealtime)")
            .that(recorded.captured - bootInstantNow() in -5_000L..5_000L).isTrue()
        verify(exactly = 1) { editor.apply() }
    }

    @Test
    fun `no prompt when we already are the default`() {
        systemDefaultIme("$PACKAGE/$SERVICE")

        IMEStatusHelper.checkAndPromptDefaultIME(context, handler, prefs, PACKAGE, SERVICE)

        verify(exactly = 0) { handler.postDelayed(any(), any()) }
        verify(exactly = 0) { editor.putBoolean(any(), any()) }
        // Being the default is not "prompted": nothing may burn this boot's slot.
        verify(exactly = 0) { editor.putLong(any(), any()) }
    }

    /** I-7 red (a): a second check within the SAME boot must be silent. */
    @Test
    fun `no second prompt within the same boot`() {
        systemDefaultIme("com.example.other/com.example.other.OtherService")
        // A prompt already fired this boot — possibly in an earlier PROCESS
        // (IME killed and restarted), which is why the instant is persisted.
        every { prefs.getLong(LAST_BOOT_KEY, any()) } returns bootInstantNow()

        IMEStatusHelper.checkAndPromptDefaultIME(context, handler, prefs, PACKAGE, SERVICE)

        verify(exactly = 0) { handler.postDelayed(any(), any()) }
        // It must also short-circuit BEFORE reading settings — the pref is the cheap guard.
        verify(exactly = 0) { Settings.Secure.getString(any(), any()) }
    }

    /**
     * I-7 red (b): a NEW boot prompts again. The audit's original finding was the
     * inverse bug — the "session" flag was a persistent pref with no reset path, so
     * the prompt fired once per INSTALL; the legacy flag being set must not matter.
     */
    @Test
    fun `a new boot prompts again even though a previous boot already did`() {
        systemDefaultIme("com.example.other/com.example.other.OtherService")
        // Last prompt happened in a boot whose instant is a day older…
        every { prefs.getLong(LAST_BOOT_KEY, any()) } returns bootInstantNow() - 86_400_000L
        // …and the legacy once-per-install flag is even set (real upgraded devices).
        every { prefs.getBoolean(LEGACY_PROMPT_SHOWN_KEY, any()) } returns true

        IMEStatusHelper.checkAndPromptDefaultIME(context, handler, prefs, PACKAGE, SERVICE)

        verify(exactly = 1) { handler.postDelayed(any(), any()) }
        verify(exactly = 1) { editor.putLong(LAST_BOOT_KEY, any()) }
    }

    /** I-7 red (c): "don't ask again" (the Settings reminder switch) suppresses forever. */
    @Test
    fun `don't-ask-again suppresses the prompt even on a new boot`() {
        systemDefaultIme("com.example.other/com.example.other.OtherService")
        every { prefs.getBoolean(PROMPT_ENABLED_KEY, any()) } returns false
        every { prefs.getLong(LAST_BOOT_KEY, any()) } returns 0L  // never prompted

        IMEStatusHelper.checkAndPromptDefaultIME(context, handler, prefs, PACKAGE, SERVICE)

        verify(exactly = 0) { handler.postDelayed(any(), any()) }
        verify(exactly = 0) { Settings.Secure.getString(any(), any()) }
        verify(exactly = 0) { editor.putLong(any(), any()) }
    }

    /**
     * I-7 (comprehensive audit 2026-09-06), the undeferred half: the prompt named the
     * WRONG PRODUCT — a hardcoded "Set Unexpected Keyboard as default …" in an app named
     * CleverKeys. The message must be built from the app-name resource. (The
     * once-per-session vs once-per-install semantics are a separate, deferred
     * maintainer decision and are deliberately not changed or pinned here.)
     */
    @Test
    fun `the prompt names this app via the app_name resource, not Unexpected Keyboard`() {
        systemDefaultIme("com.example.other/com.example.other.OtherService")
        every { context.getString(R.string.app_name) } returns "CleverKeys"

        val toastRunnable = slot<Runnable>()
        every { handler.postDelayed(capture(toastRunnable), any()) } returns true

        mockkStatic(android.widget.Toast::class)
        val toastText = slot<CharSequence>()
        every {
            android.widget.Toast.makeText(any(), capture(toastText), any())
        } returns mockk(relaxed = true)
        try {
            IMEStatusHelper.checkAndPromptDefaultIME(context, handler, prefs, PACKAGE, SERVICE)
            toastRunnable.captured.run()

            assertWithMessage("the prompt must name the app it belongs to")
                .that(toastText.captured.toString()).contains("CleverKeys")
            assertWithMessage("the old product name must be gone")
                .that(toastText.captured.toString()).doesNotContain("Unexpected Keyboard")
        } finally {
            unmockkStatic(android.widget.Toast::class)
        }
    }

    private companion object {
        const val PACKAGE = "tribixbite.cleverkeys"
        const val SERVICE = "tribixbite.cleverkeys.CleverKeysService"
        /** The retired once-per-install flag (I-7): written by pre-2026-09-08 builds. */
        const val LEGACY_PROMPT_SHOWN_KEY = "ime_prompt_shown_this_session"
        /** Mirrors IMEStatusHelper.PREF_KEY_PROMPT_ENABLED (the "don't ask again" pref). */
        const val PROMPT_ENABLED_KEY = "ime_default_prompt_enabled"
        /** Mirrors IMEStatusHelper.PREF_KEY_LAST_PROMPT_BOOT_MS (the once-per-boot record). */
        const val LAST_BOOT_KEY = "ime_prompt_last_boot_ms"
        /** Pinned SystemClock.elapsedRealtime for a deterministic boot instant. */
        const val ELAPSED_MS = 100_000L
    }
}
