package tribixbite.cleverkeys

/**
 * Observes the commit/undo stream and reports **swipe corrections**: a word a swipe
 * auto-inserted (X) that the user rejected, together with the word they wanted instead (Y).
 * Feeds the "Prefer “Y” when swiping?" offer and the swipe-ML row relabel (learning-system
 * audit 2026-09-26, `docs/audit/2026-09-26-learning-system-audit.md` "Resolution").
 *
 * Pure JVM state machine — no Android, no stores, no gates. [SuggestionHandler] feeds it only
 * while the learning gates pass and applies [SwipeCorrectionPolicy] to what it reports; this
 * class decides only WHICH word the user settled on after rejecting a swipe.
 *
 * ## The two correction routes
 *
 * 1. **Bar replace** — the user taps an alternate over the auto-inserted swipe word
 *    ([onSwipeReplacedFromBar]). Resolved immediately: Y is the tapped word.
 * 2. **Backspace undo, then the next word** — the #110 swipe undo (or delete-last-word) removes
 *    X ([onSwipeUndone]) and opens a **pending-rejection slot**. The slot is resolved by the
 *    next committed word:
 *    - a TYPED word ([onWordCommitted]) resolves it directly, but only when the editor shows
 *      that word sitting exactly where X was (the text before the cursor at the undo — the
 *      *anchor* — followed by the word and at most one separator). That single check is what
 *      makes "the cursor moved away" and "the user typed somewhere else" drop the slot, without
 *      tracking cursor events;
 *    - a SWIPED word ([onSwipeAutoInserted]) becomes a *candidate*: it may be wrong too. It is
 *      settled as Y by whatever comes next that does not reject it — the next word, a sentence
 *      boundary, leaving the field ([settleOrClear]). If it is itself undone the chain grows
 *      (`got` undone, `for` undone, then `git` typed ⇒ `[got, for] → git`); if it is replaced
 *      from the bar, the tapped word is Y for the whole chain.
 *
 * The slot is CLEARED, unresolved, by a sentence boundary / Enter / leaving the field while no
 * candidate is waiting ([settleOrClear]), by an autocorrected commit (the committed word is not
 * what the user typed — [settleOrClear] again), by the anchor check failing, and by
 * [PENDING_TIMEOUT_MS] elapsing before the next word.
 *
 * Whether a reported (X → Y) is a real correction or a changed mind is NOT decided here — see
 * [SwipeCorrectionPolicy.plausibleRejections].
 *
 * Not thread-safe: every caller is on the IME main thread.
 *
 * @param clock millisecond clock (injectable for the timeout tests)
 */
class SwipeCorrectionTracker(private val clock: () -> Long = System::currentTimeMillis) {

    /**
     * One swipe auto-insert as the tracker remembers it.
     *
     * @property word the word that actually reached the editor (post final-autocorrect and
     *   I-capitalization — the same string the #110 undo and the bar REPLACE see)
     * @property slate the engine candidates shown for this swipe, in rank order (the
     *   plausibility rule's "was Y one of this gesture's readings" evidence)
     * @property traceId the swipe-ML row stored for this swipe, or null when collection is off
     *   (Feature B relabels that row when the correction is recorded)
     */
    data class SwipeRecord(
        val word: String,
        val slate: List<String>,
        val traceId: String?,
    )

    /**
     * A settled correction: the user rejected every word in [rejected] (oldest first) and
     * ended with [chosen]. Raw — the policy still filters it.
     */
    data class Correction(val chosen: String, val rejected: List<SwipeRecord>)

    /** The open pending-rejection slot. */
    private class Pending(
        val rejected: MutableList<SwipeRecord>,
        var anchor: String?,
        var openedAtMs: Long,
        var candidate: SwipeRecord? = null,
    )

    /** The most recent swipe auto-insert, until any other commit supersedes it. */
    private var lastSwipe: SwipeRecord? = null

    private var pending: Pending? = null

    /** True while an undo is waiting for the word that replaces it (test/diagnostic view). */
    val hasPendingRejection: Boolean get() = pending != null

    /**
     * A swipe auto-inserted [record]. [editorAfter] is the text before the cursor AFTER the
     * insert (null when unreadable).
     *
     * @return the correction a waiting candidate settled into (this new swipe means the user
     *   kept the previous one), or null
     */
    fun onSwipeAutoInserted(record: SwipeRecord, editorAfter: String?): Correction? {
        val slot = pending
        lastSwipe = record
        if (slot == null) return null
        slot.candidate?.let { kept ->
            // The user swiped on after the re-swiped word — they kept it.
            pending = null
            return Correction(kept.word, slot.rejected.toList())
        }
        if (isExpired(slot) || !continuesAnchor(slot.anchor, editorAfter, record.word)) {
            pending = null
            return null
        }
        slot.candidate = record
        return null
    }

