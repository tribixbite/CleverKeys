package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Per-key / per-row layout styling ([KeyStyle], 2026-10-28): attribute parsing (tolerant,
 * problems reported), key-over-row precedence, and the frame placement that keeps a styled key's
 * frame centred on its cell — the cell centre is what hit-testing and both swipe engines read,
 * so per-key gaps must never move it. Pure JVM. The parser wiring through `KeyboardData` and the
 * swipe-centre invariance on the worked example are in [LayoutXmlStylingTest] (mock tier).
 */
class KeyStyleTest {

    private fun parse(vararg pairs: Pair<String, String>, problems: MutableList<String> = ArrayList()): KeyStyle? {
        val m = pairs.toMap()
        return KeyStyles.parse("key", { m[it] }, problems)
    }

    @Test
    fun everyStylingAttributeIsReadInItsLayoutUnit() {
        val style = parse(
            "key_horizontal_gap" to "0.6", "key_vertical_gap" to "2", "key_border" to "false",
            "key_border_radius" to "25", "key_border_width" to "2",
            "key_background" to "#112233", "key_background_pressed" to "#80112233",
            "key_border_color" to "#445566", "key_label_color" to "#778899", "key_label_color_pressed" to "#AABBCC",
        )!!
        assertThat(style.geometry).containsExactly(
            KeyGeometryField.HORIZONTAL_GAP, 0.6f, KeyGeometryField.VERTICAL_GAP, 2f, KeyGeometryField.BORDER, 0f,
            KeyGeometryField.BORDER_RADIUS, 25f, KeyGeometryField.BORDER_WIDTH, 2f,
        )
        assertThat(style.background).isEqualTo(0xFF112233.toInt())
        assertThat(style.backgroundPressed).isEqualTo(0x80112233.toInt())
        assertThat(style.borderColor).isEqualTo(0xFF445566.toInt())
        assertThat(style.labelColor).isEqualTo(0xFF778899.toInt())
        assertThat(style.labelColorPressed).isEqualTo(0xFFAABBCC.toInt())
        assertThat(KeyStyles.ATTRIBUTES).hasSize(10)
    }

    @Test
    fun invalidValuesAreDroppedAndReportedTheRestIsKept() {
        val problems = ArrayList<String>()
        val style = parse(
            "key_horizontal_gap" to "9", "key_border" to "maybe", "key_background" to "blue",
            "key_label_color" to "#FFFFFF", problems = problems,
        )
        assertThat(problems).containsExactly(
            "<key> key_horizontal_gap=\"9\"", "<key> key_border=\"maybe\"", "<key> key_background=\"blue\"",
        )
        assertThat(style).isEqualTo(KeyStyle(labelColor = 0xFFFFFFFF.toInt()))
        assertWithMessage("an element with no (valid) styling has no style")
            .that(parse("key_background" to "nope")).isNull()
        assertThat(parse()).isNull()
    }

    @Test
    fun aKeysAttributesBeatItsRowsAndUnsetOnesInheritThem() {
        val row = KeyStyle(
            geometry = mapOf(KeyGeometryField.VERTICAL_GAP to 2f, KeyGeometryField.HORIZONTAL_GAP to 1f),
            background = 1, borderColor = 2,
        )
        val key = KeyStyle(geometry = mapOf(KeyGeometryField.HORIZONTAL_GAP to 0.5f), background = 3)
        assertThat(key.over(row)).isEqualTo(
            KeyStyle(
                geometry = mapOf(KeyGeometryField.VERTICAL_GAP to 2f, KeyGeometryField.HORIZONTAL_GAP to 0.5f),
                background = 3, borderColor = 2,
            )
        )
        assertThat(key.over(null)).isEqualTo(key)
    }

    @Test
    fun aStyledKeysFrameStaysCentredOnItsCell() {
        // onDraw walks a row with the layout's gaps: x = cell left + layoutGap / 2.
        for ((layoutH, keyH) in listOf(10f to 2f, 4f to 30f, 0f to 12f, 12f to 12f)) {
            for ((layoutV, keyV) in listOf(8f to 0f, 0f to 20f)) {
                val cellLeft = 300f; val cellTop = 40f; val cellW = 96f; val cellH = 120f
                val f = KeyStyles.frame(
                    x = cellLeft + layoutH / 2f, y = cellTop + layoutV / 2f, cellWidth = cellW, cellHeight = cellH,
                    layoutHGap = layoutH, layoutVGap = layoutV, keyHGap = keyH, keyVGap = keyV,
                )
                assertWithMessage("h $layoutH→$keyH").that(f.centerX).isWithin(1e-3f).of(cellLeft + cellW / 2f)
                assertWithMessage("v $layoutV→$keyV").that(f.centerY).isWithin(1e-3f).of(cellTop + cellH / 2f)
                assertThat(f.width).isEqualTo(cellW - keyH)
                assertThat(f.height).isEqualTo(cellH - keyV)
            }
        }
    }
}
