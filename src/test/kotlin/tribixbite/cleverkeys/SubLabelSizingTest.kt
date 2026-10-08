package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.customization.DirectionMapping
import tribixbite.cleverkeys.customization.ShortSwipeCustomizations
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * Pins the shared sublabel size rule ([SubLabelSizing]) and its use by every sublabel draw
 * path (Seeker report 2026-10-08: a short-swipe mapping left on its default label was drawn
 * much larger than the layout's own sublabels and ran over the key's main letter — worst for
 * Private Copy, whose default was the colour emoji label "🔒⎘").
 *
 * The size arithmetic is pure and executed here against the real [KeyValue] flags and the real
 * [CommandRegistry]. The draw paths themselves are Android `View`s (unreachable off-device), so
 * their use of the shared rule is pinned by reading the source.
 */
class SubLabelSizingTest {

    private fun defaultMapping(command: String): ShortSwipeMapping {
        val info = CommandRegistry.getDisplayInfo(command)
        return ShortSwipeMapping(
            keyCode = "f",
            direction = SwipeDirection.W,
            displayText = info.displayText,
            actionType = ActionType.COMMAND,
            actionValue = command,
            useKeyFont = info.useKeyFont,
        )
    }

    // ------------------------------------------------------------- custom vs layout parity

    @Test
    fun aMappingOnItsDefaultLabelIsSizedLikeTheCommandsKeyOnALayout_wholeCatalogue() {
        // Every command whose key exists on layouts: a mapping left on the default label must
        // get exactly the factor the layout subkey gets. Before 2026-10-08 FLAG_SMALLER_FONT
        // was only honoured for key-font labels, so text/emoji defaults ("🔒⎘", "Ctrl", "word",
        // "clr", "repl", "Meta" …) were drawn 1/0.75 = 1.33x larger as a custom mapping.
        val disagreeing = mutableListOf<String>()
        var checked = 0
        for (command in CommandRegistry.ALL_COMMANDS) {
            val kv = KeyValue.getSpecialKeyByName(command.name) ?: continue
            // getDisplayInfo truncates to 4 chars; the layout draws non-string keys whole.
            if (kv.getString().length > 4) continue
            checked++
            val custom = SubLabelSizing.scaleFor(defaultMapping(command.name))
            val layout = SubLabelSizing.scaleFor(kv)
            if (custom != layout) disagreeing += "${command.name}: custom $custom vs layout $layout"
        }
        assertThat(checked).isGreaterThan(150)
        assertWithMessage("custom-mapping default labels must match the layout sublabel size")
            .that(disagreeing).isEmpty()
    }

