package tribixbite.cleverkeys.swipe.ctc

/**
 * Whether a word is short enough for the CTC decoder to be able to emit it at all.
 *
 * ## Why a word can be in the dictionary and still be unswipeable
 *
 * The exported encoder produces a fixed `log_emissions [1, 32, 65]` — **32 frames**
 * ([CtcFeaturizer] resamples every trace to that budget regardless of how long the gesture was).
 * A CTC path spends at least one frame per emitted character, and the standard CTC collapse rule
 * requires a **blank between two identical adjacent characters** or they merge into one. So the
 * frames a word needs are `length + (count of adjacent duplicate pairs)`, and a word needing
 * more than 32 has NO valid alignment: the beam cannot produce it from any trace.
 *
 * This is invisible today. A too-long custom word merges into the lexicon happily
 * ([CtcLexiconMerge]), occupies trie nodes, and is simply never offered — no exception, no log,
 * nothing to distinguish it from a word the user just swiped badly.
 *
 * ## Scope
 *
 * This bounds only the CTC engine. The geometric decoder has no frame budget and the tap-typing
 * predictor is prefix-based, so an over-long word still works there — which is exactly why the
 * warning must say "swipe", not "unusable".
 */
object CtcDecodableLength {

    /**
     * Frames the emission head produces. Mirrors the `[1, 32, 65]` contract asserted in
     * `OnnxCtcEmissionModel`; a re-exported model with a different `T'` moves this.
     */
    const val EMISSION_FRAMES = 32

    /**
     * Frames [word] needs: one per character, plus one separating blank for each adjacent
     * duplicate pair (`ss` in "class" costs 3, not 2).
     *
     * Case-insensitive, because the trie is built from lowercased surfaces.
     */
    fun framesRequired(word: String): Int {
        if (word.isEmpty()) return 0
        var frames = 1
        for (i in 1 until word.length) {
            frames++
            if (word[i].lowercaseChar() == word[i - 1].lowercaseChar()) frames++
        }
        return frames
    }

    /** True when the CTC beam could emit [word] — i.e. some alignment fits in the budget. */
    fun isDecodable(word: String): Boolean = framesRequired(word) <= EMISSION_FRAMES

    /**
     * The longest a word of this shape may be before it becomes undecodable, for messaging.
     * Reported rather than assumed because duplicates make it word-specific: 32 plain letters
     * fit, but "aaaa…" only manages 16.
     */
    fun headroom(word: String): Int = EMISSION_FRAMES - framesRequired(word)
}
