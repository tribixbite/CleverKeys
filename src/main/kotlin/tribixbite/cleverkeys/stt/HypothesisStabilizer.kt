package tribixbite.cleverkeys.stt

/**
 * Decides which words of a re-decoded transcript are stable, for engines whose every pass
 * re-decodes the whole utterance (attention encoder–decoders such as Moonshine v2 and Whistle).
 *
 * Rule (docs/research/2026-10-28-stt-engine-deep-dive.md §5.2, measured equal to batch WER at
 * 1 s chunks): a word is stable once TWO CONSECUTIVE passes agree on it and on every word before
 * it. Agreement compares a normalised form (case, surrounding punctuation), while the surface
 * form of the newer pass is the one kept, so "world" → "world," between passes still agrees.
 *
 * Committed words never change afterwards: the editor already holds them. If a later pass
 * revises a committed word, the committed text wins and only the words after the committed
 * boundary are taken from the new pass (that is what "commit" means to a text field).
 *
 * Engine-neutral and pure (`HypothesisStabilizerTest`). Transducer engines that emit final
 * tokens directly do not need it; they report [SpeechHypothesis.stableWordCount] themselves.
 */
class HypothesisStabilizer {

    private val committed = ArrayList<String>()
    private var previous: List<String> = emptyList()

    /** The words committed so far (stable, will not change). */
    val committedWords: List<String> get() = committed

    /**
     * Fold one recognition pass. [passText] is the engine's whole transcript for the audio so
     * far; [isFinal] marks the last pass, which makes every word stable.
     */
    fun update(passText: String, isFinal: Boolean): SpeechHypothesis {
        val words = SpeechHypothesis.splitWords(passText)
        val tail = tailAfterCommitted(words)
        if (isFinal) {
            committed += tail
            previous = words
            return SpeechHypothesis(committed.joinToString(" "), committed.size, isFinal = true)
        }
        // Agreement is measured past the committed boundary only: the committed words are
        // fixed whatever a pass says about them, so a pass that flips an early (committed)
        // word must not block the words after it from stabilising.
        val agree = agreedPrefix(tailAfterCommitted(previous), tail)
        if (agree > 0) committed += tail.subList(0, agree)
        previous = words
        val display = committed + tail.drop(agree)
        return SpeechHypothesis(display.joinToString(" "), committed.size, isFinal = false)
    }

    /**
     * The words of [words] that come after the committed ones. Taken by POSITION, a pass that
     * inserted or dropped a word before the boundary shifted the tail by one: the last committed
     * word came back ("after all. all.", Saga 2026-10-29) or the next word was lost. So the tail
     * starts after the pass's own copy of the last committed words (two, else one) when it is
     * found within [MAX_SHIFT] of the boundary; otherwise by position as before.
     */
    private fun tailAfterCommitted(words: List<String>): List<String> {
        val c = committed.size
        if (c == 0) return words
        for (k in minOf(ANCHOR_WORDS, c) downTo 1) {
            val anchor = committed.subList(c - k, c).map(::normalise)
            var best = -1
            for (end in maxOf(k, c - MAX_SHIFT)..minOf(words.size, c + MAX_SHIFT)) {
                if ((0 until k).all { normalise(words[end - k + it]) == anchor[it] }) {
                    if (best < 0 || kotlin.math.abs(end - c) < kotlin.math.abs(best - c)) best = end
                }
            }
            if (best >= 0) return words.drop(best)
        }
        return words.drop(c)
    }

    /** Forget everything (a new utterance). */
    fun reset() {
        committed.clear()
        previous = emptyList()
    }

    companion object {
        /** Committed words matched to find where a pass's tail starts ([tailAfterCommitted]). */
        private const val ANCHOR_WORDS = 2

        /** How far (words) a pass may have shifted the committed boundary and still be aligned. */
        private const val MAX_SHIFT = 3

        /** Number of leading words [a] and [b] share under [normalise]. */
        fun agreedPrefix(a: List<String>, b: List<String>): Int {
            val n = minOf(a.size, b.size)
            var i = 0
            while (i < n && normalise(a[i]) == normalise(b[i])) i++
            return i
        }

        /**
         * The comparison form of a word: lower case, with leading/trailing characters that are
         * neither letters nor digits removed ("World," → "world", "'tis" → "tis"). Inner
         * apostrophes and hyphens stay ("don't", "e-mail").
         */
        fun normalise(word: String): String =
            word.lowercase().trim { !it.isLetterOrDigit() }
    }
}
