package tribixbite.cleverkeys

import android.os.Handler
import android.util.Log
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * "Space before opening punctuation" (SmartAutoSpace Feature C, maintainer 2026-11-07), driven
 * through the real KeyEventHandler.sendText with a scripted editor (cursor at the end of [text])
 * and the tracker's stamps held here, as PredictionContextTracker holds them:
 *  - an opening mark straight after a swiped word, or after the closing mark attached to it,
 *    gets a space (`hello,` + `(` → `hello, (`); closing marks still attach;
 *  - a `'` after the automatic space opens a quote unless a single quote is open (closes) or the
 *    word ends in s (possessive); a letter tapped right after a spaced `'` pulls it back
 *    (`John '` + `s` → `John's`);
 *  - typing letter by letter is unchanged (`f(x)`), and the setting off restores the old rules;
 *  - an ellipsis after a swiped word chains (`wait` + `...` → `wait... `).
 */
class OpeningPunctuationSpacingTest {

    private lateinit var recv: KeyEventHandler.IReceiver
    private lateinit var conn: InputConnection
    private lateinit var handler: KeyEventHandler
    private lateinit var cfg: Config
    private val text = StringBuilder()

    // PredictionContextTracker's state, as the bridge exposes it.
    private var autoSpacePending = false
    private var autoSpaceStamp = -1
    private var wordEnd = -1
    private var apostrophe = -1

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockkObject(Config.Companion)
        cfg = mockk(relaxed = true)
        every { Config.globalConfig() } returns cfg
        cfg.double_space_to_period = false
        cfg.smart_punctuation = true
        cfg.space_before_opening_punctuation = true
        cfg.backspace_undo_swipe = false
        cfg.backspace_undo_autocorrect = false

        conn = mockk(relaxed = true)
        every { conn.getTextBeforeCursor(any(), any()) } answers { text.takeLast(firstArg<Int>()).toString() }
        every { conn.deleteSurroundingText(any(), any()) } answers {
            repeat(firstArg<Int>()) { if (text.isNotEmpty()) text.deleteCharAt(text.length - 1) }
            true
        }
        every { conn.commitText(any(), any()) } answers {
            text.append(firstArg<CharSequence>())
            true
        }
        every { conn.getSelectedText(any()) } returns null
        // Cursor at the end. The real read builds an ExtractedTextRequest, whose android.jar stub
        // constructor throws ("Stub!") in a mock test, so the tracker's read is stubbed instead.
        mockkObject(PredictionContextTracker.Companion)
        every { PredictionContextTracker.currentSelection(any()) } answers { text.length to text.length }
        every { PredictionContextTracker.currentCursorPosition(any()) } answers { text.length }

        recv = mockk(relaxed = true)
        every { recv.getHandler() } returns mockk<Handler>(relaxed = true)
        every { recv.getCurrentInputConnection() } returns conn
        every { recv.takeOwedTrailingSpace() } returns null
        every { recv.getCurrentEditorInfo() } returns mockk<android.view.inputmethod.EditorInfo>(relaxed = true).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        every { recv.wasLastSpaceAutoInserted() } answers { autoSpacePending }
        every { recv.getAutoSpaceStampedPosition() } answers { autoSpaceStamp }
        every { recv.markAutoSpacePending(any()) } answers { autoSpacePending = true; autoSpaceStamp = firstArg() }
        every { recv.markAutoSpacePendingAfterTransit(any(), any()) } answers {
            autoSpacePending = true; autoSpaceStamp = firstArg()
        }
        every { recv.setLastSpaceAutoInserted(any()) } answers {
            autoSpacePending = firstArg()
            if (!autoSpacePending) autoSpaceStamp = -1
        }
        every { recv.getWordEndStamp() } answers { wordEnd }
        every { recv.markWordEnd(any()) } answers { wordEnd = firstArg() }
        every { recv.getOpeningApostropheStamp() } answers { apostrophe }
        every { recv.markOpeningApostrophe(any()) } answers { apostrophe = firstArg() }
        every { recv.invalidateWordEnd() } answers { wordEnd = -1; apostrophe = -1 }

        handler = KeyEventHandler(recv)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    /** What a swipe commit leaves: the word and its automatic space, stamped (SuggestionHandler). */
    private fun swiped(prefix: String, word: String) {
        text.setLength(0)
        text.append(prefix).append(word).append(' ')
        autoSpacePending = true
        autoSpaceStamp = text.length
        wordEnd = -1
        apostrophe = -1
    }

    /** A swipe commit with the automatic space turned off: the word end is stamped instead. */
    private fun swipedWithoutSpace(prefix: String, word: String) {
        text.setLength(0)
        text.append(prefix).append(word)
        autoSpacePending = false
        autoSpaceStamp = -1
        wordEnd = text.length
        apostrophe = -1
    }

    private fun type(chars: String) {
        for (c in chars) handler.key_up(KeyValue.makeCharKey(c), Pointers.Modifiers.EMPTY, false)
    }

    private fun assertText(expected: String, why: String) =
        assertWithMessage(why).that(text.toString()).isEqualTo(expected)

