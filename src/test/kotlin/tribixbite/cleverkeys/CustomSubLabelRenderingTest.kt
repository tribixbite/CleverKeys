package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.customization.CommandRegistry

/**
 * Pins how a **custom short-swipe sublabel** is rendered against how a **built-in sublabel**
 * is rendered — the subject of two published release notes:
 *
 * | version | published note |
 * |---|---|
 * | v1.1.72 | "Fix custom sublabel color to match default sublabels" |
 * | v1.1.98 | "Custom sublabel icons match built-in icon sizes" |
 *
 * ## Two parallel draw paths
 *
 * `Keyboard2View` renders a key's corners twice over:
 *
 * - **built-in** — `drawSubLabel` → colour from `labelColor(kv, isKeyDown, sublabel = true)`,
 *   size from `scaleTextSize(kv, main_label = false)` = `_subLabelSize × (0.75 if the KeyValue
 *   carries FLAG_SMALLER_FONT else 1.0)`;
 * - **custom** — `drawCustomMappings` → `drawCustomSubLabel`, colour `_theme.subLabelColor`,
 *   size `_subLabelSize × (0.75 if useKeyFont else 1.0)`, where
 *   `useKeyFont == KeyValue.hasFlagsAny(FLAG_KEY_FONT)` (`CommandRegistry.getDisplayInfo`).
 *
 * Both paths end in the same `Theme.Computed.Key.sublabel_paint(...)` factory, so font
 * selection is shared; colour and size are each computed independently, which is why each got
 * its own release note and why each needs its own guard.
 *
 * ## Tier
 *
 * `Keyboard2View` is a `View` and `Theme.Computed` allocates `android.graphics.Paint`, both of
 * which are unreachable off-device (android.jar bodies throw `"Stub!"`), so the two draw-path
 * expressions are pinned by reading the source. What *is* executed here is the half that
 * decides the outcome: the real `KeyValue` flags and the real `CommandRegistry`, which
 * determine — per command — whether the two paths agree.
 *
 * ## v1.1.98 history — regression and the 2026-09-03 fix
 *
 * The original v1.1.98 change (`3a705775`) assumed every icon-font sublabel carries
 * `FLAG_SMALLER_FONT` and scaled custom sublabels by 0.75× whenever `useKeyFont` was set. Most
 * icon KeyValues do not carry the flag: `editingKey(Int, …)`, `keyeventKey(Int, …, 0)` and
 * `eventKey(Int, …, 0)` set `FLAG_KEY_FONT` alone. So for `paste`, `copy`, `undo`, `up`,
 * `enter` … the custom sublabel was drawn at 0.75× while the identical built-in glyph was
 * drawn at 1.0× — the announced "match" held only for the `FLAG_SMALLER_FONT` subset (`tab`,
 * `home`, `end`, the `switch_*` family).
 *
 * Fixed 2026-09-03: the custom path now applies the 0.75× factor by the SAME rule the
 * built-in path uses — only when the KeyValue resolved from the mapping's command name
 * carries `FLAG_SMALLER_FONT` (`Keyboard2View.commandCarriesSmallerFont`, consulted only for
 * `useKeyFont` mappings, i.e. when the drawn glyph IS the built-in KeyValue's glyph).
 * `useKeyFont` still selects the icon typeface; it no longer selects the size.
 * [iconCommands_matchBuiltInSizeAcrossAllIconCommands] pins the parity.
 *
 * Residual, deliberate: a mapping with a user-typed text label (`useKeyFont == false`) always
 * draws at 1.0× even when the command's KeyValue has `FLAG_SMALLER_FONT` — the drawn text is
 * the user's label, not the built-in glyph, so there is nothing to match.
 */
class CustomSubLabelRenderingTest {

    private companion object {
        val VIEW_SRC = File("src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt")

        /** `Keyboard2View.scaleTextSize`'s smaller-font factor. */
        const val SMALLER_FONT_FACTOR = 0.75f
    }

