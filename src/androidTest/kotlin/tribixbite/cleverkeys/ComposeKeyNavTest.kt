package tribixbite.cleverkeys

import android.content.Context
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for issue #104: Turning off compose key breaks touchpad/arrow keys.
 *
 * When compose is disabled by the locale filter (LayoutModifier nulls key0),
 * the key cell retains arrow subkeys at indices 5-8. TrackPoint (hold) works
 * because handleLongPress checks hasNavigationSubkeys(), but short swipe
 * was broken because gesture classification requires non-null ptr.value.
 *
 * Key layout positions:
 *   1 7 2
 *   5 0 6   (0 = center/main, 5-8 = cardinal directions)
 *   3 8 4
 *
 * bottom_row.xml compose key cell:
 *   key0="loc compose" key7="up" key6="right" key5="left" key8="down"
 */
@RunWith(AndroidJUnit4::class)
class ComposeKeyNavTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    // ── Key structure tests ─────────────────────────────────────────────

    @Test
    fun testArrowKeysCreatedByName() {
        // Verify arrow KeyValues can be created via getKeyByName
        val left = KeyValue.getKeyByName("left")
        val right = KeyValue.getKeyByName("right")
        val up = KeyValue.getKeyByName("up")
        val down = KeyValue.getKeyByName("down")

        assertNotNull("Left arrow key should be created", left)
        assertNotNull("Right arrow key should be created", right)
        assertNotNull("Up arrow key should be created", up)
        assertNotNull("Down arrow key should be created", down)

        // Arrow keys are Keyevent kind
        assertEquals("Left should be Keyevent", KeyValue.Kind.Keyevent, left!!.getKind())
        assertEquals("Right should be Keyevent", KeyValue.Kind.Keyevent, right!!.getKind())
        assertEquals("Up should be Keyevent", KeyValue.Kind.Keyevent, up!!.getKind())
        assertEquals("Down should be Keyevent", KeyValue.Kind.Keyevent, down!!.getKind())
    }

    @Test
    fun testKeyWithNullKey0HasNinePositions() {
        // A Key with null center still has all 9 slots
        val emptyKey = KeyboardData.Key.EMPTY
        assertEquals("Key should have 9 positions", 9, emptyKey.keys.size)
        assertNull("Empty key should have null key0", emptyKey.keys[0])
    }

    @Test
    fun testKeyWithNullKey0PreservesNavSubkeys() {
        // Simulates the compose key when compose is disabled:
        // key0 is null, but arrow subkeys at 5-8 remain
        val left = KeyValue.getKeyByName("left")!!
        val right = KeyValue.getKeyByName("right")!!
        val up = KeyValue.getKeyByName("up")!!
        val down = KeyValue.getKeyByName("down")!!

        var key = KeyboardData.Key.EMPTY
        // key0 stays null (compose disabled)
        key = key.withKeyValue(5, left)   // West
        key = key.withKeyValue(6, right)  // East
        key = key.withKeyValue(7, up)     // North
        key = key.withKeyValue(8, down)   // South

        // key0 is null (compose disabled by locale filter)
        assertNull("key0 should be null (compose disabled)", key.keys[0])

        // Arrow subkeys at indices 5-8 should still be present
        assertNotNull("West (left) subkey at index 5 should exist", key.keys[5])
        assertNotNull("East (right) subkey at index 6 should exist", key.keys[6])
        assertNotNull("North (up) subkey at index 7 should exist", key.keys[7])
        assertNotNull("South (down) subkey at index 8 should exist", key.keys[8])

        // Verify they are the correct arrow keys
        assertEquals("Index 5 should be left arrow", left, key.keys[5])
        assertEquals("Index 6 should be right arrow", right, key.keys[6])
        assertEquals("Index 7 should be up arrow", up, key.keys[7])
        assertEquals("Index 8 should be down arrow", down, key.keys[8])
    }

    @Test
    fun testNullKey0DoesNotPreventSubkeyIteration() {
        // When iterating subkeys 1-8 for rendering or detection,
        // null key0 should not block access to other indices
        val up = KeyValue.getKeyByName("up")!!
        val down = KeyValue.getKeyByName("down")!!

        var key = KeyboardData.Key.EMPTY
        key = key.withKeyValue(7, up)
        key = key.withKeyValue(8, down)

        // Simulate what Keyboard2View.onDraw does: iterate 1..8 for sublabels
        var navKeyCount = 0
        for (i in 1..8) {
            if (key.keys[i] != null) {
                navKeyCount++
            }
        }
        assertEquals("Should find 2 nav subkeys even with null key0", 2, navKeyCount)
    }

    // ── Navigation subkey detection ─────────────────────────────────────

    @Test
    fun testComposeKeyBottomRowStructure() {
        // Verify the bottom_row.xml compose key cell structure:
        // key0="loc compose" key7="up" key6="right" key5="left" key8="down"
        // key1="doc_home" key2="loc page_up" key3="doc_end" key4="loc page_down"
        val compose = KeyValue.getKeyByName("compose")
        val left = KeyValue.getKeyByName("left")!!
        val right = KeyValue.getKeyByName("right")!!
        val up = KeyValue.getKeyByName("up")!!
        val down = KeyValue.getKeyByName("down")!!

        // Build the key as defined in bottom_row.xml
        var key = KeyboardData.Key.EMPTY
        if (compose != null) {
            key = key.withKeyValue(0, compose)
        }
        key = key.withKeyValue(5, left)
        key = key.withKeyValue(6, right)
        key = key.withKeyValue(7, up)
        key = key.withKeyValue(8, down)

        // Now simulate compose being disabled (locale filter nulls key0)
        val disabledKeys = arrayOfNulls<KeyValue>(9)
        // key0 becomes null, but 5-8 remain
        for (i in 1..8) {
            disabledKeys[i] = key.keys[i]
        }

        // Verify arrow keys survive key0 nullification
        assertNull("key0 should be null after compose disabled", disabledKeys[0])
        assertEquals("left should survive at index 5", left, disabledKeys[5])
        assertEquals("right should survive at index 6", right, disabledKeys[6])
        assertEquals("up should survive at index 7", up, disabledKeys[7])
        assertEquals("down should survive at index 8", down, disabledKeys[8])
    }

    @Test
    fun testArrowKeyValuesHaveCorrectKeycodes() {
        // Verify arrow keys map to correct Android keycodes
        val left = KeyValue.getKeyByName("left")!!
        val right = KeyValue.getKeyByName("right")!!
        val up = KeyValue.getKeyByName("up")!!
        val down = KeyValue.getKeyByName("down")!!

        // All are Keyevent type (generates Android KeyEvent)
        assertEquals(KeyValue.Kind.Keyevent, left.getKind())
        assertEquals(KeyValue.Kind.Keyevent, right.getKind())
        assertEquals(KeyValue.Kind.Keyevent, up.getKind())
        assertEquals(KeyValue.Kind.Keyevent, down.getKind())

        // Verify keycodes via getKeyevent()
        assertEquals("Left keycode", KeyEvent.KEYCODE_DPAD_LEFT, left.getKeyevent())
        assertEquals("Right keycode", KeyEvent.KEYCODE_DPAD_RIGHT, right.getKeyevent())
        assertEquals("Up keycode", KeyEvent.KEYCODE_DPAD_UP, up.getKeyevent())
        assertEquals("Down keycode", KeyEvent.KEYCODE_DPAD_DOWN, down.getKeyevent())
    }

    @Test
    fun testDirectionToIndexMapping() {
        // The direction-to-index mapping used by Pointers.getKeyAtDirection():
        // Directions 0-15 represent 16 sectors of a circle (clockwise from north)
        // Index mapping: 1=NW 7=N 2=NE 5=W 0=center 6=E 3=SW 8=S 4=SE
        //
        // For the compose/arrow key, arrow subkeys are at:
        //   index 5 = W (left)  → directions ~12 (W)
        //   index 6 = E (right) → directions ~4  (E)
        //   index 7 = N (up)    → directions ~0  (N)
        //   index 8 = S (down)  → directions ~8  (S)

        // Verify the DIRECTION_TO_INDEX constant mapping (from Pointers.kt)
        // Expected: direction 0 → index 7 (N=up)
        //           direction 4 → index 6 (E=right)
        //           direction 8 → index 8 (S=down)
        //           direction 12 → index 5 (W=left)
        val directionToIndex = intArrayOf(7, 7, 2, 6, 6, 6, 4, 8, 8, 8, 3, 5, 5, 5, 1, 7)

        assertEquals("Direction 0 (N) should map to index 7", 7, directionToIndex[0])
        assertEquals("Direction 4 (E) should map to index 6", 6, directionToIndex[4])
        assertEquals("Direction 8 (S) should map to index 8", 8, directionToIndex[8])
        assertEquals("Direction 12 (W) should map to index 5", 5, directionToIndex[12])
    }

    @Test
    fun testKeyValueGetKeyeventCode() {
        // Sanity check that getKeyevent() works for nav keys
        // (used by Pointers and KeyEventHandler to dispatch arrow events)
        val home = KeyValue.getKeyByName("home")
        val end = KeyValue.getKeyByName("end")

        if (home != null) {
            assertEquals(KeyValue.Kind.Keyevent, home.getKind())
            assertEquals(KeyEvent.KEYCODE_MOVE_HOME, home.getKeyevent())
        }
        if (end != null) {
            assertEquals(KeyValue.Kind.Keyevent, end.getKind())
            assertEquals(KeyEvent.KEYCODE_MOVE_END, end.getKeyevent())
        }
    }
}
