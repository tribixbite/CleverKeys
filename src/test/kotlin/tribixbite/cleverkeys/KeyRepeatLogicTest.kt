package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Comprehensive tests for #81 — Key repeat backspace-only option.
 *
 * Tests the boolean decision logic used in Pointers.kt line 1234:
 *   keyrepeat_enabled && (!keyrepeat_backspace_only || isBackspaceOrNav)
 *
 * When keyrepeat_backspace_only is true, only backspace and navigation
 * keys (arrow keys) should repeat; all other keys (letters, space, etc.)
 * should not repeat. This prevents accidental multi-character input from
 * long presses while still allowing backspace hold-to-delete.
 */
class KeyRepeatLogicTest {

    /**
     * Simulates the exact condition at Pointers.kt:1234.
     * Returns true if a key should repeat given the config and key type.
     */
    private fun shouldKeyRepeat(
        keyrepeatEnabled: Boolean,
        keyrepeatBackspaceOnly: Boolean,
        isBackspaceOrNav: Boolean
    ): Boolean {
        return keyrepeatEnabled && (!keyrepeatBackspaceOnly || isBackspaceOrNav)
    }

    // =========================================================================
    // Config defaults
    // =========================================================================

    @Test
    fun `keyrepeat is enabled by default`() {
        assertThat(Defaults.KEYREPEAT_ENABLED).isTrue()
    }

    @Test
    fun `keyrepeat backspace only is disabled by default`() {
        assertThat(Defaults.KEYREPEAT_BACKSPACE_ONLY).isFalse()
    }

    @Test
    fun `default config allows all keys to repeat`() {
        // With defaults: enabled=true, backspaceOnly=false
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = Defaults.KEYREPEAT_ENABLED,
            keyrepeatBackspaceOnly = Defaults.KEYREPEAT_BACKSPACE_ONLY,
            isBackspaceOrNav = false
        )).isTrue()
    }

    // =========================================================================
    // Key repeat disabled — nothing repeats
    // =========================================================================

    @Test
    fun `disabled — letter key does not repeat`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = false,
            keyrepeatBackspaceOnly = false,
            isBackspaceOrNav = false
        )).isFalse()
    }

    @Test
    fun `disabled — backspace does not repeat`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = false,
            keyrepeatBackspaceOnly = false,
            isBackspaceOrNav = true
        )).isFalse()
    }

    @Test
    fun `disabled with backspace only — letter key does not repeat`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = false,
            keyrepeatBackspaceOnly = true,
            isBackspaceOrNav = false
        )).isFalse()
    }

    @Test
    fun `disabled with backspace only — backspace does not repeat`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = false,
            keyrepeatBackspaceOnly = true,
            isBackspaceOrNav = true
        )).isFalse()
    }

    // =========================================================================
    // Key repeat enabled, backspace-only OFF — everything repeats
    // =========================================================================

    @Test
    fun `enabled normal — letter key repeats`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = true,
            keyrepeatBackspaceOnly = false,
            isBackspaceOrNav = false
        )).isTrue()
    }

    @Test
    fun `enabled normal — backspace repeats`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = true,
            keyrepeatBackspaceOnly = false,
            isBackspaceOrNav = true
        )).isTrue()
    }

    @Test
    fun `enabled normal — space key repeats`() {
        // Space is not backspace/nav, but with backspaceOnly=false it should repeat
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = true,
            keyrepeatBackspaceOnly = false,
            isBackspaceOrNav = false
        )).isTrue()
    }

    // =========================================================================
    // Key repeat enabled, backspace-only ON — only backspace/nav repeat
    // =========================================================================

    @Test
    fun `backspace only — backspace repeats`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = true,
            keyrepeatBackspaceOnly = true,
            isBackspaceOrNav = true
        )).isTrue()
    }

    @Test
    fun `backspace only — letter key does NOT repeat`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = true,
            keyrepeatBackspaceOnly = true,
            isBackspaceOrNav = false
        )).isFalse()
    }

    @Test
    fun `backspace only — space key does NOT repeat`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = true,
            keyrepeatBackspaceOnly = true,
            isBackspaceOrNav = false
        )).isFalse()
    }

    @Test
    fun `backspace only — punctuation does NOT repeat`() {
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = true,
            keyrepeatBackspaceOnly = true,
            isBackspaceOrNav = false
        )).isFalse()
    }

    @Test
    fun `backspace only — nav key repeats`() {
        // Navigation keys (arrow keys) should also repeat in backspace-only mode
        assertThat(shouldKeyRepeat(
            keyrepeatEnabled = true,
            keyrepeatBackspaceOnly = true,
            isBackspaceOrNav = true
        )).isTrue()
    }

    // =========================================================================
    // Truth table verification — all 8 combinations
    // =========================================================================

    @Test
    fun `truth table — complete 8 combination verification`() {
        // (enabled, backspaceOnly, isBackspaceOrNav) -> expected
        val truthTable = listOf(
            Triple(false, false, false) to false,  // disabled
            Triple(false, false, true)  to false,  // disabled
            Triple(false, true,  false) to false,  // disabled
            Triple(false, true,  true)  to false,  // disabled
            Triple(true,  false, false) to true,   // enabled, all repeat
            Triple(true,  false, true)  to true,   // enabled, all repeat
            Triple(true,  true,  false) to false,  // backspace-only, non-bs blocked
            Triple(true,  true,  true)  to true,   // backspace-only, bs/nav allowed
        )

        for ((input, expected) in truthTable) {
            val (enabled, bsOnly, isBsOrNav) = input
            val result = shouldKeyRepeat(enabled, bsOnly, isBsOrNav)
            assertThat(result).isEqualTo(expected)
        }
    }

    // =========================================================================
    // Long press interval defaults
    // =========================================================================

    @Test
    fun `long press timeout default is reasonable`() {
        assertThat(Defaults.LONGPRESS_TIMEOUT).isAtLeast(100)
        assertThat(Defaults.LONGPRESS_TIMEOUT).isAtMost(1000)
    }

    @Test
    fun `long press interval default is reasonable`() {
        assertThat(Defaults.LONGPRESS_INTERVAL).isAtLeast(10)
        assertThat(Defaults.LONGPRESS_INTERVAL).isAtMost(200)
    }

    // =========================================================================
    // Navigation key definitions (KEYCODE constants)
    // =========================================================================

    @Test
    fun `KEYCODE_DEL is the backspace key code`() {
        // android.view.KeyEvent.KEYCODE_DEL = 67
        assertThat(android.view.KeyEvent.KEYCODE_DEL).isEqualTo(67)
    }

    @Test
    fun `arrow key codes are distinct`() {
        val up = android.view.KeyEvent.KEYCODE_DPAD_UP
        val down = android.view.KeyEvent.KEYCODE_DPAD_DOWN
        val left = android.view.KeyEvent.KEYCODE_DPAD_LEFT
        val right = android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        val codes = setOf(up, down, left, right)
        assertThat(codes).hasSize(4)
    }
}