    @Test
    fun anOpenerAfterAnAttachedCommaGetsASpace() {
        for (opener in listOf('(', '[', '"', '$', '@', '#', '“')) {
            swiped("", "hello")
            type(",$opener")
            assertText("hello, $opener", "comma attaches, then '$opener' opens with a space")
        }
    }

    @Test
    fun theAutomaticSpaceStillServesOpenersAndClosersStillAttach() {
        swiped("", "hello")
        type("(")
        assertText("hello (", "the automatic space is kept, not doubled")
        swiped("he said ", "hello")
        type(")")
        assertText("he said hello)", "a closer still eats the automatic space")
    }

    @Test
    fun withTheAutomaticSpaceOffAnOpenerAfterTheWordGetsASpace() {
        swipedWithoutSpace("", "hello")
        type("@")
        assertText("hello @", "@ after a swiped word")
        swipedWithoutSpace("", "hello")
        type(".(")
        assertText("hello. (", "the period attaches, the bracket after it is spaced")
    }

    @Test
    fun aSecondQuoteClosesAndAttaches() {
        swiped("he said \"", "hello")
        type("\"")
        assertText("he said \"hello\"", "the second \" closes")
        swiped("he said '", "hello")
        type("'")
        assertText("he said 'hello'", "the second ' closes")
    }

    @Test
    fun anApostropheOpensUnlessPossessiveAndATappedLetterPullsItBack() {
        swiped("he ", "said")
        type("'")
        assertText("he said '", "an apostrophe after a word not ending in s opens a quote")
        swiped("", "John")
        type("'s")
        assertText("John's", "a letter tapped after it makes it a possessive")
        swiped("the ", "kids")
        type("'")
        assertText("the kids'", "after a word ending in s it is the plural possessive")
    }

    @Test
    fun typingLetterByLetterIsUnchanged() {
        text.setLength(0)
        type("f(x)")
        assertText("f(x)", "no committed word, no spacing")
        text.setLength(0)
        type("me@mail")
        assertText("me@mail", "an address typed by taps stays together")
    }

    @Test
    fun theSettingOffRestoresTheOldRules() {
        cfg.space_before_opening_punctuation = false
        swiped("he ", "said")
        type("'")
        assertText("he said'", "off: an apostrophe after the automatic space always attaches")
        swiped("", "hello")
        type(",(")
        assertText("hello,(", "off: no space before an opener")
    }

    @Test
    fun anEllipsisAfterASwipedWordChains() {
        swiped("", "wait")
        type("...")
        assertText("wait... ", "each period swallows the space the previous one re-added")
    }

    /**
     * Saga 2026-11-09 (Chrome): the same chain with the REAL tracker and the editor's selection
     * callbacks delivered after each keystroke, as Android delivers them. A period deletes the
     * automatic space and commits `. `, so the editor reports the cursor after the deletion
     * first; that callback dropped the fresh stamp and the second period stayed apart
     * (`Wait. ..`). The same holds for `?!`.
     */
    @Test
    fun anEllipsisChainsWhenTheEditorReportsEveryStep() {
        val tracker = PredictionContextTracker()
        val callbacks = ArrayDeque<Int>()
        every { conn.deleteSurroundingText(any(), any()) } answers {
            repeat(firstArg<Int>()) { if (text.isNotEmpty()) text.deleteCharAt(text.length - 1) }
            callbacks.addLast(text.length)
            true
        }
        every { conn.commitText(any(), any()) } answers {
            text.append(firstArg<CharSequence>())
            callbacks.addLast(text.length)
            true
        }
        every { recv.wasLastSpaceAutoInserted() } answers { tracker.lastSpaceWasAutoInserted }
        every { recv.getAutoSpaceStampedPosition() } answers { tracker.autoSpaceStampedPosition }
        every { recv.markAutoSpacePending(any()) } answers { tracker.markAutoSpacePending(firstArg()) }
        every { recv.markAutoSpacePendingAfterTransit(any(), any()) } answers {
            tracker.markAutoSpacePending(firstArg(), secondArg())
        }
        every { recv.setLastSpaceAutoInserted(any()) } answers {
            if (firstArg()) tracker.lastSpaceWasAutoInserted = true else tracker.invalidateAutoSpacePending()
        }

        for ((word, marks, expected) in listOf(
            Triple("wait", "...", "wait... "),
            Triple("what", "?!", "what?! "),
        )) {
            text.setLength(0)
            text.append(word).append(' ')
            tracker.markAutoSpacePending(text.length) // the swipe commit, its callback delivered
            for (c in marks) {
                type(c.toString())
                while (callbacks.isNotEmpty()) tracker.onCursorPositionChanged(callbacks.removeFirst())
            }
            assertText(expected, "each mark swallows the space the previous one re-added")
        }
    }

    @Test
    fun aRealCursorMoveStillEndsTheChain() {
        val tracker = PredictionContextTracker()
        tracker.markAutoSpacePending(10, transitPosition = 8)
        tracker.onCursorPositionChanged(8)
        assertWithMessage("the transit callback is the keyboard's own")
            .that(tracker.lastSpaceWasAutoInserted).isTrue()
        tracker.onCursorPositionChanged(8)
        assertWithMessage("the same position again is a move (the transit is owed once)")
            .that(tracker.lastSpaceWasAutoInserted).isFalse()
    }
}
