package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The Clean bulk action's transform (maintainer request 2026-10-07): trailing whitespace goes,
 * line breaks a source inserted inside a paragraph become spaces (or vanish inside a
 * hyphenated word), and every deliberate break — blank lines, lists, short lines, code — stays.
 */
class ClipboardTextCleanerTest {

    private fun clean(text: String) = ClipboardTextCleaner.clean(text)

    @Test
    fun trailingSpacesAndTabsAreRemovedFromLinesAndTheEnd() {
        assertThat(clean("Hello  \nWorld\t \n\n\n")).isEqualTo("Hello\nWorld")
        assertThat(clean("one line with trailing tab\t")).isEqualTo("one line with trailing tab")
        // Leading whitespace is extra white space too (maintainer 2026-11-18).
        assertThat(clean("  indented start")).isEqualTo("indented start")
    }

    @Test
    fun crlfLoneCrAndUnicodeSeparatorsBecomeNewlines() {
        assertThat(clean("Hello  \r\nWorld\r")).isEqualTo("Hello\nWorld")
        assertThat(clean("Hello\rWorld")).isEqualTo("Hello\nWorld")
        assertThat(clean("Hello World")).isEqualTo("Hello\nWorld")
        assertThat(clean("Hello World")).isEqualTo("Hello\n\nWorld")
        assertThat(clean("Hello\u0085World")).isEqualTo("Hello\nWorld")
    }

    @Test
    fun unicodeSpacesAtLineEndsAreTrailingWhitespace() {
        assertThat(clean("Bonjour  \nmonde　 ﻿")).isEqualTo("Bonjour monde")  // mid-sentence, so the break goes too
        // Inside a line they are content, not trailing whitespace.
        assertThat(clean("10 km")).isEqualTo("10 km")
    }

    @Test
    fun breaksInsertedInsideAParagraphBecomeSpaces() {
        val wrapped = "The quick brown fox jumps over the lazy\n" +
            "dog and keeps running through the field\n" +
            "until evening."
        assertThat(clean(wrapped)).isEqualTo(
            "The quick brown fox jumps over the lazy dog and keeps running through the field until evening."
        )
    }

    @Test
    fun crlfWrappedParagraphFromAPdfIsJoined() {
        val pdf = "Clipboard managers keep a history of copied\r\n" +
            "text so that earlier items can be pasted\r\n" +
            "again later.\r\n"
        assertThat(clean(pdf)).isEqualTo(
            "Clipboard managers keep a history of copied text so that earlier items can be pasted again later."
        )
    }

    @Test
    fun blankLinesAreParagraphBreaksAndStay() {
        val text = "First paragraph line that is long enough to wrap\n" +
            "here.\n" +
            "   \n" +
            "Second paragraph line that is also long enough\n" +
            "to wrap."
        assertThat(clean(text)).isEqualTo(
            "First paragraph line that is long enough to wrap here.\n\n" +
                "Second paragraph line that is also long enough to wrap."
        )
    }

    @Test
    fun aShortLineEndsAParagraphEvenWithoutABlankLine() {
        val text = "This first paragraph wraps over two lines of\n" +
            "text.\n" +
            "A second paragraph starts on the next line here."
        assertThat(clean(text)).isEqualTo(
            "This first paragraph wraps over two lines of text.\n" +
                "A second paragraph starts on the next line here."
        )
    }

    @Test
    fun shortLinesLikeAddressesAndGreetingsKeepTheirBreaks() {
        assertThat(clean("John Smith\n12 Main Street\nSpringfield")).isEqualTo("John Smith\n12 Main Street\nSpringfield")
        assertThat(clean("Dear Sam,\nThanks for the photos.")).isEqualTo("Dear Sam,\nThanks for the photos.")
        assertThat(clean("Sincerely\nAlex")).isEqualTo("Sincerely\nAlex")
    }

    /**
     * Maintainer 2026-11-18: "it should find any line breaks that are mid-sentence and remove
     * them". A break the line before does not end a sentence at, followed by a lowercase
     * letter, is mid-sentence however short the line is (a narrow phone screen or terminal
     * wraps well under the old 20-character floor).
     */
    @Test
    fun aShortLineBrokenMidSentenceIsJoined() {
        assertThat(clean("Hello world\nhow are you today")).isEqualTo("Hello world how are you today")
        assertThat(clean("Roses are red,\nviolets are blue")).isEqualTo("Roses are red, violets are blue")
        assertThat(clean("We tried it\ntwice and then\nit worked.")).isEqualTo("We tried it twice and then it worked.")
        // A sentence end before an uppercase start is a deliberate line: kept.
        assertThat(clean("It worked.\nThen we left.")).isEqualTo("It worked.\nThen we left.")
    }

