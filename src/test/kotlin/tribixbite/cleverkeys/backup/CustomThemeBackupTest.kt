package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Test

/**
 * Saga 2026-10-25: DIY themes had no backup path, so a reinstall plus a restore lost them and
 * the keyboard fell back to the default theme. These pin the `custom_themes` section rules:
 * validation, the per-theme diff the import preview shows, and Skip / Merge / Replace.
 */
class CustomThemeBackupTest {

    private fun theme(id: String, name: String = "Theme $id", base: Int = 0xFF102030.toInt()): JsonObject =
        JsonObject().apply {
            addProperty("id", id)
            addProperty("name", name)
            addProperty("createdAt", 1_700_000_000_000L)
            addProperty("modifiedAt", 1_700_000_000_500L)
            add("colors", JsonObject().apply {
                CustomThemeBackup.COLOR_KEYS.forEachIndexed { i, key -> addProperty(key, base + i) }
            })
        }

    private fun section(vararg themes: JsonObject, version: Int = 1) = JsonObject().apply {
        addProperty("version", version)
        add("themes", JsonArray().apply { themes.forEach(::add) })
    }

    private fun ids(themes: List<JsonObject>) = themes.map(CustomThemeBackup::idOf)

    @Test
    fun noSectionMeansNothingToImport() {
        assertThat(CustomThemeBackup.parse(null)).isNull()
    }

    @Test
    fun validThemesParseInFileOrder() {
        val parsed = CustomThemeBackup.parse(section(theme("b"), theme("a")))!!
        assertThat(ids(parsed.themes)).containsExactly("b", "a").inOrder()
        assertThat(parsed.skipped).isEmpty()
    }

    @Test
    fun parsedThemeKeepsExactlyTheStoredShape() {
        val withExtra = theme("a").apply { addProperty("futureField", true) }
        val parsed = CustomThemeBackup.parse(section(withExtra))!!.themes.single()
        assertThat(parsed.keySet()).containsExactly("id", "name", "createdAt", "modifiedAt", "colors")
        assertThat(parsed.getAsJsonObject("colors").keySet())
            .containsExactlyElementsIn(CustomThemeBackup.COLOR_KEYS)
    }

    /**
     * Version-2 themes (2026-10-28: a layout `<theme>` saved as a custom theme) carry a `look`
     * extension; the backup keeps it — normalized, so unknown and out-of-range fields never reach
     * the store — and refuses a theme whose extension has the wrong shape. A version-1 theme
     * keeps its exact stored shape.
     */
    @Test
    fun aVersionTwoThemesLookExtensionSurvivesNormalizedAndAMalformedOneIsRefused() {
        val look = JsonParser.parseString(
            """{"colors":{"enterKey":-16776961},"geometry":{"cornerRadiusDp":10,"gapHorizontal":9},""" +
                """"hints":"DIGITS","futureKey":true}"""
        ).asJsonObject
        val v2 = theme("v2").apply { addProperty("formatVersion", 2); add("look", look) }
        val wrongType = theme("bad").apply { addProperty("formatVersion", 2); addProperty("look", "not an object") }
        val wrongField = theme("bad2").apply {
            add("look", JsonParser.parseString("""{"geometry":{"cornerRadiusDp":"ten"}}"""))
        }
        val parsed = CustomThemeBackup.parse(section(v2, wrongType, wrongField))!!
        assertThat(ids(parsed.themes)).containsExactly("v2")
        assertThat(parsed.skipped.map { it.key }).containsExactly("custom_themes[1]", "custom_themes[2]").inOrder()
        val kept = parsed.themes.single()
        assertThat(kept.get("formatVersion").asInt).isEqualTo(2)
        assertThat(kept.getAsJsonObject("look")).isEqualTo(JsonParser.parseString(
            """{"colors":{"enterKey":-16776961},"geometry":{"cornerRadiusDp":10},"hints":"DIGITS"}"""
        ))
        assertThat(CustomThemeBackup.parse(section(theme("v1")))!!.themes.single().has("look")).isFalse()
        // An edit of only the extension is a change in the import preview.
        val edited = theme("v2").apply {
            addProperty("formatVersion", 2)
            add("look", JsonParser.parseString("""{"hints":"NONE"}"""))
        }
        val diff = CustomThemeBackup.diff("[$kept]", listOf(CustomThemeBackup.parse(section(edited))!!.themes.single()))!!
        assertThat(diff.changed.map { it.id }).containsExactly("v2")
    }

