package tribixbite.cleverkeys

/**
 * Tracks the source of text commits to enable context-aware deletion logic
 *
 * This allows the keyboard to distinguish between different types of input
 * and apply appropriate deletion behavior (e.g., deleting entire auto-inserted
 * words vs single characters)
 */
enum class PredictionSource {
    /**
     * Unknown or untracked source
     */
    UNKNOWN,

    /**
     * User manually tapped a key (regular typing)
     */
    USER_TYPED_TAP,

    /**
     * Auto-inserted from a swipe-typing prediction.
     *
     * Engine-agnostic: CTC and geometric commits both use this marker. It was called
     * NEURAL_SWIPE until 2026-08-18 purely because the transformer was the only swipe
     * engine when the enum was written; it never meant "decoded by the transformer".
     */
    SWIPE,

    /**
     * User manually selected a prediction from suggestion bar
     */
    CANDIDATE_SELECTION,

    /**
     * Auto-corrected text
     */
    AUTOCORRECT,

    /**
     * User selected a next-word prediction (context-only candidate surfaced
     * before any letter was typed — opt-in `next_word_prediction_enabled`)
     */
    NEXT_WORD
}