    /** Size multiplier the built-in path applies to `_subLabelSize` for [command]. */
    private fun builtInSubLabelScale(command: String): Float {
        val kv = KeyValue.getKeyByName(command)
        return if (kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)) SMALLER_FONT_FACTOR else 1f
    }

    /**
     * Size multiplier the custom short-swipe path applies to `_subLabelSize` for [command].
     *
     * Mirrors `Keyboard2View.drawCustomMappings` + `commandCarriesSmallerFont`: the icon font
     * (`useKeyFont`) gates WHETHER the built-in KeyValue's flag is consulted; the flag itself
     * (`FLAG_SMALLER_FONT`) decides the size — identical to the built-in path.
     */
    private fun customSubLabelScale(command: String): Float {
        val info = CommandRegistry.getDisplayInfo(command)
        val smallerFont = info.useKeyFont &&
            CommandRegistry.getKeyValue(command)?.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT) == true
        return if (smallerFont) SMALLER_FONT_FACTOR else 1f
    }

    // =========================================================================
    // v1.1.72 — custom sublabel colour matches the default sublabel colour
    // =========================================================================

    @Test
    fun customMappings_takeTheirColourFromTheSameThemeFieldAsBuiltInSubLabels() {
        val src = VIEW_SRC.readText()
        // Built-in: the sublabel branch of labelColor().
        assertThat(src).contains("return if (sublabel) _theme.subLabelColor else _theme.labelColor")
        // Custom: the same field, not an accent/highlight colour (which is what v1.1.72 fixed).
        assertThat(src).contains("val sublabelColor = _theme.subLabelColor")
        // …and that field is what is handed to the custom draw call.
        assertThat(src).contains("drawCustomSubLabel(")
        assertThat(src).contains("                sublabelColor,")
    }

    @Test
    fun plainCharacterSubLabels_landOnTheSubLabelColourBranch() {
        // labelColor() only reaches `_theme.subLabelColor` for keys with neither FLAG_SECONDARY
        // nor FLAG_GREYED — i.e. ordinary character corners like `~` or `{`. That is the
        // "default sublabels" a user compares a custom mapping against.
        for (symbol in listOf("~", "{", "}", "[", "]", "(", ")")) {
            val kv = KeyValue.getKeyByName(symbol)
            assertThat(kv.hasFlagsAny(KeyValue.FLAG_SECONDARY or KeyValue.FLAG_GREYED)).isFalse()
        }
    }

    @Test
    fun bothPathsShareTheSameSubLabelPaintFactory() {
        val src = VIEW_SRC.readText()
        // Same factory == same typeface selection and the same label-alpha bits, so a custom
        // mapping can never drift into a different font or opacity than a built-in sublabel.
        assertThat(src).contains(
            "tc.sublabel_paint(modifiedKv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), " +
                "labelColor(modifiedKv, isKeyDown, true), textSize, a)"
        )
        assertThat(src).contains("tc_key.sublabel_paint(useKeyFont, color, textSize, a)")
    }

    // =========================================================================
    // v1.1.98 — custom sublabel icon size vs built-in icon size
    // =========================================================================

    @Test
    fun bothPathsUseTheSameSmallerFontFactorOnTheSameBaseSize() {
        val src = VIEW_SRC.readText()
        // Built-in.
        assertThat(src).contains(
            "val smaller_font = if (k.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)) " +
                "${SMALLER_FONT_FACTOR}f else 1f"
        )
        assertThat(src).contains("val label_size = if (main_label) _mainLabelSize else _subLabelSize")
        // Custom — same 0.75 factor, same _subLabelSize base, gated on the SAME flag the
        // built-in path reads (FLAG_SMALLER_FONT of the resolved KeyValue), never on
        // useKeyFont alone (the v1.1.98 regression).
        assertThat(src).contains(
            "val textSize = if (smallerFont) _subLabelSize * ${SMALLER_FONT_FACTOR}f " +
                "else _subLabelSize"
        )
        assertThat(src).doesNotContain("if (useKeyFont) _subLabelSize * ${SMALLER_FONT_FACTOR}f")
        // The flag is derived from the built-in KeyValue for the mapping's command…
        assertThat(src).contains("?.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT) == true")
        // …and only consulted when the drawn glyph IS that KeyValue's glyph (icon font).
        assertThat(src).contains(
            "mapping.useKeyFont && commandCarriesSmallerFont(mapping.actionValue)"
        )
    }

    @Test
    fun iconCommands_matchBuiltInSizeAcrossAllIconCommands() {
        // FLAG_KEY_FONT + FLAG_SMALLER_FONT → both paths give 0.75×.
        for (command in listOf("tab", "home", "end", "left", "right", "switch_emoji", "config")) {
            val kv = KeyValue.getKeyByName(command)
            assertThat(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT)).isTrue()
            assertThat(kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)).isTrue()
            assertThat(customSubLabelScale(command)).isEqualTo(builtInSubLabelScale(command))
            assertThat(customSubLabelScale(command)).isEqualTo(SMALLER_FONT_FACTOR)
        }

        // FLAG_KEY_FONT alone → both paths give 1.0×. Under the regressed v1.1.98 code the
        // custom path drew these at 0.75× while the identical built-in glyph drew at 1.0×.
        for (command in listOf(
            "paste", "copy", "cut", "undo", "redo", "selectAll",
            "up", "down", "enter", "backspace", "delete", "capslock"
        )) {
            val kv = KeyValue.getKeyByName(command)
            assertThat(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT)).isTrue()
            assertThat(kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)).isFalse()
            assertThat(builtInSubLabelScale(command)).isEqualTo(1f)
            assertThat(customSubLabelScale(command)).isEqualTo(builtInSubLabelScale(command))
        }
    }

    @Test
    fun everyIconCommandRendersAtBuiltInSize_acrossTheWholeCatalogue() {
        // Sweep the whole short-swipe command catalogue so the parity above cannot be a
        // cherry-picked pair: every command that resolves to an icon-font KeyValue must scale
        // identically on both draw paths — the size rule is exactly FLAG_SMALLER_FONT.
        val disagreeing = mutableListOf<String>()
        var checkedIcons = 0
        for (command in CommandRegistry.ALL_COMMANDS) {
            val kv = CommandRegistry.getKeyValue(command.name) ?: continue
            if (!kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT)) continue
            checkedIcons++
            if (customSubLabelScale(command.name) != builtInSubLabelScale(command.name)) {
                disagreeing += command.name
            }
        }
        assertThat(checkedIcons).isGreaterThan(50)
        assertThat(disagreeing).isEmpty()
    }

    @Test
    fun customPathTakesItsFontFlagFromTheKeyValueNotFromTheCommandName() {
        // getDisplayInfo derives useKeyFont from the resolved KeyValue, so a command with a
        // plain-text symbol never gets the icon font (which is what made icons render as CJK
        // before v1.1.98).
        val plainText = CommandRegistry.getDisplayInfo("esc")
        assertThat(plainText.useKeyFont).isFalse()
        assertThat(plainText.displayText).isEqualTo("Esc")

        val icon = CommandRegistry.getDisplayInfo("paste")
        assertThat(icon.useKeyFont).isTrue()
        assertThat(icon.displayText).isEqualTo("")

        // Unknown names fall back to a non-icon rendering rather than throwing.
        val unknown = CommandRegistry.getDisplayInfo("definitely_not_a_command")
        assertThat(unknown.useKeyFont).isFalse()
    }
}
