package tribixbite.cleverkeys

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Small editor receipt for explicit edits. Unknown selection/content fails closed. */
data class EditorReadback private constructor(
    val start: Int,
    val end: Int,
    val before: String,
    val after: String,
    val selectedText: String,
) {
    val rangeStart: Int get() = minOf(start, end)
    val collapsed: Boolean get() = start == end
    fun matches(ic: InputConnection): Boolean = capture(ic) == this

    companion object {
        const val GUARD_LENGTH = 32
        fun capture(ic: InputConnection): EditorReadback? = runCatching {
            val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = GUARD_LENGTH }, 0) ?: return null
            if (extracted.startOffset < 0 || extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
            val start = Math.addExact(extracted.startOffset, extracted.selectionStart)
            val end = Math.addExact(extracted.startOffset, extracted.selectionEnd)
            val length = kotlin.math.abs(start.toLong() - end.toLong())
            if (length > tribixbite.cleverkeys.customization.DynamicTemplate.MAX_EXPANDED_LENGTH) return null
            val selected = if (start == end) "" else ic.getSelectedText(0)?.toString() ?: return null
            if (selected.length.toLong() != length) return null
            val before = ic.getTextBeforeCursor(GUARD_LENGTH, 0)?.toString() ?: return null
            val after = ic.getTextAfterCursor(GUARD_LENGTH, 0)?.toString() ?: return null
            if (before.length > GUARD_LENGTH || after.length > GUARD_LENGTH) return null
            EditorReadback(start, end, before, after, selected)
        }.getOrNull()

        fun matchesReplacement(ic: InputConnection, old: EditorReadback, text: String, cursor: Int): Boolean {
            val now = capture(ic) ?: return false
            return isReplacement(now, old, text, cursor)
        }

        /** [now] is exactly [old] with its selection replaced by [text], caret at [cursor]. */
        fun isReplacement(now: EditorReadback, old: EditorReadback, text: String, cursor: Int): Boolean =
            now.start == cursor && now.end == cursor && now.before == (old.before + text).takeLast(GUARD_LENGTH) && now.after == old.after
    }
}

/** Serial editor-write receipt; learning is allowed only after exact insertion readback. */
class EditorCommitGuard(private var expected: EditorReadback, private val ownsSession: () -> Boolean) {
    fun prepare(ic: InputConnection): Boolean = runCatching {
        ownsSession() && expected.matches(ic) && ic.finishComposingText() &&
            ownsSession() && expected.matches(ic) && ownsSession()
    }.getOrDefault(false)

    fun accepted(ic: InputConnection, text: String): Boolean = runCatching {
        val end = Math.addExact(expected.rangeStart, text.length)
        if (!ownsSession()) return false
        // One readback both verifies the write and becomes the next expected state.
        val now = EditorReadback.capture(ic) ?: return false
        if (!EditorReadback.isReplacement(now, expected, text, end) || !ownsSession()) return false
        expected = now
        true
    }.getOrDefault(false)
}
