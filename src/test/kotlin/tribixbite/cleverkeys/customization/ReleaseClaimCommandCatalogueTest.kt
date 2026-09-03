package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.KeyValue

/**
 * Release-record guards for the short-swipe **command catalogue** claims.
 *
 * Rows pinned here (see `docs/RELEASE_RECORD.md`):
 *
 * | version | published note |
 * |---|---|
 * | v1.0.0  | "208 short-swipe gesture actions" |
 * | v1.1.98 | "Editing commands now work (replaceText, textAssist)" |
 * | v1.1.98 | "Icon characters render correctly (was showing Chinese)" |
 * | v1.1.99 | "Works when text is selected in any app" (textAssist is offered at all) |
 * | v1.2.0  | "Show Text Menu — selects word at cursor and triggers the native toolbar" |
 *
 * ## Why the "Chinese characters" row is testable at all
 *
 * The icons on special keys are **Private Use Area** code points (U+E000–U+F8FF) that only
 * `special_font.ttf` can draw. Rendered with the system font a PUA code point falls through
 * to whatever the font happens to map there — on the reporter's device, CJK glyphs. The fix
 * (`368193e6`) was to render those labels through the key font, and the flag that decides it
 * is [CommandRegistry.CommandDisplayInfo.useKeyFont]. So the exact invariant behind the
 * user-visible bug is: **no catalogue entry may offer a PUA display glyph while telling the
 * renderer it does not need the key font.** That is what `everyPrivateUseGlyph…` asserts,
 * across the whole catalogue rather than the handful of icons that were reported.
 *
 * Pure JVM: [CommandRegistry] and [KeyValue] are Android-free at the paths used here.
 */
class ReleaseClaimCommandCatalogueTest {

    /** The catalogue size CleverKeys advertised at launch. It may grow, never shrink below. */
    private val announcedActionCount = 208

    /** Unicode Private Use Area — where every key-font icon glyph lives. */
    private val privateUseArea = 0xE000..0xF8FF

    private fun CommandRegistry.CommandDisplayInfo.hasPrivateUseGlyph(): Boolean =
        displayText.any { it.code in privateUseArea }

    // ---------------------------------------------------------------- v1.0.0 catalogue size

    @Test
    fun `catalogue still offers at least the 208 announced actions`() {
        assertWithMessage("totalCount must report the real list length")
            .that(CommandRegistry.totalCount)
            .isEqualTo(CommandRegistry.ALL_COMMANDS.size)

        assertWithMessage(
            "v1.0.0 published '208 short-swipe gesture actions'; the catalogue may grow but " +
                "dropping below the advertised floor breaks a shipped promise"
        ).that(CommandRegistry.totalCount).isAtLeast(announcedActionCount)

        val distinctNames = CommandRegistry.ALL_COMMANDS.map { it.name }.distinct()
        assertWithMessage("distinct addressable action names, not just list rows")
            .that(distinctNames.size).isAtLeast(announcedActionCount)
    }

    @Test
    fun `every catalogue entry is addressable by its internal name`() {
        for (command in CommandRegistry.ALL_COMMANDS) {
            assertWithMessage("getByName('${command.name}')")
                .that(CommandRegistry.getByName(command.name)?.name)
                .isEqualTo(command.name)
        }
    }

    @Test
    fun `every catalogue entry is reachable by searching for its own name`() {
        for (command in CommandRegistry.ALL_COMMANDS) {
            assertWithMessage("search('${command.name}') must surface it")
                .that(CommandRegistry.search(command.name).map { it.name })
                .contains(command.name)
            assertWithMessage("searchRanked('${command.name}') must surface it")
                .that(CommandRegistry.searchRanked(command.name).map { it.name })
                .contains(command.name)
        }
    }

    @Test
    fun `searchRanked puts the exact name match first`() {
        // A user typing the full command name expects that command at the top of the palette.
        for (name in listOf("copy", "paste", "undo", "selectAll", "showTextMenu")) {
            assertWithMessage("searchRanked('$name')[0]")
                .that(CommandRegistry.searchRanked(name).first().name)
                .isEqualTo(name)
        }
    }

    @Test
    fun `getByCategory partitions the catalogue in declared sort order`() {
        val grouped = CommandRegistry.getByCategory()
        assertThat(grouped.values.sumOf { it.size }).isEqualTo(CommandRegistry.totalCount)

        val orders = grouped.keys.map { it.sortOrder }
        assertWithMessage("categories are presented in Category.sortOrder order")
            .that(orders).isInOrder()
        assertThat(orders).containsNoDuplicates()

        for ((category, commands) in grouped) {
            assertThat(commands.map { it.category }.distinct()).containsExactly(category)
            assertThat(CommandRegistry.getByCategory(category)).isEqualTo(commands)
        }
    }

    @Test
    fun `no command name is listed twice`() {
        // textAssist / replaceText were declared once under EDITING (v1.1.98) and again under
        // TEXT_ACTIONS (v1.2.0) — identical KeyValue resolution (getKeyValue is by name), so
        // the palette showed each twice and getByName silently answered with the EDITING row.
        // Deduped 2026-09 keeping the TEXT_ACTIONS copy (the category v1.2.0 announced them
        // under). Ratchet: the catalogue must stay duplicate-free.
        val duplicated = CommandRegistry.ALL_COMMANDS
            .groupingBy { it.name }.eachCount()
            .filterValues { it > 1 }.keys
        assertThat(duplicated).isEmpty()

        assertThat(CommandRegistry.getByName("textAssist")!!.category)
            .isEqualTo(CommandRegistry.Category.TEXT_ACTIONS)
        assertThat(CommandRegistry.getByName("replaceText")!!.category)
            .isEqualTo(CommandRegistry.Category.TEXT_ACTIONS)
    }

