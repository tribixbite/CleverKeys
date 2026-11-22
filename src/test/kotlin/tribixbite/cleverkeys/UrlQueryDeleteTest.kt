package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [UrlQueryDelete]: in a URL's query a backward word delete removes exactly one key/value pair
 * (maintainer request 2026-11-22), and the link stays valid after every press.
 */
class UrlQueryDeleteTest {

    /** Delete at the `|` in [marked]; returns the text with the caret marked again, or null. */
    private fun press(marked: String, beforeComplete: Boolean = true, afterComplete: Boolean = true): String? {
        val caret = marked.indexOf('|')
        val text = marked.removeRange(caret, caret + 1)
        val range = UrlQueryDelete.range(text, caret, beforeComplete, afterComplete) ?: return null
        return text.substring(0, range.first) + "|" + text.substring(range.last + 1)
    }

    @Test
    fun theLastPairGoesWithItsAmpersandThenTheQueryWithItsQuestionMark() {
        assertEquals("https://x.example/p?a=1&b=2|", press("https://x.example/p?a=1&b=2&c=3|"))
        assertEquals("https://x.example/p?a=1|", press("https://x.example/p?a=1&b=2|"))
        assertEquals("https://x.example/p|", press("https://x.example/p?a=1|"))
    }

    @Test
    fun aTrackingLinkIsCleanedPairByPair() {
        var marked = "https://shop.example/item?id=42&utm_source=news&utm_medium=email&utm_campaign=fall|"
        val steps = ArrayList<String>()
        while (true) {
            marked = press(marked) ?: break
            steps += marked
        }
        assertEquals(
            listOf(
                "https://shop.example/item?id=42&utm_source=news&utm_medium=email|",
                "https://shop.example/item?id=42&utm_source=news|",
                "https://shop.example/item?id=42|",
                "https://shop.example/item|",
            ),
            steps,
        )
    }

    @Test
    fun aCaretInsideAPairRemovesTheWholePair() {
        assertEquals("https://x.example/p?a=1&|b=2", press("https://x.example/p?a=1&ut|m=xyz&b=2"))
        // The first pair keeps the '?' for the pair after it.
        assertEquals("https://x.example/p?|b=2", press("https://x.example/p?a=|1&b=2"))
        // The last pair, caret inside it.
        assertEquals("https://x.example/p?a=1|", press("https://x.example/p?a=1&b|=2"))
    }

    @Test
    fun aCaretBetweenPairsRemovesThePairBeforeIt() {
        // Before a '&': the pair it ends.
        assertEquals("https://x.example/p?a=1&|c=3", press("https://x.example/p?a=1&b=2|&c=3"))
        assertEquals("https://x.example/p?|b=2", press("https://x.example/p?a=1|&b=2"))
        // After a '&': still the pair before it, not the one after.
        assertEquals("https://x.example/p?a=1&|c=3", press("https://x.example/p?a=1&b=2&|c=3"))
        assertEquals("https://x.example/p?|b=2&c=3", press("https://x.example/p?a=1&|b=2&c=3"))
    }

    @Test
    fun aCaretRightAfterTheQuestionMarkRemovesTheFirstPair() {
        assertEquals("https://x.example/p?|b=2", press("https://x.example/p?|a=1&b=2"))
        assertEquals("https://x.example/p|", press("https://x.example/p?|a=1"))
    }

    @Test
    fun emptyPairsAndATrailingAmpersandGoOneAtATime() {
        assertEquals("https://x.example/p?a=1|", press("https://x.example/p?a=1&|"))
        assertEquals("https://x.example/p|", press("https://x.example/p?|"))
        assertEquals("https://x.example/p?a=1&|", press("https://x.example/p?a=1&&b=2|"))
        assertEquals("https://x.example/p?a=1&|b=2", press("https://x.example/p?a=1&|&b=2"))
    }

    @Test
    fun theFragmentGoesWholeBeforeAnyPair() {
        assertEquals("https://x.example/p?a=1&b=2|", press("https://x.example/p?a=1&b=2#:~:text=some%20quote|"))
        assertEquals("https://x.example/p?a=1&b=2|", press("https://x.example/p?a=1&b=2#sec|tion"))
        // No query at all: the fragment still goes as one.
        assertEquals("https://x.example/page|", press("https://x.example/page#section|"))
        // A '?' or '&' inside the fragment is the fragment's.
        assertEquals("https://x.example/app|", press("https://x.example/app#/route?x=1&y=2|"))
        // Caret right before the '#': the last pair of the query.
        assertEquals("https://x.example/p?a=1|#top", press("https://x.example/p?a=1&b=2|#top"))
    }

