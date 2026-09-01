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
 * Instrumented tests for key event handling and KeyValue functionality.
 * Tests key creation, modifier handling, and event codes.
 *
 * ARC-044: strengthened from liveness (`assertNotNull`) to behavior. Every named
 * special key is pinned to the Kind + payload the `KeyValue.getSpecialKeyByName`
 * table actually declares (keyevent code, modifier ordinal, event id, char), so a
 * table regression now fails the specific test instead of sailing through a
 * null-check.
 */
@RunWith(AndroidJUnit4::class)
class KeyEventTest {

    private lateinit var context: Context
    private lateinit var config: Config

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        config = Config.globalConfig()
    }

    /** Resolve a name that MUST be a special key and pin its Keyevent code. */
    private fun assertKeyeventKey(name: String, expectedCode: Int) {
        val key = KeyValue.getSpecialKeyByName(name)
        assertNotNull("'$name' must be in the special-key table", key)
        assertEquals("'$name' must be a Keyevent key", KeyValue.Kind.Keyevent, key!!.getKind())
        assertEquals("'$name' must emit its documented key event code", expectedCode, key.getKeyevent())
        // getKeyByName must resolve through the same table entry
        assertTrue(
            "getKeyByName('$name') must return the special key, not a string fallback",
            key.sameKey(KeyValue.getKeyByName(name))
        )
    }

    /** Resolve a name that MUST be a modifier key and pin its Modifier. */
    private fun assertModifierKey(name: String, expected: KeyValue.Modifier) {
        val key = KeyValue.getSpecialKeyByName(name)
        assertNotNull("'$name' must be in the special-key table", key)
        assertEquals("'$name' must be a Modifier key", KeyValue.Kind.Modifier, key!!.getKind())
        assertEquals("'$name' must carry the $expected modifier", expected, key.getModifier())
    }

    // =========================================================================
    // KeyValue creation tests
    // =========================================================================

    @Test
    fun testCharKeyCreation() {
        val key = KeyValue.makeCharKey('a')
        assertEquals("Should have CHAR kind", KeyValue.Kind.Char, key.getKind())
        assertEquals("Char payload must be the requested char", 'a', key.getChar())
        assertEquals("Symbol must default to the char itself", "a", key.getString())
        assertEquals("makeCharKey(c) must not set flags", 0, key.getFlags())
    }

    @Test
    fun testCharKeyUppercase() {
        val key = KeyValue.makeCharKey('A')
        assertEquals("Uppercase char must be preserved, not case-folded", 'A', key.getChar())
        assertEquals("Symbol must be the uppercase char", "A", key.getString())
        assertEquals("Should have CHAR kind", KeyValue.Kind.Char, key.getKind())
    }

    @Test
    fun testCharKeyDigit() {
        val key = KeyValue.makeCharKey('5')
        assertEquals("Digit char must be preserved", '5', key.getChar())
        assertEquals("Should have CHAR kind", KeyValue.Kind.Char, key.getKind())
    }

    @Test
    fun testCharKeySymbol() {
        val key = KeyValue.makeCharKey('@')
        assertEquals("Symbol char must be preserved", '@', key.getChar())
        assertEquals("Should have CHAR kind", KeyValue.Kind.Char, key.getKind())
    }

    @Test
    fun testCharKeyEquality() {
        // sameKey is the type-safe equality the layout system relies on
        assertTrue(
            "Two char keys for the same char must be the same key",
            KeyValue.makeCharKey('a').sameKey(KeyValue.makeCharKey('a'))
        )
        assertFalse(
            "Char keys for different chars must not be the same key",
            KeyValue.makeCharKey('a').sameKey(KeyValue.makeCharKey('b'))
        )
    }

    @Test
    fun testStringKeyCreation() {
        val key = KeyValue.makeStringKey("test")
        assertEquals("Multi-char string must have String kind", KeyValue.Kind.String, key.getKind())
        assertEquals("String payload must be the input verbatim", "test", key.getString())
    }

    @Test
    fun testStringKeyEmoji() {
        // "😀" is one code point but TWO UTF-16 units, so it takes the String
        // branch of makeStringKey (only length==1 collapses to a Char key).
        val key = KeyValue.makeStringKey("😀")
        assertEquals("Surrogate-pair emoji must be a String key", KeyValue.Kind.String, key.getKind())
        assertEquals("Emoji payload must be preserved verbatim", "😀", key.getString())
    }

    @Test
    fun testStringKeyMultiChar() {
        val key = KeyValue.makeStringKey("abc")
        assertEquals("Multi-char string must have String kind", KeyValue.Kind.String, key.getKind())
        assertEquals("String payload must be the input verbatim", "abc", key.getString())
    }

    @Test
    fun testStringKeySingleCharCollapsesToCharKey() {
        // Documented contract: "A char key is returned for a string of length 1."
        val key = KeyValue.makeStringKey("x")
        assertEquals("Length-1 string must collapse to a Char key", KeyValue.Kind.Char, key.getKind())
        assertEquals("Collapsed char key must carry the char", 'x', key.getChar())
    }

    // =========================================================================
    // Special key tests
    // =========================================================================

    @Test
    fun testBackspaceKey() {
        assertKeyeventKey("backspace", KeyEvent.KEYCODE_DEL)
    }

    @Test
    fun testEnterKey() {
        assertKeyeventKey("enter", KeyEvent.KEYCODE_ENTER)
    }

    @Test
    fun testSpaceKey() {
        val key = KeyValue.getSpecialKeyByName("space")
        assertNotNull("'space' must be in the special-key table", key)
        assertEquals("Space is a Char key, not a Keyevent", KeyValue.Kind.Char, key!!.getKind())
        assertEquals("Space must type the space character", ' ', key.getChar())
    }

    @Test
    fun testTabKey() {
        assertKeyeventKey("tab", KeyEvent.KEYCODE_TAB)
    }

    @Test
    fun testEscapeKey() {
        // The special-key table names it "esc" — "escape" is NOT a registered
        // name and would fall back to a String key typing the literal text.
        assertKeyeventKey("esc", KeyEvent.KEYCODE_ESCAPE)
        assertNull(
            "'escape' must not be in the special-key table (the name is 'esc')",
            KeyValue.getSpecialKeyByName("escape")
        )
    }

    @Test
    fun testDeleteKey() {
        assertKeyeventKey("delete", KeyEvent.KEYCODE_FORWARD_DEL)
    }

    // =========================================================================
    // Modifier key tests
    // =========================================================================

    @Test
    fun testShiftKey() {
        assertModifierKey("shift", KeyValue.Modifier.SHIFT)
        // Shift is the double-tap-lockable modifier
        assertTrue(
            "Shift must carry FLAG_DOUBLE_TAP_LOCK",
            KeyValue.getKeyByName("shift").hasFlagsAny(KeyValue.FLAG_DOUBLE_TAP_LOCK)
        )
    }

    @Test
    fun testCtrlKey() {
        assertModifierKey("ctrl", KeyValue.Modifier.CTRL)
    }

    @Test
    fun testAltKey() {
        assertModifierKey("alt", KeyValue.Modifier.ALT)
    }

    @Test
    fun testMetaKey() {
        assertModifierKey("meta", KeyValue.Modifier.META)
    }

    @Test
    fun testCapsLockKey() {
        val key = KeyValue.getSpecialKeyByName("capslock")
        assertNotNull("'capslock' must be in the special-key table", key)
        assertEquals("Caps lock is an Event key, not a modifier", KeyValue.Kind.Event, key!!.getKind())
        assertEquals("Caps lock must fire the CAPS_LOCK event", KeyValue.Event.CAPS_LOCK, key.getEvent())
    }

    // =========================================================================
    // Navigation key tests
    // =========================================================================

    @Test
    fun testUpKey() {
        assertKeyeventKey("up", KeyEvent.KEYCODE_DPAD_UP)
    }

    @Test
    fun testDownKey() {
        assertKeyeventKey("down", KeyEvent.KEYCODE_DPAD_DOWN)
    }

    @Test
    fun testLeftKey() {
        assertKeyeventKey("left", KeyEvent.KEYCODE_DPAD_LEFT)
    }

    @Test
    fun testRightKey() {
        assertKeyeventKey("right", KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    @Test
    fun testHomeKey() {
        assertKeyeventKey("home", KeyEvent.KEYCODE_MOVE_HOME)
    }

    @Test
    fun testEndKey() {
        assertKeyeventKey("end", KeyEvent.KEYCODE_MOVE_END)
    }

    @Test
    fun testPageUpKey() {
        assertKeyeventKey("page_up", KeyEvent.KEYCODE_PAGE_UP)
    }

    @Test
    fun testPageDownKey() {
        assertKeyeventKey("page_down", KeyEvent.KEYCODE_PAGE_DOWN)
    }

    // =========================================================================
    // Function key tests
    // =========================================================================

    @Test
    fun testF1Key() {
        assertKeyeventKey("f1", KeyEvent.KEYCODE_F1)
        assertEquals("F1 must display as 'F1'", "F1", KeyValue.getKeyByName("f1").getString())
    }

    @Test
    fun testF12Key() {
        assertKeyeventKey("f12", KeyEvent.KEYCODE_F12)
        assertEquals("F12 must display as 'F12'", "F12", KeyValue.getKeyByName("f12").getString())
    }

    // =========================================================================
    // KeyValue kind tests
    // =========================================================================

    @Test
    fun testCharKind() {
        val key = KeyValue.makeCharKey('x')
        assertEquals("Char key should have Char kind", KeyValue.Kind.Char, key.getKind())
        assertEquals("Char key must carry its char", 'x', key.getChar())
    }

    @Test
    fun testModifierKind() {
        val key = KeyValue.getKeyByName("shift")
        assertEquals("Shift should have Modifier kind", KeyValue.Kind.Modifier, key.getKind())
        assertEquals("Shift must carry the SHIFT modifier", KeyValue.Modifier.SHIFT, key.getModifier())
    }

    // =========================================================================
    // Unknown key name tests (creates string keys for flexibility)
    // =========================================================================

    @Test
    fun testUnknownKeyCreatesStringKey() {
        // getKeyByName falls back to a string key for unknown names, so custom
        // layouts can put arbitrary text on a key. The payload must be verbatim.
        val key = KeyValue.getKeyByName("nonexistent_key_xyz")
        assertEquals("Unknown name must become a String key", KeyValue.Kind.String, key.getKind())
        assertEquals(
            "String fallback must type the name verbatim",
            "nonexistent_key_xyz", key.getString()
        )
        assertNull(
            "The fallback must not shadow a real special key",
            KeyValue.getSpecialKeyByName("nonexistent_key_xyz")
        )
    }

    @Test
    fun testEmptyKeyNameCreatesKey() {
        // "" fails the parser (no kind after ':' syntax) and falls back to an
        // empty string key.
        val key = KeyValue.getKeyByName("")
        assertEquals("Empty name must become a String key", KeyValue.Kind.String, key.getKind())
        assertEquals("Empty name must carry an empty payload", "", key.getString())
    }

    // =========================================================================
    // Config key event settings
    //
    // Values come from prefs (possibly user-set on a real device), so exact
    // values are not pinned; instead each field is checked against the published
    // ConfigSnapshot — the hot-path read model that publishSnapshot() must keep
    // in sync with the live Config on every refresh.
    // =========================================================================

    @Test
    fun testLongPressTimeout() {
        val timeout = config.longPressTimeout
        assertTrue("Longpress timeout should be non-negative", timeout >= 0)
        assertEquals(
            "Snapshot must mirror the live longPressTimeout",
            timeout, config.snapshot.longPressTimeout
        )
    }

    @Test
    fun testLongPressInterval() {
        val interval = config.longPressInterval
        assertTrue("Longpress interval should be non-negative", interval >= 0)
        assertEquals(
            "Snapshot must mirror the live longPressInterval",
            interval, config.snapshot.longPressInterval
        )
    }

    @Test
    fun testKeyRepeatEnabled() {
        assertEquals(
            "Snapshot must mirror the live keyrepeat_enabled",
            config.keyrepeat_enabled, config.snapshot.keyrepeat_enabled
        )
    }

    @Test
    fun testDoubleTapLockShift() {
        assertEquals(
            "Snapshot must mirror the live double_tap_lock_shift",
            config.double_tap_lock_shift, config.snapshot.double_tap_lock_shift
        )
    }

    // =========================================================================
    // Vibration/Haptic settings
    // =========================================================================

    @Test
    fun testHapticEnabled() {
        // The global Config must be a stable singleton: haptic gating reads it
        // from many sites and they must all see the same instance.
        assertSame(
            "Config.globalConfig() must return the same instance",
            config, Config.globalConfig()
        )
        // Read must not throw and must be stable across reads
        assertEquals(
            "haptic_enabled must be stable across reads",
            config.haptic_enabled, Config.globalConfig().haptic_enabled
        )
    }

    @Test
    fun testVibrateDuration() {
        val duration = config.vibrate_duration
        assertTrue("Vibrate duration should be non-negative", duration >= 0)
    }

    // =========================================================================
    // Key appearance settings
    // =========================================================================

    @Test
    fun testKeyPadding() {
        val padding = config.keyPadding
        assertTrue("Key padding should be non-negative", padding >= 0)
        assertEquals(
            "Snapshot must mirror the live keyPadding",
            padding, config.snapshot.keyPadding, 0.0f
        )
    }

    @Test
    fun testKeyboardMarginBottom() {
        val margin = config.margin_bottom
        assertTrue("Margin should be non-negative", margin >= 0)
        assertEquals(
            "Snapshot must mirror the live margin_bottom",
            margin, config.snapshot.margin_bottom, 0.0f
        )
    }

    @Test
    fun testKeyboardMarginRight() {
        val margin = config.margin_right
        assertTrue("Margin should be non-negative", margin >= 0)
        assertEquals(
            "Snapshot must mirror the live margin_right",
            margin, config.snapshot.margin_right, 0.0f
        )
    }
}
