package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Paged inline editing of clipboard entries too long to edit whole (todo 22, 2026-10-26).
 *
 * Page splitting is checked on small limits (so each boundary rule is visible) and once at the
 * real limits on a 520,000-char entry: every page within the limits, never cut inside a
 * surrogate pair or a CRLF, joined pages == the original. Splice math: an edit that grows or
 * shrinks a page, recomputed pages, navigation with unsaved edits, and the saved full text.
 */
class ClipboardEditPagesTest {

    private fun pages(text: String, maxChars: Int, maxLines: Int = 1_000): List<String> {
        val starts = ClipboardEditPages.pageStarts(text, maxChars = maxChars, maxLines = maxLines)
        return starts.indices.map { i ->
            val end = if (i + 1 < starts.size) starts[i + 1] else text.length
            text.substring(starts[i], end)
        }
    }

    private fun assertWellFormed(text: String, maxChars: Int, maxLines: Int) {
        val parts = pages(text, maxChars, maxLines)
        assertWithMessage("joined pages must equal the original").that(parts.joinToString("")).isEqualTo(text)
        var offset = 0
        for ((i, p) in parts.withIndex()) {
            assertWithMessage("page $i length").that(p.length).isAtMost(maxChars)
            assertWithMessage("page $i lines").that(p.count { it == '\n' }).isAtMost(maxLines)
            if (text.isNotEmpty()) assertWithMessage("page $i must not be empty").that(p).isNotEmpty()
            offset += p.length
            if (offset in 1 until text.length) {
                assertWithMessage("page $i splits a surrogate pair")
                    .that(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()).isFalse()
                assertWithMessage("page $i splits a CRLF")
                    .that(text[offset - 1] == '\r' && text[offset] == '\n').isFalse()
            }
        }
    }

    // ---------------------------------------------------------------- splitting

    @Test
    fun textWithinTheLimitIsOnePage() {
        assertThat(pages("hello world", 20)).containsExactly("hello world")
        assertThat(pages("", 20)).containsExactly("")
    }

    @Test
    fun exactlyTheCharLimitIsOnePageAndOneMoreIsTwo() {
        assertThat(pages("a".repeat(20), 20)).hasSize(1)
        assertThat(pages("a".repeat(21), 20)).hasSize(2)
    }

    @Test
    fun aCharLimitedPageEndsAfterALineBreakInTheLastPartOfTheWindow() {
        // window = 20 chars; the newline at index 17 is in the last quarter → cut after it
        val text = "aaaaaaaaaaaaaaaaa\nbbbbbbbbbb"
        assertThat(pages(text, 20)).containsExactly("aaaaaaaaaaaaaaaaa\n", "bbbbbbbbbb").inOrder()
    }

    @Test
    fun aLineBreakEarlyInTheWindowIsNotUsedAWordBoundaryIsPreferred() {
        // newline at index 2 is outside the last part; the space at 16 is inside it
        val text = "aa\nbbbbbbbbbbbbb cccccccccc"
        assertThat(pages(text, 20)).containsExactly("aa\nbbbbbbbbbbbbb ", "cccccccccc").inOrder()
    }

    @Test
    fun textWithoutBreaksIsCutAtTheCharLimit() {
        val text = "x".repeat(50)
        assertThat(pages(text, 20).map { it.length }).containsExactly(20, 20, 10).inOrder()
    }

    @Test
    fun aHardCutNeverSplitsASurrogatePair() {
        // 19 ASCII + an emoji (2 units) at 19..20: the 20-unit window would end inside it
        val text = "a".repeat(19) + "😀" + "b".repeat(5)
        val parts = pages(text, 20)
        assertThat(parts[0]).isEqualTo("a".repeat(19))
        assertThat(parts[1]).startsWith("😀")
        assertWellFormed(text, 20, 1_000)
    }

    @Test
    fun anAllEmojiTextSplitsOnCodePoints() {
        assertWellFormed("😀🎉🚀👍".repeat(40), 21, 1_000)
    }

    @Test
    fun aHardCutNeverSplitsACrlf() {
        // no break in the last quarter except a CR whose LF is just past the window
        val text = "a".repeat(19) + "\r\n" + "b".repeat(5)
        val parts = pages(text, 20)
        assertThat(parts[0]).doesNotContain("\r")
        assertWellFormed(text, 20, 1_000)
    }

    @Test
    fun crlfTextIsCutAfterTheLf() {
        val text = ("line12345\r\n").repeat(10)
        val parts = pages(text, 25)
        for (p in parts.dropLast(1)) assertThat(p).endsWith("\r\n")
        assertWellFormed(text, 25, 1_000)
    }

