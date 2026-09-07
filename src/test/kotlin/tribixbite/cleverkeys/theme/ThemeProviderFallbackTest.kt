package tribixbite.cleverkeys.theme

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.Theme

/**
 * Audit 2026-09-06, H-2 (P1): a dangling `theme` pref must NEVER crash the IME.
 *
 * ## The crash loop
 *
 * Deleting the currently-selected custom theme leaves `theme=custom_<uuid>` behind
 * (the two live in different prefs files, so nothing keeps them coherent). On the next
 * IME cold start `Keyboard2View.init` runs
 * `ThemeProvider.getInstance(context).getTheme(snap.themeName)` with NO try/catch, and
 * `loadCustomTheme` threw `IllegalStateException("Custom theme not found")` straight
 * into the view constructor — every subsequent keyboard inflation crashed until the
 * user somehow changed the theme pref (with no working keyboard to do it with). The
 * same class exists for a stale `decorative_<id>` restored from a backup taken on a
 * build with different decorative IDs.
 *
 * ## The contract pinned here
 *
 * [ThemeProvider.getTheme] is TOTAL: for any theme id — dangling custom, unknown
 * decorative, garbage — it returns a usable [Theme] (the default cleverkeysdark
 * palette) instead of throwing. The fallback is built from the in-code colour scheme
 * (not XML style resolution) so the escape hatch has no failure modes of its own.
 *
 * Red (pre-fix):
 *   `java.lang.IllegalStateException: Custom theme not found: custom_nonexistent`
 *
 * Mock-tier: [Theme]'s runtime constructor touches `android.graphics.Color` statics and
 * the cached key font, which are stubbed here; the colour mapping itself is pure math.
 */
class ThemeProviderFallbackTest {

    private lateinit var context: Context
    private lateinit var customThemeManager: CustomThemeManager
    private lateinit var provider: ThemeProvider

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        // Theme's private toArgb/adjustLight/isColorLight go through android.graphics.Color
        // statics, which the android.jar stubs throw on. Re-implement the pure ones; HSV is
        // not asserted on (greyedLabelColor only) so it may collapse to 0.
        mockkStatic(Color::class)
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } answers {
            (arg<Int>(0) shl 24) or (arg<Int>(1) shl 16) or (arg<Int>(2) shl 8) or arg<Int>(3)
        }
        every { Color.red(any<Int>()) } answers { (firstArg<Int>() shr 16) and 0xFF }
        every { Color.green(any<Int>()) } answers { (firstArg<Int>() shr 8) and 0xFF }
        every { Color.blue(any<Int>()) } answers { firstArg<Int>() and 0xFF }
        every { Color.colorToHSV(any<Int>(), any<FloatArray>()) } just runs
        every { Color.HSVToColor(any<FloatArray>()) } returns 0

        // The key font is a process-lifetime companion cache; pre-seed it so the runtime
        // constructor's getKeyFont() never reaches Typeface.createFromAsset.
        Theme::class.java.getDeclaredField("_key_font")
            .apply { isAccessible = true }
            .set(null, mockk<Typeface>())

        context = mockk(relaxed = true)
        customThemeManager = mockk()
        provider = ThemeProvider(context, customThemeManager)
    }

    @After
    fun teardown() {
        Theme::class.java.getDeclaredField("_key_font")
            .apply { isAccessible = true }
            .set(null, null)
        unmockkAll()
    }

    /** The same conversion [Theme]'s private toArgb applies, for exact expectations. */
    private fun androidx.compose.ui.graphics.Color.toThemeArgb(): Int =
        ((alpha * 255).toInt() shl 24) or
            ((red * 255).toInt() shl 16) or
            ((green * 255).toInt() shl 8) or
            (blue * 255).toInt()

    // ------------------------------------------------------------- dangling custom id

    @Test
    fun danglingCustomThemeId_fallsBackToDefaultThemeInsteadOfThrowing() {
        every { customThemeManager.getCustomTheme("custom_nonexistent") } returns null

        // Pre-fix: IllegalStateException("Custom theme not found: custom_nonexistent")
        // thrown from here — in production, straight into Keyboard2View's constructor.
        val theme = provider.getTheme("custom_nonexistent")

        val expected = provider.getColorScheme("cleverkeysdark")
        assertWithMessage("fallback must carry the default cleverkeysdark palette")
            .that(theme.colorKeyboardBackground)
            .isEqualTo(expected!!.keyboardBackground.toThemeArgb())
        assertThat(theme.colorKey).isEqualTo(expected.keyDefault.toThemeArgb())
    }

    // ------------------------------------------------------------- stale decorative id

    @Test
    fun unknownDecorativeThemeId_fallsBackToDefaultThemeInsteadOfThrowing() {
        // Pre-fix: IllegalArgumentException("Unknown decorative theme: ...") — reachable
        // via a backup restored from a build with different decorative IDs.
        val theme = provider.getTheme("decorative_from_some_other_build")

        val expected = provider.getColorScheme("cleverkeysdark")
        assertThat(theme.colorKeyboardBackground)
            .isEqualTo(expected!!.keyboardBackground.toThemeArgb())
    }

    // ------------------------------------------------------------- intact path untouched

    @Test
    fun existingCustomTheme_stillLoadsItsOwnColours() {
        val scheme = darkKeyboardColorScheme().copy(
            keyDefault = androidx.compose.ui.graphics.Color(0xFF123456),
            keyboardBackground = androidx.compose.ui.graphics.Color(0xFF654321),
        )
        every { customThemeManager.getCustomTheme("custom_mine") } returns CustomTheme(
            id = "mine",
            name = "Mine",
            colors = scheme,
        )

        val theme = provider.getTheme("custom_mine")

        assertThat(theme.colorKeyboardBackground).isEqualTo(scheme.keyboardBackground.toThemeArgb())
        assertThat(theme.colorKey).isEqualTo(scheme.keyDefault.toThemeArgb())
    }
}
