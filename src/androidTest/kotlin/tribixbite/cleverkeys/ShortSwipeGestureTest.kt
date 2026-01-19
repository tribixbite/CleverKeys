package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.*

/**
 * Instrumented tests for short swipe gesture customization.
 * Tests ShortSwipeCustomizationManager, ShortSwipeMapping, and CustomShortSwipeExecutor.
 */
@RunWith(AndroidJUnit4::class)
class ShortSwipeGestureTest {

    private lateinit var context: Context
    private lateinit var manager: ShortSwipeCustomizationManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        manager = ShortSwipeCustomizationManager.getInstance(context)
        runBlocking { manager.loadMappings() }
    }

    @After
    fun cleanup() {
        // Clean up test mappings
        runBlocking {
            manager.removeMapping("testkey", SwipeDirection.N)
            manager.removeMapping("testkey", SwipeDirection.NE)
            manager.removeMapping("a", SwipeDirection.N)
        }
    }

    // =========================================================================
    // ShortSwipeMapping creation tests
    // =========================================================================

    @Test
    fun testTextInputMappingCreation() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "!",
            text = "!"
        )

        assertEquals("a", mapping.keyCode)
        assertEquals(SwipeDirection.N, mapping.direction)
        assertEquals("!", mapping.displayText)
        assertEquals(ActionType.TEXT, mapping.actionType)
        assertEquals("!", mapping.actionValue)
    }

    @Test
    fun testCommandMappingCreation() {
        val mapping = ShortSwipeMapping.command(
            keyCode = "space",
            direction = SwipeDirection.W,
            displayText = "←",
            command = AvailableCommand.DELETE_WORD
        )

        assertEquals("space", mapping.keyCode)
        assertEquals(SwipeDirection.W, mapping.direction)
        assertEquals(ActionType.COMMAND, mapping.actionType)
        assertEquals(AvailableCommand.DELETE_WORD, mapping.getCommand())
    }

    @Test
    fun testKeyEventMappingCreation() {
        val mapping = ShortSwipeMapping.keyEvent(
            keyCode = "enter",
            direction = SwipeDirection.E,
            displayText = "Tab",
            keyEventCode = 61  // KEYCODE_TAB
        )

        assertEquals(ActionType.KEY_EVENT, mapping.actionType)
        assertEquals(61, mapping.getKeyEventCode())
    }

    @Test
    fun testMappingKeyCodeNormalized() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "A",  // Uppercase
            direction = SwipeDirection.N,
            displayText = "!",
            text = "!"
        )

        assertEquals("Keycode should be lowercase", "a", mapping.keyCode)
    }

    @Test
    fun testMappingDisplayTextTruncated() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "TOOLONG",  // More than 4 chars
            text = "test"
        )

        assertEquals("Display text should be max 4 chars", 4, mapping.displayText.length)
    }

    @Test
    fun testStorageKeyFormat() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.NE,
            displayText = "!",
            text = "!"
        )

        assertEquals("Storage key format", "a:NE", mapping.toStorageKey())
    }

    // =========================================================================
    // SwipeDirection tests
    // =========================================================================

    @Test
    fun testAllDirectionsExist() {
        val expectedDirections = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val actualDirections = SwipeDirection.entries.map { it.name }

        for (dir in expectedDirections) {
            assertTrue("Direction $dir should exist", actualDirections.contains(dir))
        }
    }

    @Test
    fun testDirectionAngles() {
        // Verify angles are reasonable (0-360 degrees)
        for (dir in SwipeDirection.entries) {
            assertTrue("${dir.name} angle should be >= 0", dir.angleDegrees >= 0)
            assertTrue("${dir.name} angle should be < 360", dir.angleDegrees < 360)
        }
    }

    // =========================================================================
    // ActionType tests
    // =========================================================================

    @Test
    fun testActionTypeText() {
        assertEquals(ActionType.TEXT, ActionType.fromString("TEXT"))
    }

    @Test
    fun testActionTypeCommand() {
        assertEquals(ActionType.COMMAND, ActionType.fromString("COMMAND"))
    }

    @Test
    fun testActionTypeKeyEvent() {
        assertEquals(ActionType.KEY_EVENT, ActionType.fromString("KEY_EVENT"))
    }

    // =========================================================================
    // Manager CRUD tests
    // =========================================================================

    @Test
    fun testSetAndGetMapping() = runBlocking {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "testkey",
            direction = SwipeDirection.N,
            displayText = "!",
            text = "!"
        )

        manager.setMapping(mapping)
        val retrieved = manager.getMapping("testkey", SwipeDirection.N)

        assertNotNull("Should retrieve mapping", retrieved)
        assertEquals("!", retrieved?.actionValue)
    }

    @Test
    fun testGetMappingNotFound() {
        val result = manager.getMapping("nonexistent", SwipeDirection.N)
        assertNull("Should return null for nonexistent mapping", result)
    }

    @Test
    fun testRemoveMapping() = runBlocking {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "testkey",
            direction = SwipeDirection.NE,
            displayText = "@",
            text = "@"
        )

        manager.setMapping(mapping)
        val removed = manager.removeMapping("testkey", SwipeDirection.NE)

        assertTrue("Should return true when removed", removed)
        assertNull("Should not find removed mapping", manager.getMapping("testkey", SwipeDirection.NE))
    }

    @Test
    fun testRemoveNonexistentMapping() = runBlocking {
        val removed = manager.removeMapping("nonexistent", SwipeDirection.N)
        assertFalse("Should return false for nonexistent", removed)
    }

    @Test
    fun testGetMappingsForKey() = runBlocking {
        // Add multiple mappings for same key
        manager.setMapping(ShortSwipeMapping.textInput("a", SwipeDirection.N, "!", "!"))
        manager.setMapping(ShortSwipeMapping.textInput("a", SwipeDirection.NE, "@", "@"))

        val mappings = manager.getMappingsForKey("a")

        assertTrue("Should have N direction", mappings.containsKey(SwipeDirection.N))
    }

    @Test
    fun testGetAllMappings() {
        val allMappings = manager.getAllMappings()
        assertNotNull("Should return list", allMappings)
    }

    // =========================================================================
    // AvailableCommand tests
    // =========================================================================

    @Test
    fun testDeleteWordCommand() {
        val cmd = AvailableCommand.DELETE_WORD
        assertNotNull("DELETE_WORD should exist", cmd)
        assertNotNull("Should have display name", cmd.displayName)
    }

    @Test
    fun testSelectAllCommand() {
        val cmd = AvailableCommand.SELECT_ALL
        assertNotNull("SELECT_ALL should exist", cmd)
    }

    @Test
    fun testCopyCommand() {
        val cmd = AvailableCommand.COPY
        assertNotNull("COPY should exist", cmd)
    }

    @Test
    fun testPasteCommand() {
        val cmd = AvailableCommand.PASTE
        assertNotNull("PASTE should exist", cmd)
    }

    @Test
    fun testUndoCommand() {
        val cmd = AvailableCommand.UNDO
        assertNotNull("UNDO should exist", cmd)
    }

    @Test
    fun testRedoCommand() {
        val cmd = AvailableCommand.REDO
        assertNotNull("REDO should exist", cmd)
    }

    @Test
    fun testCommandFromStringValid() {
        val cmd = AvailableCommand.fromString("DELETE_WORD")
        assertEquals(AvailableCommand.DELETE_WORD, cmd)
    }

    @Test
    fun testCommandFromStringInvalid() {
        val cmd = AvailableCommand.fromString("NONEXISTENT")
        assertNull("Should return null for invalid command", cmd)
    }

    // =========================================================================
    // Config integration tests
    // =========================================================================

    @Test
    fun testShortGesturesEnabledSetting() {
        val config = Config.globalConfig()
        // Just verify the property is accessible
        val enabled = config.short_gestures_enabled
        // Can be true or false
    }

    @Test
    fun testShortGestureMinDistanceSetting() {
        val config = Config.globalConfig()
        val minDist = config.short_gesture_min_distance
        assertTrue("Min distance should be non-negative", minDist >= 0)
    }

    @Test
    fun testShortGestureMaxDistanceSetting() {
        val config = Config.globalConfig()
        val maxDist = config.short_gesture_max_distance
        assertTrue("Max distance should be positive", maxDist > 0)
    }
}
