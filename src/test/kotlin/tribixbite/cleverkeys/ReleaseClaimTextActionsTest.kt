package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Release-record guards for the **selection-driven text actions** (`textAssist`,
 * `replaceText`, `showTextMenu`).
 *
 * Rows pinned here (see `docs/RELEASE_RECORD.md`):
 *
 * | version | published note |
 * |---|---|
 * | v1.1.99 | "Uses ACTION_PROCESS_TEXT intent instead of unsupported context menu" |
 * | v1.1.99 | "Shows app chooser (Google Assistant, translators, etc.)" |
 * | v1.1.99 | "Works when text is selected in any app" |
 * | v1.1.99 | "Falls back gracefully if no text selected" |
 * | v1.2.0  | "Selects word at cursor and triggers the native cut/copy/paste toolbar" |
 * | v1.2.0  | "Text Assist and Replace Text now show No text selected when no selection exists" |
 *
 * The dispatch itself (`Intent`, `InputConnection`, the suggestion bar) is not constructible
 * off-device — `android.jar`'s stubs throw `RuntimeException("Stub!")`. Every *decision* it
 * makes was extracted verbatim into [TextActionPolicy], and that is what this pins.
 *
 * Note the `Intent.ACTION_PROCESS_TEXT` comparison below works in the pure tier because the
 * framework constant is a compile-time `String` constant: Kotlin bakes its value into this
 * class file, so no Android class is loaded at run time. (The production code carries
 * `@SuppressLint("InlinedApi")` for exactly that reason.)
 */
class ReleaseClaimTextActionsTest {

    // ------------------------------------------------- v1.1.99 ACTION_PROCESS_TEXT + chooser

    @Test
    fun `the policy action is the framework's ACTION_PROCESS_TEXT`() {
        assertWithMessage(
            "v1.1.99 replaced the unsupported android.R.id.textAssist context-menu action " +
                "with an ACTION_PROCESS_TEXT intent; the hand-spelled constant must not drift"
        ).that(TextActionPolicy.ACTION_PROCESS_TEXT)
            .isEqualTo(android.content.Intent.ACTION_PROCESS_TEXT)

        assertThat(TextActionPolicy.ACTION_PROCESS_TEXT)
            .isEqualTo("android.intent.action.PROCESS_TEXT")
    }

    @Test
    fun `text assist dispatches a process-text request with its own chooser`() {
        val request = TextActionPolicy.processTextRequest(
            "hello world", TextActionPolicy.TextAction.ASSIST
        )

        assertThat(request.action).isEqualTo(TextActionPolicy.ACTION_PROCESS_TEXT)
        assertThat(request.mimeType).isEqualTo("text/plain")
        assertThat(request.text).isEqualTo("hello world")
        assertWithMessage("v1.1.99 'Shows app chooser (Google Assistant, translators, etc.)'")
            .that(request.chooserTitle).isEqualTo("Process text with...")
    }

    @Test
    fun `replace text dispatches the same intent under its own chooser title`() {
        val request = TextActionPolicy.processTextRequest(
            "hello world", TextActionPolicy.TextAction.REPLACE
        )

        assertThat(request.action).isEqualTo(TextActionPolicy.ACTION_PROCESS_TEXT)
        assertThat(request.mimeType).isEqualTo("text/plain")
        assertThat(request.chooserTitle).isEqualTo("Replace text with...")
    }

    @Test
    fun `both actions offer the selection as editable so the target app can replace it`() {
        for (action in TextActionPolicy.TextAction.entries) {
            assertWithMessage(
                "EXTRA_PROCESS_TEXT_READONLY must stay false for $action — a read-only " +
                    "request turns 'Replace Text' into a lookup that can never write back"
            ).that(TextActionPolicy.processTextRequest("x", action).readOnly).isFalse()
        }
    }

    @Test
    fun `the selection is forwarded verbatim, whatever the source app put in it`() {
        // v1.1.99: "Works when text is selected in any app" — no trimming, no truncation,
        // no case folding; whatever the editor handed back is what the chooser receives.
        val selections = listOf(
            "  padded  ",
            "multi\nline",
            "emoji 🎉 and accents éàü",
            "مرحبا", // RTL Arabic
            "a".repeat(5000)
        )
        for (selection in selections) {
            val request = TextActionPolicy.processTextRequest(
                selection, TextActionPolicy.TextAction.ASSIST
            )
            assertThat(request.text).isEqualTo(selection)
        }
    }

    @Test
    fun `the two chooser titles are distinct`() {
        val titles = TextActionPolicy.TextAction.entries.map { it.chooserTitle }
        assertThat(titles).containsNoDuplicates()
    }

