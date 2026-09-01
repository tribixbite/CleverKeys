package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for subkey (long-press popup) functionality.
 * Tests KeyboardData.Key structure and subkey access patterns.
 *
 * Key layout positions:
 *   1 7 2
 *   5 0 6
 *   3 8 4
 * Where 0 is the main key and 1-8 are directional subkeys.
 */
@RunWith(AndroidJUnit4::class)
class SubkeyTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    // =========================================================================
    // KeyboardData.Key structure tests
    // =========================================================================

    @Test
    fun testEmptyKeyHasNinePositions() {
        val emptyKey = KeyboardData.Key.EMPTY
        assertEquals("Key should have 9 positions", 9, emptyKey.keys.size)
    }

    @Test
    fun testEmptyKeyAllPositionsNull() {
        val emptyKey = KeyboardData.Key.EMPTY
        for (i in 0 until 9) {
            assertNull("Position $i should be null in empty key", emptyKey.getKeyValue(i))
        }
    }

    @Test
    fun testKeyPosition0IsCenter() {
        // Position 0 is the main key (center)
        val emptyKey = KeyboardData.Key.EMPTY
        assertNull("Center position should be accessible", emptyKey.getKeyValue(0))
    }

    @Test
    fun testKeyPositionsAreDirectional() {
        // Directional layout: 1=NW, 2=NE, 3=SW, 4=SE, 5=W, 6=E, 7=N, 8=S.
        // Each of the 9 positions must be independently addressable: writing a
        // distinct KeyValue to position i must be readable back at i and must
        // not bleed into any other position.
        for (i in 0 until 9) {
            val marker = KeyValue.makeCharKey('a' + i)
            val key = KeyboardData.Key.EMPTY.withKeyValue(i, marker)
            assertEquals(
                "Position $i must hold exactly the value written to it",
                marker, key.getKeyValue(i)
            )
            for (j in 0 until 9) {
                if (j == i) continue
                assertNull(
                    "Writing position $i must not populate position $j",
                    key.getKeyValue(j)
                )
            }
        }
    }

    // =========================================================================
    // KeyValue creation tests
    // =========================================================================

    @Test
    fun testKeyValueCharCreation() {
        val keyValue = KeyValue.makeCharKey('a')
        assertEquals("Should have CHAR kind", KeyValue.Kind.Char, keyValue.getKind())
        assertEquals("Char key must carry the requested char", 'a', keyValue.getChar())
    }

    @Test
    fun testKeyValueStringCreation() {
        val keyValue = KeyValue.makeStringKey("test")
        assertEquals("Multi-char string must be a String key", KeyValue.Kind.String, keyValue.getKind())
        assertEquals("Payload must be the input verbatim", "test", keyValue.getString())
    }

    @Test
    fun testKeyValueModifierCreation() {
        val shiftKey = KeyValue.getKeyByName("shift")
        assertEquals("Shift must be a Modifier key", KeyValue.Kind.Modifier, shiftKey.getKind())
        assertEquals("Shift must carry the SHIFT modifier", KeyValue.Modifier.SHIFT, shiftKey.getModifier())
    }

    @Test
    fun testKeyValueSpecialKeys() {
        val backspace = KeyValue.getKeyByName("backspace")
        assertEquals("Backspace must be a Keyevent key", KeyValue.Kind.Keyevent, backspace.getKind())
        assertEquals(
            "Backspace must emit KEYCODE_DEL",
            android.view.KeyEvent.KEYCODE_DEL, backspace.getKeyevent()
        )

        val enter = KeyValue.getKeyByName("enter")
        assertEquals("Enter must be a Keyevent key", KeyValue.Kind.Keyevent, enter.getKind())
        assertEquals(
            "Enter must emit KEYCODE_ENTER",
            android.view.KeyEvent.KEYCODE_ENTER, enter.getKeyevent()
        )

        val space = KeyValue.getKeyByName("space")
        assertEquals("Space is a Char key", KeyValue.Kind.Char, space.getKind())
        assertEquals("Space must type the space character", ' ', space.getChar())
    }

    // =========================================================================
    // Key with subkeys tests
    // =========================================================================

    @Test
    fun testWithKeyValueCreatesNewKey() {
        val emptyKey = KeyboardData.Key.EMPTY
        val charKey = KeyValue.makeCharKey('a')

        val keyWithA = emptyKey.withKeyValue(0, charKey)

        assertNotSame("Should create new key instance", emptyKey, keyWithA)
        assertEquals("New key should have 'a' at center", charKey, keyWithA.getKeyValue(0))
        assertNull(
            "withKeyValue must not mutate the source key (EMPTY stays empty)",
            emptyKey.getKeyValue(0)
        )
    }

    @Test
    fun testSubkeyAtNorthPosition() {
        val emptyKey = KeyboardData.Key.EMPTY
        val mainKey = KeyValue.makeCharKey('e')
        val northSubkey = KeyValue.makeCharKey('é')

        var key = emptyKey.withKeyValue(0, mainKey)
        key = key.withKeyValue(7, northSubkey)  // 7 = North

        assertEquals("Main key should be 'e'", mainKey, key.getKeyValue(0))
        assertEquals("North subkey should be 'é'", northSubkey, key.getKeyValue(7))
    }

    @Test
    fun testMultipleSubkeys() {
        val emptyKey = KeyboardData.Key.EMPTY
        val mainKey = KeyValue.makeCharKey('a')
        val neSubkey = KeyValue.makeCharKey('á')
        val nwSubkey = KeyValue.makeCharKey('à')
        val nSubkey = KeyValue.makeCharKey('â')

        var key = emptyKey.withKeyValue(0, mainKey)
        key = key.withKeyValue(2, neSubkey)  // NE
        key = key.withKeyValue(1, nwSubkey)  // NW
        key = key.withKeyValue(7, nSubkey)   // N

        assertEquals("Center must keep the main key", mainKey, key.getKeyValue(0))
        assertEquals("NE subkey must be 'á'", neSubkey, key.getKeyValue(2))
        assertEquals("NW subkey must be 'à'", nwSubkey, key.getKeyValue(1))
        assertEquals("N subkey must be 'â'", nSubkey, key.getKeyValue(7))
        // Untouched positions must remain empty
        for (pos in intArrayOf(3, 4, 5, 6, 8)) {
            assertNull("Position $pos was never written and must be null", key.getKeyValue(pos))
        }
    }

    @Test
    fun testHasValueFindsMainKey() {
        val emptyKey = KeyboardData.Key.EMPTY
        val mainKey = KeyValue.makeCharKey('x')
        val key = emptyKey.withKeyValue(0, mainKey)

        assertTrue("Should find main key", key.hasValue(mainKey))
    }

    @Test
    fun testHasValueFindsSubkey() {
        val emptyKey = KeyboardData.Key.EMPTY
        val mainKey = KeyValue.makeCharKey('n')
        val subkey = KeyValue.makeCharKey('ñ')

        var key = emptyKey.withKeyValue(0, mainKey)
        key = key.withKeyValue(7, subkey)

        assertTrue("Should find subkey", key.hasValue(subkey))
    }

    @Test
    fun testHasValueReturnsFalseForMissing() {
        val emptyKey = KeyboardData.Key.EMPTY
        val mainKey = KeyValue.makeCharKey('a')
        val missingKey = KeyValue.makeCharKey('z')
        val key = emptyKey.withKeyValue(0, mainKey)

        assertFalse("Should not find missing key", key.hasValue(missingKey))
    }

    // =========================================================================
    // Key width and shift tests
    // =========================================================================

    @Test
    fun testKeyDefaultWidth() {
        val key = KeyboardData.Key.EMPTY
        assertEquals("Default width should be 1.0", 1f, key.width, 0.001f)
    }

    @Test
    fun testKeyScaleWidth() {
        val key = KeyboardData.Key.EMPTY
        val scaledKey = key.scaleWidth(1.5f)

        assertEquals("Scaled width should be 1.5", 1.5f, scaledKey.width, 0.001f)
        assertEquals("scaleWidth must not mutate the source key", 1f, key.width, 0.001f)
    }

    @Test
    fun testKeyWithShift() {
        val key = KeyboardData.Key.EMPTY
        val shiftedKey = key.withShift(0.5f)

        assertEquals("Shift should be 0.5", 0.5f, shiftedKey.shift, 0.001f)
    }

    // =========================================================================
    // Anticircle key tests
    // =========================================================================

    @Test
    fun testEmptyKeyHasNoAnticircle() {
        val key = KeyboardData.Key.EMPTY
        assertNull("Empty key should have no anticircle", key.anticircle)
    }
}
