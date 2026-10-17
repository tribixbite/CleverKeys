package tribixbite.cleverkeys

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * Reads an [EditorTextWindow] from an InputConnection. Kept apart from the pure
 * [EditorTextNavigation] so pure-JVM tests never load an Android class (mock tests replace
 * [capture] with `mockkObject`, as they do `EditorReadback.capture`).
 *
 * Fails closed: an editor that cannot report its selection, its selected text or the text
 * around it gives null, and the calling command refuses (or falls back to key events) rather
 * than guessing offsets.
 */
internal object EditorTextWindowReader {

    /** Selections longer than this are not read whole (same cap as template readback). */
    const val MAX_SELECTION = 65_536

    fun capture(ic: InputConnection, reach: Int): EditorTextWindow? = runCatching {
        val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 0 }, 0) ?: return null
        if (extracted.startOffset < 0 || extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
        val start = Math.addExact(extracted.startOffset, extracted.selectionStart)
        val end = Math.addExact(extracted.startOffset, extracted.selectionEnd)
        val length = kotlin.math.abs(start.toLong() - end.toLong())
        if (length > MAX_SELECTION) return null
        val selected = if (start == end) "" else ic.getSelectedText(0)?.toString() ?: return null
        if (selected.length.toLong() != length) return null
        val before = ic.getTextBeforeCursor(reach, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(reach, 0)?.toString() ?: return null
        if (before.length > reach || after.length > reach || before.length > minOf(start, end)) return null
        EditorTextWindow(
            selStart = start,
            selEnd = end,
            before = before,
            selected = selected,
            after = after,
            beforeComplete = before.length < reach || before.length == minOf(start, end),
            afterComplete = after.length < reach,
        )
    }.getOrNull()
}
