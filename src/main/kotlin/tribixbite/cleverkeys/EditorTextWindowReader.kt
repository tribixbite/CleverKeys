package tribixbite.cleverkeys

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
        // Bounded on API 31+ for a caret; see EditorSelectionReader (review 2026-11-14).
        val read = EditorSelectionReader.read(ic, reach, reach, MAX_SELECTION.toLong()) ?: return null
        val start = read.start
        val end = read.end
        val before = read.before
        val after = read.after
        if (before.length > minOf(start, end)) return null
        EditorTextWindow(
            selStart = start,
            selEnd = end,
            before = before,
            selected = read.selected,
            after = after,
            beforeComplete = before.length < reach || before.length == minOf(start, end),
            afterComplete = after.length < reach,
        )
    }.getOrNull()
}
