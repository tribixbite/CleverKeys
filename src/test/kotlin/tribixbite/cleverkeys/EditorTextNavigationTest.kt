package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Test
import tribixbite.cleverkeys.EditorTextNavigation.Command
import tribixbite.cleverkeys.EditorTextNavigation.Plan

/**
 * Pure word/line boundary rules behind word_left/word_right, select_word_left/right,
 * select_word, select_line and case cycling ([EditorTextNavigation]). Offsets are UTF-16, as
 * on every InputConnection.
 */
class EditorTextNavigationTest {

    private fun whole(text: String, start: Int, end: Int = start) = EditorTextWindow.ofWholeText(text, start, end)

    @Test
    fun wordMovesSkipSeparatorsThenTheWord() {
        val t = "Hello, world!  Next"
        assertEquals(15, EditorTextNavigation.previousWordStart(t, t.length))
        assertEquals(7, EditorTextNavigation.previousWordStart(t, 15))
        assertEquals(0, EditorTextNavigation.previousWordStart(t, 7))
        assertEquals(0, EditorTextNavigation.previousWordStart(t, 0))
        assertEquals(5, EditorTextNavigation.nextWordEnd(t, 0))
        assertEquals(12, EditorTextNavigation.nextWordEnd(t, 5))
        assertEquals(t.length, EditorTextNavigation.nextWordEnd(t, 12))
        assertEquals(t.length, EditorTextNavigation.nextWordEnd(t, t.length))
    }

    @Test
    fun innerApostrophesBelongToTheWordButQuotesDoNot() {
        assertEquals(3 until 8, EditorTextNavigation.wordBounds("we don't go", 5, 5))
        assertEquals(3 until 8, EditorTextNavigation.wordBounds("we don’t go", 4, 4))
        // 'word' in single quotes: the quotes stay outside the word.
        assertEquals(1 until 5, EditorTextNavigation.wordBounds("'word'", 2, 2))
        assertEquals(0, EditorTextNavigation.previousWordStart("qu'est", 6))
    }

    @Test
    fun supplementaryLettersAndCombiningMarksAreNeverSplit() {
        // U+1D400 MATHEMATICAL BOLD CAPITAL A is a letter outside the BMP (two UTF-16 units).
        val bold = "𝐀𝐁"
        val t = "x $bold y"
        // Offset 4 lies between the two letters (offsets inside a surrogate pair are not valid).
        assertEquals(2 until 6, EditorTextNavigation.wordBounds(t, 4, 4))
        assertEquals(2, EditorTextNavigation.previousWordStart(t, 6))
        assertEquals(6, EditorTextNavigation.nextWordEnd(t, 2))
        // e + COMBINING ACUTE ACCENT stays one word.
        assertEquals(0 until 5, EditorTextNavigation.wordBounds("cafe\u0301 x", 2, 2))
    }

    @Test
    fun linesExcludeTheirBreaks() {
        val t = "a\nbc\n\nd"
        assertEquals(2 until 4, EditorTextNavigation.lineBounds(t, 3, 3))
        assertEquals(0 until 1, EditorTextNavigation.lineBounds(t, 0, 0))
        assertEquals(5 until 5, EditorTextNavigation.lineBounds(t, 5, 5))
        assertEquals(2 until 7, EditorTextNavigation.lineBounds(t, 3, 6))
    }

    @Test
    fun plansUseAbsoluteOffsetsOfAPartialWindow() {
        // Window starting at absolute 100: "...alpha beta|" read 10 chars back.
        val w = EditorTextWindow(110, 110, "alpha beta", "", " gamma", beforeComplete = false, afterComplete = true)
        assertEquals(Plan.Select(106, 106), EditorTextNavigation.plan(Command.WORD_LEFT, w))
        assertEquals(Plan.Select(116, 116), EditorTextNavigation.plan(Command.WORD_RIGHT, w))
        assertEquals(Plan.Select(106, 110), EditorTextNavigation.plan(Command.SELECT_WORD, w))
        // "alpha" reaches the cut start of the window: its true start is unknown.
        val atEdge = EditorTextWindow(103, 103, "alp", "", "ha", beforeComplete = false, afterComplete = true)
        assertEquals(Plan.Unknown, EditorTextNavigation.plan(Command.SELECT_WORD, atEdge))
        assertEquals(Plan.Unknown, EditorTextNavigation.plan(Command.SELECT_LINE, atEdge))
        // A move simply stops at the edge of what was read.
        assertEquals(Plan.Select(100, 100), EditorTextNavigation.plan(Command.WORD_LEFT, atEdge))
    }