    // ------------------------------------------------- v1.1.98 "Editing commands now work"

    @Test
    fun `replaceText and textAssist resolve to their Editing key values`() {
        val replace = CommandRegistry.getKeyValue("replaceText")!!
        assertThat(replace.getKind()).isEqualTo(KeyValue.Kind.Editing)
        assertThat(replace.getEditing()).isEqualTo(KeyValue.Editing.REPLACE)

        val assist = CommandRegistry.getKeyValue("textAssist")!!
        assertThat(assist.getKind()).isEqualTo(KeyValue.Kind.Editing)
        assertThat(assist.getEditing()).isEqualTo(KeyValue.Editing.ASSIST)
    }

    @Test
    fun `the other announced editing commands still resolve to Editing key values`() {
        // v1.1.98 named replaceText/textAssist; these are the rest of the Editing family a
        // short swipe can bind, and they share the same dispatch branch in Keyboard2View.
        val expected = mapOf(
            "copy" to KeyValue.Editing.COPY,
            "paste" to KeyValue.Editing.PASTE,
            "cut" to KeyValue.Editing.CUT,
            "selectAll" to KeyValue.Editing.SELECT_ALL,
            "undo" to KeyValue.Editing.UNDO,
            "redo" to KeyValue.Editing.REDO,
            "autofill" to KeyValue.Editing.AUTOFILL
        )
        for ((name, editing) in expected) {
            val kv = CommandRegistry.getKeyValue(name)!!
            assertWithMessage("'$name' kind").that(kv.getKind()).isEqualTo(KeyValue.Kind.Editing)
            assertWithMessage("'$name' editing action").that(kv.getEditing()).isEqualTo(editing)
        }
    }

    // -------------------------------------------- v1.1.99 / v1.2.0 text-action availability

    @Test
    fun `textAssist is published as a Text Action usable on any selection`() {
        val textActions = CommandRegistry.getByCategory(CommandRegistry.Category.TEXT_ACTIONS)
        assertThat(textActions.map { it.name })
            .containsExactly("textAssist", "replaceText", "showTextMenu")

        val assist = textActions.single { it.name == "textAssist" }
        assertThat(assist.displayName).isEqualTo("Text Assist")
        assertThat(assist.description).isEqualTo("Process selected text with AI assistants")
    }

    @Test
    fun `showTextMenu is published as a Text Action for the native toolbar`() {
        val menu = CommandRegistry.getByName("showTextMenu")!!
        assertThat(menu.category).isEqualTo(CommandRegistry.Category.TEXT_ACTIONS)
        assertThat(menu.displayName).isEqualTo("Show Text Menu")
        assertThat(menu.description).isEqualTo("Select word at cursor and show native toolbar")
        assertThat(menu.keywords).containsAtLeast("toolbar", "cut", "copy", "paste", "select")
    }

    // ------------------------------- v1.1.98 "Icon characters render correctly (was Chinese)"

    @Test
    fun `every private-use glyph in the catalogue asks for the key font`() {
        val offenders = CommandRegistry.ALL_COMMANDS
            .map { it.name to CommandRegistry.getDisplayInfo(it.name) }
            .filter { (_, info) -> info.hasPrivateUseGlyph() && !info.useKeyFont }
            .map { (name, info) -> "$name -> ${info.displayText.map { c -> "U+%04X".format(c.code) }}" }

        assertWithMessage(
            "v1.1.98 fixed icon labels rendering as CJK glyphs: a Private Use Area code " +
                "point drawn WITHOUT special_font.ttf falls through to whatever the system " +
                "font maps there. Any command offering a PUA glyph with useKeyFont=false " +
                "reintroduces that bug."
        ).that(offenders).isEmpty()
    }

    @Test
    fun `known icon commands report a private-use glyph and the key font`() {
        for (name in listOf("config", "switch_clipboard", "switch_emoji", "voice_typing", "shift", "left")) {
            val info = CommandRegistry.getDisplayInfo(name)
            assertWithMessage("'$name' must render with the key font")
                .that(info.useKeyFont).isTrue()
            assertWithMessage("'$name' display glyph")
                .that(info.displayText).hasLength(1)
            assertWithMessage("'$name' glyph is in the Private Use Area")
                .that(info.displayText[0].code).isIn(privateUseArea)
        }
    }

    @Test
    fun `plain-text commands do not ask for the key font`() {
        for (name in listOf("ctrl", "alt", "meta")) {
            val info = CommandRegistry.getDisplayInfo(name)
            assertWithMessage("'$name' has a readable label, not an icon")
                .that(info.useKeyFont).isFalse()
            assertThat(info.hasPrivateUseGlyph()).isFalse()
        }
        assertThat(CommandRegistry.getDisplayInfo("ctrl").displayText).isEqualTo("Ctrl")
    }

    @Test
    fun `display text never exceeds the sub-label budget`() {
        // The label is drawn in a key corner; getDisplayInfo truncates to 4 chars, the same
        // ceiling ShortSwipeMapping.MAX_DISPLAY_LENGTH enforces on user-authored labels.
        for (command in CommandRegistry.ALL_COMMANDS) {
            assertWithMessage("'${command.name}' label length")
                .that(CommandRegistry.getDisplayInfo(command.name).displayText.length)
                .isAtMost(ShortSwipeMapping.MAX_DISPLAY_LENGTH)
        }
    }
}