    @Test
    fun theLineLimitCutsAfterTheLastAllowedLine() {
        val text = "x\n".repeat(7)
        assertThat(pages(text, 1_000, maxLines = 3)).containsExactly("x\nx\nx\n", "x\nx\nx\n", "x\n").inOrder()
    }

    @Test
    fun exactlyTheLineLimitIsOnePage() {
        assertThat(pages("x\n".repeat(3), 1_000, maxLines = 3)).hasSize(1)
        assertThat(pages("x\nx\nx", 1_000, maxLines = 3)).hasSize(1)
        assertThat(pages("x\nx\nx\nx", 1_000, maxLines = 3)).hasSize(2)
    }

    @Test
    fun theFirstPageAgreesWithTheSinglePageEditorRule() {
        val big = ClipboardPreviewPolicy.EXPANDED_MAX_CHARS
        val lines = ClipboardPreviewPolicy.EXPANDED_MAX_LINES
        val cases = listOf(
            "", "short", "a".repeat(big), "a".repeat(big + 1), "x\n".repeat(lines),
            "x\n".repeat(lines + 1), "x\n".repeat(lines - 1) + "x", "😀".repeat(big / 2),
            "a".repeat(big - 1) + "😀",
        )
        for (c in cases) {
            assertWithMessage("len=${c.length}").that(ClipboardEditPages.needsPaging(c))
                .isEqualTo(!ClipboardPreviewPolicy.isEditableInline(c))
            assertWithMessage("len=${c.length}").that(ClipboardEditPages.pageStarts(c).size > 1)
                .isEqualTo(ClipboardEditPages.needsPaging(c))
        }
    }

    @Test
    fun aRealSizedEntrySplitsWithinTheRealLimitsAndRoundTrips() {
        val sb = StringBuilder()
        var i = 0
        while (sb.length < 520_000) {
            sb.append("L").append(i).append(' ').append("word ".repeat(i % 300)).append(if (i % 7 == 0) "\r\n" else "\n")
            if (i % 11 == 0) sb.append("😀🎉")
            i++
        }
        val text = sb.toString()
        assertWellFormed(text, ClipboardEditPages.PAGE_MAX_CHARS, ClipboardEditPages.PAGE_MAX_LINES)
        assertThat(ClipboardEditPages.pageStarts(text).size).isAtLeast(26)
    }

    @Test
    fun manyShortLinesAreCutByTheLineLimit() {
        val text = "ab\n".repeat(5_000)
        val parts = pages(text, ClipboardEditPages.PAGE_MAX_CHARS, ClipboardEditPages.PAGE_MAX_LINES)
        assertThat(parts.size).isEqualTo(5)
        assertWellFormed(text, ClipboardEditPages.PAGE_MAX_CHARS, ClipboardEditPages.PAGE_MAX_LINES)
    }

    // ---------------------------------------------------------------- session splice math

    private val tenPages = (0 until 10).joinToString("") { p -> "p$p".padEnd(19, '.') + "\n" }  // 10 × 20 chars

    private fun session(text: String = tenPages) = ClipboardEditPages.Session(text, maxChars = 20, maxLines = 1_000)

    @Test
    fun aSessionOpensOnTheFirstPage() {
        val s = session()
        assertThat(s.pageCount).isEqualTo(10)
        assertThat(s.pageIndex).isEqualTo(0)
        assertThat(s.pageText()).isEqualTo("p0".padEnd(19, '.') + "\n")
    }

    @Test
    fun anUneditedSessionSavesTheOriginal() {
        val s = session()
        assertThat(s.fullText(s.pageText())).isEqualTo(tenPages)
    }

    @Test
    fun savingSplicesTheEditedPageIntoTheFullText() {
        val s = session()
        s.next(s.pageText())
        s.next(s.pageText())  // page 3 (index 2)
        val edited = s.pageText().replace("p2", "EDITED-p2")
        val expected = tenPages.replace("p2", "EDITED-p2")
        assertThat(s.fullText(edited)).isEqualTo(expected)
    }

    @Test
    fun navigatingKeepsUnsavedEditsInTheWorkingCopy() {
        val s = session()
        val first = s.pageText().replace("p0", "FIRST")  // 23 chars: split into 20 + 3
        assertThat(s.next(first)).isTrue()
        assertWithMessage("next is the following page of the working copy: the overflow")
            .that(s.pageText()).isEqualTo("..\n")
        assertThat(s.pageIndex).isEqualTo(1)
        assertThat(s.first(s.pageText())).isTrue()
        assertThat(s.pageText()).startsWith("FIRST")
        assertThat(s.fullText(s.pageText())).isEqualTo(tenPages.replace("p0", "FIRST"))
    }

