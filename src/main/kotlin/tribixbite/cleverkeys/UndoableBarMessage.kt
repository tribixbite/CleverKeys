package tribixbite.cleverkeys

/**
 * The two-tap undo of a suggestion-bar confirmation such as "Added “git” to dictionary"
 * (2026-09-26). Pure JVM state; [SuggestionBar.showUndoableMessage] renders it and owns the
 * timers.
 *
 * The confirmation is a bar message, not a Toast — Toasts render beneath the IME window and are
 * invisible (`.claude/skills/ime-visual-feedback.md`), and they cannot be tapped anyway. So the
 * message itself is the control:
 *
 * ```
 * SHOWING ──tap──▶ CONFIRMING ("Tap again to remove “git”") ──tap──▶ FINISHED + undo
 *    │                  │
 *    └── timeout / typing / another message ──▶ FINISHED (nothing undone)
 * ```
 *
 * Two taps, not one, because the message sits exactly where the user's next suggestion tap
 * lands: a single stray tap must never delete a word they just chose to add. [undo] runs at most
 * once, and never after [finish].
 *
 * Not thread-safe: the bar drives it on the main thread.
 *
 * @property message the confirmation shown first
 * @property confirmMessage the armed state, shown after the first tap
 * @property undo reverses the add; run by the bar once its own state is torn down
 */
class UndoableBarMessage(
    val message: String,
    val confirmMessage: String,
    val undo: () -> Unit,
) {
    enum class Phase { SHOWING, CONFIRMING, FINISHED }

    /** What a tap asks the bar to do. */
    sealed interface TapResult {
        /** Arm: replace the text with [text] and restart the timeout at [durationMs]. */
        data class Confirm(val text: String, val durationMs: Long) : TapResult

        /** Second tap: tear the message down, then run [undo]. */
        data object Undo : TapResult

        /** The message already finished (a late tap racing the timeout). */
        data object Ignored : TapResult
    }

    var phase: Phase = Phase.SHOWING
        private set

    /** Advance on a tap of the message. */
    fun onTap(): TapResult = when (phase) {
        Phase.SHOWING -> {
            phase = Phase.CONFIRMING
            TapResult.Confirm(confirmMessage, CONFIRM_DURATION_MS)
        }
        Phase.CONFIRMING -> {
            phase = Phase.FINISHED
            TapResult.Undo
        }
        Phase.FINISHED -> TapResult.Ignored
    }

    /**
     * The message went away without the second tap (timeout, typing, a newer message).
     * @return true when this call ended a live message
     */
    fun finish(): Boolean {
        val wasLive = phase != Phase.FINISHED
        phase = Phase.FINISHED
        return wasLive
    }

    companion object {
        /**
         * How long the first state stays up. Longer than the 1.5–2 s the plain confirmation used:
         * the user has to read it, decide, and tap twice.
         */
        const val MESSAGE_DURATION_MS = 3_000L

        /** How long the armed state waits for the second tap before giving up. */
        const val CONFIRM_DURATION_MS = 3_000L
    }
}
