package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Test

/**
 * Saga 2026-10-25: the import preview for a settings file that carries custom (DIY) themes.
 * The plan carries the file's valid themes and the device's current ones, and the preview's
 * per-theme rows come from [CustomThemeBackup.diff] of the two, narrowed to the chosen mode.
 */
class CustomThemeImportPlanTest {

    private val screen = ScreenMetrics(1080, 2400, 3.0f)

    private fun theme(id: String, name: String = "Theme $id", base: Int = 0xFF203040.toInt()) = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("createdAt", 1L)
        addProperty("modifiedAt", 2L)
        add("colors", JsonObject().apply {
            CustomThemeBackup.COLOR_KEYS.forEachIndexed { i, key -> addProperty(key, base + i) }
        })
    }

    private fun backup(themes: List<Any>, withPreferences: Boolean = true): String = JsonObject().apply {
        add("metadata", JsonObject().apply { addProperty("app_version", "2.0.0") })
        if (withPreferences) add("preferences", JsonObject().apply { addProperty("theme", "custom_b") })
        add("custom_themes", JsonObject().apply {
            addProperty("version", 1)
            add("themes", JsonArray().apply {
                themes.forEach { if (it is JsonObject) add(it) else add(it.toString()) }
            })
        })
    }.toString()

    @Test
    fun planCarriesTheFilesValidThemesAndTheDevicesCurrentOnes() {
        val current = CustomThemeBackup.toArrayJson(listOf(theme("a")))
        val plan = SettingsImportPlanBuilder.fromJson(
            backup(listOf(theme("a"), theme("b"))), emptyMap(), screen,
            currentCustomThemesRawJson = current,
        )
        assertThat(plan.customThemesImportSize).isEqualTo(2)
        assertThat(CustomThemeBackup.parseStored(plan.customThemesImportRawJson)!!.map(CustomThemeBackup::idOf))
            .containsExactly("a", "b").inOrder()
        assertThat(plan.currentCustomThemesRawJson).isEqualTo(current)
        // The selected theme is an ordinary settings row beside the section.
        assertThat(plan.changes.map { it.key }).contains("theme")
    }

    @Test
    fun previewRowsFollowTheChosenMode() {
        val current = CustomThemeBackup.toArrayJson(listOf(theme("a"), theme("local", name = "Mine")))
        val plan = SettingsImportPlanBuilder.fromJson(
            backup(listOf(theme("a", base = 0xFF000000.toInt()), theme("b", name = "Ocean"))), emptyMap(), screen,
            currentCustomThemesRawJson = current,
        )
        val diff = CustomThemeBackup.diff(
            plan.currentCustomThemesRawJson, CustomThemeBackup.parseStored(plan.customThemesImportRawJson)!!,
        )!!
        val replace = diff.forMode(ShortSwipeImportMode.REPLACE)
        assertThat(replace.added.map { it.name }).containsExactly("Ocean")
        assertThat(replace.changed.map { it.id }).containsExactly("a")
        assertThat(replace.removed.map { it.name }).containsExactly("Mine")
        // The default Merge keeps "Mine": it must not be listed as removed.
        assertThat(diff.forMode(ShortSwipeImportMode.MERGE).removed).isEmpty()
        assertThat(diff.forMode(ShortSwipeImportMode.SKIP).isEmpty).isTrue()
    }

    @Test
    fun invalidThemesAreListedAsNotImportable() {
        val plan = SettingsImportPlanBuilder.fromJson(backup(listOf(theme("ok"), "garbage")), emptyMap(), screen)
        assertThat(plan.customThemesImportSize).isEqualTo(1)
        assertThat(plan.parseSkippedKeys.map { it.key }).contains("custom_themes[1]")
    }

    @Test
    fun aFileWithOnlyThemesStillImportsThem() {
        val plan = SettingsImportPlanBuilder.fromJson(backup(listOf(theme("a")), withPreferences = false), emptyMap(), screen)
        assertThat(plan.changes).isEmpty()
        assertThat(plan.customThemesImportSize).isEqualTo(1)
        assertThat(plan.customThemesImportRawJson).isNotNull()
    }

    @Test
    fun aBackupWithoutTheSectionLeavesThemesAlone() {
        val plan = SettingsImportPlanBuilder.fromJson(
            """{"preferences":{"theme":"dark"}}""", emptyMap(), screen,
            currentCustomThemesRawJson = CustomThemeBackup.toArrayJson(listOf(theme("a"))),
        )
        assertThat(plan.customThemesImportSize).isEqualTo(0)
        assertThat(plan.customThemesImportRawJson).isNull()
    }

    @Test
    fun anEmptySectionImportsNothing() {
        val plan = SettingsImportPlanBuilder.fromJson(backup(emptyList()), emptyMap(), screen)
        assertThat(plan.customThemesImportSize).isEqualTo(0)
        // Null raw JSON: a Replace with an empty section must not wipe the device's themes.
        assertThat(plan.customThemesImportRawJson).isNull()
    }
}
