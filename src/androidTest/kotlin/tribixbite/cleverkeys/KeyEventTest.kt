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

    // =========================================================================
    // KeyValue creation tests
    // =========================================================================

    @Test
    fun testCharKeyCreation() {
        val key = KeyValue.makeCharKey('a')
        assertNotNull("Char key should be created", key)
        assertEquals("Should have CHAR kind", KeyValue.Kind.Char, key.getKind())
    }

    @Test
    fun testCharKeyUppercase() {
        val key = KeyValue.makeCharKey('A')
        assertNotNull("Uppercase char key should be created", key)
    }

    @Test
    fun testCharKeyDigit() {
        val key = KeyValue.makeCharKey('5')
        assertNotNull("Digit key should be created", key)
    }

    @Test
    fun testCharKeySymbol() {
        val key = KeyValue.makeCharKey('@')
        assertNotNull("Symbol key should be created", key)
    }

    @Test
    fun testStringKeyCreation() {
        val key = KeyValue.makeStringKey("test")
        assertNotNull("String key should be created", key)
    }

    @Test
    fun testStringKeyEmoji() {
        val key = KeyValue.makeStringKey("😀")
        assertNotNull("Emoji string key should be created", key)
    }

    @Test
    fun testStringKeyMultiChar() {
        val key = KeyValue.makeStringKey("abc")
        assertNotNull("Multi-char string key should be created", key)
    }

    // =========================================================================
    // Special key tests
    // =========================================================================

    @Test
    fun testBackspaceKey() {
        val key = KeyValue.getKeyByName("backspace")
        assertNotNull("Backspace key should exist", key)
    }

    @Test
    fun testEnterKey() {
        val key = KeyValue.getKeyByName("enter")
        assertNotNull("Enter key should exist", key)
    }

    @Test
    fun testSpaceKey() {
        val key = KeyValue.getKeyByName("space")
        assertNotNull("Space key should exist", key)
    }

    @Test
    fun testTabKey() {
        val key = KeyValue.getKeyByName("tab")
        assertNotNull("Tab key should exist", key)
    }

    @Test
    fun testEscapeKey() {
        val key = KeyValue.getKeyByName("escape")
        assertNotNull("Escape key should exist", key)
    }

    // =========================================================================
    // Modifier key tests
    // =========================================================================

    @Test
    fun testShiftKey() {
        val key = KeyValue.getKeyByName("shift")
        assertNotNull("Shift key should exist", key)
    }

    @Test
    fun testCtrlKey() {
        val key = KeyValue.getKeyByName("ctrl")
        assertNotNull("Ctrl key should exist", key)
    }

    @Test
    fun testAltKey() {
        val key = KeyValue.getKeyByName("alt")
        assertNotNull("Alt key should exist", key)
    }

    @Test
    fun testMetaKey() {
        val key = KeyValue.getKeyByName("meta")
        assertNotNull("Meta key should exist", key)
    }

    @Test
    fun testCapsLockKey() {
        val key = KeyValue.getKeyByName("capslock")
        assertNotNull("Caps lock key should exist", key)
    }

    // =========================================================================
    // Navigation key tests
    // =========================================================================

    @Test
    fun testUpKey() {
        val key = KeyValue.getKeyByName("up")
        assertNotNull("Up arrow key should exist", key)
    }

    @Test
    fun testDownKey() {
        val key = KeyValue.getKeyByName("down")
        assertNotNull("Down arrow key should exist", key)
    }

    @Test
    fun testLeftKey() {
        val key = KeyValue.getKeyByName("left")
        assertNotNull("Left arrow key should exist", key)
    }

    @Test
    fun testRightKey() {
        val key = KeyValue.getKeyByName("right")
        assertNotNull("Right arrow key should exist", key)
    }

    @Test
    fun testHomeKey() {
        val key = KeyValue.getKeyByName("home")
        assertNotNull("Home key should exist", key)
    }

    @Test
    fun testEndKey() {
        val key = KeyValue.getKeyByName("end")
        assertNotNull("End key should exist", key)
    }

    @Test
    fun testPageUpKey() {
        val key = KeyValue.getKeyByName("page_up")
        assertNotNull("Page up key should exist", key)
    }

    @Test
    fun testPageDownKey() {
        val key = KeyValue.getKeyByName("page_down")
        assertNotNull("Page down key should exist", key)
    }

    // =========================================================================
    // Function key tests
    // =========================================================================

    @Test
    fun testF1Key() {
        val key = KeyValue.getKeyByName("f1")
        assertNotNull("F1 key should exist", key)
    }

    @Test
    fun testF12Key() {
        val key = KeyValue.getKeyByName("f12")
        assertNotNull("F12 key should exist", key)
    }

    // =========================================================================
    // KeyValue kind tests
    // =========================================================================

    @Test
    fun testCharKind() {
        val key = KeyValue.makeCharKey('x')
        assertEquals("Char key should have Char kind", KeyValue.Kind.Char, key.getKind())
    }

    @Test
    fun testModifierKind() {
        val key = KeyValue.getKeyByName("shift")
        assertNotNull(key)
        assertEquals("Shift should have Modifier kind", KeyValue.Kind.Modifier, key!!.getKind())
    }

    // =========================================================================
    // Unknown key name tests (creates string keys for flexibility)
    // =========================================================================

    @Test
    fun testUnknownKeyCreatesStringKey() {
        // getKeyByName creates string keys for unknown names - allows custom strings
        val key = KeyValue.getKeyByName("nonexistent_key_xyz")
        assertNotNull("Unknown name should create string key", key)
    }

    @Test
    fun testEmptyKeyNameCreatesKey() {
        // Empty string creates an empty string key
        val key = KeyValue.getKeyByName("")
        assertNotNull("Empty name should create key", key)
    }

    // =========================================================================
    // Config key event settings
    // =========================================================================

    @Test
    fun testLongPressTimeout() {
        val timeout = config.longPressTimeout
        assertTrue("Longpress timeout should be non-negative", timeout >= 0)
    }

    @Test
    fun testLongPressInterval() {
        val interval = config.longPressInterval
        assertTrue("Longpress interval should be non-negative", interval >= 0)
    }

    @Test
    fun testKeyRepeatEnabled() {
        // Just verify property is accessible
        val enabled = config.keyrepeat_enabled
    }

    @Test
    fun testDoubleTapLockShift() {
        // Just verify property is accessible
        val enabled = config.double_tap_lock_shift
    }

    // =========================================================================
    // Vibration/Haptic settings
    // =========================================================================

    @Test
    fun testHapticEnabled() {
        // Just verify property is accessible
        val enabled = config.haptic_enabled
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
    }

    @Test
    fun testKeyboardMarginBottom() {
        val margin = config.margin_bottom
        assertTrue("Margin should be non-negative", margin >= 0)
    }

    @Test
    fun testKeyboardMarginRight() {
        val margin = config.margin_right
        assertTrue("Margin should be non-negative", margin >= 0)
    }
}
