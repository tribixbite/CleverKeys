package tribixbite.cleverkeys.a11y

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData

/**
 * Pure-JVM parity test for [KeyboardGeometry] — the shared hit-test geometry
 * that ALL users' tap AND swipe hit-testing runs through (extracted verbatim
 * from `Keyboard2View.getKeyAtPosition`). A drift here silently breaks typing
 * for everyone, so this test is deliberately thorough:
 *
 *  - rect count == number of non-placeholder keys
 *  - `keyAt(center of rect) === rect.key` for every rect
 *  - all three slop rules (left-of-row→'a', gap→next key, right→last key)
 *  - rects are row-major and horizontally disjoint within a row
 *  - marginLeft shift moves every cell by the same delta (consistency)
 *
 * Builds [KeyboardData.Row]s directly (public API, no Android XML parser) and
 * drives the `internal` row-list overloads.
 */
class KeyboardGeometryTest {

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun charKey(c: Char, width: Float = 1f, shift: Float = 0f): KeyboardData.Key =
        KeyboardData.Key.EMPTY
            .withKeyValue(0, KeyValue.makeCharKey(c))
            .let { KeyboardData.Key(it.keys, it.anticircle, 0, width, shift, null) }

    /** A placeholder cell: keys[0] == null. Advances the x cursor, no rect. */
    private fun placeholder(width: Float = 1f, shift: Float = 0f): KeyboardData.Key =
        KeyboardData.Key(List(9) { null }, null, 0, width, shift, null)

    private fun row(vararg keys: KeyboardData.Key, height: Float = 1f, shift: Float = 0f) =
        KeyboardData.Row(keys.toList(), height, shift)

    // A 3-row QWERTY-ish fixture. Middle row contains 'a' and 'l' → exercises
    // the a/l special case + left-of-row slop.
    private val topRow = row(*"qwertyuiop".map { charKey(it) }.toTypedArray())
    private val midRow = row(*"asdfghjkl".map { charKey(it) }.toTypedArray())
    private val botRow = row(*"zxcvbnm".map { charKey(it) }.toTypedArray())
    private val rows = listOf(topRow, midRow, botRow)

    private fun params(keyWidth: Float = 100f, marginLeft: Float = 50f) =
        KeyboardGeometry.Params(
            keyWidth = keyWidth,
            rowHeight = 160f,
            marginTop = 20f,
            marginLeft = marginLeft,
        )

    // Center of a rect, for round-trip lookups.
    private fun centerX(r: KeyboardGeometry.KeyRect) = (r.bounds.left + r.bounds.right) / 2f
    private fun centerY(r: KeyboardGeometry.KeyRect) = (r.bounds.top + r.bounds.bottom) / 2f

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun rectCountEqualsNonPlaceholderKeys() {
        val p = params()
        val rects = KeyboardGeometry.computeKeyRects(rows, p)
        // 10 + 9 + 7 = 26 real keys, no placeholders in this fixture.
        assertThat(rects).hasSize(26)

        // Now inject placeholders and confirm they don't produce rects but DO
        // advance the cursor (so the following key's rect shifts right).
        val withGap = listOf(row(charKey('a'), placeholder(), charKey('b')))
        val gapRects = KeyboardGeometry.computeKeyRects(withGap, p)
        assertThat(gapRects).hasSize(2)                       // 'a' and 'b' only
        assertThat(gapRects[0].kv.getChar()).isEqualTo('a')
        assertThat(gapRects[1].kv.getChar()).isEqualTo('b')
        // 'b' starts after 'a'(1u) + placeholder(1u) = 2 units from margin.
        assertThat(gapRects[1].bounds.left).isEqualTo(p.marginLeft + 2 * p.keyWidth)
    }

    @Test
    fun virtualIdsAreFlatRowMajorAndContiguous() {
        val rects = KeyboardGeometry.computeKeyRects(rows, params())
        rects.forEachIndexed { i, r -> assertThat(r.virtualId).isEqualTo(i) }
    }

    @Test
    fun keyAtCenterResolvesBackToThatKey() {
        val p = params()
        val rects = KeyboardGeometry.computeKeyRects(rows, p)
        for (r in rects) {
            val hit = KeyboardGeometry.keyAt(rows, p, centerX(r), centerY(r))
            assertThat(hit).isSameInstanceAs(r.key)
        }
    }

