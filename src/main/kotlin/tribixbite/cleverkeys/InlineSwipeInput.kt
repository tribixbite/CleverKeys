package tribixbite.cleverkeys

/**
 * Swipe typing into the IME's OWN inline text fields (clipboard entry editor, tag entry,
 * clipboard search, emoji search, GIF search).
 *
 * Tap typing reaches those fields through `KeyEventHandler.sendText`'s routing chain, but a
 * swipe-typed word is committed by `SuggestionHandler` straight to the app's InputConnection —
 * so before this file a swipe made with the clipboard editor open landed in the app hidden
 * behind the panel (maintainer report, 2026-10-20). Everything here is Android-free: the
 * receiver side ([InlineTextTarget]) is a thin adapter over each field's existing
 * setText + setSelection plumbing, and the word/space/ownership rules are unit-tested on the
 * JVM (`InlineSwipeSessionTest`).
 *
 * Learning: NOTHING typed into an inline field is learned. Tap typing there returns from
 * `sendText` before `handle_text_typed`, so it never reaches the prediction tracker, the
 * personalization stores or the bigram model; a swiped word follows the same rule (no
 * contextTracker commit, no ML trace, no swipe-correction record).
 */

/** The inline fields, in the routing priority order of `KeyEventHandler.sendText`. */
enum class InlineField(
    /**
     * True when the field has a real caret/selection (the clipboard entry editor). The other
     * fields only ever append at their end and delete from it — their tap-typing routes are
     * `current + text` / `dropLast(1)` — so a swipe there appends too.
     */
    val cursorAware: Boolean,
) {
    CLIPBOARD_TAG(false),
    CLIPBOARD_EDIT(true),
    CLIPBOARD_SEARCH(false),
    EMOJI_SEARCH(false),
    GIF_SEARCH(false),
}

/**
 * One inline field's text and selection at a moment. [session] identifies the field instance
 * (the editor's edit session, or the search view): an ownership recorded in one session is
 * never applied to another one that happens to hold the same text.
 */
data class InlineFieldState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val session: Long,
) {
    /** Lower selection bound, clamped to the text. */
    val lo: Int get() = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)

    /** Upper selection bound, clamped to the text. */
    val hi: Int get() = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)

    companion object {
        /** The state of an append-only field: the caret is always at the end. */
        fun appendOnly(text: String, session: Long): InlineFieldState =
            InlineFieldState(text, text.length, text.length, session)

        /**
         * The text an append-only field holds after replacing [start, end) with [replacement],
         * or null when the range does not end at the field's end (such a field cannot edit
         * anywhere else) or is out of range.
         */
        fun appendOnlyReplace(current: String, start: Int, end: Int, replacement: String): String? {
            if (end != current.length || start < 0 || start > end) return null
            return current.substring(0, start) + replacement
        }
    }
}

/** The active inline field as the swipe pipeline sees it (built by `KeyEventHandler`). */
interface InlineTextTarget {
    val field: InlineField

    /** The field's current state, or null when it is no longer active or has no view. */
    fun state(): InlineFieldState?

    /**
     * Replace [start, end) with [text] and put the caret after it. In the clipboard editor this
     * is one step of the editor's own undo history (`InlineEditHistory`). False when the field
     * refused (inactive, or a range an append-only field cannot edit).
     */
    fun replace(start: Int, end: Int, text: String): Boolean
}

/** Where a swiped word goes in a field, and the spaces around it. Pure. */
object InlineWordPlanner {

    /**
     * Replace [start, end) with [text]; the word itself occupies
     * [start + wordOffset, start + wordOffset + wordLength) of the result.
     */
    data class Insertion(val start: Int, val end: Int, val text: String, val wordOffset: Int, val wordLength: Int)

    /**
     * The insertion of [word] at [state]'s selection (replacing it).
     *
     * Spacing mirrors what typing a word there would need, read from the FIELD's own text
     * (never the app's): a leading space unless the word starts the field or follows
     * whitespace; a trailing space only when the word would otherwise fuse with a letter or
     * digit right after it (a swipe into the middle of a text). No trailing auto-space at the
     * end: the next tap-typed punctuation then attaches to the word ("word."), which the
     * inline fields have no smart-punctuation swallow to repair, and a search query keeps no
     * stray space.
     */
    fun insertion(state: InlineFieldState, word: String): Insertion {
        val lo = state.lo
        val hi = state.hi
        val lead = if (lo > 0 && !state.text[lo - 1].isWhitespace()) " " else ""
        val trail = if (hi < state.text.length && state.text[hi].isLetterOrDigit()) " " else ""
        return Insertion(lo, hi, lead + word + trail, lead.length, word.length)
    }
}

/**
 * Word deletion in an inline field (maintainer report 2026-10-28: "swipe to delete word doesn't
 * work while editing a clipboard clipping"). The delete-word commands — `delete_last_word` (the
 * Backspace NW flick), `delete_word` (Backspace round-trip gesture, palette, Ctrl+Backspace),
 * `forward_delete_word` (Ctrl+Delete) — reach an app as a Ctrl+Del key event or an
 * InputConnection rewrite, neither of which the IME's own fields receive, so they were
 * swallowed there. This plans the same edit over the FIELD's text. Pure.
 *
 * Boundaries are [EditorTextNavigation]'s (the inline editor's word_left/word_right rules):
 * backward skips the separators before the caret, then the word; forward skips separators after
 * it, then the word. A selection is deleted as it is, the way Ctrl+Backspace treats one in a
 * text field. Append-only fields (caret always at the end) can only delete backward.
 */
object InlineWordDelete {