    /** A prose line ending in a semicolon or colon is not code; the rest of the clip is cleaned. */
    @Test
    fun aSemicolonOrColonAtALineEndDoesNotStopCleaning() {
        assertThat(clean("We tried it twice;\nthe second time it worked.\nNext   time  we  know."))
            .isEqualTo("We tried it twice; the second time it worked.\nNext time we know.")
        assertThat(clean("The answer is simple:\nwe stay home tonight."))
            .isEqualTo("The answer is simple: we stay home tonight.")
    }

    /**
     * A clip copied from a terminal or chat app where EVERY line, the first too, carries the
     * same indent: the shared indent goes and the wrapped prose is joined (it used to read as
     * code, "Nothing to clean").
     */
    @Test
    fun aClipWhoseEveryLineIsIndentedIsCleaned() {
        val clip = "  The tag is blocked only on your\n" +
            "  three items. The code itself is\n" +
            "  green, and the suites pass."
        assertThat(clean(clip)).isEqualTo("The tag is blocked only on your three items. The code itself is green, and the suites pass.")
        // A short line before an indented continuation is still a wrap when it is mid-sentence.
        assertThat(clean("Short line here\n   continues on the next")).isEqualTo("Short line here continues on the next")
    }

    /** Extra white space: blank lines at the ends go, runs of blank lines become one, tabs become a space. */
    @Test
    fun extraBlankLinesAndInnerTabsAreRemoved() {
        assertThat(clean("\n\n  First paragraph.\n\n\n\nSecond paragraph.  \n\n"))
            .isEqualTo("First paragraph.\n\nSecond paragraph.")
        assertThat(clean("Name:\tSam  \t Lee")).isEqualTo("Name: Sam Lee")
    }

    @Test
    fun aWordHyphenatedAtTheLineEndIsRejoinedWithoutTheHyphen() {
        assertThat(clean("This sentence contains a hyphenated exam-\nple of a broken word."))
            .isEqualTo("This sentence contains a hyphenated example of a broken word.")
        // Soft hyphen (U+00AD) is always a break opportunity, never content.
        assertThat(clean("This sentence contains a hyphenated exam­\nple of a broken word."))
            .isEqualTo("This sentence contains a hyphenated example of a broken word.")
    }

    @Test
    fun realHyphensAreKeptWhenTheLineBreaksAtThem() {
        // The token already holds a hyphen: a compound broken at one of its hyphens.
        assertThat(clean("The team finally shipped a state-of-\nthe-art design after many weeks."))
            .isEqualTo("The team finally shipped a state-of-the-art design after many weeks.")
        // An uppercase or digit continuation is a compound name or code, not a split word.
        assertThat(clean("Yesterday our new colleague Jean-\nPaul arrived late to the meeting today."))
            .isEqualTo("Yesterday our new colleague Jean-Paul arrived late to the meeting today.")
        assertThat(clean("The vaccine studies focused mostly on COVID-\n19 and the related variants."))
            .isEqualTo("The vaccine studies focused mostly on COVID-19 and the related variants.")
        // A dash set off by a space is punctuation: an ordinary space join.
        assertThat(clean("The answer was simple and obvious to all -\nwe stay home tonight instead."))
            .isEqualTo("The answer was simple and obvious to all - we stay home tonight instead.")
    }

    @Test
    fun listLinesKeepTheirBreaks() {
        val list = "Shopping list for the weekend trip to the lake:\n" +
            "- apples\n" +
            "* bread and butter\n" +
            "• cheese\n" +
            "1. first numbered item\n" +
            "2) second numbered item\n" +
            "(3) third numbered item\n" +
            "a) lettered item\n" +
            "iv. roman item\n" +
            "[x] done checkbox"
        assertThat(clean(list)).isEqualTo(list)
    }

    @Test
    fun aListItemWrappedMidSentenceIsJoined() {
        assertThat(clean("- a very long list item that goes on and on and wraps\naround onto the next line here"))
            .isEqualTo("- a very long list item that goes on and on and wraps around onto the next line here")
        // A new sentence after a finished item is not part of it.
        val text = "- apples and pears.\nBread is on the other list."
        assertThat(clean(text)).isEqualTo(text)
    }

