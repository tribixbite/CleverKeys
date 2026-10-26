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
 * Text larger than [CHUNK_BUDGET_BYTES] on the wire is therefore sent as ordered chunks inside
 * ONE batch edit:
 * - oneway calls from one thread to one binder node are delivered in order, so the chunks
 *   reassemble exactly;
 * - chunks end on code-point boundaries, never between the two halves of a surrogate pair
 *   (a terminal decodes each commit separately and would emit two replacement characters);
 * - a refused chunk (false: the kernel had no async space left for it, or the connection is
 *   gone) was NOT delivered, so it is retried after a short pause ([RETRY_PAUSES_MS]) without
 *   breaking the order: the receiver frees its async space as soon as its binder thread has
 *   taken the earlier chunks. Only after the last retry is the paste reported incomplete.
 *
 * Chunks are bounded by their size on the wire, never by their char count, and fill that budget:
 * a char count is up to 3 bytes per char in UTF-8, so it either wastes most of the budget on
 * ASCII or risks the limit on 3-byte scripts. Fewer chunks also paste faster: Chrome re-lays out
 * the whole textarea after each commit, so 260,000 chars of Hebrew took 54 s in 8 chunks of 32 Ki
 * chars (Saga 2026-10-26). Chrome's own per-line handling of newlines dominates line-broken text
 * (524,000 chars in 13,100 lines: ~350 s at any chunk size); no chunk size changes that.
 * docs/eval/2026-10-26-large-paste-chunk-size.md has the measurements.
 *
 * No synchronous read is used as back-pressure between chunks: Chrome does not answer
 * `getTextBeforeCursor` inside a batch edit, so each such read blocked the keyboard's main
 * thread for the framework's full 2 s timeout (Saga 2026-10-25, 15 timeouts for one paste).
 *
 * The same sequence works for every editor kind the clipboard paths reach: a composing span is
 * replaced by the first chunk exactly as by a single commit; terminals (Termux) and blind remote
 * fields (TYPE_NULL, RustDesk) receive consecutive commits exactly as consecutive typing.
 *
 * The Boolean result is the IPC's success — the only thing the IME side of a connection can
 * report (see CLAUDE.md "IME-side InputConnection results"). False means a chunk stayed refused,
 * i.e. the paste is incomplete; callers tell the user.
 */
internal object LargeTextCommit {

    /**
     * Wire bytes per `commitText`, counted by [codePointWireBytes] on the larger of UTF-8
     * (API 34+) and UTF-16 (older releases). Two chunks fit at once in the ~508 KiB async binder space, so the
     * next chunk can be queued while the receiver still holds the previous one, with room left
     * for the other oneway calls that share that space.
     */
    const val CHUNK_BUDGET_BYTES: Int = 192 * 1024

    /** Pauses before each retry of a refused chunk: about 0.3 s in all, then give up. */
    val RETRY_PAUSES_MS: List<Long> = listOf(5L, 10L, 20L, 40L, 80L, 160L)

    /**
     * Wire size of the code point starting at [i] in [text] (2 UTF-16 units for a surrogate
     * pair, else 1): a pair is 4 bytes in both encodings; any other unit is 2 bytes as UTF-16 and
     * 1–3 as UTF-8 (a lone surrogate is replaced by a 3-byte character), so the larger is taken.
     */
    private fun codePointWireBytes(text: CharSequence, i: Int): Int {
        val c = text[i]
        if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
            return PAIR_BYTES
        }
        return if (c.code < 0x800) UTF16_UNIT_BYTES else 3
    }

    /**
     * End (exclusive) of the chunk that starts at [start]: as many whole code points as fit in
     * [budgetBytes], never ending between the halves of a surrogate pair. Always > [start] while
     * [start] < length.
     */
    fun chunkEnd(text: CharSequence, start: Int, budgetBytes: Int = CHUNK_BUDGET_BYTES): Int {
        require(budgetBytes >= PAIR_BYTES) { "a chunk must hold a surrogate pair" }
        var end = start
        var bytes = 0
        while (end < text.length) {
            val size = codePointWireBytes(text, end)
            if (bytes + size > budgetBytes) break
            bytes += size
            end += if (size == PAIR_BYTES) 2 else 1
        }
        return end
    }

    private const val PAIR_BYTES = 4
    private const val UTF16_UNIT_BYTES = 2

    /**
     * Commit [text] at the cursor of [conn] (cursor placed after it, like `commitText(text, 1)`),
     * in one batch edit and in chunks of at most [budgetBytes] wire bytes. A refused chunk is retried
     * after each of [retryPausesMs] (spent by [pause]); returns false when it stays refused, and
     * nothing after it is sent.
     */
    fun commit(
        conn: InputConnection,
        text: CharSequence,
        budgetBytes: Int = CHUNK_BUDGET_BYTES,
        retryPausesMs: List<Long> = RETRY_PAUSES_MS,
        pause: (Long) -> Unit = Thread::sleep,
    ): Boolean {
        conn.beginBatchEdit()
        try {
            var start = 0
            do {
                val end = chunkEnd(text, start, budgetBytes)
                val chunk = text.subSequence(start, end)
                if (!commitWithRetries(conn, chunk, retryPausesMs, pause)) return false
                start = end
            } while (start < text.length)
            return true
        } finally {
            conn.endBatchEdit()
        }
    }

    private fun commitWithRetries(
        conn: InputConnection,
        chunk: CharSequence,
        retryPausesMs: List<Long>,
        pause: (Long) -> Unit,
    ): Boolean {
        if (conn.commitText(chunk, 1)) return true
        for (ms in retryPausesMs) {
            pause(ms)
            if (conn.commitText(chunk, 1)) return true
        }
        return false
    }
}
