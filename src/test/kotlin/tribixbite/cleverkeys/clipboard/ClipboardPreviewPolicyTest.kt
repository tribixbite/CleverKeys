package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.clipboard.ClipboardPreviewPolicy.COLLAPSED_MAX_CHARS
import tribixbite.cleverkeys.clipboard.ClipboardPreviewPolicy.COLLAPSED_MAX_LINES
import tribixbite.cleverkeys.clipboard.ClipboardPreviewPolicy.ELLIPSIS
import tribixbite.cleverkeys.clipboard.ClipboardPreviewPolicy.EXPANDED_MAX_CHARS
import tribixbite.cleverkeys.clipboard.ClipboardPreviewPolicy.EXPANDED_MAX_LINES

/**
 * Saga 2026-10-26 (todo 22 a): rows bound an entry's FULL text, so three ~0.5 MB entries made
 * the pane's line breaking block the keyboard's main thread 46–58 s, and a tap in that window
 * was an ANR. Rows now bind a bounded preview; this pins how the preview is cut.
 */
class ClipboardPreviewPolicyTest {

    @Test
    fun shortTextIsShownWholeWithoutEllipsis() {
        val p = ClipboardPreviewPolicy.cut("hello world", maxChars = 300, maxLines = 2)

        assertThat(p.text).isEqualTo("hello world")
        assertThat(p.truncated).isFalse()
        assertThat(p.shownLength).isEqualTo(11)
    }

    @Test
    fun textAtExactlyTheCharLimitIsNotTruncated() {
        val text = "a".repeat(300)

        val p = ClipboardPreviewPolicy.cut(text, maxChars = 300, maxLines = 2)

        assertThat(p.text).isEqualTo(text)
        assertThat(p.truncated).isFalse()
    }

    @Test
    fun longTextIsCutAtTheCharLimitWithAnEllipsis() {
        val text = "abcdefghij".repeat(52_400)  // 524,000 chars, the Saga entry's length

        val p = ClipboardPreviewPolicy.cut(text, maxChars = 300, maxLines = 2)

        assertThat(p.truncated).isTrue()
        assertThat(p.shownLength).isEqualTo(300)
        assertThat(p.text).isEqualTo(text.substring(0, 300) + ELLIPSIS)
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        // 299 ASCII chars, then an emoji (2 UTF-16 units) straddling the 300-unit limit.
        val text = "a".repeat(299) + "😀" + "tail"

        val p = ClipboardPreviewPolicy.cut(text, maxChars = 300, maxLines = 2)

        assertWithMessage("the high surrogate alone must not be kept").that(p.shownLength).isEqualTo(299)
        assertThat(p.text).isEqualTo("a".repeat(299) + ELLIPSIS)
        assertThat(p.text.last().isSurrogate()).isFalse()
    }

    @Test
    fun aPairEndingExactlyAtTheLimitIsKeptWhole() {
        val text = "a".repeat(298) + "😀" + "tail"

        val p = ClipboardPreviewPolicy.cut(text, maxChars = 300, maxLines = 2)

        assertThat(p.shownLength).isEqualTo(300)
        assertThat(p.text).isEqualTo("a".repeat(298) + "😀" + ELLIPSIS)
    }

    @Test
    fun allEmojiTextIsCutOnCodePointBoundaries() {
        val text = "😀".repeat(256_000)  // the 256K-unit emoji entry

        for (limit in listOf(1, 2, 3, 299, 300, 20_000, 20_001)) {
            val p = ClipboardPreviewPolicy.cut(text, maxChars = limit, maxLines = 1)
            assertWithMessage("limit $limit").that(p.shownLength % 2).isEqualTo(0)
            assertWithMessage("limit $limit").that(p.shownLength).isAtMost(limit)
        }
    }

    @Test
    fun aLimitInsideTheFirstPairShowsNothingButTheEllipsis() {
        val p = ClipboardPreviewPolicy.cut("😀x", maxChars = 1, maxLines = 1)

        assertThat(p.shownLength).isEqualTo(0)
        assertThat(p.text).isEqualTo(ELLIPSIS)
        assertThat(p.truncated).isTrue()
    }

    @Test
    fun aLoneSurrogateIsKeptLikeAnyOtherChar() {
        // A lone high surrogate followed by an ordinary char is not a pair: nothing to protect.
        val text = "a".repeat(299) + "\uD83Dbc"

        val p = ClipboardPreviewPolicy.cut(text, maxChars = 300, maxLines = 2)

        assertThat(p.shownLength).isEqualTo(300)
    }

    @Test
    fun theLineLimitCutsBeforeTheNextLine() {
        val text = (0 until 13_100).joinToString("\n") { "L%06d abcdefghijklmnopqrstuvwxyz01234".format(it) }

        val p = ClipboardPreviewPolicy.cut(text, maxChars = 300, maxLines = 2)

        assertThat(p.truncated).isTrue()
        assertThat(p.text).isEqualTo(
            "L000000 abcdefghijklmnopqrstuvwxyz01234\nL000001 abcdefghijklmnopqrstuvwxyz01234$ELLIPSIS"
        )
        assertThat(p.text.count { it == '\n' }).isEqualTo(1)
    }

