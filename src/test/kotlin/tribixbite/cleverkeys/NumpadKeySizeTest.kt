package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import java.io.File
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
 *    when `config.scale_numpad_height && !layout.bottom_row`, so a numeric keyboard fills the
 *    configured keyboard height instead of overflowing it. That branch is only reachable if
 *    the shipped numeric layouts declare `bottom_row="false"` and the default stays on —
 *    both pinned below. (The arithmetic itself needs `android.graphics.Paint`, so it is out
 *    of reach of a pure test; what is pinned here is that the branch's inputs still hold.)
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
        // Theme.Computed: `if (config.scale_numpad_height && !layout.bottom_row)` → divide by
        // the layout's own keysHeight so the rows fill the configured keyboard height.
        assertThat(Defaults.SCALE_NUMPAD_HEIGHT).isTrue()
        assertThat(bottomRow(PIN)).isFalse()
        assertThat(bottomRow(NUMERIC)).isFalse()
    }

    @Test
    fun numericLayout_isSixUnitsWideAndUnaffectedByThePinFix() {
        // The #58 fix was PIN-only; numeric.xml's seven-key rows genuinely need 6.0 units.
        // Pinned so a future "make numeric match PIN" edit is a deliberate, visible change.
        assertThat(computedWidth(NUMERIC)).isEqualTo(6.0f)
    }
}
