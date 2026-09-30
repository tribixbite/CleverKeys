package tribixbite.cleverkeys.popover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection

/** [SubkeyPopoverSlots]: a slot shows exactly what a short swipe there would do. */
class SubkeyPopoverSlotsTest {

    private val defaults = mapOf(
        SwipeDirection.NE to KeyValue.makeStringKey("3"),
        SwipeDirection.NW to KeyValue.makeStringKey("%"),
    )

    private fun resolve(custom: Map<SwipeDirection, ShortSwipeMapping> = emptyMap()) =
        SubkeyPopoverSlots.resolve(defaultAt = { defaults[it] }, customAt = { custom[it] })

    private fun slotAt(slots: List<PopoverSlot>, dir: SwipeDirection) = slots.single { it.direction == dir }

    @Test
    fun eightSlots_clockwiseFromNorth() {
        assertEquals(SwipeDirection.clockwiseFromNorth(), resolve().map { it.direction })
    }

    @Test
    fun layoutSubkeys_fillTheirSlots_andTheRestAreEmpty() {
        val slots = resolve()
        assertEquals(PopoverSlot.Default(SwipeDirection.NE, KeyValue.makeStringKey("3")), slotAt(slots, SwipeDirection.NE))
        assertEquals(PopoverSlot.Empty(SwipeDirection.S, hiddenDefault = false), slotAt(slots, SwipeDirection.S))
    }

    @Test
    fun aCustomMapping_replacesTheDefault_andRemembersItHidesOne() {
        val euro = ShortSwipeMapping.textInput("e", SwipeDirection.NE, "€", "€")
        val slot = slotAt(resolve(mapOf(SwipeDirection.NE to euro)), SwipeDirection.NE)
        assertEquals(PopoverSlot.Custom(SwipeDirection.NE, euro, hidesDefault = true), slot)
    }

    @Test
    fun aCustomMappingOnAnEmptySlot_hidesNothing() {
        val at = ShortSwipeMapping.textInput("e", SwipeDirection.S, "@", "@")
        val slot = slotAt(resolve(mapOf(SwipeDirection.S to at)), SwipeDirection.S) as PopoverSlot.Custom
        assertFalse(slot.hidesDefault)
    }

    @Test
    fun aRemovalMapping_emptiesTheSlot_butKnowsTheDefaultIsThere() {
        val slot = slotAt(resolve(mapOf(SwipeDirection.NW to ShortSwipeMapping.removal("e", SwipeDirection.NW))), SwipeDirection.NW)
        assertEquals(PopoverSlot.Empty(SwipeDirection.NW, hiddenDefault = true), slot)
    }

    @Test
    fun placeholderSubkeys_areNotOffered() {
        val slots = SubkeyPopoverSlots.resolve(
            defaultAt = { if (it == SwipeDirection.E) KeyValue.getKeyByName("removed") else null },
            customAt = { null },
        )
        assertTrue(slotAt(slots, SwipeDirection.E) is PopoverSlot.Empty)
    }

    @Test
    fun labels_emptyHasNone_blankCustomLabelStillAimable() {
        assertNull(SubkeyPopoverSlots.labelOf(PopoverSlot.Empty(SwipeDirection.N, false)).text)
        val blank = ShortSwipeMapping.textInput("e", SwipeDirection.N, "", "x")
        assertEquals("•", SubkeyPopoverSlots.labelOf(PopoverSlot.Custom(SwipeDirection.N, blank, false)).text)
    }

    @Test
    fun onlyAssignedSlots_openTheEditorOnDwell() {
        assertFalse(SubkeyPopoverSlots.isEditable(PopoverSlot.Empty(SwipeDirection.N, true)))
        assertTrue(SubkeyPopoverSlots.isEditable(PopoverSlot.Default(SwipeDirection.N, KeyValue.makeCharKey('x'))))
    }

    @Test
    fun removalMapping_roundTripsItsPredicate() {
        assertTrue(ShortSwipeMapping.removal("E", SwipeDirection.N).isRemoval)
        assertEquals("e", ShortSwipeMapping.removal("E", SwipeDirection.N).keyCode)
        assertFalse(ShortSwipeMapping.textInput("e", SwipeDirection.N, "r", "removed").isRemoval)
    }
}
