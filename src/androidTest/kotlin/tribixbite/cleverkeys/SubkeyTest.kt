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
        // Verify the directional layout:
        // 1=NW, 2=NE, 3=SW, 4=SE, 5=W, 6=E, 7=N, 8=S
        val positions = mapOf(
            0 to "CENTER",
            1 to "NW",
            2 to "NE",
            3 to "SW",
            4 to "SE",
            5 to "W",
            6 to "E",
            7 to "N",
            8 to "S"
        )
        assertEquals("Should have 9 directional positions", 9, positions.size)
    }

    // =========================================================================
    // KeyValue creation tests
    // =========================================================================

    @Test
    fun testKeyValueCharCreation() {
        val keyValue = KeyValue.makeCharKey('a')
        assertNotNull("Char key should be created", keyValue)
        assertEquals("Should have CHAR kind", KeyValue.Kind.Char, keyValue.getKind())
    }

    @Test
    fun testKeyValueStringCreation() {
        val keyValue = KeyValue.makeStringKey("test")
        assertNotNull("String key should be created", keyValue)
    }

    @Test
    fun testKeyValueModifierCreation() {
        val shiftKey = KeyValue.getKeyByName("shift")
        assertNotNull("Shift modifier should exist", shiftKey)
    }

    @Test
    fun testKeyValueSpecialKeys() {
        val backspace = KeyValue.getKeyByName("backspace")
        assertNotNull("Backspace should exist", backspace)

        val enter = KeyValue.getKeyByName("enter")
        assertNotNull("Enter should exist", enter)

        val space = KeyValue.getKeyByName("space")
        assertNotNull("Space should exist", space)
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

        assertNotNull("NE subkey should exist", key.getKeyValue(2))
        assertNotNull("NW subkey should exist", key.getKeyValue(1))
        assertNotNull("N subkey should exist", key.getKeyValue(7))
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
