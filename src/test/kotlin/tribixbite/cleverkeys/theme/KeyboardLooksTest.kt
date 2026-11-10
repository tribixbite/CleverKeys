package tribixbite.cleverkeys.theme

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
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

    /** WCAG relative luminance, recovered from the contrast against black: (L + 0.05) / 0.05. */
    private fun relativeLuminance(color: Int) = contrast(color, 0xFF000000.toInt()) * 0.05 - 0.05

    @Test
    fun theLooksAreRegisteredUnderTheLookPrefix() {
        assertThat(KeyboardLooks.FAMILIAR_IDS).containsExactly(
            KeyboardLooks.IOS, KeyboardLooks.GBOARD, KeyboardLooks.SWIFTKEY, KeyboardLooks.SAMSUNG,
            KeyboardLooks.IOS18,
        ).inOrder()
        assertThat(KeyboardLooks.IDS).containsExactlyElementsIn(listOf(KeyboardLooks.CLEVERKEYS) + KeyboardLooks.FAMILIAR_IDS)
            .inOrder()
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
            assertWithMessage("${look.id} horizontal gap").that(g.keyGapHorizontal).isNotNull()
            // Either a vertical fraction or "equal to the horizontal gap" (the CleverKeys look).
            assertWithMessage("${look.id} vertical gap")
                .that(g.keyGapVertical != null || g.equalGaps == true).isTrue()
            assertWithMessage("${look.id} radius").that(g.cornerRadiusDp).isNotNull()
            assertWithMessage("${look.id} outline").that(g.borderWidthDp).isNotNull()
            assertWithMessage("${look.id} label scale").that(g.labelScale).isNotNull()
            // The resolved values must sit inside the resolver's clamps (no silent clamping).
            assertThat(g.keyGapHorizontal!!).isAtMost(KeyboardLookGeometry.MAX_GAP)
            g.keyGapVertical?.let { assertThat(it).isAtMost(KeyboardLookGeometry.MAX_GAP) }
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
    fun legendsAndPillsFollowTheProductsDefaults() {
        // Gboard shows the top-row digit hints; SwiftKey (Pixel reference), iOS and Samsung
        // ("Alternative characters" off) show none. Gboard's ?123 and Enter are pills.
        val gboard = KeyboardLooks.byId(KeyboardLooks.GBOARD)!!
        assertThat(gboard.legends).isEqualTo(LookLegends.DIGITS)
        assertThat(gboard.pillKeys).containsExactly(LookPillKey.ENTER, LookPillKey.ACTION, LookPillKey.NUMBERS)
        for (id in listOf(KeyboardLooks.IOS, KeyboardLooks.SWIFTKEY, KeyboardLooks.SAMSUNG, KeyboardLooks.IOS18)) {
            val look = KeyboardLooks.byId(id)!!
            assertWithMessage("$id legends").that(look.legends).isEqualTo(LookLegends.NONE)
            assertWithMessage("$id pills").that(look.pillKeys).isEmpty()
        }
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

    /**
     * Samsung Keyboard (One UI), dark, measured from the maintainer's screenshots
     * (`build/oct28-samsung-ref/1.jpg`, 1080 px wide, 2026-10-28): background #010101, letter /
     * comma / period / space keys #3A3A3C, shift / delete / !#1 / action #171719, labels #FBFAFF,
     * alternative-character hints #9A999E, the search icon blue #678EED on the dark function fill.
     * Key pitch 105 px with 89 px keys (gap 0.15 of a key unit), rows 150 px apart with 116 px keys
     * (gap 0.23 of the row), corners about 14 px on a 116 px key (6 dp).
     */
    @Test
    fun samsungDarkMatchesTheOneUiReference() {
        val look = KeyboardLooks.byId(KeyboardLooks.SAMSUNG)!!
        val d = look.dark
        fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
        assertThat(listOf(d.keyboardBackground, d.key, d.modifierKey, d.enterKey, d.actionKey, d.label, d.modifierLabel, d.hint, d.actionLabel).map(::hex))
            .containsExactly("#010101", "#3A3A3C", "#171719", "#171719", "#171719", "#FBFAFF", "#FBFAFF", "#9A999E", "#678EED").inOrder()
        assertWithMessage("plain Enter keeps a white icon").that(hex(d.enterLabel)).isEqualTo("#FBFAFF")
        assertThat(look.geometry.keyGapHorizontal).isEqualTo(0.15f)
        assertThat(look.geometry.keyGapVertical).isEqualTo(0.23f)
        assertThat(look.geometry.cornerRadiusDp).isEqualTo(6f)
        assertWithMessage("light keeps its blue action glyph too").that(look.light.actionLabel).isEqualTo(look.light.lockedLabel)
    }

    /**
     * The CleverKeys default look (2026-11-06): CleverKeys' own parts stay — every corner label
     * (short-swipe hints), the scrolling chips, the purple accent — and it takes what the familiar
     * looks share: tonal function keys, an accented primary action, a press preview, a filled
     * Shift, 8 dp keys framed by a 1 dp hairline (maintainer 2026-11-06: "make keys not
     * borderless"), corner hints between the familiar looks' sizes (0.34 was "too small"). It is
     * the only look that yields to the user's settings.
     */
    @Test
    fun theCleverKeysLookKeepsCleverKeysPartsAndTheSharedModernOnes() {
        val look = KeyboardLooks.cleverkeysLook
        assertThat(KeyboardLooks.byId(KeyboardLooks.CLEVERKEYS)).isSameInstanceAs(look)
        assertWithMessage("short-swipe hints stay").that(look.legends).isEqualTo(LookLegends.ALL)
        assertWithMessage("scrolling chips, not a three-slot bar").that(look.bar).isNull()
        assertWithMessage("no wallpaper colours (Monet is its own theme)").that(look.dynamicColor).isFalse()
        assertThat(look.preview.shape).isEqualTo(LookPreviewShape.BUBBLE)
        assertThat(look.shiftFilled).isTrue()
        assertThat(look.geometry.cornerRadiusDp).isEqualTo(8f)
        assertThat(look.geometry.borderWidthDp).isEqualTo(1f)
        assertWithMessage("hints sit in the familiar looks' 0.38..0.45 band, not 0.34")
            .that(look.hintStyle.sizeFraction).isIn(Range.closed(0.38f, 0.45f))
        for ((mode, p) in listOf("light" to look.light, "dark" to look.dark)) {
            assertWithMessage("$mode: the hairline shows against the key").that(p.keyBorder).isNotEqualTo(p.key)
            assertWithMessage("$mode: and against the background").that(p.keyBorder).isNotEqualTo(p.keyboardBackground)
            assertWithMessage("$mode: function keys read apart from letters").that(p.modifierKey).isNotEqualTo(p.key)
            assertWithMessage("$mode: the primary action is accented").that(p.actionKey).isNotEqualTo(p.modifierKey)
        }
        assertWithMessage("a soft shadow in light mode only")
            .that(listOf(look.light.keyShadow != 0, look.dark.keyShadow != 0)).containsExactly(true, false).inOrder()
        assertThat(KeyboardLooks.ALL.filter { it.yieldsToUserSettings }).containsExactly(look)
    }

    /**
     * Maintainer 2026-11-07: SwiftKey's spacing with the same gap between rows as between keys,
     * darker keys, and a background darker than the keys painted as an aurora (dark mode only).
     */
    @Test
    fun theCleverKeysLookHasEqualGapsAndADarkAuroraBehindDarkerKeys() {
        val look = KeyboardLooks.cleverkeysLook
        val g = look.geometry
        assertThat(g.equalGaps).isTrue()
        assertWithMessage("SwiftKey's horizontal gap, about 0.11..0.12 of a key")
            .that(g.keyGapHorizontal).isIn(Range.closed(0.10f, 0.12f))
        // Equal in pixels at any row height.
        val e = KeyboardLookGeometry.resolve(
            g, KeyboardLookGeometry.UserGeometry(1f, 1f, 1f, 1f, 1f), keyWidth = 92f, rowHeight = 137f, density = 2.6f,
        )
        assertThat(e.verticalMargin).isEqualTo(e.horizontalMargin)

        val dark = look.dark
        val backdrop = dark.backdrop
        assertWithMessage("the dark palette paints the aurora").that(backdrop).isNotNull()
        assertWithMessage("light mode stays flat").that(look.light.backdrop).isNull()
        val base = relativeLuminance(dark.keyboardBackground)
        for ((role, fill) in listOf("key" to dark.key, "function key" to dark.modifierKey, "border" to dark.keyBorder)) {
            assertWithMessage("the base is darker than the $role").that(base).isLessThan(relativeLuminance(fill))
        }
        // "Darker than the keys overall": the painted background's mean luminance, sampled on a
        // phone-shaped keyboard with the falloff AuroraBackdropDrawable draws, stays below the
        // key fill's. (A curtain's rim may be brighter: that is the aurora in the gaps.)
        val mean = meanBackdropLuminance(dark.keyboardBackground, backdrop!!, width = 411f, height = 290f)
        assertWithMessage("mean background luminance vs the key fill")
            .that(mean).isLessThan(relativeLuminance(dark.key))
        assertWithMessage("the darker keys still carry the hints at 3:1")
            .that(contrast(dark.hint, dark.key)).isAtLeast(3.0)
        assertWithMessage("the bar joins the faded top edge in the base colour")
            .that(dark.barBackground).isEqualTo(dark.keyboardBackground)
        assertThat(backdrop.topFade).isGreaterThan(0f)
    }

    /**
     * Maintainer's pick from ten previews (2026-11-10): three curtains, the lowest one nearly level
     * and folded, finer rays, a partly green rim, twice the stars with more glints, and keys a
     * little translucent. Translucent fills must still carry every label and hint over the
     * brightest spot of the painted aurora.
     */
    @Test
    fun theAuroraPickHasThreeFoldedCurtainsAndLegibleTranslucentKeys() {
        val dark = KeyboardLooks.cleverkeysLook.dark
        val aurora = dark.backdrop!!
        assertThat(aurora.ribbons).hasSize(3)
        val near = aurora.ribbons.first()
        assertWithMessage("the lowest curtain runs nearly level").that(abs(near.slope)).isAtMost(0.1f)
        assertWithMessage("and folds more than twice across the view").that(near.waves).isAtLeast(2f)
        for (r in aurora.ribbons) assertWithMessage("finer rays").that(r.raysPer100Dp).isAtLeast(40f)
        val rimGreenOverRed = ((near.edgeColor shr 8) and 0xFF) - ((near.edgeColor shr 16) and 0xFF)
        assertWithMessage("a green-leaning rim").that(rimGreenOverRed).isGreaterThan(0)
        val stars = aurora.stars!!
        assertThat(stars.perSquare100Dp).isAtLeast(8f)
        assertThat(stars.glintEvery).isIn(Range.closed(1, 5))
        assertWithMessage("slightly translucent keys").that(aurora.keyOpacity).isIn(Range.closedOpen(0.8f, 1f))

        // The brightest backdrop pixel any key can sit over (curtains at a phone keyboard's raster).
        val w = 205
        val h = 145
        val curtains = AuroraRaster.render(dark.keyboardBackground, aurora.ribbons, w, h, w / 411f)
        val brightest = curtains.maxByOrNull { relativeLuminance(it) }!!
        for ((role, fill, label) in listOf(
            Triple("key", dark.key, dark.label),
            Triple("function key", dark.modifierKey, dark.modifierLabel),
        )) {
            val seen = blend(withAlpha(fill, aurora.keyOpacity), brightest)
            assertWithMessage("label on a translucent $role over the brightest aurora")
                .that(contrast(label, seen)).isAtLeast(4.5)
            assertWithMessage("hint on a translucent $role over the brightest aurora")
                .that(contrast(dark.hint, seen)).isAtLeast(3.0)
        }
        assertWithMessage("light mode keeps opaque keys").that(KeyboardLooks.cleverkeysLook.light.backdrop).isNull()
    }

    private fun withAlpha(color: Int, share: Float) = ((255 * share).toInt() shl 24) or (color and 0xFFFFFF)

    /**
     * Mean relative luminance of [backdrop] painted over [base] on a [width] × [height] view: the
     * curtains ([AuroraRaster]), then the glows' radial falloff (full alpha at the centre, 45 % at half the radius, 0 at the edge, on
     * an ellipse tilted by its rotation) and the top fade, as AuroraBackdropDrawable paints them.
     */
    private fun meanBackdropLuminance(base: Int, backdrop: LookBackdrop, width: Float, height: Float): Double {
        val cols = 96
        val rows = 64
        // The curtains exactly as AuroraBackdropDrawable renders them, at the sample grid's size
        // (width and height are in dp, so the grid's density is cols / width).
        val curtains = AuroraRaster.render(base, backdrop.ribbons, cols, rows, cols / width)
        var sum = 0.0
        for (j in 0 until rows) for (i in 0 until cols) {
            val x = (i + 0.5f) / cols * width
            val y = (j + 0.5f) / rows * height
            var color = curtains[j * cols + i]
            for (g in backdrop.glows) {
                val rx = g.rx * width
                val ry = g.ry * height
                val a = Math.toRadians(-g.rotationDeg.toDouble())
                val dx = x - g.cx * width
                val dy = y - g.cy * height
                val u = dx * Math.cos(a) - dy * Math.sin(a)
                val v = (dx * Math.sin(a) + dy * Math.cos(a)) * rx / ry
                val t = Math.hypot(u, v) / rx
                val f = when {
                    t <= 0.5 -> 1.0 - (1.0 - 0.45) * (t / 0.5)
                    t <= 1.0 -> 0.45 * (1.0 - (t - 0.5) / 0.5)
                    else -> 0.0
                }
                val alpha = (((g.color ushr 24) and 0xFF) * f).toInt()
                color = blend((alpha shl 24) or (g.color and 0xFFFFFF), color)
            }
            val fadeH = backdrop.topFade * height
            if (y < fadeH) {
                val alpha = (255 * (1 - y / fadeH)).toInt()
                color = blend((alpha shl 24) or (base and 0xFFFFFF), color)
            }
            sum += relativeLuminance(color)
        }
        return sum / (cols * rows)
    }

    /** [top] (with alpha) over the opaque [under], per channel. */
    private fun blend(top: Int, under: Int): Int {
        val a = ((top ushr 24) and 0xFF) / 255.0
        fun ch(shift: Int) = (((top shr shift) and 0xFF) * a + ((under shr shift) and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Only Samsung prints the language on the space bar ("English (US)", 1.jpg). */
    @Test
    fun onlySamsungShowsTheLanguageOnTheSpaceBar() {
        for (look in KeyboardLooks.ALL) {
            assertWithMessage(look.id).that(look.spaceLanguage).isEqualTo(look.id == KeyboardLooks.SAMSUNG)
        }
    }
}
