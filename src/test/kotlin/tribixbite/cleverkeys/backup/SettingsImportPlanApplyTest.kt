package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class SettingsImportPlanApplyTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var ssImporter: ShortSwipeImporter

    private val screen = ScreenMetrics(1080, 2400, 3.0f)

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true
        every { prefs.all } returns emptyMap()
        ssImporter = mockk(relaxed = true)
        coEvery { ssImporter.importFromJson(any(), any()) } returns 0
    }

    private fun planWith(vararg changes: SettingsChange) = SettingsImportPlan(
        sourceVersion = "1.4.0",
        sourceScreen = screen,
        currentScreen = screen,
        changes = changes.toList(),
        parseSkippedKeys = emptyList(),
        internalRemoves = emptyList(),
        shortSwipeImportSize = 0,
        shortSwipeImportRawJson = null,
    )

    @Test
    fun apply_eachPrefValueVariant_dispatchesCorrectEditorMethod() {
        val plan = planWith(
            SettingsChange("b", PrefValue.Unset, PrefValue.Bool(true), ChangeType.ADDED),
            SettingsChange("i", PrefValue.Unset, PrefValue.IntV(42), ChangeType.ADDED),
            SettingsChange("f", PrefValue.Unset, PrefValue.FloatV(3.14f), ChangeType.ADDED),
            SettingsChange("s", PrefValue.Unset, PrefValue.Str("hi"), ChangeType.ADDED),
            SettingsChange("layouts", PrefValue.Unset, PrefValue.JsonBlob("[]"), ChangeType.ADDED),
        )

        val result = runBlocking {
            SettingsImportApplier.apply(
                plan = plan,
                excludedKeys = emptySet(),
                shortSwipeMode = ShortSwipeImportMode.SKIP,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }

        verify(exactly = 1) { editor.putBoolean("b", true) }
        verify(exactly = 1) { editor.putInt("i", 42) }
        verify(exactly = 1) { editor.putFloat("f", 3.14f) }
        verify(exactly = 1) { editor.putString("s", "hi") }
        verify(exactly = 1) { editor.putString("layouts", "[]") }
        verify(exactly = 1) { editor.commit() }
        assertThat(result.importedCount).isEqualTo(5)
        assertThat(result.excludedByUserCount).isEqualTo(0)
        coVerify(exactly = 0) { ssImporter.importFromJson(any(), any()) }
    }
}