    @Test
    fun anIndentedListAfterAnUnindentedLeadLineIsCleaned() {
        // Device report 2026-11-18: an answer copied from a chat app as an unindented lead line
        // and a list whose markers and continuations all carry the same 6-space indent read as
        // code, so Clean said "Nothing to clean".
        val text = "Why this plan works well for the trip:\n" +
            "      1. The ferry leaves at nine, which gives us a\n" +
            "      full morning on the island before the rain.\n" +
            "      2. Under the new timetable the last boat back\n" +
            "      (the 18:40 from the north pier) is guaranteed."
        assertThat(clean(text)).isEqualTo(
            "Why this plan works well for the trip:\n" +
                "1. The ferry leaves at nine, which gives us a full morning on the island before the rain.\n" +
                "2. Under the new timetable the last boat back (the 18:40 from the north pier) is guaranteed."
        )
        // Nesting below the list's own indent survives.
        assertThat(clean("Packing:\n    - bags for the long weekend trip to\n    the lake\n      - snacks"))
            .isEqualTo("Packing:\n- bags for the long weekend trip to the lake\n  - snacks")
    }

    @Test
    fun chatAndLogMessagesKeepTheirBreaks() {
        // Audit 2026-11-18: a message without final punctuation was joined to the next one.
        for (text in listOf(
            "[10:31] Alice: are you free later this\n[10:32] Bob: sure",
            "18/11/2026, 10:31 - Alice: are you free later this\n18/11/2026, 10:32 - Bob: sure",
            "<alice> hey did you see the new release notes they posted\n<bob> yes it looks good",
            "Alice: are you free later this evening for a coffee\nBob: sure what time works",
        )) assertWithMessage(text).that(clean(text)).isEqualTo(text)
        // A message that wraps still joins its own continuation.
        assertThat(clean("[10:31] Alice: are you free later this\nevening for a coffee\n[10:32] Bob: sure"))
            .isEqualTo("[10:31] Alice: are you free later this evening for a coffee\n[10:32] Bob: sure")
    }

    @Test
    fun oneCodeLikeLineDoesNotStopCleaningProse() {
        // Audit 2026-11-18: any one of these used to mark the whole clip as code.
        assertThat(clean("Total = five apples and two pears which is more than\nwe needed  for the pie"))
            .isEqualTo("Total = five apples and two pears which is more than we needed for the pie")
        assertThat(clean("$ 5 off your next order if you sign up today and use\nthe code at checkout"))
            .isEqualTo("$ 5 off your next order if you sign up today and use the code at checkout")
        assertThat(clean("Bring water;\nsnacks;\nand a hat")).isEqualTo("Bring water; snacks; and a hat")
        assertThat(clean("<alice> hey did you see the new release notes they\nposted  yesterday"))
            .isEqualTo("<alice> hey did you see the new release notes they posted yesterday")
        // Real code is still code.
        for (code in listOf("$ ls -la\ntotal 12", "x = 1\ny = 2", "foo();\nbar();", "<p>Hello\nthere</p>")) {
            assertWithMessage(code).that(clean(code)).isEqualTo(code)
        }
    }

    @Test
    fun aClipWhoseEveryLineIsTabIndentedIsCleaned() {
        // Audit 2026-11-18: the shared indent was removed only when it was spaces.
        assertThat(clean("\tthis whole message was indented with a tab by the\n\tmail client and should be joined"))
            .isEqualTo("this whole message was indented with a tab by the mail client and should be joined")
        // Deeper tabs inside a tab-indented clip are still code.
        val nested = "\tif (ready)\n\t\tstart()"
        assertThat(clean(nested)).isEqualTo(nested)
    }

    @Test
    fun shortLinesBrokenMidSentenceAreJoined() {
        // Audit 2026-11-18: these kept their breaks because the next line starts uppercase or a digit.
        assertThat(clean("We visited New\nYork last week")).isEqualTo("We visited New York last week")
        assertThat(clean("Please call Mike\nJohnson when you\nget this")).isEqualTo("Please call Mike Johnson when you get this")
        assertThat(clean("the total came to\n42 dollars")).isEqualTo("the total came to 42 dollars")
        assertThat(clean("We met on March 3,\n2026 at noon")).isEqualTo("We met on March 3, 2026 at noon")
        assertThat(clean("I sent it to the\nOffice yesterday")).isEqualTo("I sent it to the Office yesterday")
        // Deliberate breaks stay: greetings, addresses, unpunctuated lists, names.
        for (text in listOf(
            "Dear John,\nThanks for the update.",
            "John Smith\n123 Main St\nSpringfield, IL",
            "Buy milk today\nCall the bank\nPick up kids",
            "Hello\nWorld",
        )) assertWithMessage(text).that(clean(text)).isEqualTo(text)
    }

