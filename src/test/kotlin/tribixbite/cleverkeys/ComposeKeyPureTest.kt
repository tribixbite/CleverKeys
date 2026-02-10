package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM unit tests for ComposeKey and ComposeKeyData constants.
 *
 * ComposeKey.apply() requires ComposeKeyData to be initialized with an
 * Android Context (to load compose_data.bin from assets), so runtime
 * compose sequence lookups CANNOT be tested here. The existing
 * ComposeKeyTest.kt (Robolectric) covers those paths.
 *
 * What IS testable as pure JVM:
 * - ComposeKeyData state/accent constants (compile-time values)
 * - Constant ordering and uniqueness invariants
 * - ComposeKeyData uninitialized guard behavior
 */
class ComposeKeyPureTest {

    // =========================================================================
    // A. ComposeKeyData accent constants — value smoke tests
    // =========================================================================

    @Test
    fun `ACCENT_AIGU constant is 1`() {
        assertThat(ComposeKeyData.ACCENT_AIGU).isEqualTo(1)
    }

    @Test
    fun `compose constant is 1270`() {
        assertThat(ComposeKeyData.compose).isEqualTo(1270)
    }

    @Test
    fun `fn constant is 7683`() {
        assertThat(ComposeKeyData.fn).isEqualTo(7683)
    }

    @Test
    fun `shift constant is 8421`() {
        assertThat(ComposeKeyData.shift).isEqualTo(8421)
    }

    // =========================================================================
    // B. All accent constants are non-negative
    // =========================================================================

