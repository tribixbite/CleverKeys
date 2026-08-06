package tribixbite.cleverkeys.swipe.ctc

import kotlin.math.ln

/**
 * A single node in the [CtcLexiconTrie].
 *
 * Mirrors FUTO's `ITrie` node contract (`itrie.h`) and the Python port's `TrieNode`
 * ([scripts/futo_decoder_eval.py]). The beam reads exactly these fields:
 *  - [id] — a stable, unique node identity used as the beam dedup key
 *    (`(id shl 1) or blank_ended`, matching `beam_search.cpp`'s `(node<<1)|be`).
 *  - [charIdx] — the emission-column index of the character that reaches this node
 *    (`get_char_idx`); `-1` for the root.
 *  - [depth] — number of characters from the root (`get_depth`); the word length.
 *  - [isWord] / [logFreq] — terminal flag and AOSP-scale log-frequency (`is_word` /
 *    `get_log_frequency`).
 *
 * Children are held in an insertion-ordered map so beam expansion visits them in the
 * same order the Python port does (dict-insertion order), keeping golden-trace parity
 * exact under tie conditions.
 */
class CtcTrieNode internal constructor(
    /** Stable unique node identity (assigned at creation; only used as a dedup key). */
    val id: Int,
    /** Emission-column index of the incoming character, or `-1` for the root. */
    val charIdx: Int,
    /** The incoming character (NUL for the root, which never reads it). */
    val incomingChar: Char,
    /** Number of characters from the root to this node. */
    val depth: Int,
    /** Parent node, or `null` for the root (used to reconstruct the surface word). */
    internal val parent: CtcTrieNode?,
) {
    internal val childMap = LinkedHashMap<Char, CtcTrieNode>()

    /**
     * The surface word spelled by the root→this path (`get_word`). Reconstructed by
     * walking parent pointers; the beam calls this only for the ≤beamWidth terminal
     * nodes at the end of a decode, so the walk cost is negligible.
     */
    fun word(): String {
        if (depth == 0) return ""
        val chars = CharArray(depth)
        var n: CtcTrieNode? = this
        var i = depth - 1
        while (n != null && n.depth > 0) {
            chars[i--] = n.incomingChar
            n = n.parent
        }
        return String(chars)
    }

    /** True when the path from root to this node spells a complete lexicon word. */
    var isWord: Boolean = false
        internal set

    /**
     * AOSP-scale log-frequency (`ln(freq + 1e-10)`), or `0.0` for non-word nodes.
     * Kept on the same 1..255 log scale FUTO's `scoring.json` lambda is calibrated
     * against (study H5). `0.0` doubles as the "unset" sentinel, matching the port's
     * `log_freq == 0.0` retention quirk in [CtcLexiconTrie.insert].
     */
    var logFreq: Double = 0.0
        internal set

    /** Child nodes in insertion order (for deterministic beam expansion). */
    val children: Collection<CtcTrieNode> get() = childMap.values

    /** Number of children (`get_child_count`). */
    val childCount: Int get() = childMap.size
}

/**
 * Prefix trie over a fixed alphabet with per-word AOSP-scale log-frequencies — the
 * lexicon surface the [CtcBeamDecoder] traverses.
 *
 * This is a direct port of the Python port's `LexTrie` (`scripts/futo_decoder_eval.py`)
 * with the `ITrie` accessors the C++ beam relies on folded into [CtcTrieNode]. The
 * alphabet is the emission-column ordering: class column `c` in a [CtcEmissions] matrix
 * corresponds to `alphabet[c]`, and each edge character maps to its column via
 * [charIndexOf]. Blank occupies column `alphabet.size`.
 *
 * Frequencies are stored as `ln(freq + 1e-10)` on the AOSP 1..255 scale (see study H5);
 * do NOT feed normalized `[0,1]` frequencies or the beam's `lambda*logFreq` term becomes
 * ~2 orders of magnitude too weak.
 *
 * @property alphabet emission-column ordering of the active keys (a..z for en_qwerty).
 */
class CtcLexiconTrie(val alphabet: CharArray) {

    private val charToIndex: Map<Char, Int> =
        HashMap<Char, Int>(alphabet.size * 2).apply {
            for (i in alphabet.indices) put(alphabet[i], i)
        }

    private var nextNodeId = 0

