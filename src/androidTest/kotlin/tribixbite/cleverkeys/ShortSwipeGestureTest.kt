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
        assertEquals("Retrieved actionValue must round-trip", "!", retrieved!!.actionValue)
        assertEquals("Retrieved keyCode must round-trip", "testkey", retrieved.keyCode)
        assertEquals("Retrieved direction must round-trip", SwipeDirection.N, retrieved.direction)
        assertEquals("Retrieved actionType must round-trip", ActionType.TEXT, retrieved.actionType)
        assertEquals("Retrieved displayText must round-trip", "!", retrieved.displayText)
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
        assertTrue("Should have NE direction", mappings.containsKey(SwipeDirection.NE))
        assertEquals("N mapping value must round-trip", "!", mappings[SwipeDirection.N]?.actionValue)
        assertEquals("NE mapping value must round-trip", "@", mappings[SwipeDirection.NE]?.actionValue)
    }

    @Test
    fun testGetAllMappings() = runBlocking {
        // A freshly set mapping must be visible in the all-mappings view under
        // its storage key.
        manager.setMapping(ShortSwipeMapping.textInput("testkey", SwipeDirection.N, "!", "!"))
        val allMappings = manager.getAllMappings()
        assertTrue(
            "getAllMappings must contain the mapping just set (storage key testkey:N)",
            allMappings.any { it.keyCode == "testkey" && it.direction == SwipeDirection.N }
        )
    }

    // =========================================================================
    // AvailableCommand tests
    // =========================================================================

    @Test
    fun testDeleteWordCommand() {
        val cmd = AvailableCommand.DELETE_WORD
        assertEquals("DELETE_WORD display name", "Delete Word", cmd.displayName)
        assertTrue("DELETE_WORD must have a description", cmd.description.isNotBlank())
        assertEquals(
            "DELETE_WORD must round-trip through fromString",
            cmd, AvailableCommand.fromString("DELETE_WORD")
        )
    }

    @Test
    fun testSelectAllCommand() {
        assertEquals("SELECT_ALL display name", "Select All", AvailableCommand.SELECT_ALL.displayName)
    }

    @Test
    fun testCopyCommand() {
        assertEquals("COPY display name", "Copy", AvailableCommand.COPY.displayName)
    }

    @Test
    fun testPasteCommand() {
        assertEquals("PASTE display name", "Paste", AvailableCommand.PASTE.displayName)
    }

    @Test
    fun testUndoCommand() {
        assertEquals("UNDO display name", "Undo", AvailableCommand.UNDO.displayName)
    }

    @Test
    fun testRedoCommand() {
        assertEquals("REDO display name", "Redo", AvailableCommand.REDO.displayName)
    }

    @Test
    fun testEveryCommandRoundTripsThroughFromString() {
        // The persistence layer stores commands by enum name; every constant
        // must survive the round trip, and display metadata must be complete.
        for (cmd in AvailableCommand.entries) {
            assertEquals(
                "${cmd.name} must round-trip through fromString",
                cmd, AvailableCommand.fromString(cmd.name)
            )
            assertTrue("${cmd.name} must have a display name", cmd.displayName.isNotBlank())
            assertTrue("${cmd.name} must have a description", cmd.description.isNotBlank())
        }
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
        // The hot-path snapshot (what Pointers actually reads per gesture) must
        // mirror the live Config field.
        assertEquals(
            "Snapshot must mirror the live short_gestures_enabled",
            config.short_gestures_enabled, config.snapshot.short_gestures_enabled
        )
    }

    @Test
    fun testShortGestureMinDistanceSetting() {
        val config = Config.globalConfig()
        val minDist = config.short_gesture_min_distance
        assertTrue("Min distance should be non-negative", minDist.v >= 0)
        assertEquals(
            "Snapshot must mirror the live short_gesture_min_distance",
            minDist, config.snapshot.short_gesture_min_distance
        )
    }

    @Test
    fun testShortGestureMaxDistanceSetting() {
        val config = Config.globalConfig()
        val maxDist = config.short_gesture_max_distance
        assertTrue("Max distance should be positive", maxDist.v > 0)
        assertEquals(
            "Snapshot must mirror the live short_gesture_max_distance",
            maxDist, config.snapshot.short_gesture_max_distance
        )
    }
}
