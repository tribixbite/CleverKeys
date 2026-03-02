package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.theme.KeyboardColorScheme
import tribixbite.cleverkeys.theme.darkKeyboardColorScheme
import tribixbite.cleverkeys.theme.lightKeyboardColorScheme
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * Tests for issue #92: Custom background color ignored in custom themes.
 *
 * Root cause: Custom/decorative themes use Config.getThemeId() which maps to
 * R.style.CleverKeysDark. The XML attribute ?attr/colorKeyboard in that style
 * is hardcoded to #1E1030. The custom KeyboardColorScheme.keyboardBackground
 * is stored in Theme.colorNavBar (for system nav bar) but never applied to
 * the actual keyboard view background.
 *
 * Fix: Theme.colorKeyboardBackground must be populated from the color scheme
 * in the runtime constructor, and Keyboard2View must apply it programmatically
 * when using runtime themes.
 */
@RunWith(AndroidJUnit4::class)
class CustomThemeBackgroundTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    // ── Color scheme data integrity ─────────────────────────────────────

    @Test
    fun testDarkColorSchemeHasNonTransparentBackground() {
        val scheme = darkKeyboardColorScheme()
        val bg = scheme.keyboardBackground
        assertNotEquals("Dark scheme background should not be transparent",
            ComposeColor.Transparent, bg)
        // Default dark background is #1E1E1E
        assertEquals("Dark scheme background should be near-black",
            0xFF1E1E1E.toInt(), composeColorToArgb(bg))
    }

    @Test
    fun testLightColorSchemeHasNonTransparentBackground() {
        val scheme = lightKeyboardColorScheme()
        val bg = scheme.keyboardBackground
        assertNotEquals("Light scheme background should not be transparent",
            ComposeColor.Transparent, bg)
    }

    @Test
    fun testCustomColorSchemePreservesBackground() {
        // User sets a custom red background
        val customBg = ComposeColor(0xFFFF0000)
        val scheme = darkKeyboardColorScheme().copy(keyboardBackground = customBg)

        assertEquals("Custom background should be preserved in scheme",
            customBg, scheme.keyboardBackground)
        assertEquals("Custom background ARGB should be red",
            Color.RED, composeColorToArgb(scheme.keyboardBackground))
    }

    @Test
    fun testColorSchemeKeyboardBackgroundIndependentOfKeyDefault() {
        // keyboardBackground and keyDefault are separate properties
        val scheme = darkKeyboardColorScheme().copy(
            keyboardBackground = ComposeColor(0xFFFF0000),  // Red
            keyDefault = ComposeColor(0xFF0000FF)           // Blue
        )
        assertNotEquals("keyboardBackground should differ from keyDefault",
            scheme.keyboardBackground, scheme.keyDefault)
    }

    // ── Theme runtime constructor ───────────────────────────────────────

    @Test
    fun testThemeNavBarMatchesKeyboardBackground() {
        // Theme.colorNavBar is set from keyboardBackground (for system nav bar)
        val customBg = ComposeColor(0xFF00FF00)  // Green
        val scheme = darkKeyboardColorScheme().copy(keyboardBackground = customBg)
        val theme = Theme(context, scheme)

        val expectedArgb = Color.argb(255, 0, 255, 0)
        assertEquals("Theme.colorNavBar should match keyboardBackground",
            expectedArgb, theme.colorNavBar)
    }

    @Test
    fun testThemeKeyboardBackgroundFromRuntimeScheme() {
        // BUG #92: Theme.colorKeyboardBackground should be set from the color scheme's
        // keyboardBackground, but the runtime constructor doesn't populate it.
        // This test FAILS until the bug is fixed.
        val customBg = ComposeColor(0xFFFF0000)  // Red
        val scheme = darkKeyboardColorScheme().copy(keyboardBackground = customBg)
        val theme = Theme(context, scheme)

        val expectedArgb = Color.argb(255, 255, 0, 0)

        // colorKeyboardBackground should expose the custom background color
        // so Keyboard2View can apply it programmatically
        assertNotEquals(
            "Theme.colorKeyboardBackground should not be 0 for runtime themes",
            0, theme.colorKeyboardBackground)
        assertEquals(
            "Theme.colorKeyboardBackground should match custom scheme's keyboardBackground",
            expectedArgb, theme.colorKeyboardBackground)
    }

    @Test
    fun testThemeKeyboardBackgroundNonZeroForGreenScheme() {
        // Different color to verify it's not coincidentally matching
        val customBg = ComposeColor(0xFF00CC00)  // Dark green
        val scheme = darkKeyboardColorScheme().copy(keyboardBackground = customBg)
        val theme = Theme(context, scheme)

        val expectedArgb = Color.argb(255, 0, 204, 0)
        assertEquals(
            "Theme.colorKeyboardBackground should match dark green background",
            expectedArgb, theme.colorKeyboardBackground)
    }

    @Test
    fun testThemeKeyboardBackgroundDiffersFromXmlTheme() {
        // Custom theme should have a DIFFERENT background than the hardcoded XML theme
        val customBg = ComposeColor(0xFF8B0000)  // Dark red
        val scheme = darkKeyboardColorScheme().copy(keyboardBackground = customBg)
        val runtimeTheme = Theme(context, scheme)

        // CleverKeysDark has colorKeyboard=#1E1030
        val cleverKeysDarkBg = Color.parseColor("#1E1030")

        assertNotEquals(
            "Runtime theme background should differ from XML CleverKeysDark",
            cleverKeysDarkBg, runtimeTheme.colorKeyboardBackground)
    }

    // ── XML theme constructor ───────────────────────────────────────────

    @Test
    fun testXmlThemeColorKeyboardAttribute() {
        // Verify that the colorKeyboard attribute is defined and resolvable
        val attrId = context.resources.getIdentifier(
            "colorKeyboard", "attr", context.packageName)
        assertTrue("colorKeyboard attr should be defined", attrId != 0)
    }

    @Test
    fun testCleverKeysDarkHasHardcodedBackground() {
        // CleverKeysDark theme has colorKeyboard=#1E1030
        val styleId = context.resources.getIdentifier(
            "CleverKeysDark", "style", context.packageName)
        if (styleId == 0) return  // Skip if style not found

        val themedCtx = ContextThemeWrapper(context, styleId)
        val attrId = context.resources.getIdentifier(
            "colorKeyboard", "attr", context.packageName)
        val ta = themedCtx.obtainStyledAttributes(intArrayOf(attrId))
        val xmlBg = ta.getColor(0, 0)
        ta.recycle()

        val expectedBg = Color.parseColor("#1E1030")
        assertEquals("CleverKeysDark colorKeyboard should be #1E1030",
            expectedBg, xmlBg)
    }

    @Test
    fun testCustomThemeGetsCleverKeysDarkXmlBase() {
        // Config.getThemeId() maps custom themes to R.style.CleverKeysDark
        // This means the XML-resolved colorKeyboard will always be #1E1030,
        // regardless of the custom theme's keyboardBackground
        val config = Config.globalConfig() ?: return

        // Verify that custom theme names would map to CleverKeysDark
        // (The actual getThemeId logic: "custom_*" → R.style.CleverKeysDark)
        // We can't call getThemeId directly without changing the config,
        // but we can verify the XML theme has the hardcoded color
        val styleId = context.resources.getIdentifier(
            "CleverKeysDark", "style", context.packageName)
        assertTrue("CleverKeysDark style should exist", styleId != 0)
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private fun composeColorToArgb(color: ComposeColor): Int {
        return Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
    }
}
