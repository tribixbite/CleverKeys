package tribixbite.cleverkeys.pinyin

/**
 * Pure state machine for ONE pinyin composing run: the typed/swiped key buffer and the
 * ranked candidate list assembled from a [CkpyPhraseTable.Table].
 *
 * This class owns no Android types on purpose — the IME glue ([PinyinController]) owns the
 * `InputConnection`, the suggestion bar, and the async table load; every decision about what
 * the buffer contains and what the candidates are is testable in `runPureTests`.
 *
 * ## Candidate assembly (deterministic)
 *
 *  1. exact key match (`nihao`) — its candidates in rank order;
 *  2. then every longer key the buffer is a prefix of (`ni` → `nian`, `niang`, …), in key
 *     order, each entry's candidates in rank order, de-duplicated by text;
 *  3. if neither matched, greedy longest-match segmentation (`woaini` → `wo` + `ai` + `ni`)
 *     joins each segment's top candidate, plus bounded second-candidate variants.
 *
 * Rule 3 is the Phase-3 segmentation cut agreed in `docs/specs/pinyin-ime.md`: greedy, not a
 * lattice. It makes unbounded input useful without pretending to be a full decoder.
 *
 * ## Input normalization (matches the pack-side rules)
 *  - letters are lowercased; `ü`/`Ü` become `v` (女 = `nv`)
 *  - `'`, `’`, `‘`, `` ` `` become `'` (so `xi'an` and `xian` stay distinct)
 *  - anything else is not pinyin and is refused by [appendLetter]
 */
class PinyinSession(private val table: CkpyPhraseTable.Table) {

    companion object {
        /** Most candidates the bar is offered per update. */
        const val MAX_CANDIDATES = 8

        /** Hard cap on one composing run; longer input cannot resolve to one key anyway. */
        const val MAX_BUFFER_LENGTH = 48

        /**
         * Stop scanning extension keys after this many de-duplicated candidates, so a
         * one-letter buffer with a huge prefix fan-out cannot walk a 500k-entry table.
         */
        private const val EXTENSION_SCAN_LIMIT = MAX_CANDIDATES * 3

        /** [CkpyPhraseTable] rank (0 = best) → display score; engine-relative, never a probability. */
        internal fun scoreForRank(rank: Int): Int = 1000 - rank.coerceIn(0, 255) * 3

        /** Returns the normalized form of [letter], or null when it is not pinyin input. */
        internal fun normalizeLetter(letter: Char): Char? = when (letter) {
            in 'a'..'z' -> letter
            in 'A'..'Z' -> letter.lowercaseChar()
            '\'', '\u2019', '\u2018', '`' -> '\''
            '\u00fc', '\u00dc' -> 'v' // ü / Ü
            else -> null
        }
    }

    /** One display candidate with its engine-relative score (order is the contract). */
    data class Candidate(val text: String, val score: Int)

    private val buffer = StringBuilder()

    /** The preedit shown (or hidden) by the composing region. */
    val composingText: String get() = buffer.toString()

    /** Current buffer length in characters. */
    val length: Int get() = buffer.length

    /** True when no input has been buffered yet. */
    val isEmpty: Boolean get() = buffer.isEmpty()

    /** True when the buffer is at [MAX_BUFFER_LENGTH] and [appendLetter] would refuse. */
    val isFull: Boolean get() = buffer.length >= MAX_BUFFER_LENGTH

    /**
     * Append one keyboard character.
     *
     * @return true when it became part of the buffer (the caller must then refresh the
     *   preedit/candidates); false for non-pinyin characters and a full buffer, in which
     *   case the caller should let the normal key path handle it.
     */
    fun appendLetter(letter: Char): Boolean {
        val normalized = normalizeLetter(letter) ?: return false
        if (isFull) return false
        buffer.append(normalized)
        return true
    }

    /**
     * Append a whole surface (a swipe-decoded pinyin spelling), keeping only the characters
     * pinyin accepts.
     *
     * @return the exact normalized text that was appended; empty when nothing usable was
     *   left or the buffer was already full.
     */
    fun appendSurface(surface: String): String {
        val appended = StringBuilder()
        for (char in surface) {
            val normalized = normalizeLetter(char) ?: continue
            if (buffer.length >= MAX_BUFFER_LENGTH) break
            buffer.append(normalized)
            appended.append(normalized)
        }
        return appended.toString()
    }