    // ------------------------- v1.1.99 / v1.2.0 "no text selected" fallback (not a crash)

    @Test
    fun `no-selection messages name the action that was invoked`() {
        assertThat(TextActionPolicy.noTextSelectedMessage(TextActionPolicy.TextAction.ASSIST))
            .isEqualTo("No text selected for Text Assist")
        assertThat(TextActionPolicy.noTextSelectedMessage(TextActionPolicy.TextAction.REPLACE))
            .isEqualTo("No text selected for Replace Text")
    }

    @Test
    fun `every text action has a distinct user-facing no-selection message`() {
        val messages = TextActionPolicy.TextAction.entries
            .map { TextActionPolicy.noTextSelectedMessage(it) }
        assertThat(messages).containsNoDuplicates()
        for (message in messages) {
            assertThat(message).startsWith("No text selected for ")
        }
    }

    // ------------------------------------- v1.2.0 showTextMenu: "selects the word at cursor"

    @Test
    fun `a cursor inside a word selects the whole word`() {
        // "hello wor|ld" — three characters behind the cursor, two ahead.
        val span = TextActionPolicy.wordAtCursor("hello wor", "ld")
        assertThat(span.backward).isEqualTo(3)
        assertThat(span.forward).isEqualTo(2)
        assertThat(span.isEmpty).isFalse()
    }

    @Test
    fun `a cursor at the start of a word selects forward only`() {
        val span = TextActionPolicy.wordAtCursor("hello ", "world")
        assertThat(span.backward).isEqualTo(0)
        assertThat(span.forward).isEqualTo(5)
    }

    @Test
    fun `a cursor at the end of a word selects backward only`() {
        val span = TextActionPolicy.wordAtCursor("hello world", " ")
        assertThat(span.backward).isEqualTo(5)
        assertThat(span.forward).isEqualTo(0)
    }

    @Test
    fun `a cursor at the very end of the field selects the trailing word`() {
        val span = TextActionPolicy.wordAtCursor("hello world", "")
        assertThat(span.backward).isEqualTo(5)
        assertThat(span.forward).isEqualTo(0)
    }

    @Test
    fun `an apostrophe does not split the word`() {
        // "do|n't" must select all of "don't", not "do" — the elision mark is a word char.
        val span = TextActionPolicy.wordAtCursor("do", "n't")
        assertThat(span.backward).isEqualTo(2)
        assertThat(span.forward).isEqualTo(3)

        // French elision at the front: "qu'e|st"
        val french = TextActionPolicy.wordAtCursor("qu'e", "st")
        assertThat(french.backward).isEqualTo(4)
        assertThat(french.forward).isEqualTo(2)
    }

    @Test
    fun `digits and letters belong to the same word`() {
        val span = TextActionPolicy.wordAtCursor("build42", "b")
        assertThat(span.backward).isEqualTo(7)
        assertThat(span.forward).isEqualTo(1)
    }

    @Test
    fun `punctuation bounds the word on both sides`() {
        val span = TextActionPolicy.wordAtCursor("(wor", "ld)")
        assertThat(span.backward).isEqualTo(3)
        assertThat(span.forward).isEqualTo(2)
    }

    @Test
    fun `a cursor surrounded by whitespace selects nothing`() {
        // v1.1.99 "Falls back gracefully if no text selected": the caller reports
        // "No word at cursor" instead of issuing a zero-width setSelection.
        val span = TextActionPolicy.wordAtCursor("hi ", " there")
        assertThat(span.backward).isEqualTo(0)
        assertThat(span.forward).isEqualTo(0)
        assertThat(span.isEmpty).isTrue()
    }

    @Test
    fun `an empty field selects nothing`() {
        assertThat(TextActionPolicy.wordAtCursor("", "").isEmpty).isTrue()
    }

    @Test
    fun `a newline bounds the word`() {
        val span = TextActionPolicy.wordAtCursor("first\nsec", "ond\nthird")
        assertThat(span.backward).isEqualTo(3)
        assertThat(span.forward).isEqualTo(3)
    }

    @Test
    fun `the selected span never runs past the text it was given`() {
        // The caller converts the span into absolute setSelection offsets; a span longer
        // than the context it was scanned from would select text that was never inspected.
        val samples = listOf(
            "" to "",
            "word" to "word",
            "  " to "  ",
            "don't" to "'t",
            "a" to "b"
        )
        for ((before, after) in samples) {
            val span = TextActionPolicy.wordAtCursor(before, after)
            assertWithMessage("backward for '$before'|'$after'")
                .that(span.backward).isAtMost(before.length)
            assertWithMessage("forward for '$before'|'$after'")
                .that(span.forward).isAtMost(after.length)
        }
    }
}
