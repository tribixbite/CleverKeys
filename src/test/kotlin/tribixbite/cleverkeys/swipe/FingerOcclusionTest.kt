package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * ARC-005 — `finger_occlusion_offset` must mean ONE thing across both swipe engines.
 *
 * The slider is engine-agnostic in the UI, but until 2026-08-28 only `CtcEngineAdapter`
 * applied it: every geometric-served cell (non-Latin script, letter-incomplete layout,
 * CTC-unsupported language, `swipe_engine_mode=geometric`) silently ignored the knob. The
 * fix routes both adapters through [FingerOcclusion], and these are the semantics they
 * share — the numbers a user's percentage turns into, and the PARITY that makes one slider
 * position produce one shift regardless of which engine happens to serve the swipe.
 *
 * The adapters themselves need a `Context` and cannot run here; that both call this helper
 * (and neither hand-rolls the percentage) is pinned by `CoreImeHygieneDriftTest`.
 */
class FingerOcclusionTest {

    /**
     * A three-row letter block with 4 px of inter-row gap, in view px. Both adapters
     * measure the same union: CTC over its a–z rects, the geometric adapter over its
     * letter-node rects.
     */
    private val rowHeightPx = 100f
    private val rowGapPx = 4f
    private val letterTopPx = 320f
    private val letterBottomPx = letterTopPx + 3 * rowHeightPx + 2 * rowGapPx // 628f
    private val letterBoxHeightPx = letterBottomPx - letterTopPx              // 308f

    @Test
    fun defaultOffsetIsExactlyNoShift() {
        assertWithMessage(
            "The shipped default is 0 and CTC's 89.31 top-1 was measured on UNCORRECTED " +
                "traces — a 0 offset must not perturb a single sample."
        ).that(FingerOcclusion.yShiftPx(0, letterBoxHeightPx, 3)).isEqualTo(0f)
    }

    @Test
    fun offsetIsAPercentOfONEKEYROWNotOfTheWholeLetterBox() {
        // 10% of one row (308/3 = 102.667 px), NOT 10% of the 308 px box.
        val shift = FingerOcclusion.yShiftPx(10, letterBoxHeightPx, 3)
        assertThat(shift).isWithin(1e-4f).of(letterBoxHeightPx / 3f * 0.10f)
        assertWithMessage("a percent of the whole box would be ~3x too big")
            .that(shift).isLessThan(letterBoxHeightPx * 0.10f)
    }

    @Test
    fun positiveOffsetReadsTheSwipeLowerAndNegativeReadsItHigher() {
        // View coordinates grow DOWNWARD: a positive shift moves the read point toward the
        // bottom of the keyboard, which is the correction for traces that land ABOVE the
        // intended keys (the fingertip occluding its target). The string promises exactly
        // this: "Positive if your swipes land above the keys you aim for".
        val down = FingerOcclusion.yShiftPx(12, letterBoxHeightPx, 3)
        val up = FingerOcclusion.yShiftPx(-12, letterBoxHeightPx, 3)
        assertThat(down).isGreaterThan(0f)
        assertThat(up).isLessThan(0f)
        assertWithMessage("the correction must be symmetric — users overshoot both ways")
            .that(down).isWithin(1e-4f).of(-up)
    }

    @Test
    fun shiftScalesWithKeyboardHeightSoThePercentTravels() {
        // Same percentage, keyboard twice as tall → twice the pixel shift. This is why the
        // knob is a percent of a key row and not a pixel count.
        val small = FingerOcclusion.yShiftPx(25, letterBoxHeightPx, 3)
        val large = FingerOcclusion.yShiftPx(25, letterBoxHeightPx * 2f, 3)
        assertThat(large).isWithin(1e-4f).of(small * 2f)
    }

    @Test
    fun degenerateGeometryNeverShiftsATrace() {
        // A layout with no measurable letter box (or no letter rows) must fall back to "no
        // correction" rather than producing an infinite/NaN offset that would move every
        // sample off the keyboard.
        assertThat(FingerOcclusion.yShiftPx(25, 0f, 3)).isEqualTo(0f)
        assertThat(FingerOcclusion.yShiftPx(25, -10f, 3)).isEqualTo(0f)
        assertThat(FingerOcclusion.yShiftPx(25, letterBoxHeightPx, 0)).isEqualTo(0f)
        assertThat(FingerOcclusion.yShiftPx(25, letterBoxHeightPx, -1)).isEqualTo(0f)
    }

    /**
     * The parity that ARC-005 is about: on a layout BOTH engines can serve, the same trace
     * and the same slider position must be shifted by the same number of pixels.
     *
     * The two adapters reach the helper by different routes — CTC passes `1/invH` (the
     * inverse of its letter-box affine's vertical extent) with the a–z gate's fixed three
     * rows; the geometric adapter passes the letter-node bounding box it measures while
     * building `LayoutGeometry`, with its own counted row total. Both are the SAME union of
     * letter-key rect bounds from `KeyboardGeometry.computeKeyRects`, so the shifts must
     * agree exactly, at every offset in the pref's clamped range.
     */
    @Test
    fun bothAdaptersDeriveTheIdenticalShiftFromTheSameLayout() {
        // CTC: MappedLayout stores invH = 1 / (letter-box height), so it recovers the box
        // height as 1/invH before dividing by its constant row count.
        val invH = 1f / letterBoxHeightPx
        // Geometric: three letter rows counted while walking the same rects.
        val countedRows = 3

        for (offsetPercent in -25..25) {
            val ctcShift = FingerOcclusion.yShiftPx(
                offsetPercent,
                letterBoxHeightPx = 1f / invH,
                letterRowCount = FingerOcclusion.LATIN_LETTER_ROWS,
            )
            val geometricShift = FingerOcclusion.yShiftPx(
                offsetPercent,
                letterBoxHeightPx = letterBottomPx - letterTopPx,
                letterRowCount = countedRows,
            )
            assertWithMessage(
                "offset $offsetPercent%: CTC shifted by $ctcShift px and geometric by " +
                    "$geometricShift px — one slider position must be one correction"
            ).that(geometricShift).isWithin(1e-4f).of(ctcShift)
        }
    }

    /**
     * The geometric adapter serves layouts whose letters do NOT occupy three rows (that is
     * precisely why it counts instead of assuming). The unit stays "one key row" there.
     */
    @Test
    fun countedRowsKeepTheUnitCorrectOnANonThreeRowLetterBlock() {
        val fourRowBoxPx = 4 * rowHeightPx + 3 * rowGapPx // 412f
        val shift = FingerOcclusion.yShiftPx(15, fourRowBoxPx, 4)
        assertThat(shift).isWithin(1e-4f).of(fourRowBoxPx / 4f * 0.15f)
        assertWithMessage(
            "assuming three rows on a four-row letter block would inflate the shift by 4/3"
        ).that(FingerOcclusion.yShiftPx(15, fourRowBoxPx, 3)).isGreaterThan(shift)
    }
}
