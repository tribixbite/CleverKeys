package tribixbite.cleverkeys.customization

import tribixbite.cleverkeys.EnglishResourceText
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
                .that(CommandRegistry.search(command.name, EnglishResourceText).map { it.name })
                .contains(command.name)
            assertWithMessage("searchRanked('${command.name}') must surface it")
                .that(CommandRegistry.searchRanked(command.name, EnglishResourceText).map { it.name })
                .contains(command.name)
        }
    }

    @Test
    fun `searchRanked puts the exact name match first`() {
        // A user typing the full command name expects that command at the top of the palette.
        for (name in listOf("copy", "paste", "undo", "selectAll", "showTextMenu")) {
            assertWithMessage("searchRanked('$name')[0]")
                .that(CommandRegistry.searchRanked(name, EnglishResourceText).first().name)
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
        assertThat(EnglishResourceText.string(assist.nameRes)).isEqualTo("Text Assist")
        assertThat(EnglishResourceText.string(assist.descriptionRes)).isEqualTo("Process selected text with AI assistants")
    }

    @Test
    fun `showTextMenu is published as a Text Action for the native toolbar`() {
        val menu = CommandRegistry.getByName("showTextMenu")!!
        assertThat(menu.category).isEqualTo(CommandRegistry.Category.TEXT_ACTIONS)
        assertThat(EnglishResourceText.string(menu.nameRes)).isEqualTo("Show Text Menu")
        assertThat(EnglishResourceText.string(menu.descriptionRes)).isEqualTo("Select word at cursor and show native toolbar")
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
    fun `every catalogue default label is drawable in a single font`() {
        // A label is drawn with ONE paint: the key font (useKeyFont) or the system font.
        // getDisplayInfo cannot express a mixed label, so a key-font label must be all PUA
        // (the key font has no text glyphs) and a system-font label must have no PUA (the
        // system font has no key-font icons) — which also rules out emoji + PUA mixes.
        val offenders = CommandRegistry.ALL_COMMANDS.mapNotNull { command ->
            val info = CommandRegistry.getDisplayInfo(command.name)
            val pua = info.displayText.count { it.code in privateUseArea }
            val singleFont = if (info.useKeyFont) pua == info.displayText.length && pua > 0 else pua == 0
            if (singleFont) null
            else "${command.name} -> ${info.displayText.map { c -> "U+%04X".format(c.code) }} keyFont=${info.useKeyFont}"
        }
        assertWithMessage("default labels mixing key-font glyphs with other text").that(offenders).isEmpty()
    }

    @Test
    fun `every key-font glyph a command shows exists in special_font_ttf`() {
        // A PUA code point the font does not map draws as tofu (or a fallback font's glyph).
        val mapped = TrueTypeCmap.codePoints(java.io.File("assets/special_font.ttf"))
        assertWithMessage("special_font.ttf cmap parsed").that(mapped.size).isAtLeast(100)
        val missing = CommandRegistry.ALL_COMMANDS.flatMap { command ->
            CommandRegistry.getDisplayInfo(command.name).displayText
                .filter { it.code in privateUseArea && it.code !in mapped }
                .map { "${command.name} -> U+%04X".format(it.code) }
        }
        assertThat(missing).isEmpty()
    }

    @Test
    fun `private copy shows one key-font glyph, not the colour-emoji text label`() {
        // Seeker report 2026-10-08: the default label "🔒⎘" (a colour emoji + U+2398) drew a
        // large yellow padlock over the key's main letter. Now the copy glyph's sibling U+E039.
        val info = CommandRegistry.getDisplayInfo("copy_private")
        assertThat(info.useKeyFont).isTrue()
        assertThat(info.displayText).isEqualTo("\uE039")
        val key = KeyValue.getKeyByName("copy_private")
        assertThat(key.getString()).isEqualTo("\uE039")
        assertThat(key.hasFlagsAny(KeyValue.FLAG_KEY_FONT)).isTrue()
        // Same font and size class as the plain copy key (U+E030).
        val copy = KeyValue.getKeyByName("copy")
        assertThat(key.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)).isEqualTo(copy.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT))
        // Its SVG source sits beside the other glyph sources (U+E000 + 0x039).
        assertThat(java.io.File("src/main/special_font/039.svg").isFile).isTrue()
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
    fun `name-dispatched commands show declared symbols, not name fragments`() {
        // KeyValue.getKeyByName never returns null — an unknown name falls through to
        // makeStringKey(name) — so getDisplayInfo used to render raw-name fragments in the
        // palette for the eight commands that dispatch by NAME instead of by KeyValue
        // ("past" for paste_pinned_1, "prim"/"seco"/"show" for the toggles/menu). Each of
        // them now declares a symbol and getDisplayInfo must surface it.
        val expected = mapOf(
            "paste_pinned_1" to "📌1",
            "paste_pinned_2" to "📌2",
            "paste_pinned_3" to "📌3",
            "paste_pinned_4" to "📌4",
            "paste_pinned_5" to "📌5",
            "primaryLangToggle" to "🌐1",
            "secondaryLangToggle" to "🌐2",
            "showTextMenu" to "☰"
        )
        for ((name, symbol) in expected) {
            val info = CommandRegistry.getDisplayInfo(name)
            assertWithMessage("'$name' palette label").that(info.displayText).isEqualTo(symbol)
            assertWithMessage("'$name' is a plain symbol, not a key-font glyph")
                .that(info.useKeyFont).isFalse()
        }
    }

    @Test
    fun `every catalogue entry has either a key glyph or a declared symbol`() {
        // Ratchet against future truncated labels: a command whose name is NOT a special
        // key (KeyValue falls back to a string key of the raw name) must declare a symbol,
        // or the palette would show a 4-char name fragment.
        for (command in CommandRegistry.ALL_COMMANDS) {
            if (KeyValue.getSpecialKeyByName(command.name) == null) {
                assertWithMessage(
                    "'${command.name}' has no special KeyValue — it must declare a symbol " +
                        "or its palette label degrades to '${command.name.take(4)}'"
                ).that(command.symbol).isNotNull()
            }
        }
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

/**
 * Minimal TrueType `cmap` reader (format 4 subtables, i.e. the BMP — where every key-font glyph
 * lives) so a pure test can ask which code points `special_font.ttf` actually maps.
 */
internal object TrueTypeCmap {
    fun codePoints(file: java.io.File): Set<Int> {
        val b = java.nio.ByteBuffer.wrap(file.readBytes())
        fun u16(at: Int) = b.getShort(at).toInt() and 0xFFFF
        fun u32(at: Int) = b.getInt(at).toLong() and 0xFFFFFFFFL
        val numTables = u16(4)
        val cmap = (0 until numTables).map { 12 + 16 * it }
            .firstOrNull { String(ByteArray(4) { i -> b.get(it + i) }, Charsets.US_ASCII) == "cmap" }
            ?.let { u32(it + 8).toInt() } ?: error("no cmap table")
        val out = HashSet<Int>()
        for (t in 0 until u16(cmap + 2)) {
            val sub = cmap + u32(cmap + 4 + 8 * t + 4).toInt()
            if (u16(sub) != 4) continue
            val segX2 = u16(sub + 6)
            val ends = sub + 14
            val starts = ends + segX2 + 2
            val deltas = starts + segX2
            val offsets = deltas + segX2
            for (s in 0 until segX2 / 2) {
                val end = u16(ends + 2 * s)
                val start = u16(starts + 2 * s)
                val delta = u16(deltas + 2 * s)
                val rangeOffset = u16(offsets + 2 * s)
                for (c in start..end) {
                    if (c == 0xFFFF) continue
                    val glyph = if (rangeOffset == 0) (c + delta) and 0xFFFF
                    else u16(offsets + 2 * s + rangeOffset + 2 * (c - start)).let { g ->
                        if (g == 0) 0 else (g + delta) and 0xFFFF
                    }
                    if (glyph != 0) out += c
                }
            }
        }
        return out
    }
}