    @Test
    fun extendingKeepsTheAnchorAndMovesTheHeadEvenForABackwardSelection() {
        // Backward selection: anchor 9, head 4 in "one two three".
        val w = whole("one two three", 9, 4)
        assertEquals(Plan.Select(9, 0), EditorTextNavigation.plan(Command.SELECT_WORD_LEFT, w))
        assertEquals(Plan.Select(9, 7), EditorTextNavigation.plan(Command.SELECT_WORD_RIGHT, w))
    }

    @Test
    fun caseTargetIsTheSelectionTheTouchingWordOrTheWordBeforeTheSpaces() {
        assertEquals(Plan.Select(2, 7), EditorTextNavigation.caseTarget(whole("a hello world", 2, 7)))
        assertEquals(Plan.Select(2, 7), EditorTextNavigation.caseTarget(whole("a hello world", 4)))
        // After a swipe's auto-space.
        assertEquals(Plan.Select(0, 5), EditorTextNavigation.caseTarget(whole("hello ", 6)))
        assertEquals(Plan.Select(0, 5), EditorTextNavigation.caseTarget(whole("hello \t ", 8)))
        // Punctuation is not skipped, and a line break is not crossed.
        assertEquals(Plan.NoTarget, EditorTextNavigation.caseTarget(whole("hello. ", 7)))
        assertEquals(Plan.NoTarget, EditorTextNavigation.caseTarget(whole("hello\n", 6)))
        assertEquals(Plan.NoTarget, EditorTextNavigation.caseTarget(whole("", 0)))
    }

    // ------------------------------------------------------------------ editor-key plans

    private fun keys(command: Command, before: String, selected: String, after: String) =
        EditorTextNavigation.keyPlan(command, before, selected, after)

    private fun send(vararg keys: EditorTextNavigation.EditorKey) = EditorTextNavigation.KeyPlan.Send(keys.toList())

    @Test
    fun selectWordKeysGoToTheWordStartOnlyWhenNotAlreadyThere() {
        val start = EditorTextNavigation.EditorKey.WORD_START
        val extend = EditorTextNavigation.EditorKey.EXTEND_TO_WORD_END
        val collapse = EditorTextNavigation.EditorKey.COLLAPSE_TO_START
        assertEquals(send(start, extend), keys(Command.SELECT_WORD, "On", "", "e two"))
        assertEquals(send(start, extend), keys(Command.SELECT_WORD, "One", "", " two"))
        assertEquals(send(extend), keys(Command.SELECT_WORD, "One ", "", "two"))
        assertEquals(send(extend), keys(Command.SELECT_WORD, "", "", "One"))
        // An inner apostrophe keeps one word; a selection covers every word it touches.
        assertEquals(send(start, extend), keys(Command.SELECT_WORD, "we do", "", "n't"))
        assertEquals(send(collapse, start, extend, extend), keys(Command.SELECT_WORD, "O", "ne tw", "o three"))
        // A cut edge does not matter: the editor finds the real boundary.
        assertEquals(send(start, extend), keys(Command.SELECT_WORD, "y".repeat(EditorTextNavigation.WORD_REACH), "", "yy"))
    }

    @Test
    fun selectWordKeysRefuseWhatTheyCannotDo() {
        assertEquals(EditorTextNavigation.KeyPlan.NoTarget, keys(Command.SELECT_WORD, "a ", "", " b"))
        assertEquals(EditorTextNavigation.KeyPlan.NoTarget, keys(Command.SELECT_WORD, "", "", ""))
        val many = (1..EditorTextNavigation.MAX_KEY_WORDS + 1).joinToString(" ") { "w" }
        assertEquals(EditorTextNavigation.KeyPlan.Unknown, keys(Command.SELECT_WORD, "", many, ""))
    }

    @Test
    fun selectLineKeysAreHomeThenShiftEndAndRefuseAMultiLineSelection() {
        val home = EditorTextNavigation.EditorKey.LINE_START
        val end = EditorTextNavigation.EditorKey.EXTEND_TO_LINE_END
        assertEquals(send(home, end), keys(Command.SELECT_LINE, "first\nsec", "", "ond"))
        assertEquals(send(EditorTextNavigation.EditorKey.COLLAPSE_TO_START, home, end), keys(Command.SELECT_LINE, "a", "bc", "d"))
        assertEquals(EditorTextNavigation.KeyPlan.Unknown, keys(Command.SELECT_LINE, "", "one\ntwo", ""))
    }
}
