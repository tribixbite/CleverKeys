package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
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
 *  2. **The prompt fires at most once per session, and never when we ARE the default.**
 *     That is the whole point of the session flag — the toast is a 5-second, unmissable
 *     interruption over whatever the user is typing into.
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

        context = mockk(relaxed = true)
        every { context.contentResolver } returns mockk(relaxed = true)
        every { context.getSystemService(Context.INPUT_METHOD_SERVICE) } returns
            mockk<InputMethodManager>(relaxed = true)

        editor = mockk(relaxed = true)
        every { editor.putBoolean(any(), any()) } returns editor
        prefs = mockk(relaxed = true)
        every { prefs.edit() } returns editor

        handler = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkStatic(Settings.Secure::class)
        unmockkStatic(Log::class)
    }

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
    // checkAndPromptDefaultIME — the once-per-session nag
    // =========================================================================

    @Test
    fun `prompt is scheduled and the session flag is set when we are not the default`() {
        systemDefaultIme("com.example.other/com.example.other.OtherService")
        every { prefs.getBoolean(PROMPT_SHOWN_KEY, false) } returns false

        val delay = slot<Long>()
        every { handler.postDelayed(any(), capture(delay)) } returns true

        IMEStatusHelper.checkAndPromptDefaultIME(context, handler, prefs, PACKAGE, SERVICE)

        verify(exactly = 1) { handler.postDelayed(any(), any()) }
        assertWithMessage("the toast is delayed so it lands after the IME window settles")
            .that(delay.captured).isEqualTo(2000L)
        verify(exactly = 1) { editor.putBoolean(PROMPT_SHOWN_KEY, true) }
        verify(exactly = 1) { editor.apply() }
    }

    @Test
    fun `no prompt when we already are the default`() {
        systemDefaultIme("$PACKAGE/$SERVICE")
        every { prefs.getBoolean(PROMPT_SHOWN_KEY, false) } returns false

        IMEStatusHelper.checkAndPromptDefaultIME(context, handler, prefs, PACKAGE, SERVICE)

        verify(exactly = 0) { handler.postDelayed(any(), any()) }
        verify(exactly = 0) { editor.putBoolean(any(), any()) }
    }

    @Test
    fun `no second prompt once the session flag is set`() {
        systemDefaultIme("com.example.other/com.example.other.OtherService")
        every { prefs.getBoolean(PROMPT_SHOWN_KEY, false) } returns true

        IMEStatusHelper.checkAndPromptDefaultIME(context, handler, prefs, PACKAGE, SERVICE)

        verify(exactly = 0) { handler.postDelayed(any(), any()) }
        // It must also short-circuit BEFORE reading settings — the flag is the cheap guard.
        verify(exactly = 0) { Settings.Secure.getString(any(), any()) }
    }

    @Test
    fun `resetSessionPrompt clears the flag so the next session can prompt again`() {
        IMEStatusHelper.resetSessionPrompt(prefs)
        verify(exactly = 1) { editor.putBoolean(PROMPT_SHOWN_KEY, false) }
        verify(exactly = 1) { editor.apply() }
    }

    private companion object {
        const val PACKAGE = "tribixbite.cleverkeys"
        const val SERVICE = "tribixbite.cleverkeys.CleverKeysService"
        /** Mirrors IMEStatusHelper.PREF_KEY_PROMPT_SHOWN (private there). */
        const val PROMPT_SHOWN_KEY = "ime_prompt_shown_this_session"
    }
}
