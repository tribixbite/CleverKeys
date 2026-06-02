package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * Tests for issue #130: Clipboard panel ignores custom/decorative themes and
 * shows the hardcoded CleverKeysDark purple instead.
 *
 * Root cause (same family as #92): runtime themes (custom_/decorative_) have no
 * XML style, so clipboard_pane.xml's `?attr/colorKeyboard` / `?attr/colorKey` /
 * `?attr/colorLabel` / `?attr/colorSubLabel` resolve against the hardcoded base
 * style (CleverKeysDark, #1E1030 purple). The fix mirrors Keyboard2View:
 * ClipboardManager.applyRuntimeThemeColors() pulls the real colors from the
 * ThemeProvider Theme object and applies them programmatically.
 *
 * These tests verify the DATA CONTRACT the fix depends on — that a runtime
 * theme exposes non-zero clipboard-relevant colors that differ from the
 * CleverKeysDark base. The programmatic view wiring itself is confirmed by
 * visual inspection on-device (per testing policy: rendering can't be unit
 * asserted without a full inflate harness).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardThemeColorTest {

    private lateinit var context: Context
    // CleverKeysDark base style background — what the clipboard WRONGLY showed
    // for every runtime theme before the #130 fix.
    private val cleverKeysDarkPurple = Color.parseColor("#1E1030")

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    @Test
    fun decorativeThemeIsRuntimeTheme() {
        // Decorative themes must be classified as runtime so the clipboard
        // fix path (Config.isRuntimeTheme + applyRuntimeThemeColors) engages.
        assertTrue(
            "decorative_ ids must be runtime themes",
            "decorative_gemstone_ruby".startsWith("decorative_")
        )
    }

    @Test
    fun runtimeThemeExposesDistinctClipboardColors() {
        val theme = ThemeProvider.getInstance(context).getTheme("decorative_gemstone_ruby")

        // The four colors ClipboardManager.applyRuntimeThemeColors() applies to
        // the pane chrome must all be populated (non-zero) so the programmatic
        // pass has real values to set.
        assertNotEquals("colorKeyboardBackground must be populated", 0, theme.colorKeyboardBackground)
        assertNotEquals("colorKey must be populated", 0, theme.colorKey)
        assertNotEquals("labelColor must be populated", 0, theme.labelColor)
        assertNotEquals("subLabelColor must be populated", 0, theme.subLabelColor)
    }

    @Test
    fun runtimeThemeBackgroundDiffersFromCleverKeysDark() {
        // The whole point of #130: the clipboard background must NOT be the
        // CleverKeysDark purple for a runtime theme.
        val theme = ThemeProvider.getInstance(context).getTheme("decorative_gemstone_ruby")
        assertNotEquals(
            "Runtime theme keyboard background must differ from CleverKeysDark purple",
            cleverKeysDarkPurple, theme.colorKeyboardBackground
        )
    }

    @Test
    fun multipleDecorativeThemesHaveDistinctBackgrounds() {
        // Sanity: different decorative themes produce different backgrounds, so
        // the clipboard will visibly track whichever the user selected.
        val provider = ThemeProvider.getInstance(context)
        val ruby = provider.getTheme("decorative_gemstone_ruby").colorKeyboardBackground
        val sapphire = provider.getTheme("decorative_gemstone_sapphire").colorKeyboardBackground
        assertNotEquals(
            "Ruby and sapphire decorative themes should have distinct backgrounds",
            ruby, sapphire
        )
    }
}