    @Test
    fun aHardWrappedParagraphJoinsAtSentenceEndsToo() {
        val wrapped = "This is a long paragraph that was hard wrapped at seventy two chars.\n" +
            "Then it continues with another sentence that also wraps at the end of\n" +
            "the line and finishes here."
        assertThat(clean(wrapped)).isEqualTo(
            "This is a long paragraph that was hard wrapped at seventy two chars. Then it continues " +
                "with another sentence that also wraps at the end of the line and finishes here."
        )
        // Separate full-length messages that each end a sentence are not a wrapped paragraph.
        val messages = "I will be there around eight tonight if the traffic is okay.\n" +
            "Please save me a seat near the front of the room if you can."
        assertThat(clean(messages)).isEqualTo(messages)
    }

    @Test
    fun markdownHeadingsQuotesTablesAndRulesKeepTheirBreaks() {
        val text = "# Title of the document that is long enough\n" +
            "Body text that follows the heading line here.\n" +
            "> A quoted line that is long enough to wrap\n" +
            "| col | col |\n" +
            "---\n" +
            "More text after the horizontal rule line."
        assertThat(clean(text)).isEqualTo(text)
    }

    @Test
    fun codeKeepsEveryLineBreakButLosesTrailingWhitespace() {
        assertThat(clean("fun main() {\n    println(\"hi\")   \n}\n")).isEqualTo("fun main() {\n    println(\"hi\")\n}")
        val semicolons = "int x = computeTheValueFromSomewhere(a, b);\nint y = computeAnotherValueFromElsewhere(c);"
        assertThat(clean(semicolons)).isEqualTo(semicolons)
        val shell = "$ ./gradlew assembleRelease --no-daemon --stacktrace\nBUILD SUCCESSFUL in 3m 12s and more output"
        assertThat(clean(shell)).isEqualTo(shell)
        val markup = "<div class=\"container\">some content that is long enough\nto wrap in the source</div>"
        assertThat(clean(markup)).isEqualTo(markup)
        val fenced = "```\nconst value = computeSomethingRatherLongHere(1)\nconsole.log(value)\n```"
        assertThat(clean(fenced)).isEqualTo(fenced)
    }

    @Test
    fun anIndentedLineMarksTheTextPreformatted() {
        val text = "Here is a paragraph line that is long enough to wrap\n\tand an indented line that follows it"
        assertThat(clean(text)).isEqualTo(text)
    }

    @Test
    fun terminalOutputWithAHangingIndentIsJoined() {
        // Copied from a terminal (maintainer report 2026-11-11): every wrapped line is indented
        // by three spaces. Indentation alone used to mark it as code, so nothing was cleaned.
        val terminal = "Typing: the motion-on run used less power than both off\n" +
            "   runs. The remaining cost is below the run-to-run\n" +
            "   noise: the display alone varied by about 300 mW between\n" +
            "   runs at the same pinned brightness."
        assertThat(clean(terminal)).isEqualTo(
            "Typing: the motion-on run used less power than both off runs. The remaining cost is " +
                "below the run-to-run noise: the display alone varied by about 300 mW between runs at " +
                "the same pinned brightness."
        )
    }

    @Test
    fun aListItemJoinsItsIndentedContinuationOnly() {
        val item = "  - Frames: the on run still drew about twice as many frames as\n" +
            "    the off runs, because the script pauses between bursts.\n" +
            "  - Idle: the scene moves for about twenty seconds of a minute."
        // The indent every line shares is extra white space and goes.
        assertThat(clean(item)).isEqualTo(
            "- Frames: the on run still drew about twice as many frames as the off runs, because " +
                "the script pauses between bursts.\n" +
                "- Idle: the scene moves for about twenty seconds of a minute."
        )
    }

    @Test
    fun spaceIndentedCodeKeepsEveryBreak() {
        val python = "def compute_the_value(alpha, beta, gamma):\n" +
            "    first_value = alpha * 2 + gamma_offset\n" +
            "    return first_value + beta * gamma_offset"
        assertThat(clean(python)).isEqualTo(python)
        // A fragment from inside a block: it starts indented.
        val fragment = "        result = compute_value(alpha, beta)\n        return result + offset_value"
        assertThat(clean(fragment)).isEqualTo(fragment)
        val yaml = "services:\n  keyboard:\n    image: cleverkeys/build-image-latest"
        assertThat(clean(yaml)).isEqualTo(yaml)
    }

