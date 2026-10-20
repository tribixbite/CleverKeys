package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Swipe typing into the IME's own inline fields (maintainer report 2026-10-20: swiped words
 * landed in the app behind the clipboard editor). [InlineWordPlanner] decides where the word
 * goes and the spaces around it from the FIELD's text; [InlineSwipeSession] owns the word so a
 * bar tap replaces it and backspace removes it there, and drops that ownership as soon as the
 * field changes under it.
 */
class InlineSwipeSessionTest {

    /** A field like the clipboard editor (caret-aware) or a search box (append-only). */
    private class FakeField(
        override val field: InlineField,
        var text: String,
        var selStart: Int = text.length,
        var selEnd: Int = selStart,
        var session: Long = 1L,
    ) : InlineTextTarget {
        var active = true
        val writes = mutableListOf<Triple<Int, Int, String>>()

        override fun state(): InlineFieldState? = if (!active) null else
            if (field.cursorAware) InlineFieldState(text, selStart, selEnd, session)
            else InlineFieldState.appendOnly(text, session)

        override fun replace(start: Int, end: Int, text: String): Boolean {
            if (!active) return false
            val newText = if (field.cursorAware) {
                if (start < 0 || start > end || end > this.text.length) return false
                this.text.substring(0, start) + text + this.text.substring(end)
            } else {
                InlineFieldState.appendOnlyReplace(this.text, start, end, text) ?: return false
            }
            writes += Triple(start, end, text)
            this.text = newText
            selStart = start + text.length
            selEnd = selStart
            return true
        }

        /** Tap-type [s] at the caret, the way the inline typing route does. */
        fun type(s: String) {
            text = text.substring(0, selStart) + s + text.substring(selEnd)
            selStart += s.length
            selEnd = selStart
        }
    }

    private fun editor(text: String, at: Int = text.length, end: Int = at) =
        FakeField(InlineField.CLIPBOARD_EDIT, text, at, end)

    // ── spacing ─────────────────────────────────────────────────────────────────────

    @Test
    fun anEmptyFieldGetsTheBareWord() {
        val f = editor("")
        assertTrue(InlineSwipeSession().insert(f, "hello"))
        assertEquals("hello", f.text)
        assertEquals(5, f.selStart)
    }

    @Test
    fun aWordAfterTextGetsALeadingSpace() {
        val f = editor("hello")
        InlineSwipeSession().insert(f, "world")
        assertEquals("hello world", f.text)
    }

    @Test
    fun aWordAfterWhitespaceGetsNoLeadingSpace() {
        val f = editor("hello ")
        InlineSwipeSession().insert(f, "world")
        assertEquals("hello world", f.text)
        val g = editor("line\n")
        InlineSwipeSession().insert(g, "next")
        assertEquals("line\nnext", g.text)
    }

    @Test
    fun aWordSwipedIntoTheMiddleOfTextIsSeparatedOnBothSides() {
        // Caret right after "big" in "bigdog": the word must not fuse with either side.
        val f = editor("bigdog", at = 3)
        InlineSwipeSession().insert(f, "red")
        assertEquals("big red dog", f.text)
        // Caret after the inserted trailing space, before "dog".
        assertEquals(8, f.selStart)
    }

    @Test
    fun noTrailingSpaceBeforePunctuationOrWhitespace() {
        val f = editor("say .", at = 4)
        InlineSwipeSession().insert(f, "hi")
        assertEquals("say hi.", f.text)
        val g = editor("a,b", at = 2)
        InlineSwipeSession().insert(g, "x")
        // "," before the caret is not whitespace → leading space; "b" after → trailing space.
        assertEquals("a, x b", g.text)
    }

    @Test
    fun aSelectionIsReplacedByTheWord() {
        val f = editor("one two three", at = 4, end = 7)
        InlineSwipeSession().insert(f, "six")
        assertEquals("one six three", f.text)
    }

    @Test
    fun appendOnlyFieldsAppendWithASeparatingSpace() {
        val f = FakeField(InlineField.CLIPBOARD_SEARCH, "red")
        val session = InlineSwipeSession()
        assertTrue(session.insert(f, "car"))
        assertEquals("red car", f.text)
        val g = FakeField(InlineField.EMOJI_SEARCH, "")
        InlineSwipeSession().insert(g, "smile")
        assertEquals("smile", g.text)
    }

    // ── bar tap replaces the swiped word ─────────────────────────────────────────────

