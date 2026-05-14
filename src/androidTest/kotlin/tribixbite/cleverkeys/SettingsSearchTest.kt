package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UIAutomator instrumented tests for Settings search-to-scroll functionality.
 *
 * Regression test for MonotonicFrameClock crash: tapping a search result
 * triggered animateScrollTo() via lifecycleScope (missing Compose frame clock).
 * Fixed by using rememberCoroutineScope() which provides the clock.
 *
 * Uses individual key code presses for Compose TextField compatibility —
 * UiObject2.setText() and shell "input text" bypass Compose's onValueChange.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSearchTest {

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private val LAUNCH_TIMEOUT = 5000L
    private val UI_TIMEOUT = 3000L

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Launch SettingsActivity
        val intent = Intent(context, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)

        // Wait for the activity to appear
        device.wait(Until.hasObject(By.text("CleverKeys Settings")), LAUNCH_TIMEOUT)
    }

    @After
    fun teardown() {
        device.pressBack()
    }

    /** Type text into the focused Compose TextField via individual key code presses.
     *  This is the only reliable method — setText() and shell "input text" bypass
     *  Compose's internal text state management and don't trigger onValueChange. */
    private fun typeKeys(text: String) {
        for (char in text) {
            when {
                char == ' ' -> device.pressKeyCode(KeyEvent.KEYCODE_SPACE)
                char in 'a'..'z' -> device.pressKeyCode(KeyEvent.KEYCODE_A + (char - 'a'))
                char in 'A'..'Z' -> device.pressKeyCode(
                    KeyEvent.KEYCODE_A + (char.lowercaseChar() - 'a'),
                    KeyEvent.META_SHIFT_ON
                )
                char in '0'..'9' -> device.pressKeyCode(KeyEvent.KEYCODE_0 + (char - '0'))
            }
            Thread.sleep(30)
        }
    }

    /** Click the search field and type a query */
    private fun searchFor(query: String) {
        // Find the search field by its placeholder text or by description
        val searchField = device.wait(
            Until.findObject(By.text("Search settings...")),
            UI_TIMEOUT
        ) ?: device.wait(
            Until.findObject(By.desc("Search settings...")),
            UI_TIMEOUT
        ) ?: device.wait(
            Until.findObject(By.textContains("Search")),
            UI_TIMEOUT
        )
        assertNotNull("Search field should be found", searchField)
        searchField!!.click()

        Thread.sleep(500) // Wait for focus + keyboard
        typeKeys(query)
        Thread.sleep(1000) // Wait for search results to filter
    }

    // =========================================================================
    // Search scroll crash regression (MonotonicFrameClock)
    // =========================================================================

    @Test
    fun searchAndTapResult_backspaceUndoSwipe_doesNotCrash() {
        searchFor("backspace undo")

        val result = device.wait(
            Until.findObject(By.text("Backspace Undo Swipe")),
            UI_TIMEOUT
        )
        assertNotNull("Search result 'Backspace Undo Swipe' should appear", result)

        // Tap the search result — this is where the MonotonicFrameClock crash happened
        result.click()
        Thread.sleep(1500)

        // If we get here without crashing, the fix works.
        val toggle = device.wait(
            Until.findObject(By.textContains("Tapping backspace immediately")),
            UI_TIMEOUT
        )
        assertNotNull("Backspace Undo Swipe description should be visible after scroll", toggle)
    }

    @Test
    fun searchAndTapResult_keyRepeat_doesNotCrash() {
        searchFor("key repeat")

        val result = device.wait(
            Until.findObject(By.text("Key Repeat")),
            UI_TIMEOUT
        )
        assertNotNull("Search result 'Key Repeat' should appear", result)

        result.click()
        Thread.sleep(1500)

        // No crash = pass. Header may scroll off-screen so check package is still foreground.
        // startsWith covers both release (tribixbite.cleverkeys) and debug (.debug suffix).
        val pkg = device.currentPackageName ?: ""
        assertTrue(
            "Settings activity should still be running, got pkg=$pkg",
            pkg.startsWith("tribixbite.cleverkeys")
        )
    }

    @Test
    fun searchAndTapResult_gatedSetting_doesNotCrash() {
        // Beam width is gated by swipe_typing — tests the gated scrollToSetting path
        searchFor("beam width")

        val result = device.wait(
            Until.findObject(By.text("Beam Width")),
            UI_TIMEOUT
        )
        assertNotNull("Search result 'Beam Width' should appear", result)

        result.click()
        Thread.sleep(1500)

        val pkg = device.currentPackageName ?: ""
        assertTrue(
            "Settings activity should still be running after gated search tap, got pkg=$pkg",
            pkg.startsWith("tribixbite.cleverkeys")
        )
    }

    // =========================================================================
    // #110: Backspace Undo Swipe search result visibility
    // =========================================================================

    @Test
    fun backspaceUndoSwipe_appearsInWordPredictionSection() {
        searchFor("backspace undo")

        val result = device.wait(
            Until.findObject(By.text("Backspace Undo Swipe")),
            UI_TIMEOUT
        )
        assertNotNull("Backspace Undo Swipe should appear in search results", result)

        val sectionLabel = device.wait(
            Until.findObject(By.text("in Word Prediction")),
            UI_TIMEOUT
        )
        assertNotNull("Should show 'in Word Prediction' section label", sectionLabel)
    }

    @Test
    fun searchResults_backspace_showsMultipleResults() {
        // Search for "backspace undo" — matches "Backspace Undo Swipe" and
        // "Backspace Undo Autocorrect" (2 results, both visible without scrolling).
        // Note: searching bare "backspace" returns 5 results that overflow the
        // 200dp search results card, making items below the fold invisible to
        // UIAutomator on some devices.
        searchFor("backspace undo")

        val undoSwipe = device.wait(
            Until.findObject(By.text("Backspace Undo Swipe")),
            UI_TIMEOUT
        )
        val undoAutocorrect = device.wait(
            Until.findObject(By.text("Backspace Undo Autocorrect")),
            UI_TIMEOUT
        )
        assertNotNull("'Backspace Undo Swipe' should appear in search results", undoSwipe)
        assertNotNull("'Backspace Undo Autocorrect' should appear in search results", undoAutocorrect)
    }
}
