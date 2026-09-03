package tribixbite.cleverkeys.prefs

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import tribixbite.cleverkeys.KeyValue

/**
 * v1.2.9: "Full AndroidX migration: ExtraKeysPreference, ListGroupPreference".
 *
 * Release-record row `prefs/ExtraKeysPreference.kt#ExtraKeysPreference`, PRESENT-UNTESTED.
 *
 * "Full" is the load-bearing word. The framework `android.preference.*` package is deprecated
 * and its classes are NOT interchangeable with `androidx.preference.*` — a screen that mixes
 * them throws at inflation time, and a single re-introduced framework import is enough to do
 * it. That is a fact about types and imports, so it is asserted about types and imports:
 * the actual supertypes of the migrated classes, and a sweep proving no `android.preference`
 * import survives anywhere in the app.
 *
 * The behaviour underneath the migration is pinned too. `getExtraKeys` is what turns the
 * checkbox states into the keys the keyboard actually draws, and the checkbox `key` strings are
 * what carry a user's existing selection across the migration — get either wrong and every
 * user silently reverts to defaults on upgrade.
 */
class AndroidXPreferenceMigrationTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        prefs = mockk()
        // Default answer: every key falls back to whatever default the caller passes.
        every { prefs.getBoolean(any(), any()) } answers { secondArg() }
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------ the migration, as types

    @Test
    fun extraKeysPreferenceIsAnAndroidXPreferenceCategory() {
        assertWithMessage(
            "the whole point of the v1.2.9 note: this must be androidx.preference." +
                "PreferenceCategory, not the deprecated framework class of the same name"
        ).that(ExtraKeysPreference::class.java.superclass)
            .isEqualTo(PreferenceCategory::class.java)
        assertThat(PreferenceCategory::class.java.name)
            .isEqualTo("androidx.preference.PreferenceCategory")
    }

    @Test
    fun theExtraKeyCheckboxIsAnAndroidXCheckBoxPreference() {
        assertThat(ExtraKeysPreference.ExtraKeyCheckBoxPreference::class.java.superclass)
            .isEqualTo(CheckBoxPreference::class.java)
        assertThat(CheckBoxPreference::class.java.name)
            .isEqualTo("androidx.preference.CheckBoxPreference")
    }

    @Test
    fun listGroupPreferenceIsAnAndroidXPreferenceGroup() {
        assertThat(ListGroupPreference::class.java.superclass)
            .isEqualTo(PreferenceGroup::class.java)
        assertThat(PreferenceGroup::class.java.name)
            .isEqualTo("androidx.preference.PreferenceGroup")
    }

    @Test
    fun everyOtherPreferenceInTheTreeIsAndroidXToo() {
        // LayoutsPreference and CustomExtraKeysPreference reach the tree through
        // ListGroupPreference; the slider dialogs are AndroidX DialogPreferences. All of them
        // sit in the same PreferenceScreen, so one framework class among them breaks inflation.
        assertThat(LayoutsPreference::class.java.superclass).isEqualTo(ListGroupPreference::class.java)
        assertThat(CustomExtraKeysPreference::class.java.superclass).isEqualTo(ListGroupPreference::class.java)
        for (cls in listOf(SlideBarPreference::class.java, IntSlideBarPreference::class.java)) {
            assertWithMessage("${cls.simpleName} must extend an androidx.preference type")
                .that(cls.superclass.name).startsWith("androidx.preference.")
        }
    }

    @Test
    fun noFrameworkPreferenceImportSurvivesAnywhereInTheApp() {
        val root = File("src/main/kotlin")
        assertWithMessage("expected ${root.path} (run from project root)").that(root.isDirectory).isTrue()

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    line.trimStart().startsWith("import android.preference.")
                }
            }
            .map { it.path }
            .toList()

        assertWithMessage(
            "found framework android.preference imports in $offenders. AndroidX and framework " +
                "Preference types cannot coexist in one PreferenceScreen; re-introducing one " +
                "un-does the v1.2.9 migration and crashes Settings at inflation."
        ).that(offenders).isEmpty()
    }

    // ------------------------------------------- the behaviour the migration preserved

    @Test
    fun theCheckboxKeyIsTheStoredPreferenceKeyForThatExtraKey() {
        // This string IS the user's saved selection. Changing its shape orphans every stored
        // choice, which on upgrade reads as "all my extra keys reset".
        assertThat(ExtraKeysPreference.prefKeyOfKeyName("alt")).isEqualTo("extra_key_alt")
        assertThat(ExtraKeysPreference.prefKeyOfKeyName("copy_private")).isEqualTo("extra_key_copy_private")
        assertThat(ExtraKeysPreference.prefKeyOfKeyName("€")).isEqualTo("extra_key_€")
    }

    @Test
    fun theDefaultOnKeysAreExactlyTheDocumentedSet() {
        val onByDefault = ExtraKeysPreference.EXTRA_KEYS
            .filter { ExtraKeysPreference.defaultChecked(it) }
            .toSet()

        assertWithMessage("a fresh install shows exactly these extra keys")
            .that(onByDefault).containsExactly(
                "voice_typing", "change_method", "switch_clipboard", "compose",
                "tab", "esc", "f11_placeholder", "f12_placeholder",
                "cut", "copy", "paste", "undo",
                "home", "end", "page_up", "page_down", "menu",
            )
        assertWithMessage("the accent and combining keys are opt-in, not on by default")
            .that(ExtraKeysPreference.defaultChecked("accent_aigu")).isFalse()
        assertWithMessage("copy_private (#156) is opt-in")
            .that(ExtraKeysPreference.defaultChecked("copy_private")).isFalse()
    }

    @Test
    fun theCatalogueHasNoDuplicateKeyNames() {
        // Duplicates would create two checkboxes writing the same preference key — the second
        // silently shadows the first in the AndroidX tree.
        val duplicates = ExtraKeysPreference.EXTRA_KEYS
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertThat(duplicates).isEmpty()
        assertWithMessage("the `extraKeys` alias must stay a faithful view of EXTRA_KEYS")
            .that(ExtraKeysPreference.extraKeys)
            .containsExactlyElementsIn(ExtraKeysPreference.EXTRA_KEYS.toList()).inOrder()
    }

    @Test
    fun getExtraKeysReadsEachCheckboxUnderItsOwnKeyWithItsOwnDefault() {
        // Nothing stored: the result is exactly the default-on set.
        val defaults = ExtraKeysPreference.getExtraKeys(prefs)
        assertThat(defaults).hasSize(
            ExtraKeysPreference.EXTRA_KEYS.count { ExtraKeysPreference.defaultChecked(it) }
        )

        // Turn one default-on key off and one default-off key on; both must be honoured.
        every { prefs.getBoolean("extra_key_tab", any()) } returns false
        every { prefs.getBoolean("extra_key_accent_aigu", any()) } returns true

        val chosen = ExtraKeysPreference.getExtraKeys(prefs)
        val names = chosen.keys.map { it.toString() }

        assertThat(chosen).hasSize(defaults.size) // one off, one on
        assertWithMessage("a key the user unchecked must not be drawn")
            .that(chosen.keys).doesNotContain(KeyValue.getKeyByName("tab"))
        assertWithMessage("a key the user checked must be drawn")
            .that(chosen.keys).contains(KeyValue.getKeyByName("accent_aigu"))
        assertWithMessage("sanity: the map is keyed by real KeyValues, not names")
            .that(names).isNotEmpty()
    }

    @Test
    fun getExtraKeysAndItsLegacyAliasAgree() {
        // KeyboardData.PreferredPos has identity equality, so the maps can never compare equal
        // even when built identically; the KEY SET is the part that decides what is drawn.
        assertWithMessage(
            "`get_extra_keys` is the snake_case alias older call sites use; it must not drift " +
                "into a second implementation"
        ).that(ExtraKeysPreference.get_extra_keys(prefs).keys)
            .containsExactlyElementsIn(ExtraKeysPreference.getExtraKeys(prefs).keys)
    }
}
