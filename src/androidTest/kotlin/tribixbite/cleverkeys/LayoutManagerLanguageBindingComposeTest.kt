package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.prefs.ListGroupPreference
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Layout Manager's per-layout language binding UI (GH #186/#61; audit test gap 2026-10-08):
 * the Language chip shows the binding, a binding whose dictionary is not on the device carries
 * the "not installed" warning on the row and in the picker, and choosing "Follow Multi-Language
 * settings" in the picker stores the unbound entry and clears the warning.
 *
 * The `layouts` preference is snapshotted and restored with a synchronous commit (orchestrator
 * processes can exit before apply() reaches disk).
 */
@RunWith(AndroidJUnit4::class)
class LayoutManagerLanguageBindingComposeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private var savedLayouts: String? = null
    private var hadLayouts = false

    /** A valid code shape that no bundled dictionary or pack provides. */
    private val missing = "zz"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        hadLayouts = prefs.contains(LayoutsPreference.KEY)
        savedLayouts = prefs.getString(LayoutsPreference.KEY, null)
        val editor = prefs.edit()
        ListGroupPreference.saveToPreferences(
            LayoutsPreference.KEY, editor,
            listOf(LayoutsPreference.NamedLayout("latn_qwerty_us", language = missing)),
            LayoutsPreference.SERIALIZER,
        )
        check(editor.commit())
        LanguageAvailability.invalidate()
    }

    @After
    fun tearDown() {
        val editor = prefs.edit()
        if (hadLayouts) editor.putString(LayoutsPreference.KEY, savedLayouts) else editor.remove(LayoutsPreference.KEY)
        check(editor.commit())
    }

    private fun name(code: String) =
        LanguageDisplayNames.displayName(code, context.resources.configuration.locales[0])

    @Test
    fun boundChipWarnsWhenNotInstalledAndPickerUnbinds() {
        ActivityScenario.launch(LayoutManagerActivity::class.java).use {
            val chip = context.getString(R.string.keyboard_lang_layout_active, name(missing))
            val warning = context.getString(R.string.layout_language_not_installed, name(missing))
            compose.onNodeWithText(chip).assertIsDisplayed()
            compose.onNodeWithText(warning).assertIsDisplayed()

            // The picker lists the bound language with the same warning, plus "Follow".
            compose.onNodeWithText(chip).performClick()
            compose.onNodeWithText(context.getString(R.string.layout_language_title)).assertIsDisplayed()
            val follow = context.getString(R.string.layout_language_follow_multilang)
            // The picker is a height-capped LazyColumn sorted by display name, so the unavailable
            // binding can sit below the fold among the device's installed languages and is not
            // composed until scrolled to (ew-cli Pixel7/34, 2026-10-08: 1 warning, not 2). The
            // picker's list is the scrollable whose first row is "Follow …".
            val picker = compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasText(follow)))
            picker.performScrollToNode(hasText(warning))
            assertEquals(2, compose.onAllNodesWithText(warning).fetchSemanticsNodes().size)
            // Scroll "Follow …" back into view before tapping it: a click on a row the scroll
            // left outside the dialog lands outside it and only dismisses the picker.
            picker.performScrollToNode(hasText(follow))
            compose.onAllNodesWithText(follow)[0].performClick()
            compose.waitForIdle()

            compose.onNodeWithText(context.getString(R.string.keyboard_lang_layout_active, follow)).assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithText(warning).fetchSemanticsNodes().size)
            val stored = ListGroupPreference.loadFromPreferences(
                LayoutsPreference.KEY, prefs, LayoutsPreference.DEFAULT, LayoutsPreference.SERIALIZER
            )!!.single()
            assertNull("following Multi-Language stores no binding", stored.language)
        }
    }
}