    @Test
    fun aSingleTrailingNewlineIsNotATruncation() {
        val p = ClipboardPreviewPolicy.cut("one\ntwo\n", maxChars = 300, maxLines = 2)

        assertThat(p.truncated).isFalse()
        assertThat(p.text).isEqualTo("one\ntwo\n")
    }

    @Test
    fun crlfLineEndsDropTheCarriageReturnAtTheCut() {
        val p = ClipboardPreviewPolicy.cut("one\r\ntwo\r\nthree", maxChars = 300, maxLines = 2)

        assertThat(p.text).isEqualTo("one\r\ntwo$ELLIPSIS")
    }

    @Test
    fun aCharCutRightAfterALineBreakPutsTheEllipsisOnTheLastLine() {
        // Saga 2026-10-26: 500 lines of exactly 40 chars cut at 20,000 left the ellipsis (and the
        // row's metadata after it) alone on an extra line.
        val text = "abc\r\ndef\r\nghi"

        val p = ClipboardPreviewPolicy.cut(text, maxChars = 5, maxLines = 10)

        assertThat(p.text).isEqualTo("abc$ELLIPSIS")
        assertThat(p.shownLength).isEqualTo(3)
    }

    @Test
    fun blankLinesCountAsLines() {
        val p = ClipboardPreviewPolicy.cut("\n\n\nreal text", maxChars = 300, maxLines = 2)

        // Two lines kept ("" and ""); the break that ends the kept text is not shown either.
        assertThat(p.text).isEqualTo(ELLIPSIS)
        assertThat(p.truncated).isTrue()
    }

    @Test
    fun theCutReadsOnlyTheBoundedPrefix() {
        // A CharSequence that fails if the cut walks past the char limit, i.e. the cost of a
        // preview does not grow with the entry's length.
        val limit = COLLAPSED_MAX_CHARS
        val guarded = object : CharSequence {
            override val length = 600_000
            override fun get(index: Int): Char {
                check(index <= limit) { "read index $index past the limit $limit" }
                return 'x'
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                check(endIndex <= limit + 1) { "subSequence to $endIndex past the limit" }
                return "x".repeat(endIndex - startIndex)
            }
            override fun toString(): String = error("the whole text must not be copied")
        }

        val p = ClipboardPreviewPolicy.cut(guarded, maxChars = limit, maxLines = COLLAPSED_MAX_LINES)

        assertThat(p.shownLength).isEqualTo(limit)
        assertThat(p.truncated).isTrue()
    }

    @Test
    fun rowPreviewsUseTheCollapsedAndExpandedBounds() {
        val text = "w ".repeat(300_000)

        val collapsed = ClipboardPreviewPolicy.forRow(text, expanded = false)
        val expanded = ClipboardPreviewPolicy.forRow(text, expanded = true)

        assertThat(collapsed.shownLength).isEqualTo(COLLAPSED_MAX_CHARS)
        assertThat(expanded.shownLength).isEqualTo(EXPANDED_MAX_CHARS)
        assertThat(collapsed.truncated).isTrue()
        assertThat(expanded.truncated).isTrue()
    }

    @Test
    fun theExpandedLineLimitBoundsManyShortLines() {
        val text = "x\n".repeat(100_000)

        val expanded = ClipboardPreviewPolicy.forRow(text, expanded = true)

        assertThat(expanded.text.count { it == '\n' }).isEqualTo(EXPANDED_MAX_LINES - 1)
    }

    @Test
    fun countsAreInCodePoints() {
        val text = "😀".repeat(30_000)  // 60,000 units, 30,000 code points
        val p = ClipboardPreviewPolicy.forRow(text, expanded = true)

        val (shown, total) = ClipboardPreviewPolicy.codePointCounts(text, p)

        assertThat(shown).isEqualTo(EXPANDED_MAX_CHARS / 2)
        assertThat(total).isEqualTo(30_000)
    }

    @Test
    fun inlineEditingIsOfferedOnlyWhenTheWholeEntryCanBeShown() {
        assertThat(ClipboardPreviewPolicy.isEditableInline("a".repeat(EXPANDED_MAX_CHARS))).isTrue()
        assertThat(ClipboardPreviewPolicy.isEditableInline("a".repeat(EXPANDED_MAX_CHARS + 1))).isFalse()
        // Many short lines: the expanded view would cut it, so the editor would too.
        assertThat(ClipboardPreviewPolicy.isEditableInline("x\n".repeat(EXPANDED_MAX_LINES + 1))).isFalse()
        assertThat(ClipboardPreviewPolicy.isEditableInline("")).isTrue()
    }
}