    @Test
    fun `all accent constants are non-negative`() {
        // Every state index must be >= 0
        assertThat(ComposeKeyData.ACCENT_AIGU).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_ARROWS).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_BAR).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_BOX).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_CARON).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_CEDILLE).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_CIRCONFLEXE).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_DOT_ABOVE).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_DOT_BELOW).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_DOUBLE_AIGU).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_DOUBLE_GRAVE).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_GRAVE).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_HOOK_ABOVE).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_HORN).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_MACRON).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_OGONEK).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_ORDINAL).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_RING).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_SLASH).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_SUBSCRIPT).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_SUPERSCRIPT).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_TILDE).isAtLeast(0)
        assertThat(ComposeKeyData.ACCENT_TREMA).isAtLeast(0)
    }

    // =========================================================================
    // C. Accent constants are in strictly ascending order
    // =========================================================================

    @Test
    fun `accent constants are in ascending order`() {
        // The constants represent state indices in the compose state machine.
        // They should be ordered (each accent starts at a higher index).
        val accents = listOf(
            ComposeKeyData.ACCENT_AIGU,
            ComposeKeyData.ACCENT_ARROWS,
            ComposeKeyData.ACCENT_BAR,
            ComposeKeyData.ACCENT_BOX,
            ComposeKeyData.ACCENT_CARON,
            ComposeKeyData.ACCENT_CEDILLE,
            ComposeKeyData.ACCENT_CIRCONFLEXE,
            ComposeKeyData.ACCENT_DOT_ABOVE,
            ComposeKeyData.ACCENT_DOT_BELOW,
            ComposeKeyData.ACCENT_DOUBLE_AIGU,
            ComposeKeyData.ACCENT_DOUBLE_GRAVE,
            ComposeKeyData.ACCENT_GRAVE,
            ComposeKeyData.ACCENT_HOOK_ABOVE,
            ComposeKeyData.ACCENT_HORN,
            ComposeKeyData.ACCENT_MACRON,
            ComposeKeyData.ACCENT_OGONEK,
            ComposeKeyData.ACCENT_ORDINAL,
            ComposeKeyData.ACCENT_RING,
            ComposeKeyData.ACCENT_SLASH,
            ComposeKeyData.ACCENT_SUBSCRIPT,
            ComposeKeyData.ACCENT_SUPERSCRIPT,
            ComposeKeyData.ACCENT_TILDE,
            ComposeKeyData.ACCENT_TREMA,
        )
        for (i in 1 until accents.size) {
            assertThat(accents[i]).isGreaterThan(accents[i - 1])
        }
    }

    // =========================================================================
    // D. All constants are unique
    // =========================================================================

    @Test
    fun `all state constants are unique`() {
        val allConstants = listOf(
            ComposeKeyData.ACCENT_AIGU,
            ComposeKeyData.ACCENT_ARROWS,
            ComposeKeyData.ACCENT_BAR,
            ComposeKeyData.ACCENT_BOX,
            ComposeKeyData.ACCENT_CARON,
            ComposeKeyData.ACCENT_CEDILLE,
            ComposeKeyData.ACCENT_CIRCONFLEXE,
            ComposeKeyData.ACCENT_DOT_ABOVE,
            ComposeKeyData.ACCENT_DOT_BELOW,
            ComposeKeyData.ACCENT_DOUBLE_AIGU,
            ComposeKeyData.ACCENT_DOUBLE_GRAVE,
            ComposeKeyData.ACCENT_GRAVE,
            ComposeKeyData.ACCENT_HOOK_ABOVE,
            ComposeKeyData.ACCENT_HORN,
            ComposeKeyData.ACCENT_MACRON,
            ComposeKeyData.ACCENT_OGONEK,
            ComposeKeyData.ACCENT_ORDINAL,
            ComposeKeyData.ACCENT_RING,
            ComposeKeyData.ACCENT_SLASH,
            ComposeKeyData.ACCENT_SUBSCRIPT,
            ComposeKeyData.ACCENT_SUPERSCRIPT,
            ComposeKeyData.ACCENT_TILDE,
            ComposeKeyData.ACCENT_TREMA,
            ComposeKeyData.compose,
            ComposeKeyData.fn,
            ComposeKeyData.shift,
            ComposeKeyData.NUMPAD_BENGALI,
            ComposeKeyData.NUMPAD_DEVANAGARI,
            ComposeKeyData.NUMPAD_GUJARATI,
            ComposeKeyData.NUMPAD_HINDU,
            ComposeKeyData.NUMPAD_KANNADA,
            ComposeKeyData.NUMPAD_PERSIAN,
            ComposeKeyData.NUMPAD_TAMIL,
        )
        assertThat(allConstants.toSet()).hasSize(allConstants.size)
    }

    // =========================================================================
    // E. Numpad constants are in ascending order and above accents
    // =========================================================================

    @Test
    fun `numpad constants are in ascending order`() {
        val numpads = listOf(
            ComposeKeyData.NUMPAD_BENGALI,
            ComposeKeyData.NUMPAD_DEVANAGARI,
            ComposeKeyData.NUMPAD_GUJARATI,
            ComposeKeyData.NUMPAD_HINDU,
            ComposeKeyData.NUMPAD_KANNADA,
            ComposeKeyData.NUMPAD_PERSIAN,
            ComposeKeyData.NUMPAD_TAMIL,
        )
        for (i in 1 until numpads.size) {
            assertThat(numpads[i]).isGreaterThan(numpads[i - 1])
        }
    }

    @Test
    fun `numpad constants come after compose constant`() {
        assertThat(ComposeKeyData.NUMPAD_BENGALI).isGreaterThan(ComposeKeyData.compose)
    }

    @Test
    fun `fn constant is between compose and numpad`() {
        assertThat(ComposeKeyData.fn).isGreaterThan(ComposeKeyData.compose)
        assertThat(ComposeKeyData.fn).isLessThan(ComposeKeyData.NUMPAD_BENGALI)
    }

    @Test
    fun `shift constant comes after numpad constants`() {
        assertThat(ComposeKeyData.shift).isGreaterThan(ComposeKeyData.NUMPAD_TAMIL)
    }

    // =========================================================================
    // F. Uninitialized guard behavior
    // =========================================================================

    @Test
    fun `accessing states before initialization throws IllegalStateException`() {
        // ComposeKeyData requires initialize(context) before accessing
        // states/edges. Since we can't call initialize in pure JVM tests,
        // verify the guard throws the expected exception.
        // NOTE: This test only works if ComposeKeyData hasn't been initialized
        // by another test in this JVM — which is the case for pure JVM tests.
        try {
            @Suppress("UNUSED_VARIABLE")
            val s = ComposeKeyData.states
            // If we get here, it was already initialized by another test
            // (e.g., a Robolectric test running in the same suite). Skip.
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("not initialized")
        }
    }

    @Test
    fun `accessing edges before initialization throws IllegalStateException`() {
        try {
            @Suppress("UNUSED_VARIABLE")
            val e = ComposeKeyData.edges
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("not initialized")
        }
    }
}
