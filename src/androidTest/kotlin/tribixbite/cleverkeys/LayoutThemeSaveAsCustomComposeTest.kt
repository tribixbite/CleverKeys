package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * Layout Manager → "Save theme as custom theme" (maintainer 2026-10-27: "export theme meaning it
 * will show up in theme editor as a custom theme"): a custom layout whose XML carries a
 * `<theme>` (the worked example, androidTest assets layouts/harbour.xml) is saved through THE
 * ThemeProvider's store under the layout's name without losing a field, Theme Manager lists it
 * and the Theme Creator opens it with its "Keyboard Look" section. Leaves no trace: the layouts
 * preference and the custom-theme store are restored.
 */
@RunWith(AndroidJUnit4::class)
class LayoutThemeSaveAsCustomComposeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aLayoutsThemeIsSavedAsACustomThemeThatThemeManagerAndTheCreatorShow() {
        val xml = instrumentation.context.assets.open("layouts/harbour.xml").bufferedReader().readText()
        val expected = requireNotNull(KeyboardData.load_string(xml)?.embedded_theme)
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val savedLayouts = prefs.getString(LayoutsPreference.KEY, null)
        val manager = ThemeProvider.getInstance(context).customThemeManager
        val before = manager.customThemes.value.map { it.id }.toSet()
        try {
            prefs.edit().apply {
                LayoutsPreference.saveToPreferences(this, listOf(LayoutsPreference.CustomLayout.parse(xml)))
            }.commit()
            ActivityScenario.launch(LayoutManagerActivity::class.java).use {
                compose.onNodeWithText("Save theme as custom theme").performScrollTo().performClick()
                compose.waitUntil(5_000L) { manager.customThemes.value.any { it.id !in before } }
            }
            val saved = manager.customThemes.value.single { it.id !in before }
            assertEquals("named after the layout", "Harbour", saved.name)
            assertEquals("nothing of the layout's <theme> is lost", expected, saved.fullSpec())
            assertNotNull(ThemeProvider.getInstance(context).getColorScheme("custom_${saved.id}"))

            ActivityScenario.launch(ThemeSettingsActivity::class.java).use {
                // Theme Manager is a LazyColumn: the Custom section is composed once scrolled to.
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Harbour"))
                compose.onNodeWithText("Harbour").assertIsDisplayed()
                // The Harbour card's own Edit button: a real phone can hold other custom themes
                // (2026-11-23, Galaxy Note10+), so the list's first "Edit" may be another card's.
                compose.onNode(hasContentDescription("Edit") and hasAnyAncestor(hasText("Harbour"))).performClick()
                compose.onNodeWithText("Keyboard Look").performScrollTo().assertIsDisplayed()
            }
        } finally {
            manager.customThemes.value.filter { it.id !in before }.forEach { manager.deleteCustomTheme(it.id) }
            commitCustomThemeStore()
            prefs.edit().apply {
                if (savedLayouts == null) remove(LayoutsPreference.KEY) else putString(LayoutsPreference.KEY, savedLayouts)
            }.commit()
        }
    }

    /**
     * Puts the theme store's queued write on disk now (2026-11-23, Galaxy Note10+, one
     * `am instrument` process): `deleteCustomTheme` saves with apply(), and the process was
     * force-stopped ~14 ms after `run finished`, before the write could run, so this test's
     * "Harbour" came back in every later process (and ThemeCreatorBarArrangementComposeTest's
     * first "Edit" opened it). An empty commit() on the same file (the platform keeps one
     * SharedPreferences per file) returns once every earlier apply() of it is written.
     * `custom_keyboard_themes` in device-protected storage is CustomThemeManager's PREFS_NAME
     * (private there).
     */
    private fun commitCustomThemeStore() {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("custom_keyboard_themes", android.content.Context.MODE_PRIVATE)
            .edit().commit()
    }
}
