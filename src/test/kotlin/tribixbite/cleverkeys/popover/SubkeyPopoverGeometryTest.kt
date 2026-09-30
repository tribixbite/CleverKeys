package tribixbite.cleverkeys.popover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.customization.SwipeDirection

/** [SubkeyPopoverGeometry]: neutral zone, sector hit-testing, clamping, arming. */
class SubkeyPopoverGeometryTest {

    // Cells 100 × 150 (a key taller than wide); neutral 60 % × 60 % = ±30 × ±45 px.
    private fun slot(dx: Float, dy: Float, nw: Float = 0.6f, nh: Float = 0.6f) =
        SubkeyPopoverGeometry.slotAt(dx, dy, 100f, 150f, nw, nh)

    @Test
    fun insideTheNeutralRectangle_selectsNothing() {
        assertNull(slot(0f, 0f))
        assertNull(slot(29f, 44f))
        assertNull(slot(-29f, -44f))
    }

    @Test
    fun theNeutralZoneIsARectangle_notACircle() {
        // 40 px right is outside the ±30 width even though 40 < 45 (the height half-extent).
        assertEquals(SwipeDirection.E, slot(40f, 0f))
        // 40 px down stays neutral: inside ±45.
        assertNull(slot(0f, 40f))
    }

    @Test
    fun aWiderNeutralSetting_widensTheCancelArea() {
        assertEquals(SwipeDirection.E, slot(40f, 0f, nw = 0.6f))
        assertNull(slot(40f, 0f, nw = 1.0f))
    }

    @Test
    fun cellCentres_selectTheirOwnDirection() {
        for (dir in SwipeDirection.entries) {
            val dx = SubkeyPopoverGeometry.cellColumn(dir) * 100f
            val dy = SubkeyPopoverGeometry.cellRow(dir) * 150f
            assertEquals("centre of the $dir cell", dir, slot(dx, dy))
        }
    }

    @Test
    fun sectorsFollowTheGridDiagonals_forTallKeys() {
        // Half a cell right, one cell up: raw px angle 71.6° would be N; normalised by the
        // 100 × 150 cell it is 63.4°, inside NE's 22.5°–67.5° sector — the drawn NE cell.
        assertEquals(SwipeDirection.NE, slot(50f, -150f))
    }

    @Test
    fun screenYGrowsDown() {
        assertEquals(SwipeDirection.S, slot(0f, 150f))
        assertEquals(SwipeDirection.N, slot(0f, -150f))
    }

    @Test
    fun gridCentre_staysOnTheFinger_whenTheGridFits() {
        assertEquals(500f, SubkeyPopoverGeometry.gridCentre(500f, 100f, 1000f))
    }

    @Test
    fun gridCentre_clampsAtBothEdges() {
        assertEquals(150f, SubkeyPopoverGeometry.gridCentre(40f, 100f, 1000f))
        assertEquals(850f, SubkeyPopoverGeometry.gridCentre(990f, 100f, 1000f))
    }

    @Test
    fun gridCentre_centresOnAViewSmallerThanTheGrid() {
        assertEquals(125f, SubkeyPopoverGeometry.gridCentre(10f, 100f, 250f))
    }

    @Test
    fun arming_needsMovementPastAFractionOfTheSmallerCellSide() {
        // 15 % of min(100, 150) = 15 px.
        assertFalse(SubkeyPopoverGeometry.isArmed(0f, 0f, 10f, 10f, 100f, 150f))  // 14.1 px
        assertTrue(SubkeyPopoverGeometry.isArmed(0f, 0f, 12f, 12f, 100f, 150f))   // 17.0 px
    }

    @Test
    fun degenerateCells_selectNothing() {
        assertNull(SubkeyPopoverGeometry.slotAt(50f, 50f, 0f, 150f, 0.6f, 0.6f))
    }
}