    @Test
    fun smallerFontTextCommandsGetTheSmallerFactorAsMappingsToo() {
        for (command in listOf("ctrl", "meta", "delete_last_word", "clear", "replaceText", "tab")) {
            val kv = KeyValue.getKeyByName(command)
            assertWithMessage("'$command' carries FLAG_SMALLER_FONT")
                .that(kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)).isTrue()
            assertWithMessage("'$command' default label as a custom mapping")
                .that(SubLabelSizing.scaleFor(defaultMapping(command)))
                .isEqualTo(SubLabelSizing.SMALLER_FONT_SCALE)
        }
    }

    @Test
    fun aUserTypedLabelIsPlainTextAtTheNormalSize() {
        val typed = defaultMapping("ctrl").copy(displayText = "ct")
        assertThat(SubLabelSizing.scaleFor(typed)).isEqualTo(1f)
        // Text and intent mappings never inherit a command's flags.
        val text = typed.copy(actionType = ActionType.TEXT, actionValue = "Ctrl", displayText = "Ctrl")
        assertThat(SubLabelSizing.scaleFor(text)).isEqualTo(1f)
    }

    @Test
    fun colourEmojiLabelsAreDrawnAtTheGlyphSize_neverCompounded() {
        for (label in listOf("📌1", "🌐2", "🔊", "📅", "⏩", "⌚")) {
            assertWithMessage("'$label' is a colour emoji label")
                .that(SubLabelSizing.containsColourEmoji(label)).isTrue()
            assertThat(SubLabelSizing.scale(false, false, label)).isEqualTo(SubLabelSizing.GLYPH_SCALE)
            assertThat(SubLabelSizing.scale(true, false, label)).isEqualTo(SubLabelSizing.SMALLER_FONT_SCALE)
        }
        // Monochrome symbols and text keep the normal size.
        for (label in listOf("☰", "⌧", "⎘", "Esc", "word", "ℂ", "πλ∇¬")) {
            assertWithMessage("'$label'").that(SubLabelSizing.containsColourEmoji(label)).isFalse()
            assertThat(SubLabelSizing.scale(false, false, label)).isEqualTo(1f)
        }
        val pinned = defaultMapping("paste_pinned_1")
        assertThat(pinned.displayText).isEqualTo("📌1")
        assertThat(SubLabelSizing.scaleFor(pinned)).isEqualTo(SubLabelSizing.GLYPH_SCALE)
    }

    @Test
    fun keyFontIconsAreDrawnAtTheGlyphSizeOnLayoutsAndAsMappings() {
        // Material icons fill ~0.92 em of the special font: at 0.75 their ink matches a text
        // sublabel's ~0.7 em capitals instead of reading 1.3x larger (Seeker report 2026-10-08).
        assertThat(SubLabelSizing.GLYPH_SCALE).isEqualTo(SubLabelSizing.SMALLER_FONT_SCALE)
        for (command in listOf("copy", "paste", "cut", "undo", "copy_private", "voice_typing", "config", "tab")) {
            val kv = KeyValue.getKeyByName(command)
            assertWithMessage("'$command' is a key-font icon").that(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT)).isTrue()
            assertWithMessage("'$command' on a layout").that(SubLabelSizing.scaleFor(kv)).isEqualTo(SubLabelSizing.GLYPH_SCALE)
            assertWithMessage("'$command' as a mapping on its default label")
                .that(SubLabelSizing.scaleFor(defaultMapping(command))).isEqualTo(SubLabelSizing.GLYPH_SCALE)
        }
        // Main labels are unaffected: the rule is for sublabels only.
        val view = File("src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt").readText()
        assertThat(view).contains("val textSize = scaleTextSize(modifiedKv, true)")
    }

    @Test
    fun labelPreviewsUseTheSameFactorAgainstTheTextBesideThem() {
        // Command palette preview (18 sp text), subkey assign badge (titleLarge, 22 sp) and the
        // mapping list (13 sp) all derive the icon / emoji size from the keyboard's factor.
        assertThat(SubLabelSizing.previewSp(18f, true, "\uE039")).isEqualTo(13.5f)
        assertThat(SubLabelSizing.previewSp(22f, true, "\uE030")).isEqualTo(16.5f)
        assertThat(SubLabelSizing.previewSp(18f, false, "📅")).isEqualTo(13.5f)
        assertThat(SubLabelSizing.previewSp(18f, false, "word")).isEqualTo(18f)
        val palette = File("src/main/kotlin/tribixbite/cleverkeys/customization/CommandPaletteDialog.kt").readText()
        val assign = File("src/main/kotlin/tribixbite/cleverkeys/popover/SubkeyAssignActivity.kt").readText()
        val list = File("src/main/kotlin/tribixbite/cleverkeys/activities/ShortSwipeCustomizationActivity.kt").readText()
        for ((name, src) in listOf("palette" to palette, "assign badge" to assign, "mapping list" to list)) {
            assertWithMessage("$name sizes label previews with SubLabelSizing.previewSp")
                .that(src).contains("SubLabelSizing.previewSp(")
            assertWithMessage("$name has no hard-coded key-font preview size")
                .that(Regex("COMPLEX_UNIT_SP, \\d+f\\)").containsMatchIn(src)).isFalse()
        }
    }

    // ---------------------------------------------------------------- slot width fitting

    @Test
    fun slotWidthsKeepSideLabelsClearOfTheCentredMainLabel() {
        val keyW = 100f
        val pad = 4f
        // Corners: half the key minus padding. W/E (5, 6): the outer third. N/S (7, 8): centred.
        for (corner in 1..4) assertThat(SubLabelSizing.maxWidth(keyW, pad, corner)).isEqualTo(46f)
        for (side in 5..6) assertThat(SubLabelSizing.maxWidth(keyW, pad, side)).isWithin(1e-4f).of(30f)
        for (centre in 7..8) assertThat(SubLabelSizing.maxWidth(keyW, pad, centre)).isWithin(1e-4f).of(60f)
        // A W/E label never reaches the key's centre line, where the main label sits.
        assertThat(pad + SubLabelSizing.maxWidth(keyW, pad, 5)).isLessThan(keyW / 2f)
        // Degenerate keys never produce a negative budget.
        assertThat(SubLabelSizing.maxWidth(2f, 4f, 5)).isEqualTo(0f)
        // A wide main label ("w", 40 % of the key) narrows W/E further: they stop 4 % short of it.
        val side = SubLabelSizing.maxWidth(keyW, pad, 5, mainLabelWidth = 40f)
        assertThat(side).isWithin(1e-4f).of(22f)
        assertThat(pad + side + keyW * SubLabelSizing.SIDE_GAP_FRACTION).isWithin(1e-4f).of((keyW - 40f) / 2f)
        // …and never corners or centre slots, which do not share the main label's band.
        assertThat(SubLabelSizing.maxWidth(keyW, pad, 1, mainLabelWidth = 40f)).isEqualTo(46f)
    }

    @Test
    fun fitToWidthShrinksOnlyLabelsThatOverflow() {
        assertThat(SubLabelSizing.fitToWidth(20f, 10f, 30f)).isEqualTo(20f)
        assertThat(SubLabelSizing.fitToWidth(20f, 30f, 30f)).isEqualTo(20f)
        assertThat(SubLabelSizing.fitToWidth(20f, 60f, 30f)).isEqualTo(10f)
        // Width scales linearly with text size, so the fitted label measures exactly the budget.
        val fitted = SubLabelSizing.fitToWidth(24f, 72f, 30f)
        assertThat(72f * fitted / 24f).isWithin(1e-4f).of(30f)
        // Nothing measured or no budget: leave the size alone instead of collapsing it.
        assertThat(SubLabelSizing.fitToWidth(20f, 0f, 30f)).isEqualTo(20f)
        assertThat(SubLabelSizing.fitToWidth(20f, 10f, 0f)).isEqualTo(20f)
    }

    // ----------------------------------------------------------------- legacy saved labels

    @Test
    fun aSavedPrivateCopyMappingOnTheOldEmojiDefaultBecomesTheKeyFontGlyph() {
        val saved = ShortSwipeCustomizations(
            mappings = mapOf(
                "f" to mapOf(
                    "W" to DirectionMapping("🔒⎘", "COMMAND", "copy_private", false),
                    "E" to DirectionMapping("priv", "COMMAND", "copy_private", false),
                ),
            ),
        ).toMappingList().associateBy { it.direction }

        val upgraded = saved.getValue(SwipeDirection.W)
        assertThat(upgraded.displayText).isEqualTo("")
        assertThat(upgraded.useKeyFont).isTrue()
        // A label the user typed is theirs: kept as saved.
        val typed = saved.getValue(SwipeDirection.E)
        assertThat(typed.displayText).isEqualTo("priv")
        assertThat(typed.useKeyFont).isFalse()
        // Only COMMAND mappings of that command are touched.
        assertThat(CommandRegistry.upgradeLegacyDefaultLabel("copy", "🔒⎘", false)).isNull()
    }

    // ------------------------------------------------------- every draw path uses the rule

    @Test
    fun everySublabelDrawPathUsesTheSharedRule() {
        val view = File("src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt").readText()
        // Layout subkeys and custom mappings: same base, same factor source, same width fit.
        assertThat(view).contains("val textSize = _subLabelSize * SubLabelSizing.scaleFor(modifiedKv)")
        assertThat(view).contains("SubLabelSizing.scaleFor(mapping)")
        assertThat(view).contains("val textSize = _subLabelSize * sizeScale")
        assertThat(Regex("SubLabelSizing\\.maxWidth\\(keyW, subPadding, sub_index, mainLabelW\\)").findAll(view).count())
            .isEqualTo(2)
        // The per-key customization magnifier.
        val magnifier = File("src/main/kotlin/tribixbite/cleverkeys/customization/KeyMagnifierView.kt").readText()
        assertThat(magnifier).contains("SubLabelSizing.scaleFor(customMapping)")
        assertThat(magnifier).contains("SubLabelSizing.scaleFor(subKv)")
        assertThat(magnifier).contains("SubLabelSizing.scaleFor(mapping)")
        assertThat(magnifier).contains("SubLabelSizing.fitToWidth(")
        // The subkey popover (cells already shrink to their width; the factor keeps proportions).
        val slots = File("src/main/kotlin/tribixbite/cleverkeys/popover/SubkeyPopoverSlots.kt").readText()
        assertThat(slots).contains("SubLabelSizing.scaleFor(slot.value)")
        assertThat(slots).contains("SubLabelSizing.scaleFor(slot.mapping)")
        val renderer = File("src/main/kotlin/tribixbite/cleverkeys/popover/SubkeyPopoverRenderer.kt").readText()
        assertThat(renderer).contains("h * 0.46f * label.sizeScale")
    }
}