    @Test
    fun spacesAfterTheLinkGoWithItsLastPair() {
        assertEquals("see https://x.example/p?a=1|", press("see https://x.example/p?a=1&b=2 |"))
        assertEquals("https://x.example/p|", press("https://x.example/p?a=1\n|"))
        // Text after the caret is not the link's.
        assertEquals("go https://x.example/p?a=1| later", press("go https://x.example/p?a=1&b=2 | later"))
    }

    @Test
    fun theLinkCanSitInsideBracketsOrQuotesAndUseAnyScheme() {
        assertEquals("(https://x.example/p?a=1|)", press("(https://x.example/p?a=1&b=2|)"))
        assertEquals("\"www.example.com/s?q=red|", press("\"www.example.com/s?q=red&page=2|"))
        assertEquals(
            "market://details?id=com.example|",
            press("market://details?id=com.example&referrer=utm_source%3Dads|"),
        )
        assertEquals("HTTPS://X.EXAMPLE/p?a=1|", press("HTTPS://X.EXAMPLE/p?a=1&b=2|"))
    }

    @Test
    fun punctuationAndAnUnbalancedCloserAfterTheLinkAreNotTheLinks() {
        assertEquals("Read https://x.example/p?a=1|.", press("Read https://x.example/p?a=1&b=2|."))
        assertEquals("<https://x.example/p?a=1|>", press("<https://x.example/p?a=1&b=2|>"))
        assertEquals("\u201chttps://x.example/p?a=1|\u201d", press("\u201chttps://x.example/p?a=1&b=2|\u201d"))
        // A closer the link opened is the link's (a Wikipedia title in a value).
        assertEquals("https://x.example/w?a=1|", press("https://x.example/w?a=1&title=Foo_(bar)|"))
        // A caret after the trimmed punctuation is outside the link.
        assertNull(press("(https://x.example/p?a=1&b=2)|"))
        assertNull(press("Read https://x.example/p?a=1&b=2. |"))
    }

    @Test
    fun aLinkInsideAQueryValueIsPartOfThatValue() {
        // The first scheme starts the link; '?' and '&' of the outer query delimit.
        assertEquals(
            "https://r.example/out?u=https://x.example/p|",
            press("https://r.example/out?u=https://x.example/p&sig=abc|"),
        )
    }

    @Test
    fun outsideAUrlQueryTheOrdinaryWordRuleApplies() {
        assertNull("plain words", press("hello world|"))
        assertNull("a path, no query", press("https://x.example/a/b/c|"))
        assertNull("caret in the path before the query", press("https://x.example/a/b|?x=1"))
        assertNull("key=value text that is not a link", press("a=1&b=2|"))
        assertNull("no scheme and no www.", press("example.com/?q=red&page=2|"))
        assertNull("a scheme without '//'", press("mailto:me@x.example?subject=hi&body=yo|"))
        assertNull("caret before the link", press("|https://x.example/p?a=1"))
        assertNull("only spaces", press("   |"))
        assertNull("empty", press("|"))
    }

    @Test
    fun aLinkCutByTheReadIsLeftToTheOrdinaryRule() {
        // Its start was not read: the scheme seen may be a query value's.
        assertNull(press("x.example/p?a=1&b=2|", beforeComplete = false))
        // Its rest was not read: whether a later pair follows is unknown.
        assertNull(press("https://x.example/p?a=1&b=|2", afterComplete = false))
        // A cut far from the link changes nothing.
        assertEquals("cut https://x.example/p?a=1| more", press("cut https://x.example/p?a=1&b=2| more", false, false))
    }

    @Test
    fun anEditorWindowGivesAbsoluteOffsetsAndASelectionGivesNone() {
        val window = EditorTextWindow(
            selStart = 1_000, selEnd = 1_000,
            before = "go https://x.example/p?a=1&b=2", selected = "", after = " now",
            beforeComplete = false, afterComplete = true,
        )
        val base = 1_000 - window.before.length
        assertEquals(base + "go https://x.example/p?a=1".length until 1_000, UrlQueryDelete.plan(window))
        val selection = window.copy(selEnd = 1_002, selected = " n", after = "ow")
        assertNull(UrlQueryDelete.plan(selection))
    }
}