    /** The range [start, end) a word deletion removes from [state], or null when nothing would go. */
    fun range(state: InlineFieldState, forward: Boolean): IntRange? {
        val lo = state.lo
        val hi = state.hi
        if (lo != hi) return lo until hi
        val start = if (forward) lo else EditorTextNavigation.previousWordStart(state.text, lo)
        val end = if (forward) EditorTextNavigation.nextWordEnd(state.text, hi) else hi
        return if (start < end) start until end else null
    }

    /** Delete one word at [target]'s caret (see [range]). False when nothing was deleted. */
    fun delete(target: InlineTextTarget, forward: Boolean): Boolean {
        val state = target.state() ?: return false
        val range = range(state, forward) ?: return false
        return target.replace(range.first, range.last + 1, "")
    }
}

/**
 * The word most recently swiped into an inline field, so that a suggestion-bar tap replaces it
 * and backspace removes it — in THAT field, the way the app path's swipe ownership
 * (`lastAutoInsertedWord`) works for the app.
 *
 * Ownership holds only while the field is exactly as the swipe left it: same field, same
 * session, same text, caret collapsed where the insertion put it. Any tap-typed character,
 * backspace, caret move, paste or undo changes that, and the next tap inserts a new word
 * instead of rewriting text the user has since edited.
 */
class InlineSwipeSession {

    private data class Owned(
        val field: InlineField,
        val session: Long,
        /** Start of the whole insertion (leading space included). */
        val insertionStart: Int,
        /** The inserted text: lead + word + trail. */
        val inserted: String,
        /** The text the insertion replaced (a selection, or ""), restored by [undo]. */
        val replaced: String,
        val wordOffset: Int,
        val wordLength: Int,
        /** The field's whole text right after the insertion. */
        val textAfter: String,
    ) {
        val caret: Int get() = insertionStart + inserted.length
        val wordStart: Int get() = insertionStart + wordOffset
    }

    private var owned: Owned? = null

    /** The word currently owned, or null. */
    val ownedWord: String?
        get() = owned?.let { it.inserted.substring(it.wordOffset, it.wordOffset + it.wordLength) }

    /** Forget the owned word. */
    fun clear() {
        owned = null
    }

    /** Insert a swiped [word] at the field's selection and own it. False when nothing was written. */
    fun insert(target: InlineTextTarget, word: String): Boolean {
        owned = null
        if (word.isEmpty()) return false
        val state = target.state() ?: return false
        val plan = InlineWordPlanner.insertion(state, word)
        if (!target.replace(plan.start, plan.end, plan.text)) return false
        val textAfter = state.text.substring(0, plan.start) + plan.text + state.text.substring(plan.end)
        owned = Owned(
            target.field, state.session, plan.start, plan.text,
            state.text.substring(plan.start, plan.end), plan.wordOffset, plan.wordLength, textAfter,
        )
        return true
    }

    /**
     * A suggestion-bar tap of [word]: replaces the owned word when the field is untouched since
     * the swipe, otherwise inserts [word] as a new word. False when nothing was written.
     */
    fun commitTap(target: InlineTextTarget, word: String): Boolean {
        if (word.isEmpty()) return false
        val current = validOwned(target) ?: return insert(target, word)
        // Rewrite from the word to the insertion's end (word + its trailing separator) so the
        // caret lands where the insertion left it — after the separator, not inside it.
        val trail = current.inserted.substring(current.wordOffset + current.wordLength)
        if (!target.replace(current.wordStart, current.caret, word + trail)) {
            owned = null
            return false
        }
        val inserted = current.inserted.substring(0, current.wordOffset) + word +
            current.inserted.substring(current.wordOffset + current.wordLength)
        val textAfter = current.textAfter.substring(0, current.wordStart) + word +
            current.textAfter.substring(current.wordStart + current.wordLength)
        owned = current.copy(inserted = inserted, wordLength = word.length, textAfter = textAfter)
        return true
    }

    /**
     * Backspace right after a swipe: remove the whole insertion (its spaces included) and
     * restore what it replaced. False — the caller falls through to an ordinary backspace —
     * when there is no valid ownership.
     */
    fun undo(target: InlineTextTarget): Boolean {
        val current = validOwned(target) ?: return false
        owned = null
        return target.replace(current.insertionStart, current.caret, current.replaced)
    }

    /**
     * The separator a tap-typed [typed] needs right after a swiped word (maintainer report
     * 2026-10-28: tapping after a swiped word fused with it, `help` + `t` → `helpt`).
     *
     * The app path appends the automatic space when it commits the swipe; an inline field
     * does not ([InlineWordPlanner]: a query keeps no stray space, and punctuation attaches),
     * so the space is owed to the NEXT tap instead — the same deferred space Gboard uses.
     * A single space when the field is untouched since the swipe, the swipe left no separator
     * after the word, and [typed] starts with a letter or digit (punctuation still attaches:
     * `word.`); else "". [autoSpaceAfter] is `auto_space_after_suggestion`: off means the user
     * wants no automatic space after a swiped word, here as in apps.
     */
    fun spaceBeforeTap(target: InlineTextTarget, typed: CharSequence, autoSpaceAfter: Boolean): String {
        if (!autoSpaceAfter || typed.isEmpty() || !Character.isLetterOrDigit(Character.codePointAt(typed, 0))) return ""
        val current = validOwned(target) ?: return ""
        val trailing = current.inserted.length - (current.wordOffset + current.wordLength)
        return if (trailing == 0) " " else ""
    }

    /** The ownership, when [target]'s field is exactly as the last swipe/tap left it. */
    private fun validOwned(target: InlineTextTarget): Owned? {
        val current = owned ?: return null
        val state = target.state()
        val valid = state != null && target.field == current.field && state.session == current.session &&
            state.text == current.textAfter && state.lo == current.caret && state.hi == current.caret
        if (!valid) owned = null
        return if (valid) current else null
    }
}