    @Test
    fun runsOfSpacesInsideProseBecomeOne() {
        assertThat(clean("One sentence.  Two  words   here.")).isEqualTo("One sentence. Two words here.")
        // Double spacing between sentences does not make prose a table.
        val doubleSpaced = "This paragraph was typed.  With two spaces after\n" +
            "every period.  It wraps over lines as well.  Done."
        assertThat(clean(doubleSpaced)).isEqualTo(
            "This paragraph was typed. With two spaces after every period. It wraps over lines as well. Done."
        )
    }

    @Test
    fun aSpaceAlignedTableKeepsItsLayout() {
        val table = "Name      Size   Date\nalpha.txt 12 KB  Monday\nbeta.txt  4 KB   Tuesday"
        assertThat(clean(table)).isEqualTo(table)
    }

    @Test
    fun breaksNextToAUrlAreKept() {
        val text = "Read the full announcement on the project site at https://example.com/news\n" +
            "and tell me what you think about it."
        assertThat(clean(text)).isEqualTo(text)
        // A URL that ends in a hyphen is never "dehyphenated" into a different address.
        val hyphenUrl = "The details of the change are described at https://example.com/some-\npath for reference."
        assertThat(clean(hyphenUrl)).isEqualTo(hyphenUrl)
        val leading = "The new documentation for this release now lives at\nwww.example.com/docs/release-notes"
        assertThat(clean(leading)).isEqualTo(leading)
    }

    @Test
    fun joinsNeverLeaveDoubleSpaces() {
        val text = "A wrapped line that ends with several trailing spaces   \n and continues after one leading space here"
        assertThat(clean(text)).isEqualTo(
            "A wrapped line that ends with several trailing spaces and continues after one leading space here"
        )
        assertThat(clean(text)).doesNotContain("  ")
    }

    @Test
    fun hanAndKanaWrapsJoinWithoutASpace() {
        val chinese = "这是一个很长的中文句子用于测试自动换行的处理是否正确\n并且不会插入多余的空格"
        assertThat(clean(chinese)).isEqualTo("这是一个很长的中文句子用于测试自动换行的处理是否正确并且不会插入多余的空格")
    }

    @Test
    fun alreadyCleanTextIsReturnedUnchanged() {
        for (text in listOf("Already clean text.", "Two\n\nparagraphs", "- a\n- b", "x")) {
            assertWithMessage("'$text' needs no cleaning").that(clean(text)).isEqualTo(text)
        }
    }

    @Test
    fun cleaningIsIdempotent() {
        val samples = listOf(
            "The quick brown fox jumps over the lazy\ndog and keeps running through the field\nuntil evening.  \n",
            "This sentence contains a hyphenated exam-\nple of a broken word.",
            "fun main() {\n    println(\"hi\")   \n}\n",
            "Shopping list for the weekend trip to the lake:\n- apples\n- bread",
            "Hello\r\n\r\nWorld \r\n",
            "Typing: the motion-on run used less power than both off\n   runs. The remaining cost is below\n   noise.",
            "One sentence.  Two  words.",
            "my friend\nand I went to see\nBob",
            "  Hello world\n  how are you\n\n\n\nFine.",
            "Roses are red,\nviolets are blue",
            "[10:31] Alice: are you free later this\nevening\n[10:32] Bob: sure",
            "We visited New\nYork last week",
            "\tthis whole message was indented with a tab by the\n\tmail client",
        )
        for (text in samples) {
            val once = clean(text)
            assertWithMessage("clean must be idempotent for '$text'").that(clean(once)).isEqualTo(once)
        }
    }

    @Test
    fun structuralAndPreformattedHelpersMatchTheDocumentedRules() {
        assertThat(ClipboardTextCleaner.isStructural("- item")).isTrue()
        assertThat(ClipboardTextCleaner.isStructural("12. item")).isTrue()
        assertThat(ClipboardTextCleaner.isStructural("did. not a list")).isFalse()
        assertThat(ClipboardTextCleaner.isStructural("e.g. not a list")).isFalse()
        assertThat(ClipboardTextCleaner.isPreformatted(listOf("plain", "prose"))).isFalse()
        assertThat(ClipboardTextCleaner.isPreformatted(listOf("x = 1;"))).isTrue()
        assertThat(ClipboardTextCleaner.isPreformatted(listOf("Text.", "  Indented"))).isTrue()
        // An indented lowercase continuation of an unfinished line is a wrap, not code.
        assertThat(ClipboardTextCleaner.isPreformatted(listOf("text", "  indented"))).isFalse()
        // One prose line ending in a semicolon is not code; two are.
        assertThat(ClipboardTextCleaner.isPreformatted(listOf("we tried;", "it worked"))).isFalse()
        assertThat(ClipboardTextCleaner.isPreformatted(listOf("a();", "b();"))).isTrue()
    }
}
