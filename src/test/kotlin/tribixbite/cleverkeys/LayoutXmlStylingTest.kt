package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.swipe.CtcMappedLayoutBuilder
import tribixbite.cleverkeys.theme.ThemeColorRole
import tribixbite.cleverkeys.theme.darkKeyboardColorScheme

/**
 * A layout fully styled from its XML (2026-10-28): the worked example
 * (docs/wiki/layouts/examples/harbour.xml) through the REAL layout parser — `<theme>`, row
 * defaults and per-key attributes — the tolerant handling of bad values, the swipe geometry
 * (per-key gaps must not move a key's cell or centre, so neither swipe engine sees a different
 * board), and the per-key frames `Theme.Computed` builds with the documented precedence
 * key > row > user per-layout choice > layout `key_*` default > theme/look > global.
 *
 * Mock tier: android.util.Xml is a stub, so kxml2 is substituted (as in LayoutKeyGeometryConfigTest).
 */
class LayoutXmlStylingTest {

    private val objenesis = ObjenesisStd()
    private lateinit var context: Context
    private val exampleXml = File("docs/wiki/layouts/examples/harbour.xml").readText()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
        mockkStatic(Color::class)
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } answers {
            (arg<Int>(0) shl 24) or (arg<Int>(1) shl 16) or (arg<Int>(2) shl 8) or arg<Int>(3)
        }
        every { Color.red(any<Int>()) } answers { (firstArg<Int>() shr 16) and 0xFF }
        every { Color.green(any<Int>()) } answers { (firstArg<Int>() shr 8) and 0xFF }
        every { Color.blue(any<Int>()) } answers { firstArg<Int>() and 0xFF }
        every { Color.colorToHSV(any<Int>(), any<FloatArray>()) } just runs
        every { Color.HSVToColor(any<FloatArray>()) } returns 0
        Theme::class.java.getDeclaredField("_key_font").apply { isAccessible = true }.set(null, mockk<Typeface>())
        val configuration = objenesis.newInstance(Configuration::class.java)
        val resources = mockk<Resources>(relaxed = true)
        every { resources.configuration } returns configuration
        context = mockk(relaxed = true)
        every { context.resources } returns resources
    }

    @After
    fun teardown() {
        Theme::class.java.getDeclaredField("_key_font").apply { isAccessible = true }.set(null, null)
        unmockkAll()
    }

    private fun keyOf(layout: KeyboardData, name: String): KeyboardData.Key =
        layout.rows.flatMap { it.keys }.first { it.keys[0]?.getString() == name }

    @Test
    fun theWorkedExampleLoadsWithItsThemeRowDefaultsAndKeyStyles() {
        val layout = KeyboardData.load_string_exn(exampleXml)
        assertThat(layout.style_problems).isEmpty()
        assertThat(layout.name).isEqualTo("Harbour")
        val theme = layout.embedded_theme!!
        assertThat(theme.name).isEqualTo("Harbour")
        assertThat(theme.colors.keys).containsExactlyElementsIn(ThemeColorRole.entries)
        assertThat(layout.declared_key_geometry).containsExactly(
            "key_horizontal_gap", "1.2", "key_vertical_gap", "1.5", "key_border", "true",
            "key_border_radius", "18", "key_border_width", "1",
        )

        val f = keyOf(layout, "f")
        assertThat(f.style).isEqualTo(KeyStyle(labelColor = 0xFF3A86C8.toInt()))
        assertWithMessage("an unstyled key in an unstyled row has no style").that(keyOf(layout, "g").style).isNull()
        assertWithMessage("row defaults reach every key of the row")
            .that(keyOf(layout, "z").style).isEqualTo(KeyStyle(background = 0xFF3E6A93.toInt(), borderColor = 0xFF9FB3C8.toInt(), labelColor = 0xFFFFFFFF.toInt()))
        assertWithMessage("a key's own attribute beats its row's, the rest is inherited")
            .that(layout.rows[2].keys.first().style)
            .isEqualTo(KeyStyle(background = 0xFF1F6FB2.toInt(), backgroundPressed = 0xFF174F80.toInt(), borderColor = 0xFF9FB3C8.toInt(), labelColor = 0xFFFFFFFF.toInt(),
                labelColorPressed = 0xFFFFFFFF.toInt()))
        val bottom = layout.rows[3].keys
        assertThat(bottom[3].style!!.geometry).containsExactly(
            KeyGeometryField.VERTICAL_GAP, 2f, KeyGeometryField.HORIZONTAL_GAP, 0.6f,
        )
        assertThat(bottom.last().style).isEqualTo(
            KeyStyle(
                geometry = mapOf(KeyGeometryField.VERTICAL_GAP to 2f, KeyGeometryField.BORDER to 0f),
                backgroundPressed = 0xFF174F80.toInt(), labelColorPressed = 0xFFFFFFFF.toInt(),
            )
        )
        assertWithMessage("layout transformations keep the styles and the embedded theme")
            .that(layout.mapKeys(KeyboardData.MapKey { it }).let { it.rows[3].keys.last().style to it.embedded_theme })
            .isEqualTo(bottom.last().style to theme)
    }

    @Test
    fun badStylingValuesAreDroppedAndTheLayoutStillLoads() {
        val xml = """<keyboard name="Broken" script="latin">
            <theme key="#nothex" corner_radius="6"><sparkle/><dark label="#FFFFFF"/></theme>
            <theme key="#000000"/>
            <row key_background="pink"><key c="a" key_horizontal_gap="99" key_label_color="#00FF00"/><key c="b"/></row>
        </keyboard>"""
        val layout = KeyboardData.load_string_exn(xml)
        assertThat(layout.rows.single().keys).hasSize(2)
        assertThat(layout.style_problems).containsExactly(
            "<sparkle> (not allowed in <theme>)", "<theme> key=\"#nothex\"", "<theme> (only the first <theme> is used)",
            "<row> key_background=\"pink\"", "<key> key_horizontal_gap=\"99\"",
        )
        assertThat(layout.embedded_theme!!.geometry.cornerRadiusDp).isEqualTo(6f)
        assertThat(layout.embedded_theme!!.colors).isEmpty()
        assertThat(layout.rows.single().keys[0].style).isEqualTo(KeyStyle(labelColor = 0xFF00FF00.toInt()))
    }

    /**
     * Per-key and per-row colours are the SAME in light and dark mode, while the `<theme>`'s
     * label colours follow the mode. A styled key whose label falls back to the theme's can
     * therefore vanish in one mode: Saga 2026-10-27, dark mode, the example's third row
     * (`key_background="#F4F7FA"`, theme dark label `#E6EEF5`, contrast 1.09) and its Shift and
     * Backspace icons were invisible. Every styled key of the shipped example must keep a
     * readable label (contrast ≥ 3:1, WCAG for large text / UI) at rest and, where the file sets
     * a pressed fill, while pressed — in BOTH modes.
     */
    @Test
    fun theWorkedExampleKeepsEveryStyledKeyLegibleInLightAndDarkMode() {
        val layout = KeyboardData.load_string_exn(exampleXml)
        val spec = layout.embedded_theme!!
        val problems = mutableListOf<String>()
        for (light in listOf(true, false)) {
            val c = spec.colorsFor(light)
            for (key in layout.rows.flatMap { it.keys }) {
                val style = key.style ?: continue
                val kv = key.keys[0] ?: continue
                val isChar = kv.getKind() == KeyValue.Kind.Char
                val mode = if (light) "light" else "dark"
                if (style.background != null || style.labelColor != null) {
                    val fill = style.background ?: c.getValue(if (isChar) ThemeColorRole.KEY else ThemeColorRole.KEY_MODIFIER)
                    val label = style.labelColor ?: c.getValue(if (isChar) ThemeColorRole.LABEL else ThemeColorRole.MODIFIER_LABEL)
                    val ratio = contrast(fill, label)
                    if (ratio < 3.0) problems += "$mode ${kv.getString()} at rest: ${hex(label)} on ${hex(fill)} = %.2f".format(ratio)
                }
                // A latched modifier (Shift after a sentence end) draws its ACTIVATED frame: the
                // pressed fill, but — not being held down — its rest label colour, so a key
                // label colour also has to read on the theme's mode-dependent pressed fill
                // (Saga 2026-10-27, light mode: white Shift arrow on #C9D6E3, 1.48).
                if (kv.getKind() == KeyValue.Kind.Modifier && style.labelColor != null) {
                    val fill = style.backgroundPressed ?: c.getValue(ThemeColorRole.KEY_PRESSED)
                    val ratio = contrast(fill, style.labelColor!!)
                    if (ratio < 3.0) problems += "$mode ${kv.getString()} latched: ${hex(style.labelColor!!)} on ${hex(fill)} = %.2f".format(ratio)
                }
                if (style.backgroundPressed != null) {
                    val label = style.labelColorPressed ?: c.getValue(ThemeColorRole.LABEL_PRESSED)
                    val ratio = contrast(style.backgroundPressed!!, label)
                    if (ratio < 3.0) problems += "$mode ${kv.getString()} pressed: ${hex(label)} on ${hex(style.backgroundPressed!!)} = %.2f".format(ratio)
                }
            }
        }
        assertWithMessage("styled keys whose label is unreadable in one system mode").that(problems).isEmpty()
    }

    private fun hex(c: Int) = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)

    /** WCAG 2 contrast ratio of two opaque sRGB colours. */
    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val (hi, lo) = lum(a).let { la -> lum(b).let { lb -> if (la > lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }

    /** The example with every styling attribute and the <theme> element removed. */
    private fun unstyled(xml: String): String {
        var s = Regex("<theme\\b[\\s\\S]*?</theme>").replace(Regex("<!--[\\s\\S]*?-->").replace(xml, ""), "")
        for (a in KeyStyles.ATTRIBUTES) s = Regex("\\s$a=\"[^\"]*\"").replace(s, "")
        return s
    }

    @Test
    fun perKeyStylingMovesNoKeyCellOrCentreSoBothSwipeEnginesSeeTheSameBoard() {
        val styled = KeyboardData.load_string_exn(exampleXml)
        val plain = KeyboardData.load_string_exn(unstyled(exampleXml))
        assertThat(plain.rows.flatMap { it.keys }.all { it.style == null }).isTrue()
        val params = KeyboardGeometry.Params(keyWidth = 108f, rowHeight = 160f, marginTop = 12f, marginLeft = 6f)
        val a = KeyboardGeometry.computeKeyRects(styled, params)
        val b = KeyboardGeometry.computeKeyRects(plain, params)
        assertWithMessage("hit-test cells (also TalkBack bounds) are identical")
            .that(a.map { it.kv to it.bounds }).isEqualTo(b.map { it.kv to it.bounds })
        val alphabet = ('a'..'z').toList().toCharArray()
        val ctcA = CtcMappedLayoutBuilder.build(a, alphabet)!!
        val ctcB = CtcMappedLayoutBuilder.build(b, alphabet)!!
        assertWithMessage("the CTC encoder's key centres are identical")
            .that(ctcA.layout.keyCentersX.toList() to ctcA.layout.keyCentersY.toList())
            .isEqualTo(ctcB.layout.keyCentersX.toList() to ctcB.layout.keyCentersY.toList())
        assertThat(listOf(ctcA.originX, ctcA.originY, ctcA.invW, ctcA.invH))
            .isEqualTo(listOf(ctcB.originX, ctcB.originY, ctcB.invW, ctcB.invH))
        assertWithMessage("hit-testing returns the same key at every key centre")
            .that(b.map { r -> KeyboardGeometry.keyAt(styled, params, (r.bounds.left + r.bounds.right) / 2f, (r.bounds.top + r.bounds.bottom) / 2f)?.keys?.get(0) })
            .isEqualTo(b.map { it.kv })
    }

    // ── Theme.Computed: per-key frames and their precedence ───────────────────────

    private fun setField(target: Any, name: String, value: Any?) {
        Config::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun config(overrides: Map<KeyGeometryField, Float>): Config = objenesis.newInstance(Config::class.java).apply {
        screenHeightPixels = 2000
        keyboardHeightPercent = 30
        keyOpacity = 255
        keyActivatedOpacity = 255
        key_vertical_margin = 0.02f
        key_horizontal_margin = 0.03f
        borderConfig = false
        customBorderRadius = 0.05f
        customBorderLineWidth = 2f
        layout_key_geometry = listOf(overrides)
    }

    @Test
    fun aStyledKeysFrameTakesItsOwnColoursAndGapsWithKeyOverRowOverLayoutPrecedence() {
        val theme = Theme(context, darkKeyboardColorScheme(), density = 1f)
        // The layout's effective overrides: the user's per-layout gap (1) over the XML default.
        val tc = Theme.Computed(theme, config(mapOf(KeyGeometryField.HORIZONTAL_GAP to 1f, KeyGeometryField.BORDER to 1f)), 100f,
            objenesis.newInstance(KeyboardData::class.java))
        assertThat(tc.horizontal_margin).isWithin(1e-4f).of(1f)

        val rowStyle = KeyStyle(geometry = mapOf(KeyGeometryField.VERTICAL_GAP to 2f), background = 0xFF112233.toInt(), borderColor = 0xFF445566.toInt())
        val keyStyle = KeyStyle(geometry = mapOf(KeyGeometryField.HORIZONTAL_GAP to 4f), labelColor = 0xFF778899.toInt(),
            labelColorPressed = 0xFFAABBCC.toInt(), backgroundPressed = 0xFF010203.toInt()).over(rowStyle)
        val rest = tc.styledFrame(Theme.Computed.KeyRole.MODIFIER, keyStyle)
        assertWithMessage("key gap 4 % beats the user's per-layout 1 %").that(rest.horizontalMargin).isWithin(1e-4f).of(4f)
        assertWithMessage("row gap 2 % beats the global 2 % of the row height")
            .that(rest.verticalMargin).isWithin(1e-3f).of(0.02f * tc.row_height)
        assertThat(rest.key.bg_paint.color).isEqualTo(0xFF112233.toInt())
        assertThat(rest.key.border_paint.color).isEqualTo(0xFF445566.toInt())
        assertWithMessage("the key's label colour replaces the role's").that(rest.key.labelColorOverride).isEqualTo(0xFF778899.toInt())
        assertWithMessage("layout border on: the custom border radius applies to the styled key")
            .that(rest.key.border_radius).isWithin(1e-4f).of(5f)
        val pressed = tc.styledFrame(Theme.Computed.KeyRole.ACTIVATED, keyStyle)
        assertThat(pressed.key.bg_paint.color).isEqualTo(0xFF010203.toInt())
        assertThat(pressed.key.pressedLabelColorOverride).isEqualTo(0xFFAABBCC.toInt())
        assertWithMessage("cached per style and role").that(tc.styledFrame(Theme.Computed.KeyRole.MODIFIER, keyStyle)).isSameInstanceAs(rest)

        val borderOff = tc.styledFrame(Theme.Computed.KeyRole.NORMAL, KeyStyle(geometry = mapOf(KeyGeometryField.BORDER to 0f)))
        assertWithMessage("key_border=false draws the theme's own frame although the layout's border is on")
            .that(borderOff.key.border_radius).isEqualTo(theme.keyBorderRadius)
        assertThat(borderOff.key.border_width).isEqualTo(theme.keyBorderWidth)
        assertWithMessage("an unstyled colour falls back to the role's fill")
            .that(borderOff.key.bg_paint.color).isEqualTo(tc.key.bg_paint.color)
        assertThat(borderOff.horizontalMargin).isEqualTo(tc.horizontal_margin)
    }
}
