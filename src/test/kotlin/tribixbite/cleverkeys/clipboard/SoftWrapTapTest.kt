package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [SoftWrapTap] (maintainer 2026-11-26, clipboard editor): "tapping the end of a line puts the
 * cursor BEFORE the last character of that line (for wraparounds like urls)".
 *
 * The platform does that on purpose: `Layout.getOffsetForHorizontal` never returns a line's end
 * offset unless it is the last line (AOSP Layout.java, `max = getOffsetToLeftRightOf(lineEnd…)`),
 * because on a soft-wrapped line that offset is also the next line's first, where TextView draws
 * the caret. A line that wraps at a space loses nothing (the space hangs past the edge, so
 * "before the last character" is after the visible text); a line that wraps INSIDE a word — a
 * URL, a long word, CJK, which breaks between any two ideographs — loses its last character.
 * A tap past that character's midpoint now goes after it. Pure tier; the real-layout taps are
 * `ClipboardSoftWrapEditTest` (instrumented).
 */
class SoftWrapTapTest {

    /**
     * `https://github.com/rust` | `desk/rustdesk-server` — the maintainer's URL wrapped inside the
     * org segment. Line 0 ends at 23; its last character `t` spans x 300..310.
     */
    private val urlLine = SoftWrapTap.LineEnd(
        lineEnd = 23, isLastLine = false, lastChar = 't', clampOffset = 22,
        leadingX = 300f, trailingX = 310f, rtl = false, directionsAgree = true,
    )

    @Test
    fun aTapPastTheLastCharactersMidpointGoesAfterIt() {
        assertWithMessage("past the glyph's end").that(SoftWrapTap.offsetForTap(22, 312f, urlLine)).isEqualTo(23)
        assertWithMessage("on its trailing half").that(SoftWrapTap.offsetForTap(22, 306f, urlLine)).isEqualTo(23)
        assertWithMessage("exactly on the midpoint").that(SoftWrapTap.offsetForTap(22, 305f, urlLine)).isEqualTo(23)
    }

    @Test
    fun aTapOnTheLastCharactersLeadingHalfStaysBeforeIt() {
        assertThat(SoftWrapTap.offsetForTap(22, 304f, urlLine)).isEqualTo(22)
    }

    @Test
    fun aTapElsewhereOnTheLineKeepsThePlatformsChoice() {
        assertThat(SoftWrapTap.offsetForTap(10, 140f, urlLine)).isEqualTo(10)
        assertWithMessage("even far right, when the platform chose another offset")
            .that(SoftWrapTap.offsetForTap(21, 312f, urlLine)).isEqualTo(21)
    }

    @Test
    fun aLineWrappedAtASpaceKeepsTheCaretAfterTheVisibleText() {
        // "meet me at the " | "station": the hanging space is invisible; before it is the end.
        val spaced = urlLine.copy(lastChar = ' ')
        assertThat(SoftWrapTap.offsetForTap(22, 400f, spaced)).isEqualTo(22)
        assertWithMessage("a no-break space hangs the same way")
            .that(SoftWrapTap.offsetForTap(22, 400f, urlLine.copy(lastChar = ' '))).isEqualTo(22)
    }

    @Test
    fun aLineEndedByABreakKeepsTheCaretBeforeTheBreak() {
        assertThat(SoftWrapTap.offsetForTap(22, 400f, urlLine.copy(lastChar = '\n'))).isEqualTo(22)
        assertThat(SoftWrapTap.offsetForTap(22, 400f, urlLine.copy(lastChar = ' '))).isEqualTo(22)
    }

    @Test
    fun theLastLineIsLeftToThePlatform() {
        // There the platform already reaches the end offset itself.
        assertThat(SoftWrapTap.offsetForTap(42, 400f, urlLine.copy(isLastLine = true, lineEnd = 43, clampOffset = 43))).isEqualTo(42)
    }

    @Test
    fun cjkWrappedBetweenIdeographsGetsTheSameCorrection() {
        // "東京都渋谷区神" | "南…": CJK lines break between any two characters, no space at all.
        val cjk = SoftWrapTap.LineEnd(
            lineEnd = 7, isLastLine = false, lastChar = '神', clampOffset = 6,
            leadingX = 288f, trailingX = 336f, rtl = false, directionsAgree = true,
        )
        assertThat(SoftWrapTap.offsetForTap(6, 330f, cjk)).isEqualTo(7)
        assertThat(SoftWrapTap.offsetForTap(6, 300f, cjk)).isEqualTo(6)
    }

    @Test
    fun anEmojiAtTheLineEndIsCrossedWhole() {
        // The platform's clamp is the START of the last cluster (a surrogate pair here).
        val emoji = urlLine.copy(lineEnd = 24, lastChar = '\uDE00', clampOffset = 22, leadingX = 290f, trailingX = 314f)
        assertThat(SoftWrapTap.offsetForTap(22, 310f, emoji)).isEqualTo(24)
    }

    @Test
    fun aRightToLeftLineEndsOnTheLeft() {
        // An Arabic/Hebrew paragraph: its last character's trailing edge is the LEFT one.
        val rtl = SoftWrapTap.LineEnd(
            lineEnd = 18, isLastLine = false, lastChar = 'ש', clampOffset = 17,
            leadingX = 22f, trailingX = 12f, rtl = true, directionsAgree = true,
        )
        assertWithMessage("left of the midpoint is past it").that(SoftWrapTap.offsetForTap(17, 14f, rtl)).isEqualTo(18)
        assertThat(SoftWrapTap.offsetForTap(17, 20f, rtl)).isEqualTo(17)
    }

    @Test
    fun aMixedDirectionLineEndIsLeftToThePlatform() {
        // A Latin URL at the end of an RTL line (or the reverse): which edge is "past" depends on
        // the bidi runs; the platform's own choice stands.
        assertThat(SoftWrapTap.offsetForTap(22, 312f, urlLine.copy(directionsAgree = false))).isEqualTo(22)
    }
}
