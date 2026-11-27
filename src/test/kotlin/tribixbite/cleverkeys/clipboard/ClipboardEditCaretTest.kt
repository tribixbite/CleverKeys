package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [ClipboardEditCaret]: one arrow / slider step in the clipboard editor moves the caret one
 * CHARACTER in logical order, as in an app's text field (maintainer 2026-11-26: the space-bar
 * slider "seems to reverse direction ... it is just in the wrong place" in a wrapped URL). The
 * old step was one UTF-16 unit, which stops between the two halves of an emoji's surrogate pair;
 * a character typed there splits the emoji into two broken halves. Pure tier.
 */
class ClipboardEditCaretTest {

    private val url = "https://github.com/rustdesk/rustdesk-server"

    @Test
    fun aStepInPlainTextIsOneCharacter() {
        val org = url.indexOf("rustdesk")
        assertThat(ClipboardEditCaret.stepForward(url, org)).isEqualTo(org + 1)
        assertThat(ClipboardEditCaret.stepBack(url, org)).isEqualTo(org - 1)
    }

    @Test
    fun aStepNeverLeavesTheText() {
        assertThat(ClipboardEditCaret.stepBack(url, 0)).isEqualTo(0)
        assertThat(ClipboardEditCaret.stepForward(url, url.length)).isEqualTo(url.length)
        assertWithMessage("a stale offset past the end is clamped").that(ClipboardEditCaret.stepForward(url, url.length + 4)).isEqualTo(url.length)
        assertWithMessage("a negative offset is clamped").that(ClipboardEditCaret.stepBack(url, -3)).isEqualTo(0)
    }

    @Test
    fun aStepCrossesAnEmojiWhole() {
        val text = "ok 😀 go"  // "ok 😀 go": the emoji is a surrogate pair at 3..5
        assertWithMessage("forward over the pair").that(ClipboardEditCaret.stepForward(text, 3)).isEqualTo(5)
        assertWithMessage("back over the pair").that(ClipboardEditCaret.stepBack(text, 5)).isEqualTo(3)
        assertWithMessage("a caret already between the halves (an old edit) leaves forward to the pair's end")
            .that(ClipboardEditCaret.stepForward(text, 4)).isEqualTo(5)
        assertWithMessage("and back to its start").that(ClipboardEditCaret.stepBack(text, 4)).isEqualTo(3)
    }

    @Test
    fun aWrappedLineIsNoDifferentFromAnyOther() {
        // A soft wrap is drawing only: the offsets on both sides of it are consecutive.
        val wrapAt = url.indexOf("rustdesk") + 4  // "…/rust|desk/…" on a narrow pane
        assertThat(ClipboardEditCaret.stepForward(url, wrapAt - 1)).isEqualTo(wrapAt)
        assertThat(ClipboardEditCaret.stepForward(url, wrapAt)).isEqualTo(wrapAt + 1)
        assertThat(ClipboardEditCaret.stepBack(url, wrapAt + 1)).isEqualTo(wrapAt)
    }
}
