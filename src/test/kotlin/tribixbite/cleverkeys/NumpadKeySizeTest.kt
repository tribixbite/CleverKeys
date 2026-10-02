package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.StringReader
import org.kxml2.io.KXmlParser
import org.objenesis.ObjenesisStd
import org.xmlpull.v1.XmlPullParser
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Pins the published "**Numpad/PIN Keyboard — 20% larger keys (#58)**" promise
 * (v1.2.6 and re-published in v1.2.8).
 *
 * ## Where the 20% actually comes from
 *
 * Two independent mechanisms were announced under one bullet; this test pins both:
 *
 * 1. **Width (the 20%).** `KeyboardData.parse_keyboard` uses the `<keyboard width=…>`
 *    attribute when it is non-zero and otherwise falls back to `compute_max_width(rows)` —
 *    the widest row's `Σ(key.width + key.shift)`. Commit *"fix: enlarge PIN keyboard keys by
 *    20% (#58)"* deleted `width="6.0"` from `pin.xml`, so the keyboard is now 5.0 units wide
 *    for the same content. Key width scales as `screenWidth / units`, so 6.0 → 5.0 is exactly
 *    a 1.20× enlargement — the announced 20%, and the reason the file still carries the
 *    comment *"This makes keys 20% larger by eliminating right-side padding"*.
 * 2. **Height.** `Theme.Computed` divides by `layout.keysHeight` instead of the usual 3.95
 *    when `config.scale_numpad_height && layout.numpad_height`, so a numeric keyboard fills the
 *    configured keyboard height instead of overflowing it. That branch is only reachable if
 *    the shipped numeric layouts opt in with `numpad_height="true"` and the default stays on.
 *    The real parser and Theme.Computed run in the mock tier with functional Paint support;
 *    custom bottom-row-free layouts retain normal height (GH #90).
 *
 * A regression in either direction — someone re-adding an explicit `width`, adding a key to a
 * PIN row, or flipping `SCALE_NUMPAD_HEIGHT` — silently shrinks the keys back and turns this
 * test red.
 */
class NumpadKeySizeTest {

    private companion object {
        val PIN = File("src/main/layouts/pin.xml")
        val NUMERIC = File("src/main/layouts/numeric.xml")

        /** The explicit width `pin.xml` carried before the #58 fix. */
        const val WIDTH_BEFORE_FIX = 6.0f

        /** The auto-computed width after it (`shift 1.0` + four unit-width keys). */
        const val WIDTH_AFTER_FIX = 5.0f
    }

    /** `<keyboard width=…>`, or 0 when absent — mirrors `attribute_float(parser, "width", 0f)`. */
    private fun declaredWidth(layout: File): Float {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout)
        val kb = doc.getElementsByTagName("keyboard").item(0) as Element
        return kb.getAttribute("width").toFloatOrNull() ?: 0f
    }

    private fun bottomRow(layout: File): Boolean {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout)
        val kb = doc.getElementsByTagName("keyboard").item(0) as Element
        // `attribute_bool(parser, "bottom_row", true)` — absent means true.
        return kb.getAttribute("bottom_row").let { if (it.isEmpty()) true else it.toBoolean() }
    }

    /** Mirrors `KeyboardData.compute_max_width`: widest row's `Σ(key.width + key.shift)`. */
    private fun computedWidth(layout: File): Float {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout)
        val rows = doc.getElementsByTagName("row")
        var widest = 0f
        for (r in 0 until rows.length) {
            val row = rows.item(r) as Element
            val keys = row.getElementsByTagName("key")
            var sum = 0f
            for (k in 0 until keys.length) {
                val key = keys.item(k) as Element
                sum += (key.getAttribute("width").toFloatOrNull() ?: 1f) +
                    (key.getAttribute("shift").toFloatOrNull() ?: 0f)
            }
            if (sum > widest) widest = sum
        }
        return widest
    }

    @Test
    fun pinLayout_declaresNoExplicitWidthSoItAutoSizesToItsContent() {
        // A non-zero `width` attribute wins over compute_max_width and would re-introduce the
        // dead right-hand column the #58 fix removed.
        assertThat(declaredWidth(PIN)).isEqualTo(0f)
        assertThat(computedWidth(PIN)).isEqualTo(WIDTH_AFTER_FIX)
    }

    @Test
    fun pinLayout_keysAreExactlyTwentyPercentLargerThanBeforeTheFix() {
        // Key width scales as screenWidth / keyboardUnits, so the enlargement factor is the
        // ratio of the old declared width to the new computed one.
        val enlargement = WIDTH_BEFORE_FIX / computedWidth(PIN)
        assertThat(enlargement).isWithin(1e-6f).of(1.20f)
    }

    @Test
    fun pinLayout_everyRowIsTheSameFiveUnitsWide() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(PIN)
        val rows = doc.getElementsByTagName("row")
        assertThat(rows.length).isEqualTo(4)
        // compute_max_width takes the WIDEST row: a single over-wide row would silently shrink
        // every key on every other row back down.
        for (r in 0 until rows.length) {
            val row = rows.item(r) as Element
            val keys = row.getElementsByTagName("key")
            var sum = 0f
            for (k in 0 until keys.length) {
                val key = keys.item(k) as Element
                sum += (key.getAttribute("width").toFloatOrNull() ?: 1f) +
                    (key.getAttribute("shift").toFloatOrNull() ?: 0f)
            }
            assertThat(sum).isEqualTo(WIDTH_AFTER_FIX)
        }
    }

    @Test
    fun numericLayouts_keepTheHeightScalingBranchReachable() {
        // Theme.Computed: `if (config.scale_numpad_height && layout.numpad_height)` → divide by
        // the layout's own keysHeight so the rows fill the configured keyboard height.
        assertThat(Defaults.SCALE_NUMPAD_HEIGHT).isTrue()
        assertThat(bottomRow(PIN)).isFalse()
        assertThat(bottomRow(NUMERIC)).isFalse()
        assertThat(parseLayout(PIN.readText()).numpad_height).isTrue()
        assertThat(parseLayout(NUMERIC.readText()).numpad_height).isTrue()
    }

    /** GH #90: no bottom row means no appended row, not numeric-height scaling. */
    @Test
    fun customSingleRow_keepsNormalRowHeightWithNumpadScalingEnabled() {
        val layout = parseLayout(
            """<keyboard bottom_row="false"><row><key key0="a"/></row></keyboard>"""
        )
        assertThat(rowHeight(layout, scaleNumpad = true)).isWithin(1e-4f).of(400f / 3.95f)
    }

    @Test
    fun customRowHeightAndShift_keepTheirAuthoredProportions() {
        val layout = parseLayout(
            """<keyboard bottom_row="false"><row height="1.5" shift="0.25">
                <key key0="a"/></row></keyboard>"""
        )
        assertThat(layout.keysHeight).isEqualTo(1.75f)
        assertThat(layout.rows.single().height).isEqualTo(1.5f)
        assertThat(layout.rows.single().shift).isEqualTo(0.25f)
        assertThat(rowHeight(layout, scaleNumpad = true)).isWithin(1e-4f).of(400f / 3.95f)
    }

    @Test
    fun numpadHeightFlag_defaultsOffAndOnlyExplicitTrueOptsIn() {
        for ((attribute, expected) in listOf(
            "" to false, "numpad_height=\"false\"" to false, "numpad_height=\"true\"" to true
        )) {
            val layout = parseLayout(
                "<keyboard bottom_row=\"false\" $attribute><row><key key0=\"a\"/></row></keyboard>"
            )
            assertThat(layout.numpad_height).isEqualTo(expected)
        }
    }

    @Test
    fun numericAndPinLayouts_scaleToConfiguredHeightOnlyWhenEnabled() {
        for (file in listOf(NUMERIC, PIN)) {
            val layout = parseLayout(file.readText())
            assertThat(rowHeight(layout, scaleNumpad = true) * layout.keysHeight)
                .isWithin(1e-4f).of(400f)
            assertThat(rowHeight(layout, scaleNumpad = false))
                .isWithin(1e-4f).of(400f / 3.95f)
        }
    }

    @Test
    fun customOptIn_scalesAuthoredHeightAndShiftProportionally() {
        val layout = parseLayout(
            """<keyboard bottom_row="false" numpad_height="true">
                <row height="1.5" shift="0.25"><key key0="a"/></row></keyboard>"""
        )
        assertThat(rowHeight(layout, scaleNumpad = true) * layout.keysHeight)
            .isWithin(1e-4f).of(400f)
        assertThat(rowHeight(layout, scaleNumpad = false))
            .isWithin(1e-4f).of(400f / 3.95f)
    }

    @Test
    fun rowAndKeyTransformations_preserveTheSourceHeightPolicy() {
        val numpad = parseLayout(NUMERIC.readText())
        val compact = parseLayout(
            """<keyboard bottom_row="false"><row><key key0="a"/></row></keyboard>"""
        )
        for (layout in listOf(numpad, compact)) {
            val extraKeys = mapOf(
                KeyValue.makeCharKey('z') to KeyboardData.PreferredPos.ANYWHERE
            )
            val transformed = listOf(
                layout.mapKeys(KeyboardData.MapKey { it }),
                layout.insert_row(layout.rows.first(), 0),
                layout.addExtraKeys(extraKeys.entries.iterator()),
                layout.addNumPad(numpad),
            )
            for (result in transformed) {
                assertThat(result.numpad_height).isEqualTo(layout.numpad_height)
            }
        }
    }

    @Test
    fun tallCustomLayouts_stillFitWithinTheScreenHeight() {
        val rows = "<row><key key0=\"a\"/></row>".repeat(20)
        val layout = parseLayout("<keyboard bottom_row=\"false\">$rows</keyboard>")
        assertThat(rowHeight(layout, scaleNumpad = true) * layout.keysHeight)
            .isWithin(1e-4f).of(1600f)
    }

    /** Run production parsing with the functional pull parser on the test classpath. */
    private fun parseLayout(xml: String): KeyboardData {
        val parser = KXmlParser().apply { setInput(StringReader(xml)) }
        val method = KeyboardData.Companion::class.java.getDeclaredMethod(
            "parse_keyboard", XmlPullParser::class.java
        ).apply { isAccessible = true }
        return method.invoke(KeyboardData.Companion, parser) as KeyboardData
    }

    /** Exercise Theme.Computed itself; only constructors needing an Android Context are skipped. */
    private fun rowHeight(layout: KeyboardData, scaleNumpad: Boolean): Float {
        val objenesis = ObjenesisStd()
        val config = objenesis.newInstance(Config::class.java).apply {
            screenHeightPixels = 1600
            keyboardHeightPercent = 25
            scale_numpad_height = scaleNumpad
        }
        val theme = objenesis.newInstance(Theme::class.java)
        return Theme.Computed(theme, config, 100f, layout).row_height
    }

    @Test
    fun numericLayout_isSixUnitsWideAndUnaffectedByThePinFix() {
        // The #58 fix was PIN-only; numeric.xml's seven-key rows genuinely need 6.0 units.
        // Pinned so a future "make numeric match PIN" edit is a deliberate, visible change.
        assertThat(computedWidth(NUMERIC)).isEqualTo(6.0f)
    }
}
