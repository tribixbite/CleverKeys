package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection

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
 *   size `_subLabelSize × SubLabelSizing.scaleFor(kv)`;
 * - **custom** — `drawCustomMappings` → `drawCustomSubLabel`, colour `_theme.subLabelColor`,
 *   size `_subLabelSize × SubLabelSizing.scaleFor(mapping)`.
 *
 * Since 2026-10-08 both sizes come from the one shared rule, [SubLabelSizing] (see
 * `SubLabelSizingTest` for the rule itself, the width fit, and the magnifier/popover paths).
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
 * Residual, deliberate: a mapping with a user-typed text label always draws at 1.0× even when
 * the command's KeyValue has `FLAG_SMALLER_FONT` — the drawn text is the user's label, not the
 * built-in glyph, so there is nothing to match.
 *
 * 2026-10-08 (Seeker report): the 2026-09-03 rule still consulted the flag only for
 * `useKeyFont` mappings, so a command whose DEFAULT label is text or emoji ("🔒⎘", "Ctrl",
 * "word", "clr") was drawn 1.33× larger as a custom mapping than on a layout. The flag is now
 * consulted whenever the mapping shows its command's default label
 * (`SubLabelSizing.showsSmallerDefault`), whatever the typeface.
 */
class CustomSubLabelRenderingTest {

    private companion object {
        val VIEW_SRC = File("src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt")

        /** The smaller-font factor ([SubLabelSizing.SMALLER_FONT_SCALE]). */
        const val SMALLER_FONT_FACTOR = SubLabelSizing.SMALLER_FONT_SCALE
    }

    /** Size multiplier the built-in path applies to `_subLabelSize` for [command]. */
    private fun builtInSubLabelScale(command: String): Float =
        SubLabelSizing.scaleFor(KeyValue.getKeyByName(command))

    /**
     * Size multiplier the custom short-swipe path applies to `_subLabelSize` for a mapping of
     * [command] left on its default label (what the palette saves for an empty label field).
     */
    private fun customSubLabelScale(command: String): Float {
        val info = CommandRegistry.getDisplayInfo(command)
        return SubLabelSizing.scaleFor(
            ShortSwipeMapping("a", SwipeDirection.NE, info.displayText, ActionType.COMMAND, command, info.useKeyFont)
        )
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
        // Built-in: _subLabelSize base, factor from the shared rule on the KeyValue.
        assertThat(src).contains("val textSize = _subLabelSize * SubLabelSizing.scaleFor(modifiedKv)")
        // Custom: the same base, factor from the same rule on the mapping — which reads the
        // SAME flag (FLAG_SMALLER_FONT of the command's KeyValue), never useKeyFont alone (the
        // v1.1.98 regression) and never gated on useKeyFont (the 2026-10-08 report).
        assertThat(src).contains("val textSize = _subLabelSize * sizeScale")
        assertThat(src).contains("SubLabelSizing.scaleFor(mapping)")
        assertThat(src).doesNotContain("if (useKeyFont) _subLabelSize * ${SMALLER_FONT_FACTOR}f")
        assertThat(src).doesNotContain("mapping.useKeyFont && commandCarriesSmallerFont")
        assertThat(SMALLER_FONT_FACTOR).isEqualTo(0.75f)
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

        // FLAG_KEY_FONT alone. Under the regressed v1.1.98 code the custom path drew these at
        // 0.75× while the identical built-in glyph drew at 1.0×. Since 2026-10-08 every key-font
        // SUBLABEL is drawn at SubLabelSizing.GLYPH_SCALE (icons fill the em box, so 1.0× read
        // 1.3× larger than the text sublabels) — on both paths alike, which is the claim.
        for (command in listOf(
            "paste", "copy", "cut", "undo", "redo", "selectAll",
            "up", "down", "enter", "backspace", "delete", "capslock"
        )) {
            val kv = KeyValue.getKeyByName(command)
            assertThat(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT)).isTrue()
            assertThat(kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)).isFalse()
            assertThat(builtInSubLabelScale(command)).isEqualTo(SubLabelSizing.GLYPH_SCALE)
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