    @Test
    fun anEditThatGrowsAPagePastTheLimitIsRepaginatedFromThatPage() {
        val s = session()
        s.last(s.pageText())
        s.first(s.pageText())
        val grown = s.pageText() + "X".repeat(25)  // page 1 now 45 chars
        assertThat(s.next(grown)).isTrue()
        // only the edited span is re-split (20 + 20 + 5); later pages keep their boundaries
        assertThat(s.pageCount).isEqualTo(12)
        assertWithMessage("next is the following page: the overflow").that(s.pageIndex).isEqualTo(1)
        assertThat(s.pageText()).isEqualTo("X".repeat(20))
        assertThat(s.next(s.pageText())).isTrue()
        assertThat(s.pageText()).isEqualTo("XXXXX")
        assertThat(s.next(s.pageText())).isTrue()
        assertWithMessage("the old page 2 keeps its boundaries").that(s.pageText()).isEqualTo(tenPages.substring(20, 40))
        val expected = tenPages.substring(0, 20) + "X".repeat(25) + tenPages.substring(20)
        assertThat(s.working).isEqualTo(expected)
        assertThat(s.fullText(s.pageText())).isEqualTo(expected)
        // every page of the working copy is within the limits again
        for (i in 0 until s.pageCount) {
            s.goTo(i, s.pageText())
            assertThat(s.pageText().length).isAtMost(20)
        }
    }

    @Test
    fun anEditThatShrinksAPageKeepsEarlierPagesAndTheCurrentStart() {
        val s = session()
        repeat(4) { s.next(s.pageText()) }  // index 4
        val before = s.working.substring(0, 80)
        assertThat(s.next("")).isTrue()  // page 5 emptied, then next
        assertThat(s.working).isEqualTo(tenPages.substring(0, 80) + tenPages.substring(100))
        assertThat(s.working.substring(0, 80)).isEqualTo(before)
        assertWithMessage("an emptied page is dropped; next shows the text that followed it")
            .that(s.pageText()).startsWith("p5")
        assertThat(s.pageIndex).isEqualTo(4)
        assertThat(s.pageCount).isEqualTo(9)
    }

    @Test
    fun emptyingTheLastPageDropsIt() {
        val s = session()
        s.last(s.pageText())
        assertThat(s.pageIndex).isEqualTo(9)
        assertThat(s.previous("")).isTrue()
        assertThat(s.pageCount).isEqualTo(9)
        assertThat(s.pageIndex).isEqualTo(8)
        assertThat(s.working).isEqualTo(tenPages.substring(0, 180))
    }

    @Test
    fun navigationStopsAtTheEnds() {
        val s = session()
        assertThat(s.previous(s.pageText())).isFalse()
        assertThat(s.pageIndex).isEqualTo(0)
        s.last(s.pageText())
        assertThat(s.next(s.pageText())).isFalse()
        assertThat(s.pageIndex).isEqualTo(9)
        assertThat(s.goTo(42, s.pageText())).isFalse()
    }

    @Test
    fun fullTextDoesNotChangeTheSession() {
        val s = session()
        s.fullText("changed")
        assertThat(s.working).isEqualTo(tenPages)
        assertThat(s.pageCount).isEqualTo(10)
    }

    @Test
    fun othersBlankTellsWhetherTheRestOfTheEntryIsWhitespace() {
        val s = ClipboardEditPages.Session(" ".repeat(25) + "word", maxChars = 20, maxLines = 1_000)
        assertWithMessage("page 1 is blank but the rest is not").that(s.othersBlank).isFalse()
        s.next(s.pageText())
        assertThat(s.pageText()).contains("word")
        assertWithMessage("page 2 holds the only text").that(s.othersBlank).isTrue()
        s.previous("x")
        assertThat(s.othersBlank).isFalse()
    }

    @Test
    fun aRealSizedSessionRoundTripsEditsOnFirstMiddleAndLastPages() {
        val text = buildString { var i = 0; while (length < 520_000) { append("line ").append(i++).append(' ').append("y".repeat(900)).append('\n') } }
        val s = ClipboardEditPages.Session(text)
        val n = s.pageCount
        assertThat(n).isAtLeast(26)
        val p0 = "FIRST-EDIT " + s.pageText()
        s.goTo(n / 2, p0)
        val mid = s.pageText().replaceFirst("line", "MIDDLE")
        s.last(mid)
        val last = s.pageText() + "LAST-EDIT"
        val saved = s.fullText(last)
        assertThat(saved.startsWith("FIRST-EDIT line 0 ")).isTrue()
        assertThat(saved.endsWith("\nLAST-EDIT")).isTrue()
        assertThat(saved.length).isEqualTo(text.length + "FIRST-EDIT ".length + 2 + "LAST-EDIT".length)
        assertThat(saved.indexOf("MIDDLE")).isGreaterThan(0)
    }
}