    @Test
    fun aBarTapReplacesTheSwipedWordInTheField() {
        val f = editor("I said")
        val session = InlineSwipeSession()
        session.insert(f, "gello")
        assertEquals("I said gello", f.text)
        assertTrue(session.commitTap(f, "hello"))
        assertEquals("I said hello", f.text)
        assertEquals("hello", session.ownedWord)
        // A second tap replaces the replacement (the alternatives flow keeps working).
        assertTrue(session.commitTap(f, "help"))
        assertEquals("I said help", f.text)
        assertEquals(f.text.length, f.selStart)
    }

    @Test
    fun aBarTapKeepsTheTrailingSeparatorOfAMidTextSwipe() {
        val f = editor("bigdog", at = 3)
        val session = InlineSwipeSession()
        session.insert(f, "rod")
        assertTrue(session.commitTap(f, "red"))
        assertEquals("big red dog", f.text)
        assertEquals(8, f.selStart)
    }

    @Test
    fun aBarTapAfterTheUserTypedInsertsANewWordInstead() {
        val f = editor("")
        val session = InlineSwipeSession()
        session.insert(f, "hello")
        f.type("!")
        assertTrue(session.commitTap(f, "world"))
        assertEquals("hello! world", f.text)
    }

    @Test
    fun aBarTapAfterACaretMoveInsertsAtTheNewCaret() {
        val f = editor("x")
        val session = InlineSwipeSession()
        session.insert(f, "yes")
        f.selStart = 0; f.selEnd = 0
        session.commitTap(f, "no")
        assertEquals("no x yes", f.text)
    }

    @Test
    fun ownershipDoesNotCrossSessionsOrFields() {
        val f = editor("")
        val session = InlineSwipeSession()
        session.insert(f, "word")
        f.session = 2L // the editor was closed and another entry opened with the same text
        session.commitTap(f, "other")
        assertEquals("word other", f.text)

        val g = FakeField(InlineField.CLIPBOARD_SEARCH, "")
        session.insert(g, "cat")
        val h = FakeField(InlineField.GIF_SEARCH, "cat")
        session.commitTap(h, "dog")
        assertEquals("cat dog", h.text)
        assertEquals("cat", g.text)
    }

    @Test
    fun appendOnlyBarTapReplacesTheTail() {
        val f = FakeField(InlineField.GIF_SEARCH, "funny")
        val session = InlineSwipeSession()
        session.insert(f, "cay")
        assertTrue(session.commitTap(f, "cat"))
        assertEquals("funny cat", f.text)
    }

    // ── backspace undo ──────────────────────────────────────────────────────────────

    @Test
    fun undoRemovesTheWholeInsertionIncludingItsSpaces() {
        val f = editor("bigdog", at = 3)
        val session = InlineSwipeSession()
        session.insert(f, "red")
        assertTrue(session.undo(f))
        assertEquals("bigdog", f.text)
        assertEquals(3, f.selStart)
        assertNull(session.ownedWord)
        // A second backspace is an ordinary one: nothing owned any more.
        assertFalse(session.undo(f))
    }

    @Test
    fun undoRestoresAReplacedSelection() {
        val f = editor("one two three", at = 4, end = 7)
        val session = InlineSwipeSession()
        session.insert(f, "six")
        session.undo(f)
        assertEquals("one two three", f.text)
    }

    @Test
    fun undoAfterABarTapRemovesTheTappedWord() {
        val f = editor("go")
        val session = InlineSwipeSession()
        session.insert(f, "hone")
        session.commitTap(f, "home")
        assertTrue(session.undo(f))
        assertEquals("go", f.text)
    }

    @Test
    fun undoIsRefusedOnceTheFieldChanged() {
        val f = editor("")
        val session = InlineSwipeSession()
        session.insert(f, "word")
        f.type("s")
        assertFalse(session.undo(f))
        assertEquals("words", f.text)
    }

    @Test
    fun anInactiveFieldWritesNothing() {
        val f = editor("abc")
        f.active = false
        val session = InlineSwipeSession()
        assertFalse(session.insert(f, "x"))
        assertFalse(session.commitTap(f, "x"))
        assertEquals("abc", f.text)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun appendOnlyReplaceOnlyEditsTheEnd() {
        assertEquals("ab", InlineFieldState.appendOnlyReplace("axy", 1, 3, "b"))
        assertNull(InlineFieldState.appendOnlyReplace("axy", 0, 1, "b"))
        assertNull(InlineFieldState.appendOnlyReplace("axy", 4, 3, ""))
    }
}
