package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Short Swipe Customization "Reset All" (Saga 2026-10-30: it removed every mapping at once with
 * no question and no way back). The screen now asks first, naming the count
 * ([ShortSwipeCustomizationManager.resetAll] returns what it removed), and offers Undo, which
 * puts the removed mappings back through [ShortSwipeCustomizationManager.restorableAfterReset].
 *
 * Pure: only [ShortSwipeMapping] / [SwipeDirection] / [ActionType].
 */
class ShortSwipeResetUndoTest {

    private fun mapping(keyCode: String, direction: SwipeDirection, value: String = "v") =
        ShortSwipeMapping(keyCode, direction, "x", ActionType.TEXT, value)

    @Test
    fun undoPutsEveryRemovedMappingBack() {
        val removed = listOf(
            mapping("a", SwipeDirection.NE),
            mapping("s", SwipeDirection.SW),
            mapping("⏎", SwipeDirection.S),
        )
        val back = ShortSwipeCustomizationManager.restorableAfterReset(currentKeys = emptySet(), removed = removed)
        assertThat(back).containsExactlyElementsIn(removed)
    }

    @Test
    fun undoNeverOverwritesAMappingMadeAfterTheReset() {
        val removed = listOf(mapping("a", SwipeDirection.NE, "old"), mapping("s", SwipeDirection.SW))
        // The user mapped a NE again before tapping Undo: the newer choice stays.
        val newer = mapping("a", SwipeDirection.NE, "new")
        val back = ShortSwipeCustomizationManager.restorableAfterReset(
            currentKeys = setOf(newer.toStorageKey()),
            removed = removed,
        )
        assertThat(back).containsExactly(mapping("s", SwipeDirection.SW))
    }

    @Test
    fun undoStoresUnderTheRuntimeKeyCode() {
        // A legacy mapping stored under "\n" is Enter's; it returns under Enter's runtime code,
        // so it does not reappear beside a new Enter mapping in the same direction.
        val legacy = mapping("\n", SwipeDirection.NE)
        val back = ShortSwipeCustomizationManager.restorableAfterReset(emptySet(), listOf(legacy))
        assertThat(back.single().keyCode).isEqualTo(ShortSwipeKeyCodes.canonical("\n"))
        val again = ShortSwipeCustomizationManager.restorableAfterReset(setOf(legacy.toStorageKey()), listOf(legacy))
        assertThat(again).isEmpty()
    }
}