    /**
     * Delete the last buffered character.
     *
     * @return true when a character was removed; false on an empty buffer (the caller
     *   should then let the normal backspace path run).
     */
    fun backspace(): Boolean {
        if (buffer.isEmpty()) return false
        buffer.setLength(buffer.length - 1)
        return true
    }

    /** Forget the whole composing run. */
    fun clear() {
        buffer.setLength(0)
    }

    /**
     * Ranked candidates for the current buffer; empty when the buffer is empty or nothing
     * can be resolved yet.
     */
    fun candidates(limit: Int = MAX_CANDIDATES): List<Candidate> {
        if (buffer.isEmpty() || limit <= 0) return emptyList()
        val key = buffer.toString()
        val ordered = LinkedHashMap<String, Int>()

        fun offer(text: String, rank: Int) {
            if (text.isNotEmpty()) ordered.putIfAbsent(text, scoreForRank(rank))
        }

        // 1. exact key — always first, in the table's rank order.
        table.lookup(key)?.candidates?.forEach { offer(it.text, it.rank) }

        // 2. completion extensions (keys the buffer is a prefix of), bounded.
        for (entry in table.withPrefix(key)) {
            if (entry.key == key) continue
            for (candidate in entry.candidates) {
                offer(candidate.text, candidate.rank)
                if (ordered.size >= EXTENSION_SCAN_LIMIT) break
            }
            if (ordered.size >= EXTENSION_SCAN_LIMIT) break
        }

        // 3. greedy segmentation fallback for input no single key covers.
        if (ordered.isEmpty()) {
            for ((text, score) in segmentationCandidates(key, limit)) {
                ordered.putIfAbsent(text, score)
            }
        }

        return ordered.entries.take(limit).map { Candidate(it.key, it.value) }
    }

    /** Best candidate for the current buffer, or null. */
    fun topCandidate(): String? = candidates(limit = 1).firstOrNull()?.text

    /**
     * Greedy longest-match segmentation of [key] into known phrase keys, then a join of the
     * segments' top candidates. Bounded second-candidate variants follow (last segments
     * first, because trailing syllables carry the most ambiguity in practice).
     */
    private fun segmentationCandidates(key: String, limit: Int): List<Pair<String, Int>> {
        val segments = ArrayList<CkpyPhraseTable.Entry>()
        var position = 0
        while (position < key.length) {
            val entry = longestKeyAt(key, position) ?: return emptyList()
            segments.add(entry)
            position += entry.key.length
        }
        if (segments.isEmpty()) return emptyList()

        fun top(entry: CkpyPhraseTable.Entry, index: Int = 0): CkpyPhraseTable.Candidate =
            entry.candidates.getOrElse(index) { entry.candidates.first() }

        fun join(pick: (Int, CkpyPhraseTable.Entry) -> String): String =
            segments.mapIndexed { index, entry -> pick(index, entry) }.joinToString("")

        val results = LinkedHashMap<String, Int>()
        val baseText = join { _, entry -> top(entry).text }
        results[baseText] = variantScore(segments, variantIndex = -1)

        // Second-candidate variants, most significant (trailing) segment first.
        for (index in segments.indices.reversed()) {
            if (results.size >= limit) break
            if (segments[index].candidates.size < 2) continue
            val text = join { i, entry -> if (i == index) top(entry, 1).text else top(entry).text }
            results.putIfAbsent(text, variantScore(segments, variantIndex = index))
        }
        return results.entries.map { it.key to it.value }
    }

    /** The longest known key that starts at [position], or null when none does. */
    private fun longestKeyAt(input: String, position: Int): CkpyPhraseTable.Entry? {
        val remaining = input.length - position
        val maxLength = minOf(remaining, MAX_BUFFER_LENGTH)
        for (length in maxLength downTo 1) {
            table.lookup(input.substring(position, position + length))?.let { return it }
        }
        return null
    }

    /**
     * Combined score of a segmentation variant: the WORST rank among its picks (0 for the
     * base variant, `variantIndex`'s runner-up candidate for a variant, everything else
     * top). A join is only as good as its least certain syllable.
     */
    private fun variantScore(segments: List<CkpyPhraseTable.Entry>, variantIndex: Int): Int {
        var worstRank = 0
        for (index in segments.indices) {
            val pick = if (index == variantIndex) 1 else 0
            val candidate = segments[index].candidates.getOrElse(pick) {
                segments[index].candidates.first()
            }
            worstRank = maxOf(worstRank, candidate.rank)
        }
        return scoreForRank(worstRank)
    }
}
