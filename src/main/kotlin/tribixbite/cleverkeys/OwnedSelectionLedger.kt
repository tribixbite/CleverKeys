package tribixbite.cleverkeys

/**
 * Selection callbacks that one of the IME's own, just-finished editor writes may still
 * deliver.
 *
 * Android gives `onUpdateSelection` no operation id and delivers it asynchronously (a
 * Binder call posted to the IME's main looper). API 24–27 TextView, WebView and Compose
 * editors can report it several loop turns after the write returned, so an allowance that
 * expires on "the next loop turn" drops the IME's own late callbacks. Entries here expire
 * only by consumption, by being replaced, or by a monotonic time bound ([WINDOW_MS]).
 *
 * A position match is only a CANDIDATE: callers must still verify that the editor's
 * current readback equals the state the write produced. The ledger never makes a callback
 * owned on coordinates alone. Pure Kotlin so the host tier can pin its rules.
 */
class OwnedSelectionLedger(private val capacity: Int) {
    private val entries = ArrayDeque<Pair<Int, Int>>()
    private var deadline = Long.MIN_VALUE

    val pending: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    /** Expect one more callback; false when the bound is reached (the caller must fail closed). */
    fun stamp(start: Int, end: Int, now: Long): Boolean {
        if (entries.size >= capacity) return false
        entries.addLast(start to end)
        deadline = now + WINDOW_MS
        return true
    }

    fun isLive(now: Long): Boolean = entries.isNotEmpty() && !isExpired(now)

    /** The time bound passed (whether or not entries remain). */
    fun isExpired(now: Long): Boolean = now > deadline

    /**
     * Consume the first live entry equal to (start, end) together with every earlier entry
     * (editors may skip or coalesce intermediate callbacks, never reorder them). Returns
     * false, consuming nothing, when there is no live match.
     */
    fun consume(start: Int, end: Int, now: Long): Boolean {
        if (!isLive(now)) return false
        val index = entries.indexOfFirst { it.first == start && it.second == end }
        if (index < 0) return false
        repeat(index + 1) { entries.removeFirst() }
        return true
    }

    /** Live match without consuming it. */
    fun contains(start: Int, end: Int, now: Long): Boolean =
        isLive(now) && entries.any { it.first == start && it.second == end }

    fun clear() { entries.clear() }

    companion object {
        /**
         * Upper bound for a late own callback. Generous against slow editors, short enough
         * that a coordinate-only coincidence long after the write cannot keep ownership.
         */
        const val WINDOW_MS = 2_000L
    }
}
