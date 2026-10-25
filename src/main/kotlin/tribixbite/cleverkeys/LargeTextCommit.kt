package tribixbite.cleverkeys

import android.view.inputmethod.InputConnection

/**
 * Commits text of any length into the app's editor without exceeding one binder transaction.
 *
 * Every `InputConnection.commitText` an IME makes is a ONEWAY binder call into the app's
 * process (IRemoteInputConnection, API 31+; IInputContext before). A oneway transaction must fit
 * in the receiver's async binder space, which is half of its ~1 MiB buffer and shared with every
 * other oneway call still queued for that process. The text crosses as UTF-8 on API 34 (the
 * Saga's 524,000-char clipboard entry was a 524,156-byte parcel) and as UTF-16 on older
 * releases. Too large a parcel is refused by the kernel ("FAILED BINDER TRANSACTION"); the
 * IME-side `commitText` then returns false and nothing reaches the editor (Saga 2026-10-25;
 * 200,000 chars still fit).
 *
 * Text longer than [CHUNK_CHARS] is therefore sent as ordered chunks inside ONE batch edit:
 * - oneway calls from one thread to one binder node are delivered in order, so the chunks
 *   reassemble exactly, and inside a batch edit the editor applies them as one change;
 * - chunks end on code-point boundaries, never between the two halves of a surrogate pair
 *   (a terminal decodes each commit separately and would emit two replacement characters);
 * - after each chunk but the last, a cheap synchronous read (`getTextBeforeCursor(1, 0)`) is a
 *   barrier: the app answers only after it has processed every earlier call, so at most one
 *   chunk at a time occupies its async binder space instead of the whole entry queued at once.
 *
 * The same sequence works for every editor kind the clipboard paths reach: a composing span is
 * replaced by the first chunk exactly as by a single commit; terminals (Termux) and blind remote
 * fields (TYPE_NULL, RustDesk) receive consecutive commits exactly as consecutive typing.
 *
 * The Boolean result is the IPC's success — the only thing the IME side of a connection can
 * report (see CLAUDE.md "IME-side InputConnection results"). False means the transaction was
 * refused (or the connection is gone), i.e. the paste is incomplete; callers tell the user.
 */
internal object LargeTextCommit {

    /**
     * Chars per `commitText`: at most 96 KiB as UTF-8 (3 bytes per UTF-16 unit) or 64 KiB as
     * UTF-16, well under the ~508 KiB async binder space even with other calls queued.
     */
    const val CHUNK_CHARS: Int = 32 * 1024

    /**
     * End (exclusive) of the chunk that starts at [start]: at most [maxChars] chars, moved back
     * by one when it would split a surrogate pair. Always > [start] while [start] < length.
     */
    fun chunkEnd(text: CharSequence, start: Int, maxChars: Int = CHUNK_CHARS): Int {
        require(maxChars >= 2) { "a chunk must hold a surrogate pair" }
        val end = minOf(text.length, start + maxChars)
        return if (end < text.length && Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) end - 1 else end
    }

    /**
     * Commit [text] at the cursor of [conn] (cursor placed after it, like `commitText(text, 1)`),
     * in one batch edit and in chunks of at most [maxChars] chars. Returns false as soon as a
     * chunk is refused; nothing after it is sent.
     */
    fun commit(conn: InputConnection, text: CharSequence, maxChars: Int = CHUNK_CHARS): Boolean {
        conn.beginBatchEdit()
        try {
            var start = 0
            do {
                val end = chunkEnd(text, start, maxChars)
                if (!conn.commitText(text.subSequence(start, end), 1)) return false
                start = end
                // Back-pressure barrier between chunks (see the class comment); its answer is unused.
                if (start < text.length) conn.getTextBeforeCursor(1, 0)
            } while (start < text.length)
            return true
        } finally {
            conn.endBatchEdit()
        }
    }
}