    @Test
    fun unsignedColourSpellingIsTheSameArgb() {
        val t = theme("a").apply { getAsJsonObject("colors").addProperty("keyDefault", 0xFF000000L) }
        val parsed = CustomThemeBackup.parse(section(t))!!.themes.single()
        assertThat(parsed.getAsJsonObject("colors").get("keyDefault").asInt).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun malformedThemesAreSkippedWithAReasonAndTheRestImported() {
        val missingColour = theme("m").apply { getAsJsonObject("colors").remove("ripple") }
        val badId = theme("x/../y")
        val blankName = theme("n", name = " ")
        val fractionalColour = theme("f").apply { getAsJsonObject("colors").addProperty("ripple", 1.5) }
        val tooBig = theme("t").apply { getAsJsonObject("colors").addProperty("ripple", 0x1_0000_0000L) }
        val parsed = CustomThemeBackup.parse(
            section(theme("ok"), missingColour, badId, blankName, fractionalColour, tooBig, theme("ok"))
        )!!
        assertThat(ids(parsed.themes)).containsExactly("ok")
        assertThat(parsed.skipped.map { it.key }).containsExactly(
            "custom_themes[1]", "custom_themes[2]", "custom_themes[3]", "custom_themes[4]",
            "custom_themes[5]", "custom_themes[6]",
        ).inOrder()
        assertThat(parsed.skipped.last().kind).isEqualTo(SkipKind.INVALID_VALUE) // duplicate id
    }

    @Test
    fun aNewerOrUnreadableSectionImportsNothingAndSaysWhy() {
        for (element in listOf(
            section(theme("a"), version = 2),
            JsonParser.parseString("[]"),
            JsonParser.parseString("{\"version\":1}"),
        )) {
            val parsed = CustomThemeBackup.parse(element)!!
            assertThat(parsed.themes).isEmpty()
            assertThat(parsed.skipped.single().key).isEqualTo("custom_themes")
        }
    }

    @Test
    fun exportedSectionParsesBackToTheSameThemes() {
        val stored = CustomThemeBackup.toArrayJson(listOf(theme("a"), theme("b")))
        val parsed = CustomThemeBackup.parse(CustomThemeBackup.section(stored))!!
        assertThat(CustomThemeBackup.toArrayJson(parsed.themes)).isEqualTo(stored)
    }

    @Test
    fun diffMatchesThemesById() {
        val current = CustomThemeBackup.toArrayJson(
            listOf(theme("same"), theme("renamed"), theme("recoloured"), theme("localOnly"))
        )
        val file = listOf(
            theme("same").apply { addProperty("modifiedAt", 9L) }, // timestamps alone are no change
            theme("renamed", name = "New name"),
            theme("recoloured", base = 0xFF405060.toInt()),
            theme("fileOnly"),
        )
        val diff = CustomThemeBackup.diff(current, file)!!
        assertThat(diff.added.map { it.id }).containsExactly("fileOnly")
        assertThat(diff.changed.map { it.id }).containsExactly("renamed", "recoloured").inOrder()
        assertThat(diff.changed.first().name).isEqualTo("New name")
        assertThat(diff.removed.map { it.id }).containsExactly("localOnly")
        assertThat(diff.unchanged).isEqualTo(1)
    }

    @Test
    fun diffIsUnknownWithoutTheCurrentSide() {
        assertThat(CustomThemeBackup.diff(null, listOf(theme("a")))).isNull()
    }

    @Test
    fun diffForModeShowsOnlyWhatTheModeDoes() {
        val diff = CustomThemeDiff(
            added = listOf(CustomThemeDiff.Entry("a", "A")),
            removed = listOf(CustomThemeDiff.Entry("r", "R")),
            changed = listOf(CustomThemeDiff.Entry("c", "C")),
            unchanged = 2,
        )
        assertThat(diff.forMode(ShortSwipeImportMode.REPLACE)).isEqualTo(diff)
        val merge = diff.forMode(ShortSwipeImportMode.MERGE)
        assertThat(merge.removed).isEmpty()
        assertThat(merge.unchanged).isEqualTo(3)
        val skip = diff.forMode(ShortSwipeImportMode.SKIP)
        assertThat(skip.isEmpty).isTrue()
        assertThat(skip.unchanged).isEqualTo(4) // every current theme: 2 + changed + removed
    }

    @Test
    fun mergeLetsTheFileWinAndKeepsOtherLocalThemes() {
        val current = listOf(theme("a"), theme("b", name = "Local B"))
        val file = listOf(theme("b", name = "File B"), theme("c"))
        val merged = CustomThemeBackup.merge(current, file, ShortSwipeImportMode.MERGE)
        assertThat(ids(merged)).containsExactly("a", "b", "c").inOrder()
        assertThat(CustomThemeBackup.nameOf(merged[1])).isEqualTo("File B")
    }

    @Test
    fun replaceInstallsExactlyTheFileSetAndSkipChangesNothing() {
        val current = listOf(theme("a"), theme("b"))
        val file = listOf(theme("c"))
        assertThat(ids(CustomThemeBackup.merge(current, file, ShortSwipeImportMode.REPLACE))).containsExactly("c")
        assertThat(CustomThemeBackup.merge(current, file, ShortSwipeImportMode.SKIP)).isEqualTo(current)
    }

    @Test
    fun mergeNeverExceedsTheStoreLimit() {
        val current = (1..CustomThemeBackup.MAX_THEMES).map { theme("local$it") }
        val file = listOf(theme("local1", name = "Updated"), theme("new"))
        val merged = CustomThemeBackup.merge(current, file, ShortSwipeImportMode.MERGE)
        assertThat(merged).hasSize(CustomThemeBackup.MAX_THEMES)
        assertThat(CustomThemeBackup.nameOf(merged.first())).isEqualTo("Updated")
        assertThat(ids(merged)).doesNotContain("new")
    }

    @Test
    fun storedThemesAreReadLeniently() {
        // A local theme the file validator would refuse (over-long name) must survive a Merge.
        val longName = "x".repeat(500)
        val stored = CustomThemeBackup.toArrayJson(listOf(theme("a", name = longName)))
        val parsed = CustomThemeBackup.parseStored(stored)!!
        assertThat(CustomThemeBackup.nameOf(parsed.single())).isEqualTo(longName)
        assertThat(CustomThemeBackup.parseStored("{}")).isNull()
        assertThat(CustomThemeBackup.parseStored("not json")).isNull()
    }
}
