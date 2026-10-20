package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keyboard-height resolution rule ([KeyboardHeightPrefs], spec
 * `docs/specs/foldable-keyboard-height.md`): per orientation, and on a foldable's inner screen an
 * unset key follows the outer-screen value — the fix for "keyboard height always changes to
 * default when using the inner screen" (user report, 2026-10-20). Pure JVM.
 */
class KeyboardHeightPrefsTest {

    private fun resolve(stored: Map<String, Int>, landscape: Boolean, unfolded: Boolean): Int =
        KeyboardHeightPrefs.percent(landscape, unfolded, stored::containsKey) { k, d -> stored[k] ?: d }

    @Test
    fun nothingStoredMeansTheBuiltInDefaults() {
        assertEquals(Defaults.KEYBOARD_HEIGHT_PORTRAIT, resolve(emptyMap(), landscape = false, unfolded = false))
        assertEquals(Defaults.KEYBOARD_HEIGHT_LANDSCAPE, resolve(emptyMap(), landscape = true, unfolded = false))
        assertEquals(Defaults.KEYBOARD_HEIGHT_PORTRAIT, resolve(emptyMap(), landscape = false, unfolded = true))
        assertEquals(Defaults.KEYBOARD_HEIGHT_LANDSCAPE, resolve(emptyMap(), landscape = true, unfolded = true))
    }

    @Test
    fun unsetInnerScreenFollowsTheOuterScreenValue_theReportedBug() {
        val outerOnly = mapOf(KeyboardHeightPrefs.PORTRAIT to 42, KeyboardHeightPrefs.LANDSCAPE to 31)
        assertEquals(42, resolve(outerOnly, landscape = false, unfolded = true))
        assertEquals(31, resolve(outerOnly, landscape = true, unfolded = true))
    }

    @Test
    fun aSetInnerScreenValueWinsOnlyOnTheInnerScreen() {
        val all = mapOf(
            KeyboardHeightPrefs.PORTRAIT to 42, KeyboardHeightPrefs.LANDSCAPE to 31,
            KeyboardHeightPrefs.PORTRAIT_UNFOLDED to 25, KeyboardHeightPrefs.LANDSCAPE_UNFOLDED to 22,
        )
        assertEquals(42, resolve(all, landscape = false, unfolded = false))
        assertEquals(31, resolve(all, landscape = true, unfolded = false))
        assertEquals(25, resolve(all, landscape = false, unfolded = true))
        assertEquals(22, resolve(all, landscape = true, unfolded = true))
    }

    @Test
    fun orientationsStayIndependent_issue161() {
        val portraitInnerOnly = mapOf(KeyboardHeightPrefs.PORTRAIT_UNFOLDED to 25)
        assertEquals(25, resolve(portraitInnerOnly, landscape = false, unfolded = true))
        assertEquals(Defaults.KEYBOARD_HEIGHT_LANDSCAPE, resolve(portraitInnerOnly, landscape = true, unfolded = true))
    }

    @Test
    fun keyNamesAreTheStoredOnes() {
        // Existing user data and backups use these literal names; they must not change.
        assertEquals("keyboard_height", KeyboardHeightPrefs.keyFor(landscape = false, unfolded = false))
        assertEquals("keyboard_height_landscape", KeyboardHeightPrefs.keyFor(landscape = true, unfolded = false))
        assertEquals("keyboard_height_unfolded", KeyboardHeightPrefs.keyFor(landscape = false, unfolded = true))
        assertEquals("keyboard_height_landscape_unfolded", KeyboardHeightPrefs.keyFor(landscape = true, unfolded = true))
    }
}
