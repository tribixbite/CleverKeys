package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.theme.CustomThemePrefPolicy
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * Saga 2026-10-25: applying the `custom_themes` section of a settings import. The themes are
 * written before the settings commit (so a restored `theme = custom_<id>` finds its theme),
 * Merge/Replace follow the short-swipe rules, and a Replace that removes the theme the
 * settings will select points `theme` at the fallback (CustomThemePrefPolicy H-2).
 */
class CustomThemeImportApplyTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var ssImporter: ShortSwipeImporter
    private val screen = ScreenMetrics(1080, 2400, 3.0f)

    /** In-memory store recording the order of writes against the settings commit. */
    private class FakeStore(var stored: String, private val events: MutableList<String>) : CustomThemeStore {
        override fun exportThemesJson(): String = stored
        override fun replaceAll(themesArrayJson: String): Int {
            events += "themes"
            stored = themesArrayJson
            return CustomThemeBackup.parseStored(themesArrayJson)!!.size
        }
    }

    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } answers { events += "commit"; true }
        every { prefs.all } returns emptyMap()
        ssImporter = mockk(relaxed = true)
        coEvery { ssImporter.importFromJson(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun theme(id: String, name: String = "Theme $id") = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("createdAt", 1L)
        addProperty("modifiedAt", 2L)
        add("colors", JsonObject().apply {
            CustomThemeBackup.COLOR_KEYS.forEachIndexed { i, key -> addProperty(key, 0xFF000000.toInt() + i) }
        })
    }

    private fun themesJson(vararg themes: JsonObject) = CustomThemeBackup.toArrayJson(themes.toList())

    private fun plan(fileThemes: String?, vararg changes: SettingsChange) = SettingsImportPlan(
        sourceVersion = "2.0.0",
        sourceScreen = screen,
        currentScreen = screen,
        changes = changes.toList(),
        parseSkippedKeys = emptyList(),
        internalRemoves = emptyList(),
        shortSwipeImportSize = 0,
        shortSwipeImportRawJson = null,
        customThemesImportSize = fileThemes?.let { CustomThemeBackup.parseStored(it)!!.size } ?: 0,
        customThemesImportRawJson = fileThemes,
    )

    private fun ids(store: FakeStore) = CustomThemeBackup.parseStored(store.stored)!!.map(CustomThemeBackup::idOf)

    private fun apply(plan: SettingsImportPlan, mode: ShortSwipeImportMode, store: CustomThemeStore?, excluded: Set<String> = emptySet()) =
        runBlocking { SettingsImportApplier.apply(plan, excluded, ShortSwipeImportMode.SKIP, prefs, ssImporter, mode, store) }

    private val selectB = SettingsChange("theme", PrefValue.Str("cleverkeysdark"), PrefValue.Str("custom_b"), ChangeType.MODIFIED)

    @Test
    fun mergeWritesTheThemesBeforeTheSettingsThatSelectOne() {
        val store = FakeStore(themesJson(theme("a")), events)
        val result = apply(plan(themesJson(theme("b")), selectB), ShortSwipeImportMode.MERGE, store)

        assertThat(ids(store)).containsExactly("a", "b").inOrder()
        assertThat(result.customThemesImported).isEqualTo(1)
        assertThat(events).containsExactly("themes", "commit").inOrder()
        verify { editor.putString("theme", "custom_b") }
        verify(exactly = 0) { editor.putString("theme", ThemeProvider.FALLBACK_THEME_ID) }
    }

    @Test
    fun replaceInstallsExactlyTheFileSet() {
        val store = FakeStore(themesJson(theme("a"), theme("b", name = "Old")), events)
        apply(plan(themesJson(theme("b", name = "New"), theme("c"))), ShortSwipeImportMode.REPLACE, store)
        val stored = CustomThemeBackup.parseStored(store.stored)!!
        assertThat(stored.map(CustomThemeBackup::idOf)).containsExactly("b", "c").inOrder()
        assertThat(CustomThemeBackup.nameOf(stored.first())).isEqualTo("New")
    }

    @Test
    fun skipAndAMissingStoreLeaveTheThemesAlone() {
        val store = FakeStore(themesJson(theme("a")), events)
        val result = apply(plan(themesJson(theme("b"))), ShortSwipeImportMode.SKIP, store)
        assertThat(ids(store)).containsExactly("a")
        assertThat(result.customThemesImported).isEqualTo(0)
        assertThat(events).doesNotContain("themes")
        // No store (tests, or a runtime without one): the settings still apply.
        apply(plan(themesJson(theme("b")), selectB), ShortSwipeImportMode.MERGE, null)
        verify { editor.putString("theme", "custom_b") }
    }

    @Test
    fun replaceThatRemovesTheSelectedThemeSelectsTheFallback() {
        every { prefs.all } returns mapOf("theme" to "custom_a")
        val store = FakeStore(themesJson(theme("a")), events)
        apply(plan(themesJson(theme("b"))), ShortSwipeImportMode.REPLACE, store)

        verify { editor.putString(CustomThemePrefPolicy.THEME_PREF_KEY, ThemeProvider.FALLBACK_THEME_ID) }
        verify { editor.putInt(CustomThemePrefPolicy.SWIPE_TRAIL_PREF_KEY, CustomThemePrefPolicy.FALLBACK_SWIPE_TRAIL_COLOR) }
    }

    @Test
    fun replaceThatKeepsTheSelectedThemeLeavesTheSelection() {
        // The backup selects b and carries it: the restored selection survives.
        every { prefs.all } returns mapOf("theme" to "custom_a")
        val store = FakeStore(themesJson(theme("a")), events)
        apply(plan(themesJson(theme("b")), selectB), ShortSwipeImportMode.REPLACE, store)

        verify { editor.putString("theme", "custom_b") }
        verify(exactly = 0) { editor.putString("theme", ThemeProvider.FALLBACK_THEME_ID) }
    }

    @Test
    fun anExcludedThemeRowIsJudgedByTheCurrentSelection() {
        // The user unticked the `theme` row: the current selection (custom_a) is what stays
        // selected, and Replace removed it.
        every { prefs.all } returns mapOf("theme" to "custom_a")
        val store = FakeStore(themesJson(theme("a")), events)
        apply(plan(themesJson(theme("b")), selectB), ShortSwipeImportMode.REPLACE, store, excluded = setOf("theme"))

        verify(exactly = 0) { editor.putString("theme", "custom_b") }
        verify { editor.putString("theme", ThemeProvider.FALLBACK_THEME_ID) }
    }

    @Test
    fun aFailedThemeWriteCommitsNothing() {
        val failing = object : CustomThemeStore {
            override fun exportThemesJson() = themesJson(theme("a"))
            override fun replaceAll(themesArrayJson: String): Int = throw IllegalStateException("disk full")
        }
        val thrown = runCatching { apply(plan(themesJson(theme("b")), selectB), ShortSwipeImportMode.MERGE, failing) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { editor.commit() }
    }
}
