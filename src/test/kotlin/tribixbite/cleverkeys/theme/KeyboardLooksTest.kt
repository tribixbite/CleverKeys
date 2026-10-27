package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.LayoutThemeBinding
import tribixbite.cleverkeys.SuggestionBarPalette
import tribixbite.cleverkeys.prefs.ConfigSnapshot

/**
 * The built-in keyboard looks (iOS-, Gboard-, SwiftKey-, Samsung- and iOS 18-style; research in
 * docs/research/2026-10-27-familiar-keyboard-looks.md): registry, light/dark variants, the
 * geometry each defines, and legibility in both modes.
 *
 * Contrast floors (WCAG 2.x): letter and modifier labels 4.5:1 on their key role; the Enter and
 * editor-action glyphs (icons) and the pressed / Caps Lock states 3:1 (WCAG 1.4.11 non-text —
 * iOS's own white-on-#007AFF return is 4.0:1); corner hints 3:1 (WCAG 1.4.11 non-text / large-text floor — the products' own hints
 * are deliberately subdued); suggestion text 4.5:1 and the highlighted suggestion 3:1 on the
 * bar. The bar colours must also pass [SuggestionBarPalette.resolve] UNCHANGED, i.e. what the
 * look defines is what is drawn.
 */
class KeyboardLooksTest {

    private fun contrast(a: Int, b: Int) = SuggestionBarPalette.contrastRatio(a, b)

    private fun luminanceOrder(palette: LookPalette) = contrast(palette.keyboardBackground, 0xFF000000.toInt())

    @Test
    fun theLooksAreRegisteredUnderTheLookPrefix() {
        assertThat(KeyboardLooks.IDS).containsExactly(
            KeyboardLooks.IOS, KeyboardLooks.GBOARD, KeyboardLooks.SWIFTKEY, KeyboardLooks.SAMSUNG,
            KeyboardLooks.IOS18,
        ).inOrder()
        for (id in KeyboardLooks.IDS) {
            assertThat(id).startsWith(KeyboardLooks.ID_PREFIX)
            assertThat(KeyboardLooks.byId(id)!!.id).isEqualTo(id)
            assertWithMessage("$id must be a valid binding id (layout XML theme=\"…\")")
                .that(LayoutThemeBinding.normalizeId(id)).isEqualTo(id)
            assertWithMessage("$id renders through the runtime-theme path")
                .that(ConfigSnapshot.isRuntimeThemeName(id)).isTrue()
        }
        assertThat(KeyboardLooks.byId("look_unknown")).isNull()
        assertThat(KeyboardLooks.byId("rosepine")).isNull()
    }

    @Test
    fun eachLookFollowsTheSystemWithADistinctLightAndDarkPalette() {
        for (look in KeyboardLooks.ALL) {
            assertThat(look.palette(systemLight = true)).isSameInstanceAs(look.light)
            assertThat(look.palette(systemLight = false)).isSameInstanceAs(look.dark)
            assertWithMessage("${look.id}: the light keyboard background must be lighter than the dark one")
                .that(luminanceOrder(look.light)).isGreaterThan(luminanceOrder(look.dark))
        }
    }

    @Test
    fun eachLookDefinesItsKeyShapeAndGaps() {
        for (look in KeyboardLooks.ALL) {
            val g = look.geometry
            assertWithMessage("${look.id} gaps").that(listOf(g.keyGapHorizontal, g.keyGapVertical)).doesNotContain(null)
            assertWithMessage("${look.id} radius").that(g.cornerRadiusDp).isNotNull()
            assertWithMessage("${look.id} outline").that(g.borderWidthDp).isNotNull()
            assertWithMessage("${look.id} label scale").that(g.labelScale).isNotNull()
            // The resolved values must sit inside the resolver's clamps (no silent clamping).
            assertThat(g.keyGapHorizontal!!).isAtMost(KeyboardLookGeometry.MAX_GAP)
            assertThat(g.keyGapVertical!!).isAtMost(KeyboardLookGeometry.MAX_GAP)
            assertThat(g.labelScale!!).isIn(
                com.google.common.collect.Range.closed(KeyboardLookGeometry.MIN_LABEL_SCALE, KeyboardLookGeometry.MAX_LABEL_SCALE)
            )
        }
        // iOS 18 draws a 1 pt shadow straight down under every key and grey function keys;
        // iOS 26 dropped both (one key colour, no shadow).
        val ios18 = KeyboardLooks.byId(KeyboardLooks.IOS18)!!
        assertThat(ios18.geometry.shadowDyDp).isEqualTo(1f)
        assertThat(ios18.light.keyShadow).isNotEqualTo(0)
        assertThat(ios18.light.modifierKey).isNotEqualTo(ios18.light.key)
        val ios = KeyboardLooks.byId(KeyboardLooks.IOS)!!
        assertThat(ios.light.keyShadow).isEqualTo(0)
        assertThat(ios.light.modifierKey).isEqualTo(ios.light.key)
        // Gboard draws its comma and period as function keys.
        assertThat(KeyboardLooks.byId(KeyboardLooks.GBOARD)!!.functionChars).isEqualTo(",.")
    }

