package tribixbite.cleverkeys.theme

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.TypedFakePreferences
import tribixbite.cleverkeys.backup.CustomThemeBackup
import tribixbite.cleverkeys.backup.RealCustomThemeStore

/**
 * Saga 2026-10-25: the real DIY-theme store behind the backup section. The pure
 * [CustomThemeBackup] validator must accept exactly what `CustomTheme.toJson` writes (its
 * colour keys are a hand-kept list), and a restore must replace the whole stored set at once.
 */
class CustomThemeBackupFormatTest {

    private lateinit var prefs: TypedFakePreferences
    private lateinit var manager: CustomThemeManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        prefs = TypedFakePreferences()
        val context = mockk<Context>(relaxed = true)
        every { context.createDeviceProtectedStorageContext() } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        manager = CustomThemeManager(context)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun scheme(seed: Long): KeyboardColorScheme {
        fun c(i: Int) = Color(0xFF000000L + seed * 0x010101L + i)
        return KeyboardColorScheme(
            keyDefault = c(0), keyActivated = c(1), keyLocked = c(2), keyModifier = c(3), keySpecial = c(4),
            keyLabel = c(5), keySubLabel = c(6), keySecondaryLabel = c(7),
            keyBorder = c(8), keyBorderActivated = c(9),
            swipeTrail = c(10), ripple = c(11),
            suggestionText = c(12), suggestionBackground = c(13), suggestionHighConfidence = c(14),
            keyboardBackground = c(15), keyboardSurface = c(16),
        )
    }

    @Test
    fun colourKeysMatchTheSerializer() {
        val keys = scheme(1).toJson().keys().asSequence().toList()
        assertThat(keys).containsExactlyElementsIn(CustomThemeBackup.COLOR_KEYS)
    }

    @Test
    fun storedThemesSurviveTheValidatorUnchanged() {
        manager.saveCustomTheme(CustomTheme(id = "a", name = "Ocean", colors = scheme(1), createdAt = 5, modifiedAt = 6))
        manager.saveCustomTheme(CustomTheme(id = "b", name = "Dusk", colors = scheme(2), createdAt = 7, modifiedAt = 8))
        val exported = RealCustomThemeStore(manager).exportThemesJson()

        val parsed = CustomThemeBackup.parse(CustomThemeBackup.section(exported))!!
        assertThat(parsed.skipped).isEmpty()
        assertThat(parsed.themes.map(CustomThemeBackup::idOf)).containsExactly("a", "b").inOrder()
    }

    @Test
    fun replaceAllRestoresTheExactThemesAndPersistsThem() {
        val original = listOf(
            CustomTheme(id = "a", name = "Ocean", colors = scheme(1), createdAt = 5, modifiedAt = 6),
            CustomTheme(id = "b", name = "Dusk", colors = scheme(2), createdAt = 7, modifiedAt = 8),
        )
        original.forEach(manager::saveCustomTheme)
        val store = RealCustomThemeStore(manager)
        val backup = CustomThemeBackup.parse(CustomThemeBackup.section(store.exportThemesJson()))!!.themes

        manager.deleteCustomTheme("a")
        manager.saveCustomTheme(CustomTheme(id = "c", name = "Other", colors = scheme(3)))
        val commitsBefore = prefs.commits

        assertThat(store.replaceAll(CustomThemeBackup.toArrayJson(backup))).isEqualTo(2)
        assertThat(manager.customThemes.value).isEqualTo(original)
        assertThat(prefs.commits).isGreaterThan(commitsBefore)
        // Persisted, not just in memory: a fresh manager on the same prefs reads the same set.
        val context = mockk<Context>(relaxed = true)
        every { context.createDeviceProtectedStorageContext() } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        assertThat(CustomThemeManager(context).customThemes.value).isEqualTo(original)
    }

    @Test
    fun aMalformedSetChangesNothing() {
        manager.saveCustomTheme(CustomTheme(id = "a", name = "Ocean", colors = scheme(1)))
        val before = manager.customThemes.value
        val bad = JSONArray().put(org.json.JSONObject().put("id", "x")).toString()

        val thrown = runCatching { manager.replaceAllThemes(bad) }.exceptionOrNull()
        assertThat(thrown).isNotNull()
        assertThat(manager.customThemes.value).isEqualTo(before)
    }
}