    /** Root node (`charIdx = -1`, depth 0). Its incoming char is NUL and never read. */
    val root: CtcTrieNode =
        CtcTrieNode(nextNodeId++, charIdx = -1, incomingChar = Char(0), depth = 0, parent = null)

    /** Number of distinct complete words inserted. */
    var wordCount: Int = 0
        private set

    /** Emission-column index of [ch], or `-1` if [ch] is not in the alphabet. */
    fun charIndexOf(ch: Char): Int = charToIndex[ch] ?: -1

    /**
     * Insert [word] with the given raw [freq] (AOSP 1..255 scale). Every character of
     * [word] MUST be in the alphabet — callers normalize first (see [loadFromFrequencyMap]).
     *
     * Log-frequency retention matches the port exactly: a node keeps the LARGER of its
     * existing and the new `ln(freq + 1e-10)`, except that an unset (`0.0`) node always
     * takes the new value.
     *
     * @throws IllegalArgumentException if a character is outside the alphabet.
     */
    fun insert(word: String, freq: Double) {
        require(word.isNotEmpty()) { "cannot insert an empty word" }
        var node = root
        for (ch in word) {
            val ci = charToIndex[ch]
                ?: throw IllegalArgumentException("char '$ch' in \"$word\" is not in the alphabet")
            val parent = node
            node = parent.childMap.getOrPut(ch) {
                CtcTrieNode(
                    nextNodeId++, charIdx = ci, incomingChar = ch,
                    depth = parent.depth + 1, parent = parent,
                )
            }
        }
        if (!node.isWord) {
            wordCount++
            node.isWord = true
        }
        val lf = ln(freq + 1e-10)
        // Port-faithful retention (LexTrie.insert): keep the max, but always overwrite
        // the 0.0 sentinel even if lf < 0 (log-freq of a small frequency is negative).
        if (lf > node.logFreq || node.logFreq == 0.0) {
            node.logFreq = lf
        }
    }

    /** True when [word] is a complete word in the lexicon. */
    fun contains(word: String): Boolean {
        var node = root
        for (ch in word) {
            node = node.childMap[ch] ?: return false
        }
        return node.isWord
    }

    companion object {
        /**
         * Build a trie from a `{word: frequency}` map (the shape of CleverKeys'
         * `en_enhanced.json` and FUTO's AOSP wordlists once parsed).
         *
         * Words are lowercased and filtered to alphabet characters only: a word with ANY
         * out-of-alphabet character is SKIPPED (the `load_flat_json_vocab` policy — as
         * opposed to `load_combined_vocab`, which strips them; see [loadStrippingNonAlphabet]).
         * Non-positive frequencies are floored to `1.0`, matching the port.
         *
         * Insertion order follows [words]' iteration order, so a [LinkedHashMap] (or any
         * ordered map) reproduces the port's child-edge ordering for golden parity.
         */
        fun loadFromFrequencyMap(alphabet: CharArray, words: Map<String, Double>): CtcLexiconTrie {
            val trie = CtcLexiconTrie(alphabet)
            val alphabetSet = alphabet.toHashSet()
            for ((raw, freq) in words) {
                val w = raw.lowercase()
                if (w.isEmpty() || w.any { it !in alphabetSet }) continue
                trie.insert(w, if (freq > 0.0) freq else 1.0)
            }
            return trie
        }

        /**
         * Build a trie from a `{word: frequency}` map, STRIPPING out-of-alphabet
         * characters rather than skipping the word (the `load_combined_vocab` policy).
         *
         * This is what makes apostrophe/hyphen surface forms reachable by an a-z-only CTC
         * model: `don't` → `dont`, `aaron's` → `aarons`. Words that become empty after
         * stripping are dropped. See the eval notes' apostrophe-lexicon correction.
         */
        fun loadStrippingNonAlphabet(alphabet: CharArray, words: Map<String, Double>): CtcLexiconTrie {
            val trie = CtcLexiconTrie(alphabet)
            val alphabetSet = alphabet.toHashSet()
            for ((raw, freq) in words) {
                val stripped = buildString {
                    for (ch in raw.lowercase()) if (ch in alphabetSet) append(ch)
                }
                if (stripped.isEmpty()) continue
                trie.insert(stripped, if (freq > 0.0) freq else 1.0)
            }
            return trie
        }
    }
}