    /** The user tapped [chosen] in the bar over the auto-inserted [rejected] (REPLACE branch). */
    fun onSwipeReplacedFromBar(rejected: String, chosen: String): Correction? {
        val swipe = lastSwipe
        val slot = pending
        lastSwipe = null
        pending = null
        if (swipe == null || !swipe.word.equals(rejected, ignoreCase = true)) return null
        val chain = if (slot != null && slot.candidate === swipe) slot.rejected + swipe else listOf(swipe)
        return Correction(chosen, chain)
    }

    /**
     * Backspace (or delete-last-word) removed the just-swiped [word]. [anchor] is the text before
     * the cursor AFTER the deletion — where the replacement is expected to appear.
     */
    fun onSwipeUndone(word: String, anchor: String?) {
        val swipe = lastSwipe
        lastSwipe = null
        if (swipe == null || !swipe.word.equals(word, ignoreCase = true)) {
            // Not the swipe we saw (or none) — the stream is not what we think it is.
            pending = null
            return
        }
        val slot = pending
        if (slot != null && slot.candidate === swipe) {
            // A re-swipe after an undo was undone too: the chain grows, the wait restarts.
            slot.rejected.add(swipe)
            slot.candidate = null
            slot.anchor = anchor
            slot.openedAtMs = clock()
        } else {
            pending = Pending(mutableListOf(swipe), anchor, clock())
        }
    }

    /**
     * A word the user TYPED (or completed by tapping a prediction for a typed partial) was
     * committed. [editorBefore] is the text before the cursor after the commit.
     *
     * @return the correction this word resolves (or a waiting candidate settles into), or null
     */
    fun onWordCommitted(word: String, editorBefore: String?): Correction? {
        lastSwipe = null
        val slot = pending ?: return null
        pending = null
        slot.candidate?.let { kept -> return Correction(kept.word, slot.rejected.toList()) }
        if (isExpired(slot)) return null
        if (!continuesAnchor(slot.anchor, editorBefore, word)) return null
        return Correction(word, slot.rejected.toList())
    }

    /**
     * A point where no replacement word can follow the undo any more: a sentence boundary,
     * Enter / the IME action, leaving the field, or a commit whose text is not what the user
     * typed (autocorrect rewrote it). A waiting candidate settles (the user kept it); an
     * unanswered undo is dropped.
     */
    fun settleOrClear(): Correction? {
        lastSwipe = null
        val slot = pending ?: return null
        pending = null
        val kept = slot.candidate ?: return null
        return Correction(kept.word, slot.rejected.toList())
    }

    /** Forget everything (gate turned off, password field, incognito field). */
    fun clear() {
        lastSwipe = null
        pending = null
    }

    private fun isExpired(slot: Pending): Boolean = clock() - slot.openedAtMs > PENDING_TIMEOUT_MS

    private fun continuesAnchor(anchor: String?, editor: String?, word: String): Boolean =
        anchor != null && editor != null && editorContinuesAnchor(anchor, editor, word)

    companion object {
        /**
         * An undo not answered by a word within this window is abandoned. Retyping one word takes
         * a few seconds; half a minute of silence means the user moved on to something else.
         */
        const val PENDING_TIMEOUT_MS = 30_000L

        /**
         * How many characters before the cursor form the anchor. Callers read the anchor with
         * this window and read the editor at resolution time with
         * `ANCHOR_WINDOW + word.length + RESOLUTION_SLACK`.
         */
        const val ANCHOR_WINDOW = 48

        /** Extra characters read at resolution time (separators after the word). */
        const val RESOLUTION_SLACK = 8

        /**
         * True when [editor] (text before the cursor, read with the window above) is exactly
         * [anchor] followed by [word] — separated from the anchor by whitespace unless the anchor
         * ends in a non-word character (or is empty) — and then at most one separator run: trailing
         * whitespace, one punctuation character, trailing whitespace.
         *
         * [anchor] shorter than [ANCHOR_WINDOW] was the whole field before the cursor, so the text
         * before the word must match it exactly; a full-window anchor is compared as a suffix.
         */
        fun editorContinuesAnchor(anchor: String, editor: String, word: String): Boolean {
            if (word.isEmpty()) return false
            var rest = editor.trimEnd()
            if (rest.isNotEmpty() && !rest.last().isLetterOrDigit()) rest = rest.dropLast(1).trimEnd()
            if (!rest.endsWith(word, ignoreCase = true)) return false
            val beforeWord = rest.dropLast(word.length)
            val before = beforeWord.trimEnd()
            val separated = before.length < beforeWord.length
            val expected = anchor.trimEnd()
            if (!before.endsWith(expected)) return false
            if (anchor.length < ANCHOR_WINDOW && before.length != expected.length) return false
            // "fix" + "git" with nothing between is a different word ("fixgit"), not a replacement.
            val joinsPreviousWord = expected.isNotEmpty() && expected.last().isLetterOrDigit()
            return separated || !joinsPreviousWord
        }
    }
}
