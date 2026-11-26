package tribixbite.cleverkeys

import android.content.Context
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Note10+ r18 (2026-11-26): an "Added “X” to dictionary" confirmation stayed on the bar for about
 * three minutes. The message is a plain `TextView` added by `showMessage`, outside the suggestion
 * view pools. When it ended (timeout, a dismiss, the undo tap, a field change) the bar was set to
 * the empty list it already held, `setSuggestionsWithScores`' identical-content skip returned
 * before `rebindSuggestionViews`, and the message view stayed until some prediction differed.
 *
 * Instrumented: the fault is in which child views a real `LinearLayout` holds, and the stubbed
 * android.jar of the mock tier has no views.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionBarMessageTeardownTest {

    private lateinit var context: Context
    private lateinit var bar: SuggestionBar
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setup() {
        context = instrumentation.targetContext
        instrumentation.runOnMainSync { bar = SuggestionBar(context) }
    }

    /** The texts of the bar's TextView children, in order. */
    private fun shownTexts(): List<String> {
        var texts: List<String> = emptyList()
        instrumentation.runOnMainSync {
            texts = (0 until bar.childCount).mapNotNull { (bar.getChildAt(it) as? TextView)?.text?.toString() }
        }
        return texts
    }

    private fun childCount(): Int {
        var count = -1
        instrumentation.runOnMainSync { count = bar.childCount }
        return count
    }

    /** Waits past a short message's timeout, letting the main looper run its restore. */
    private fun waitPast(ms: Long) {
        Thread.sleep(ms + 400)
        instrumentation.waitForIdleSync()
    }

    @Test
    fun aClearAfterMessageShownOverAnEmptyBarLeavesWhenItTimesOut() {
        instrumentation.runOnMainSync {
            bar.setSuggestions(emptyList())
            bar.showTemporaryMessage("Added “mot” to dictionary", 100L, clearAfter = true)
        }
        assertEquals(listOf("Added “mot” to dictionary"), shownTexts())

        waitPast(100L)

        assertEquals("the bar is empty once the message ends", 0, childCount())
        var showing = true
        instrumentation.runOnMainSync { showing = bar.isShowingMessage() }
        assertFalse(showing)
    }

    @Test
    fun anUndoableConfirmationLeavesWhenItIsDismissed() {
        instrumentation.runOnMainSync {
            bar.showUndoableMessage("Added “mot” to dictionary", "Tap again to undo") {}
            bar.dismissUndoableMessage()
        }
        assertEquals("a dismissed confirmation leaves the bar", 0, childCount())
    }

    @Test
    fun anUndoneConfirmationLeavesTheBar() {
        var undone = false
        instrumentation.runOnMainSync {
            bar.showUndoableMessage("Added “mot” to dictionary", "Tap again to undo") { undone = true }
            val view = bar.getChildAt(0)
            view.performClick()   // arm
            view.performClick()   // undo
        }
        assertEquals(true, undone)
        assertEquals("the undo removes the confirmation", 0, childCount())
    }

    @Test
    fun clearingTheBarAfterADroppedMessageRemovesIt() {
        // A field change: the live message is dropped, then the bar is cleared.
        instrumentation.runOnMainSync {
            bar.showTemporaryMessage("Already in dictionary", 60_000L, clearAfter = true)
            bar.dismissTemporaryMessage()
            bar.clearSuggestions()
        }
        assertEquals("nothing of the old field's message stays", 0, childCount())
    }

    @Test
    fun aMessageOverSuggestionsStillRestoresThem() {
        instrumentation.runOnMainSync {
            bar.setSuggestions(listOf("alpha", "beta"))
            bar.showTemporaryMessage("Layout: QWERTY", 100L)
        }
        waitPast(100L)
        val texts = shownTexts()
        assertFalse("the message is gone: $texts", texts.contains("Layout: QWERTY"))
        assertEquals(listOf("alpha", "beta"), texts.filter { it.isNotEmpty() })
    }
}
