package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Pins v1.2.6 / v1.2.8's "Practice typing inside settings" (#1134) — the Test Keyboard
 * panel in Settings.
 *
 * Behaviourally ([panelStateSurvivesActivityRecreation]) the thing that can silently break
 * is where the panel's state lives: `mutableStateOf` on the Activity is destroyed on every
 * rotation, so a user practising in landscape would lose their text and see the panel snap
 * shut. It lives on [SettingsViewModel] instead, and that is asserted here by exercising the
 * real ViewModel.
 *
 * The rest is a source/resource pin ([panelIsRenderedInSettings], [panelIsFindableInSearch],
 * [panelStringsExist]): a `@Composable` cannot be invoked from a JVM test — Compose UI tests
 * need an instrumented host — so the wiring that puts the field on screen, binds it to that
 * ViewModel state, clears it, and makes it findable from settings search is pinned by
 * scanning the composition. Those are exactly the edits that would remove the feature.
 *
 * Mock tier: `SettingsViewModel`'s Compose state needs android.jar (the Android Compose
 * runtime's state factory implements `Parcelable`). Run with
 * `scripts/gradle-guard.sh runMockTests -PtestClass=TestKeyboardSectionTest`.
 */
class TestKeyboardSectionTest {

    private fun read(path: String): String {
        val file = File(path)
        check(file.isFile) { "${file.path} not found — run with the project root as CWD." }
        return file.readText()
    }

    private val sectionSource by lazy {
        read("src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/TestKeyboardSection.kt")
    }

    // =========================================================================
    // State ownership (behavioural)
    // =========================================================================

    @Test
    fun panelStateSurvivesActivityRecreation() {
        val viewModel = SettingsViewModel()

        assertWithMessage("the practice field starts empty")
            .that(viewModel.testKeyboardText).isEmpty()
        assertWithMessage("the panel starts collapsed")
            .that(viewModel.testKeyboardExpanded).isFalse()

        viewModel.testKeyboardText = "the quick brown fox"
        viewModel.testKeyboardExpanded = true

        // The same ViewModel instance is handed back to the recreated Activity, so what a
        // user typed is still there after a rotation.
        assertWithMessage("typed practice text must be held outside the Activity")
            .that(viewModel.testKeyboardText).isEqualTo("the quick brown fox")
        assertWithMessage("panel expansion must be held outside the Activity")
            .that(viewModel.testKeyboardExpanded).isTrue()

        val activitySource = read("src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt")
        for (property in listOf("testKeyboardText", "testKeyboardExpanded")) {
            assertWithMessage("SettingsActivity.$property must delegate to the ViewModel, not own state")
                .that(
                    Regex("""var $property[\s\S]{0,80}?get\(\)\s*=\s*settingsViewModel\.$property""")
                        .containsMatchIn(activitySource)
                ).isTrue()
        }
    }

    // =========================================================================
    // The panel itself (source/resource)
    // =========================================================================

    @Test
    fun panelIsRenderedInSettings() {
        val screen = read("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsScreen.kt")
        assertWithMessage("the panel must actually be composed into the settings screen")
            .that(Regex("""(?m)^\s*TestKeyboardSection\(\)""").containsMatchIn(screen)).isTrue()

        assertWithMessage("the field must show and edit the persisted practice text")
            .that(sectionSource).contains("value = testKeyboardText")
        assertWithMessage("typing must write back to the persisted practice text")
            .that(sectionSource).contains("onValueChange = { testKeyboardText = it }")
        assertWithMessage("the Clear action must empty the field")
            .that(
                Regex("""onClick\s*=\s*\{\s*testKeyboardText\s*=\s*""\s*}""")
                    .containsMatchIn(sectionSource)
            ).isTrue()
        // The point of the panel is to behave like a real text field, so the IME sees the
        // same capitalization contract it would in a messaging app.
        assertWithMessage("the practice field must request sentence capitalization")
            .that(sectionSource).contains("capitalization = KeyboardCapitalization.Sentences")
        assertWithMessage("the practice field must be multi-line so wrapping can be tried")
            .that(Regex("""minLines\s*=\s*[3-9]""").containsMatchIn(sectionSource)).isTrue()
    }

    @Test
    fun panelIsFindableInSearch() {
        val search = read("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt")
        assertWithMessage("settings search must know the panel's display name")
            .that(search).contains("\"testKeyboard\" -> \"Test Keyboard\"")
        assertWithMessage("navigating to a search hit must expand the panel")
            .that(search).contains("\"testKeyboard\" -> testKeyboardExpanded = true")
        assertWithMessage("collapsing all sections must include this one, or search leaves it open")
            .that(search).contains("testKeyboardExpanded = false")
    }

    @Test
    fun panelStringsExist() {
        val strings = read("res/values/strings.xml")
        for (name in listOf("test_keyboard_section_title", "test_keyboard_hint", "common_clear")) {
            val value = Regex("""<string name="$name"[^>]*>([^<]*)</string>""").find(strings)
                ?.groupValues?.get(1)
            assertWithMessage("res/values/strings.xml must define $name")
                .that(value).isNotNull()
            assertWithMessage("$name must not be blank — it labels the panel")
                .that(value!!.trim()).isNotEmpty()
        }
        assertWithMessage("the section title must name the feature the release note announced")
            .that(strings).contains("<string name=\"test_keyboard_section_title\">⌨️ Test Keyboard</string>")
    }
}
