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
        val tail = words.drop(committed.size)
        if (isFinal) {
            committed += tail
            previous = words
            return SpeechHypothesis(committed.joinToString(" "), committed.size, isFinal = true)
        }
        // Agreement is measured past the committed boundary only: the committed words are
        // fixed whatever a pass says about them, so a pass that flips an early (committed)
        // word must not block the words after it from stabilising.
        val c = committed.size
        val agree = c + agreedPrefix(previous.drop(c), words.drop(c))
        if (agree > c) committed += words.subList(c, agree)
        previous = words
        val display = committed + words.drop(committed.size)
        return SpeechHypothesis(display.joinToString(" "), committed.size, isFinal = false)
    }

    /** Forget everything (a new utterance). */
    fun reset() {
        committed.clear()
        previous = emptyList()
    }

    companion object {
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
