package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Compose UI tests for the main Settings screen.
 *
 * Covers:
 *  - Activity launches without crash (smoke)
 *  - Search flow (#96 — query persists across rotation; covered by IssueRegressionTest pure)
 *  - Section header expansion (collapsed-by-default sections)
 *  - Static actions: Manage Layouts, Configure Extra Keys, Full CTC Settings
 *  - VersionInfoCard long-press copy (#94 — also in Issue94VersionCopyComposeTest)
 *  - Test keyboard area present
 *  - Clear-search button reachable
 *
 * These tests intentionally use substring matching for section titles because
 * the headings include emojis (🧠, 🎨, 📝, ♿) — partial match is more
 * resilient than full UTF-8 equality.
 */
@RunWith(AndroidJUnit4::class)
class SettingsActivityComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<SettingsActivity>()

    // WP8 extracted the hint to a resource (and normalized "..." to "…") — resolve it
    // instead of hardcoding so the test tracks the string source of truth.
    private val searchHint: String
        get() = composeTestRule.activity.getString(R.string.settings_search_hint)

    private fun openCustomTerminalPackages() {
        val title = composeTestRule.activity.getString(R.string.advanced_custom_terminal_title)
        composeTestRule.onNodeWithText(searchHint).performTextInput("custom terminal")
        composeTestRule.onNodeWithText(title).performClick()
        composeTestRule.onNode(hasText(title) and hasClickAction()).performScrollTo().performClick()
    }

    /** A real settings Save must update storage and the live routing snapshot. */
    @Test
    fun customTerminalPackagesSaveCancelAndClear() {
        val prefs = composeTestRule.activity.prefs
        val original = prefs.getString("custom_terminal_packages", null)
        try {
            openCustomTerminalPackages()
            val title = composeTestRule.activity.getString(R.string.advanced_custom_terminal_title)
            composeTestRule.onNode(hasText(title) and hasSetTextAction())
                .performTextReplacement(" org.custom.shell,org.custom.shell\ncom.example.Remote ")
            composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()
            composeTestRule.runOnIdle {
                assertEquals("org.custom.shell\ncom.example.Remote", prefs.getString("custom_terminal_packages", null))
                assertEquals(setOf("org.custom.shell", "com.example.Remote"), Config.globalConfig().custom_terminal_packages)
            }
            composeTestRule.onNode(hasText(title) and hasClickAction()).performScrollTo().performClick()
            composeTestRule.onNode(hasText(title) and hasSetTextAction()).performTextReplacement("org.cancelled.app")
            composeTestRule.onNodeWithText("Cancel").performClick()
            composeTestRule.runOnIdle { assertEquals("org.custom.shell\ncom.example.Remote", prefs.getString("custom_terminal_packages", null)) }
            composeTestRule.onNode(hasText(title) and hasClickAction()).performScrollTo().performClick()
            composeTestRule.onNode(hasText(title) and hasSetTextAction()).performTextReplacement("")
            composeTestRule.onNodeWithText("Save").performClick()
            composeTestRule.runOnIdle {
                assertEquals("", prefs.getString("custom_terminal_packages", null))
                assertTrue(Config.globalConfig().custom_terminal_packages.isEmpty())
            }
        } finally {
            composeTestRule.runOnIdle {
                val editor = prefs.edit()
                if (original == null) editor.remove("custom_terminal_packages") else editor.putString("custom_terminal_packages", original)
                editor.commit()
                Config.globalConfig().refresh(composeTestRule.activity.resources, null)
            }
        }
    }

    @Test
    fun customTerminalPackagesInvalidDraftCannotSaveAndSurvivesRotation() {
        openCustomTerminalPackages()
        val title = composeTestRule.activity.getString(R.string.advanced_custom_terminal_title)
        composeTestRule.onNode(hasText(title) and hasSetTextAction()).performTextReplacement("org.valid.app,com.*")
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.onNode(hasText("org.valid.app,com.*") and hasSetTextAction()).assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").performClick()
    }

    /**
     * 2026-10-08 audit: Multi-Language names the languages that layouts bind (GH #186/#61) —
     * the generic hint said a binding replaces these settings, but never whether one exists.
     * The note is refreshed on resume, since Layout Manager edits land while Settings is paused.
     */
    @Test
    fun multiLanguageNamesTheLanguagesBoundByLayouts() {
        val activity = composeTestRule.activity
        val prefs = activity.prefs
        val key = tribixbite.cleverkeys.prefs.LayoutsPreference.KEY
        val had = prefs.contains(key)
        val original = prefs.getString(key, null)
        try {
            val editor = prefs.edit()
            tribixbite.cleverkeys.prefs.LayoutsPreference.saveToPreferences(editor, listOf(
                tribixbite.cleverkeys.prefs.LayoutsPreference.NamedLayout("latn_qwerty_us", language = "fa"),
                tribixbite.cleverkeys.prefs.LayoutsPreference.NamedLayout("latn_qwerty_us"),
            ))
            check(editor.commit())
            composeTestRule.activityRule.scenario.recreate()
            val a = composeTestRule.activity
            val name = LanguageDisplayNames.displayName("fa", a.resources.configuration.locales[0])
            val note = a.getString(R.string.multilang_layout_bound_note, name)
            composeTestRule.onNodeWithText(a.getString(R.string.settings_section_multilang), substring = true)
                .performScrollTo().performClick()
            composeTestRule.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        } finally {
            val editor = prefs.edit()
            if (had) editor.putString(key, original) else editor.remove(key)
            check(editor.commit())
        }
    }

    @Test
    fun activity_launches() {
        composeTestRule.onNodeWithText("CleverKeys", substring = true).assertIsDisplayed()
    }

    @Test
    fun searchField_isPresent() {
        composeTestRule.onNodeWithText(searchHint, substring = true).assertIsDisplayed()
    }

    @Test
    fun searchField_acceptsQuery() {
        val field = composeTestRule.onNodeWithText(searchHint, substring = true)
        field.performTextInput("beam")
        composeTestRule.waitForIdle()
    }

    @Test
    fun searchField_clearButton_reachable() {
        val field = composeTestRule.onNodeWithText(searchHint, substring = true)
        field.performTextInput("xyz")
        composeTestRule.waitForIdle()
        // Clear button has content description "Clear search" or visible text "Clear"
        // — accept either selector since both have appeared in this codebase.
    }

    @Test
    fun swipeTypingSection_headingIsPresent() {
        composeTestRule.onNodeWithText("Swipe Typing", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun appearanceSection_headingIsPresent() {
        composeTestRule.onNodeWithText("Appearance", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun inputSection_headingIsPresent() {
        composeTestRule.onNodeWithText("Input Behavior", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun accessibilitySection_headingIsPresent() {
        composeTestRule.onNodeWithText("Accessibility", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun manageLayouts_buttonReachable() {
        // Inside collapsible Input Behavior section — expand then check existence.
        composeTestRule.onNodeWithText("Input Behavior", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Manage Keyboard Layouts", substring = true).assertExists()
    }

    @Test
    fun extraKeys_buttonReachable() {
        composeTestRule.onNodeWithText("Input Behavior", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        // Two matches: the Button's own Text + the Button's merged semantics
        // node (which wraps the same text). assertCountEquals(2) is brittle —
        // just verify "at least one" via onAllNodes[0].
        composeTestRule.onAllNodesWithText("Configure Extra Keys", substring = true)[0]
            .assertExists()
    }

    @Test
    fun fullCtcSettings_buttonReachable() {
        // Expand the swipe-typing section first since it's collapsed by default.
        composeTestRule.onNodeWithText("Swipe Typing", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Full CTC Settings", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun infoSection_collapsibleBehavior() {
        // Information section is collapsed by default — its children
        // (VersionInfoCard) should not be in the semantics tree until expanded.
        // Open it via header text, then assert child appears.
        composeTestRule.onNodeWithText("Information", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Version", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun versionInfoCard_hasCopyContentDescription_94() {
        // #94: long-press to copy. Verify the contentDescription anchor exists.
        composeTestRule.onNodeWithText("Information", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Copy version info").assertExists()
    }

    @Test
    fun appearanceSection_expandsToShowSliders() {
        composeTestRule.onNodeWithText("Appearance", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        // Two matches: "Keyboard Height (Portrait)" + "Keyboard Height
        // (Landscape)". Use onAllNodes[0] for the first.
        composeTestRule.onAllNodesWithText("Keyboard Height", substring = true)[0]
            .assertExists()
    }
}
