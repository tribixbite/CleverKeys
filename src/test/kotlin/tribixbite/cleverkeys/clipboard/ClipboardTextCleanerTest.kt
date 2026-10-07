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
        // Leading whitespace of the text is not "trailing" and is kept.
        assertThat(clean("  indented start")).isEqualTo("  indented start")
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
        assertThat(clean("Bonjour  \nmonde　 ﻿")).isEqualTo("Bonjour\nmonde")
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
    fun shortLinesLikeAddressesAndPoemsKeepTheirBreaks() {
        assertThat(clean("John Smith\n12 Main Street\nSpringfield")).isEqualTo("John Smith\n12 Main Street\nSpringfield")
        assertThat(clean("Roses are red,\nviolets are blue")).isEqualTo("Roses are red,\nviolets are blue")
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
    fun aLongListItemIsNotJoinedToTheFollowingLine() {
        val text = "- a very long list item that goes on and on and wraps\naround onto the next line here"
        assertThat(clean(text)).isEqualTo(text)
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
        assertThat(ClipboardTextCleaner.isPreformatted(listOf("text", "  indented"))).isTrue()
    }
}
