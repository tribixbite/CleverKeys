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
     * Auto-inserted from neural swipe typing prediction
     */
    NEURAL_SWIPE,

    /**
     * User manually selected a prediction from suggestion bar
     */
    CANDIDATE_SELECTION,

    /**
     * Auto-corrected text
     */
    AUTOCORRECT
}