    @Test
    fun rectsAreRowMajorAndHorizontallyDisjoint() {
        val p = params()
        val rects = KeyboardGeometry.computeKeyRects(rows, p)

        // Group by row via top edge (rows have distinct y bands here).
        val byRowTop = rects.groupBy { it.bounds.top }
        // Three distinct row bands.
        assertThat(byRowTop.keys).hasSize(3)

        for ((_, rowRects) in byRowTop) {
            // Within a row, each cell's left == previous cell's right (disjoint,
            // contiguous, left-to-right) for a zero-shift fixture.
            for (i in 1 until rowRects.size) {
                assertThat(rowRects[i].bounds.left).isEqualTo(rowRects[i - 1].bounds.right)
                assertThat(rowRects[i].bounds.left).isAtLeast(rowRects[i - 1].bounds.left)
            }
        }

        // Rows are top-to-bottom, non-overlapping: each row's top == previous
        // row's bottom.
        val tops = byRowTop.keys.sorted()
        val rowBottoms = tops.map { top -> rects.first { it.bounds.top == top }.bounds.bottom }
        for (i in 1 until tops.size) {
            assertThat(tops[i]).isEqualTo(rowBottoms[i - 1])
        }
    }

    @Test
    fun slopLeftOfRowReturnsAKey() {
        val p = params(marginLeft = 50f)
        // y in the middle-row band; x LEFT of the margin → should return 'a'.
        val midY = 20f + 1.5f * p.rowHeight   // marginTop + row0 + half of row1
        val hit = KeyboardGeometry.keyAt(rows, p, /*tx*/ 5f, midY)
        assertThat(hit).isNotNull()
        assertThat(hit!!.keys[0]!!.getChar()).isEqualTo('a')
    }

    @Test
    fun slopLeftOfRowReturnsNullWhenNoAKey() {
        val p = params(marginLeft = 50f)
        // Top row (qwerty) has no 'a' → left-of-margin slop yields null.
        val topY = 20f + 0.5f * p.rowHeight
        val hit = KeyboardGeometry.keyAt(rows, p, /*tx*/ 5f, topY)
        assertThat(hit).isNull()
    }

    @Test
    fun slopRightOfRowReturnsLastKey() {
        val p = params()
        val topY = 20f + 0.5f * p.rowHeight
        // x far to the right of the last key → returns last key of the row ('p').
        val farRight = p.marginLeft + 100 * p.keyWidth
        val hit = KeyboardGeometry.keyAt(rows, p, farRight, topY)
        assertThat(hit).isNotNull()
        assertThat(hit!!.keys[0]!!.getChar()).isEqualTo('p')
    }

    @Test
    fun gapBeforeKeyBelongsToThatKey() {
        val p = params(keyWidth = 100f, marginLeft = 50f)
        // Row: 'a'(1u) then a shifted 'b' with shift=0.5u (0.5u empty gap before it).
        val gapRow = listOf(row(charKey('a'), charKey('b', shift = 0.5f)))
        val topY = 20f + 0.5f * p.rowHeight
        // 'a' cell: [50,150). Gap: [150,200). 'b' cell: [200,300).
        // A touch at x=175 (inside the gap) must resolve to 'b' (gap → next key).
        val hit = KeyboardGeometry.keyAt(gapRow, p, /*tx*/ 175f, topY)
        assertThat(hit).isNotNull()
        assertThat(hit!!.keys[0]!!.getChar()).isEqualTo('b')
    }

    @Test
    fun aboveKeyboardReturnsNull() {
        val p = params()
        // y above marginTop → no row.
        assertThat(KeyboardGeometry.keyAt(rows, p, 100f, /*ty*/ 5f)).isNull()
    }

    @Test
    fun marginLeftShiftMovesEveryCellByTheSameDelta() {
        val delta = 40f
        val base = KeyboardGeometry.computeKeyRects(rows, params(marginLeft = 50f))
        val shifted = KeyboardGeometry.computeKeyRects(rows, params(marginLeft = 50f + delta))
        assertThat(shifted).hasSize(base.size)
        for (i in base.indices) {
            assertThat(shifted[i].bounds.left).isEqualTo(base[i].bounds.left + delta)
            assertThat(shifted[i].bounds.right).isEqualTo(base[i].bounds.right + delta)
            // Vertical geometry unaffected by horizontal margin.
            assertThat(shifted[i].bounds.top).isEqualTo(base[i].bounds.top)
            assertThat(shifted[i].bounds.bottom).isEqualTo(base[i].bounds.bottom)
        }
    }

    @Test
    fun computeRectsMatchKeyAtForDenseGrid() {
        // Dense parity sweep: every rect center AND a jittered point inside each
        // cell must resolve to that cell's key via keyAt (the two code paths use
        // the same x/y accumulation, so they must never disagree inside a cell).
        val p = params()
        val rects = KeyboardGeometry.computeKeyRects(rows, p)
        for (r in rects) {
            // 9-point interior sweep, staying strictly inside the half-open cell.
            val xs = listOf(r.bounds.left + 1f, centerX(r), r.bounds.right - 1f)
            val ys = listOf(r.bounds.top + 1f, centerY(r), r.bounds.bottom - 1f)
            for (x in xs) for (y in ys) {
                val hit = KeyboardGeometry.keyAt(rows, p, x, y)
                // The rightmost/last key in a row also owns right-margin slop, so
                // only assert the STRICT interior maps to the same key.
                assertThat(hit).isSameInstanceAs(r.key)
            }
        }
    }
}
