package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.InlineEditHistory.EditKind
import tribixbite.cleverkeys.InlineEditHistory.Snapshot

/**
 * Undo/redo history of the clipboard panel's inline entry editor (Pixel 2026-10-14: Ctrl+Z
 * did nothing there). The platform's own undo cannot serve this editor: every routed edit is
 * an `EditText.setText()`, and `TextView.setText` calls `Editor.forgetUndoRedo()` for an
 * editable buffer (AOSP lineage-22.2 TextView.java), so the platform history is wiped on each
 * keystroke. [InlineEditHistory] is the Android-free replacement the view drives.
 */
class InlineEditHistoryTest {

    private fun caret(text: String, at: Int = text.length) = Snapshot(text, at, at)

    /** Type [chars] one at a time at the end of [start], recording each like insertEditText. */
    private fun InlineEditHistory.typeAtEnd(start: String, chars: String): Snapshot {
        var current = caret(start)
        for (c in chars) {
            val next = caret(current.text + c)
            record(current, next, EditKind.TYPING)
            current = next
        }
        return current
    }

    @Test
    fun undoRestoresTheTextAndSelectionBeforeTheEdit() {
        val h = InlineEditHistory()
        val before = Snapshot("hello", 0, 5) // select all
        val after = caret("")                // then backspace
        h.record(before, after, EditKind.DELETION)

        assertEquals(before, h.undo(after))
        assertFalse(h.canUndo)
    }

    @Test
    fun redoReappliesWhatUndoReverted() {
        val h = InlineEditHistory()
        val after = h.typeAtEnd("", "a")
        val undone = h.undo(after)!!
        assertEquals(caret(""), undone)
        assertTrue(h.canRedo)
        assertEquals(after, h.redo(undone))
        assertFalse(h.canRedo)
    }

    @Test
    fun consecutiveTypingIsOneStepPerWord() {
        val h = InlineEditHistory()
        // "hello world": the space ends the first step's word, "world" is its own step.
        val end = h.typeAtEnd("", "hello world")
        val afterFirstUndo = h.undo(end)!!
        assertEquals(caret("hello "), afterFirstUndo)
        assertEquals(caret(""), h.undo(afterFirstUndo))
        assertNull(h.undo(caret("")))
    }

    @Test
    fun consecutiveBackspacesMergeIntoOneStep() {
        val h = InlineEditHistory()
        var current = caret("abcdef")
        repeat(3) {
            val next = caret(current.text.dropLast(1))
            h.record(current, next, EditKind.DELETION)
            current = next
        }
        assertEquals(caret("abcdef"), h.undo(current))
    }

    @Test
    fun aCaretMoveBetweenEditsStartsANewStep() {
        val h = InlineEditHistory()
        val typed = h.typeAtEnd("ab", "c")            // "abc|"
        val moved = caret(typed.text, 1)              // caret moved to "a|bc"
        val inserted = Snapshot("aXbc", 2, 2)
        h.record(moved, inserted, EditKind.TYPING)

        assertEquals(moved, h.undo(inserted))         // only the X goes
        assertEquals(caret("ab"), h.undo(moved))      // then the c
    }

    @Test
    fun pasteAndCutAreAlwaysTheirOwnStep() {
        val h = InlineEditHistory()
        val typed = h.typeAtEnd("", "ab")
        val pasted = caret("abCLIP")
        h.record(typed, pasted, EditKind.OTHER)
        val typedAgain = caret("abCLIPd")
        h.record(pasted, typedAgain, EditKind.TYPING)

        assertEquals(pasted, h.undo(typedAgain))
        assertEquals(typed, h.undo(pasted))
    }

    @Test
    fun aNewEditDiscardsTheRedoBranch() {
        val h = InlineEditHistory()
        val after = h.typeAtEnd("", "a")
        val undone = h.undo(after)!!
        h.record(undone, caret("b"), EditKind.TYPING)
        assertFalse(h.canRedo)
        assertNull(h.redo(caret("b")))
    }

    @Test
    fun aSelectionOnlyChangeIsNotRecorded() {
        val h = InlineEditHistory()
        h.record(caret("abc"), Snapshot("abc", 0, 3), EditKind.OTHER)
        assertFalse(h.canUndo)
    }

    @Test
    fun historyIsBoundedToItsCapacityDroppingTheOldestSteps() {
        val h = InlineEditHistory(capacity = 3)
        var current = caret("")
        for (i in 0 until 5) {
            val next = caret(current.text + "x")
            h.record(current, next, EditKind.OTHER) // OTHER never merges
            current = next
        }
        var steps = 0
        while (true) {
            current = h.undo(current) ?: break
            steps++
        }
        assertEquals(3, steps)
        assertEquals(caret("xx"), current) // the two oldest states were dropped
    }

    @Test
    fun historyIsBoundedByRetainedCharacters() {
        val h = InlineEditHistory(capacity = 100, maxRetainedChars = 10)
        var current = caret("")
        for (i in 0 until 6) {
            val next = caret(current.text + "abc")
            h.record(current, next, EditKind.OTHER)
            current = next
        }
        // Undo snapshots are 0, 3, … 15 chars; the 10-char budget drops the oldest, but the
        // newest step is always kept (15 chars, over budget alone) so one undo always works.
        assertTrue(h.canUndo)
        assertEquals(caret("abcabcabcabcabc"), h.undo(current))
        assertFalse(h.canUndo)
    }

    @Test
    fun clearForgetsEverything() {
        val h = InlineEditHistory()
        val after = h.typeAtEnd("", "a")
        h.undo(after)
        h.clear()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }
}
