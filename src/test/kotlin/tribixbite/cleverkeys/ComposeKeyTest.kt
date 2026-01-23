package tribixbite.cleverkeys

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ComposeKey functionality.
 *
 * Tests compose key sequences from different data sources:
 * - Compose.pre: Standard compose sequences
 * - extra.json: Extra compose sequences
 * - arabic.json: Arabic-specific compose sequences
 * - cyrillic.json: Cyrillic-specific compose sequences
 *
 * Also tests:
 * - Function key (Fn) combinations
 * - String keys with modifiers (Shift)
 *
 * Ported from Java to Kotlin with identical test coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComposeKeyTest {

    @Before
    fun setUp() {
        // Initialize ComposeKeyData with application context before running tests
        ComposeKeyData.initialize(ApplicationProvider.getApplicationContext())
    }

    /**
     * Test compose key sequences from various data files.
     * Verifies that compose sequences produce expected KeyValue results.
     */
    @Test
    fun composeEquals() {
        // From Compose.pre
        assertEquals(apply("'e"), KeyValue.makeStringKey("é"))
        assertEquals(apply("e'"), KeyValue.makeStringKey("é"))

        // From extra.json
        assertEquals(apply("Vc"), KeyValue.makeStringKey("Č"))
        assertEquals(apply("\\n"), KeyValue.getKeyByName("\\n"))

        // From arabic.json
        assertEquals(apply("اا"), KeyValue.getKeyByName("combining_alef_above"))
        assertEquals(apply("ل۷"), KeyValue.makeStringKey("ڵ"))
        assertEquals(apply("۷ل"), KeyValue.makeStringKey("ڵ"))

        // From cyrillic.json
        assertEquals(apply(",г"), KeyValue.makeStringKey("ӻ"))
        assertEquals(apply("г,"), KeyValue.makeStringKey("ӻ"))
        assertEquals(apply("ач"), KeyValue.getKeyByName("combining_aigu"))
    }

    /**
     * Test function key (Fn) combinations.
     * Verifies that Fn + key produces expected results for:
     * - Special characters (« ‹)
     * - Named function keys (F1)
     * - Named special keys (nbsp)
     * - 1-char keys with font flags
     */
    @Test
    fun fnEquals() {
        val state = ComposeKeyData.fn

        // Special characters with Fn
        assertEquals(apply("<", state), KeyValue.makeStringKey("«"))
        assertEquals(apply("{", state), KeyValue.makeStringKey("‹"))

        // Named function keys
        assertEquals(apply("1", state), KeyValue.getKeyByName("f1"))
        assertEquals(apply(" ", state), KeyValue.getKeyByName("nbsp"))

        // Named 1-char key with SMALLER_FONT flag
        assertEquals(apply("ய", state), KeyValue.makeStringKey("௰", KeyValue.FLAG_SMALLER_FONT))
    }

    /**
     * Test string keys with Shift modifier.
     * Verifies that multi-byte Unicode characters work correctly with modifiers.
     */
    @Test
    fun stringKeys() {
        val state = ComposeKeyData.shift

        // Mathematical double-struck characters with Shift
        assertEquals(apply("𝕨", state), KeyValue.makeStringKey("𝕎"))
        assertEquals(apply("𝕩", state), KeyValue.makeStringKey("𝕏"))
    }

    // ====== Helper Methods ======

    /**
     * Apply compose sequence without state modifier.
     * Uses default compose state from ComposeKeyData.
     *
     * @param seq Key sequence to compose
     * @return Resulting KeyValue
     */
    private fun apply(seq: String): KeyValue? {
        return ComposeKey.apply(ComposeKeyData.compose, seq)
    }

    /**
     * Apply compose sequence with state modifier.
     *
     * @param seq Key sequence to compose
     * @param state State modifier (fn, shift, etc.)
     * @return Resulting KeyValue
     */
    private fun apply(seq: String, state: Int): KeyValue? {
        return ComposeKey.apply(state, seq)
    }
}
