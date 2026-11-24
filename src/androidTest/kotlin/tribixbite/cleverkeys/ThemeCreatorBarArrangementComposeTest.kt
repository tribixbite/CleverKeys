package tribixbite.cleverkeys

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.theme.BarEmphasis
import tribixbite.cleverkeys.theme.CustomTheme
import tribixbite.cleverkeys.theme.LookBar
import tribixbite.cleverkeys.theme.ThemeProvider
import tribixbite.cleverkeys.theme.ThemeSpec
import tribixbite.cleverkeys.theme.darkKeyboardColorScheme

/**
 * The Theme Creator's suggestion-bar controls (2026-10-30): a custom theme whose extension
 * arranges the bar opens in the Creator with the "Suggestion Bar Layout" controls (slots, best
 * word in the middle, quoted typed word, emphasis chips, separators) composed and usable, and the
 * function-key text size and filled-Shift controls present. Writes `oct30-looks/creator-ui-*.png`.
 * Leaves no trace: the custom theme is deleted.
 */
@RunWith(AndroidJUnit4::class)
class ThemeCreatorBarArrangementComposeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val out = File(context.getExternalFilesDir(null), "oct30-looks").apply { mkdirs() }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theCreatorEditsTheBarArrangement() {
        val manager = ThemeProvider.getInstance(context).customThemeManager
        val theme = CustomTheme(
            name = "Bar Test",
            colors = darkKeyboardColorScheme(),
            extension = ThemeSpec(bar = LookBar(slots = 3, quoteTyped = true, emphasis = BarEmphasis.COMMIT)),
        )
        manager.saveCustomTheme(theme)
        try {
            ActivityScenario.launch(ThemeSettingsActivity::class.java).use {
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Bar Test"))
                compose.onNodeWithText("Bar Test").assertIsDisplayed()
                // The "Bar Test" card's own Edit button (2026-11-23, Galaxy Note10+): the list's
                // first "Edit" was another custom theme's when the device had one (a "Harbour"
                // leaked by LayoutThemeSaveAsCustomComposeTest), and that Creator has no
                // "Suggestion Bar Layout" to scroll to.
                compose.onNode(hasContentDescription("Edit") and hasAnyAncestor(hasText("Bar Test"))).performClick()
                compose.onNodeWithText("Suggestion Bar Layout").performScrollTo().assertIsDisplayed()
                for (label in listOf(
                    "Slots", "Best word in the middle", "Typed word in quotation marks",
                    "Bold pending correction", "Dim side words", "Separators between suggestions",
                )) compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Dim side words").performClick()
                compose.onNodeWithText("Hide").performScrollTo().performClick()
                compose.onNodeWithText("Suggestion Bar Layout").performScrollTo()
                shot("creator-ui-bar")
                compose.onNodeWithText("Function Key Text Size").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Filled Shift arrow while shifted").performScrollTo().assertIsDisplayed()
                shot("creator-ui-roles")
            }
        } finally {
            manager.deleteCustomTheme(theme.id)
            commitCustomThemeStore()
        }
    }

    /**
     * Puts the theme store's queued write on disk now (2026-11-23, Galaxy Note10+, one
     * `am instrument` process): `deleteCustomTheme` saves with apply(), and the process was
     * force-stopped ~14 ms after `run finished`, before the write could run, so a deleted theme
     * came back in the next process (LayoutThemeSaveAsCustomComposeTest's "Harbour" did). An
     * empty commit() on the same file (the platform keeps one SharedPreferences per file)
     * returns once every earlier apply() of it is written.
     * `custom_keyboard_themes` in device-protected storage is CustomThemeManager's PREFS_NAME
     * (private there).
     */
    private fun commitCustomThemeStore() {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("custom_keyboard_themes", android.content.Context.MODE_PRIVATE)
            .edit().commit()
    }
}
