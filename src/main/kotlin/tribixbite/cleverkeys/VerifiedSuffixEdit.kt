package tribixbite.cleverkeys

import android.view.inputmethod.InputConnection

/** Owns one editor-verified word and at most its single trailing separator. */
class VerifiedSuffixEdit private constructor(
    val baseWord: String,
    val space: String,
    val editor: EditorReadback,
    val suffix: String,
) {
    companion object {
        fun capture(ic: InputConnection, word: String, ownsSpace: Boolean): VerifiedSuffixEdit? {
            if (word.isEmpty() || word.length > 4096) return null
            val state = EditorReadback.capture(ic)?.takeIf { it.collapsed } ?: return null
            val space = if (ownsSpace) " " else ""
            if (!matchesWholeWord(ic, word + space, state.start)) return null
            return VerifiedSuffixEdit(word, space, state, "")
        }
        private fun matchesWholeWord(ic: InputConnection, tail: String, cursor: Int): Boolean {
            val before = ic.getTextBeforeCursor(tail.length + 1, 0)?.toString() ?: return false
            if (!before.endsWith(tail)) return false
            // Accepted text can be transformed by the editor. Do not own "cat"
            // inside "bobcat", including when the boundary is outside the guard.
            return cursor == tail.length || (cursor > tail.length && before.length > tail.length &&
                !isWordContinuation(before[before.length - tail.length - 1]))
        }
        private fun isWordContinuation(c: Char): Boolean = c.isLetterOrDigit() ||
            Character.getType(c) in setOf(Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) ||
            c in "'’ʼ-‐‑" || Character.isSurrogate(c)
    }
    fun matches(ic: InputConnection): Boolean = editor.matches(ic) &&
        matchesWholeWord(ic, baseWord + suffix + space, editor.start)

    /**
     * Exact command intent. [prepareSelection] stamps each expected editor state
     * BEFORE invoking the editor, so synchronous selection callbacks are owned too.
     */
    fun append(ic: InputConnection, requested: String, prepareSelection: (EditorReadback) -> Boolean = { true }): VerifiedSuffixEdit? {
        if (suffix.isNotEmpty() || requested !in setOf("'", "'s") || !matches(ic)) return null
        return replaceOwnedTail(ic, requested, prepareSelection)?.let { VerifiedSuffixEdit(baseWord, space, it, requested) }
    }
    fun undo(ic: InputConnection, prepareSelection: (EditorReadback) -> Boolean = { true }): VerifiedSuffixEdit? {
        if (suffix.isEmpty() || !matches(ic)) return null
        return replaceOwnedTail(ic, "", prepareSelection)?.let { VerifiedSuffixEdit(baseWord, space, it, "") }
    }
    private fun replaceOwnedTail(ic: InputConnection, replacementSuffix: String, prepareSelection: (EditorReadback) -> Boolean): EditorReadback? = runCatching {
        // commitText replaces a composing span before consulting the selection.
        // Finish it before touching the owned tail, then verify that finishing
        // changed neither text nor caret. Stamp first for synchronous callbacks.
        if (!prepareSelection(editor) || !ic.finishComposingText() || !matches(ic)) return null
        val ownedLength = suffix.length + space.length
        val start = editor.start - ownedLength
        if (start < 0) return null
        val before = ic.getTextBeforeCursor(EditorReadback.GUARD_LENGTH + ownedLength, 0)?.toString() ?: return null
        val selected = editor.copy(
            start = start, end = editor.start,
            before = before.dropLast(ownedLength).takeLast(EditorReadback.GUARD_LENGTH),
            selectedText = suffix + space,
        )
        if (ownedLength > 0) {
            if (!prepareSelection(selected)) return null
            runCatching { ic.setSelection(start, editor.start) }
            if (!selected.matches(ic)) return null
        }
        val replacement = replacementSuffix + space
        val end = start + replacement.length
        val expected = selected.copy(start = end, end = end,
            before = (selected.before + replacement).takeLast(EditorReadback.GUARD_LENGTH), selectedText = "")
        if (!prepareSelection(expected)) {
            restoreUnchangedSelection(ic, selected)
            return null
        }
        // False/throw can follow an applied edit: read once and never retry text.
        runCatching { ic.commitText(replacement, 1) }
        if (!expected.matches(ic)) {
            restoreUnchangedSelection(ic, selected)
            return null
        }
        expected
    }.getOrNull()

    /** Restore only our range when its text and both surrounding guards stayed exact. */
    private fun restoreUnchangedSelection(ic: InputConnection, selected: EditorReadback) {
        if (selected.collapsed || !selected.matches(ic) || !matchesWholeWord(ic, baseWord, selected.start)) return
        runCatching { ic.setSelection(editor.start, editor.end) }
        // No retry or compensating text write: a rejecting editor may keep its range.
    }
}
