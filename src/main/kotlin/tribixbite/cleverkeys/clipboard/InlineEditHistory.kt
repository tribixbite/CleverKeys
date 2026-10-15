package tribixbite.cleverkeys

/**
 * Bounded undo/redo history for the clipboard panel's inline entry editor.
 *
 * Why not the platform's own undo (`EditText.onTextContextMenuItem(android.R.id.undo)`): every
 * edit the IME routes into its own EditText is a `setText()` + `setSelection()` (IME-owned views
 * cannot rely on `Editable.replace` / `dispatchKeyEvent` — see ClipboardHistoryView), and
 * `TextView.setText` calls `Editor.forgetUndoRedo()` whenever the buffer is editable (AOSP
 * lineage-22.2 TextView.java). The platform history is therefore wiped by every keystroke and
 * its undo is always a no-op here. This class is the replacement; it is Android-free so the
 * step logic is unit-tested on the JVM (InlineEditHistoryTest).
 *
 * The caller records each edit as a ([Snapshot] before, [Snapshot] after) pair. Merging follows
 * ordinary text-field behaviour: consecutive typing is one step per word (a word typed after
 * whitespace starts a new step), consecutive backspaces are one step, and any caret move,
 * paste, cut, or undo/redo in between starts a new step. Paste and cut ([EditKind.OTHER]) are
 * always their own step.
 *
 * Memory: the history lives only for one edit session (the view clears it on exit) and is
 * bounded both by step count ([capacity]) and by total retained characters
 * ([maxRetainedChars]), so editing a very large entry cannot pin many copies of it.
 *
 * @param capacity maximum number of undo steps kept; older steps are dropped first.
 * @param maxRetainedChars soft bound on the characters held by all snapshots; the oldest steps
 *   are dropped until under it, except the newest undo step, which is always kept.
 */
class InlineEditHistory(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxRetainedChars: Int = DEFAULT_MAX_RETAINED_CHARS,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxRetainedChars > 0) { "maxRetainedChars must be positive" }
    }

    /** The editor's text and selection at one moment. `selectionStart` may exceed `selectionEnd`. */
    data class Snapshot(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /** How an edit merges with its neighbours (see the class comment). */
    enum class EditKind { TYPING, DELETION, OTHER }

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var retainedChars = 0

    // Merge state: the kind and resulting state of the last recorded edit. Null after an
    // undo/redo/clear, so the next edit always starts a fresh step.
    private var lastKind: EditKind? = null
    private var lastAfter: Snapshot? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Record that the editor went from [before] to [after] through an edit of [kind]. A change
     * of selection alone (same text) is not an edit and is ignored. Any recorded edit discards
     * the redo branch.
     */
    fun record(before: Snapshot, after: Snapshot, kind: EditKind) {
        if (before.text == after.text) return
        val merges = kind != EditKind.OTHER &&
            kind == lastKind &&
            before == lastAfter &&
            !startsNewWord(kind, before, after)
        if (!merges) push(undoStack, before)
        clearStack(redoStack)
        lastKind = kind
        lastAfter = after
        trim()
    }

    /**
     * Step back one edit. [current] is the editor's state now; it becomes the redo target.
     * Returns the state to restore, or null when there is nothing to undo.
     */
    fun undo(current: Snapshot): Snapshot? {
        val target = pop(undoStack) ?: return null
        push(redoStack, current)
        breakMerge()
        trim()
        return target
    }

    /**
     * Re-apply one undone edit. [current] is the editor's state now; it becomes the undo target.
     * Returns the state to restore, or null when there is nothing to redo.
     */
    fun redo(current: Snapshot): Snapshot? {
        val target = pop(redoStack) ?: return null
        push(undoStack, current)
        breakMerge()
        trim()
        return target
    }

    /** Forget all history (edit session ended). */
    fun clear() {
        clearStack(undoStack)
        clearStack(redoStack)
        breakMerge()
    }

    private fun breakMerge() {
        lastKind = null
        lastAfter = null
    }

    /**
     * True when a typing edit begins a new word — the insertion point follows whitespace and
     * the inserted text starts with a non-whitespace character — so "hello world" undoes as
     * "world" then "hello ", like an ordinary text field.
     */
    private fun startsNewWord(kind: EditKind, before: Snapshot, after: Snapshot): Boolean {
        if (kind != EditKind.TYPING) return false
        val at = minOf(before.selectionStart, before.selectionEnd).coerceIn(0, before.text.length)
        val precedingIsSpace = before.text.getOrNull(at - 1)?.isWhitespace() == true
        val insertedStartsWord = after.text.getOrNull(at)?.isWhitespace() == false
        return precedingIsSpace && insertedStartsWord
    }

    private fun push(stack: ArrayDeque<Snapshot>, snapshot: Snapshot) {
        stack.addLast(snapshot)
        retainedChars += snapshot.text.length
    }

    private fun pop(stack: ArrayDeque<Snapshot>): Snapshot? =
        stack.removeLastOrNull()?.also { retainedChars -= it.text.length }

    private fun clearStack(stack: ArrayDeque<Snapshot>) {
        stack.forEach { retainedChars -= it.text.length }
        stack.clear()
    }

    /**
     * Enforce both bounds. The oldest undo steps go first, then the oldest-undone redo steps
     * (the bottom of the redo stack); the newest undo step is never dropped.
     */
    private fun trim() {
        while (undoStack.size > capacity) retainedChars -= undoStack.removeFirst().text.length
        while (redoStack.size > capacity) retainedChars -= redoStack.removeFirst().text.length
        while (retainedChars > maxRetainedChars) {
            when {
                undoStack.size > 1 -> retainedChars -= undoStack.removeFirst().text.length
                redoStack.isNotEmpty() -> retainedChars -= redoStack.removeFirst().text.length
                else -> return // only the newest undo step remains: keep it
            }
        }
    }

    companion object {
        /** Undo steps kept per edit session. */
        const val DEFAULT_CAPACITY = 100

        /** Characters retained across all snapshots (~2 MB of UTF-16). */
        const val DEFAULT_MAX_RETAINED_CHARS = 1_000_000
    }
}