    @Test
    fun everyPaletteColourIsOpaqueExceptAnInvisibleSeparator() {
        for (look in KeyboardLooks.ALL) for ((mode, p) in listOf("light" to look.light, "dark" to look.dark)) {
            val colours = mapOf(
                "keyboardBackground" to p.keyboardBackground, "key" to p.key, "keyPressed" to p.keyPressed,
                "keyLocked" to p.keyLocked, "lockedLabel" to p.lockedLabel, "modifierKey" to p.modifierKey,
                "enterKey" to p.enterKey, "enterLabel" to p.enterLabel, "actionKey" to p.actionKey,
                "label" to p.label, "modifierLabel" to p.modifierLabel, "actionLabel" to p.actionLabel,
                "hint" to p.hint, "keyBorder" to p.keyBorder, "barBackground" to p.barBackground,
                "barText" to p.barText, "barHighlight" to p.barHighlight, "swipeTrail" to p.swipeTrail,
            )
            for ((name, c) in colours) {
                assertWithMessage("${look.id} $mode $name must be opaque (key opacity owns alpha)")
                    .that(c ushr 24).isEqualTo(0xFF)
            }
            val separatorAlpha = p.barSeparator ushr 24
            assertWithMessage("${look.id} $mode separator").that(separatorAlpha == 0 || separatorAlpha > 0x20).isTrue()
        }
    }

    @Test
    fun labelsAndHintsAreLegibleOnTheirKeysInBothModes() {
        for (look in KeyboardLooks.ALL) for ((mode, p) in listOf("light" to look.light, "dark" to look.dark)) {
            val where = "${look.id} $mode"
            assertWithMessage("$where letter label").that(contrast(p.label, p.key)).isAtLeast(4.5)
            assertWithMessage("$where modifier label").that(contrast(p.modifierLabel, p.modifierKey)).isAtLeast(4.5)
            assertWithMessage("$where Enter glyph").that(contrast(p.enterLabel, p.enterKey)).isAtLeast(3.0)
            assertWithMessage("$where action glyph").that(contrast(p.actionLabel, p.actionKey)).isAtLeast(3.0)
            assertWithMessage("$where pressed label").that(contrast(p.label, p.keyPressed)).isAtLeast(3.0)
            assertWithMessage("$where Caps Lock glyph").that(contrast(p.lockedLabel, p.keyLocked)).isAtLeast(3.0)
            assertWithMessage("$where hint").that(contrast(p.hint, p.key)).isAtLeast(3.0)
        }
    }

    @Test
    fun suggestionBarColoursAreLegibleAndDrawnAsDefined() {
        for (look in KeyboardLooks.ALL) for ((mode, p) in listOf("light" to look.light, "dark" to look.dark)) {
            val where = "${look.id} $mode"
            assertWithMessage("$where bar text").that(contrast(p.barText, p.barBackground)).isAtLeast(4.5)
            assertWithMessage("$where bar highlight").that(contrast(p.barHighlight, p.barBackground)).isAtLeast(3.0)
            val resolved = SuggestionBarPalette.resolve(p.barText, p.barHighlight, p.hint, p.barBackground)
            assertWithMessage("$where: the bar guard must not substitute the look's text colour")
                .that(resolved.text).isEqualTo(p.barText)
            assertWithMessage("$where: the bar guard must not substitute the look's highlight")
                .that(resolved.highlight).isEqualTo(p.barHighlight)
            assertWithMessage("$where: prompt chips use the hint colour unchanged")
                .that(resolved.secondary).isEqualTo(p.hint)
        }
    }
}
