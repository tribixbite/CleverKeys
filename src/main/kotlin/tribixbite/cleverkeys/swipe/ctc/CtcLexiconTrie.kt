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
 * Children are held in insertion order so beam expansion visits them in the same order the
 * Python port does (dict-insertion order), keeping golden-trace parity exact under tie
 * conditions.
 *
 * ## Why parallel arrays and not a `LinkedHashMap`
 *
 * This node type is instantiated ~231,000 times for the shipped English lexicon, so its
 * per-node overhead IS the trie's memory cost. A `LinkedHashMap<Char, CtcTrieNode>` child
 * map costs, per node, a map instance (~56 B) plus — for any node with children — a 16-slot
 * table array (~80 B) plus a 32 B `LinkedHashMap.Entry` per edge. Measured against the ART
 * object layout that is ~42 MB for the English trie, of which only ~9 MB is the nodes
 * themselves.
 *
 * Two lazily-allocated parallel arrays (`char` keys + node references, appended in insertion
 * order) reproduce the same semantics — ordered iteration, by-character lookup — for ~19 MB,
 * a ~23 MB saving on a 256 MB-growth-limit device. Lookup is a linear scan, which is FASTER
 * here than hashing: the trie's average branching factor is 1.44 children per internal node
 * and the hard maximum is the alphabet size (26).
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
    /**
     * Edge characters in insertion order; `null` until this node gets its first child, so a
     * leaf (30% of nodes in the English trie) pays nothing for children it will never have.
     */
    private var childChars: CharArray? = null

    /** Child nodes, parallel to [childChars]. Slots past [childCount] are unset. */
    private var childNodes: Array<CtcTrieNode?>? = null

    /** Number of children (`get_child_count`). */
    var childCount: Int = 0
        private set

    /** The child reached by [ch], or `null`. Linear scan — see the class KDoc. */
    internal fun childFor(ch: Char): CtcTrieNode? {
        val chars = childChars ?: return null
        val nodes = childNodes ?: return null
        for (i in 0 until childCount) {
            if (chars[i] == ch) return nodes[i]
        }
        return null
    }

    /**
     * Appends a child edge, preserving insertion order. Callers MUST have checked
     * [childFor] first — this does not deduplicate.
     */
    internal fun addChild(ch: Char, node: CtcTrieNode) {
        var chars = childChars
        var nodes = childNodes
        if (chars == null || nodes == null) {
            // Capacity 2 is free relative to capacity 1: both round to the same 24-byte
            // allocation under ART's 8-byte alignment, and it covers the common 2-way branch.
            chars = CharArray(INITIAL_CHILD_CAPACITY)
            nodes = arrayOfNulls(INITIAL_CHILD_CAPACITY)
            childChars = chars
            childNodes = nodes
        } else if (childCount == chars.size) {
            // Plain doubling, deliberately UNCLAMPED. This used to be
            // `minOf(chars.size * 2, MAX_CHILDREN)`, which saturates: the growth sequence is
            // 2→4→8→16→26, and once `chars.size == 26` the clamp returns 26 again, so
            // `copyOf` yields a same-size array and `chars[26]` below throws
            // ArrayIndexOutOfBounds on the 27th distinct child.
            //
            // That is unreachable today — every in-app trie is built against the 26-char
            // `CtcEngineAdapter.ALPHABET` and `insert` rejects out-of-alphabet characters — but
            // it is a certain crash the day a wider alphabet ships, and Cyrillic (33 letters) is
            // on the roadmap. The clamp bought ~36 wasted bytes on the few nodes with >16
            // children; correctness is worth more. The constructor now also range-checks the
            // alphabet, so an oversized one fails loudly at construction rather than mid-insert.
            val grown = chars.size * 2
            chars = chars.copyOf(grown)
            nodes = nodes.copyOf(grown)
            childChars = chars
            childNodes = nodes
        }
        chars[childCount] = ch
        nodes[childCount] = node
        childCount++
    }

    /**
     * The [index]-th child in insertion order. The beam iterates children by index to avoid
     * allocating an iterator per node per timestep.
     *
     * @throws IndexOutOfBoundsException if [index] is not in `0 until childCount`.
     */
    fun childAt(index: Int): CtcTrieNode {
        if (index < 0 || index >= childCount) {
            throw IndexOutOfBoundsException("child $index of $childCount")
        }
        return childNodes!![index]!!
    }

    private companion object {
        const val INITIAL_CHILD_CAPACITY = 2

        // A `MAX_CHILDREN = 26` constant used to clamp the growth above. It is deliberately
        // gone rather than merely unused: the real bound is the alphabet size, which is
        // enforced once at `CtcLexiconTrie`'s constructor against the emission-head width, so
        // a per-node cap could only ever disagree with it. See `addChild`.
    }

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

    /**
     * Child nodes in insertion order (for deterministic beam expansion).
     *
     * Allocates a view list, so the HOT beam path uses [childAt]/[childCount] instead; this
     * accessor exists for tests and diagnostics.
     */
    val children: List<CtcTrieNode>
        get() = List(childCount) { childAt(it) }
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

    init {
        // The emission head is `[1, T, MAX_EMISSION_COLUMNS]` — one column per key slot plus
        // CTC blank — so an alphabet larger than the model can emit is a wiring error, not a
        // degraded mode. Fail here, at construction, rather than producing a trie whose deeper
        // columns the decoder can never reach. Cyrillic (33) and Arabic (28) both fit; the
        // check exists so a future script that does NOT fit says so immediately.
        require(alphabet.isNotEmpty()) { "alphabet must not be empty" }
        require(alphabet.size <= CtcFeaturizer.MAX_KEYS) {
            "alphabet has ${alphabet.size} characters, but the emission head has " +
                "${CtcFeaturizer.MAX_KEYS} non-blank columns — a wider alphabet needs a " +
                "re-exported model"
        }
        require(alphabet.toSet().size == alphabet.size) {
            "alphabet has duplicate characters; emission columns must be one-to-one"
        }
    }

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

    /**
     * Number of nodes allocated, including the root — the trie's real memory driver
     * (words share prefixes, so this is 2–3× [wordCount]). Reported by the memory probe.
     */
    val nodeCount: Int get() = nextNodeId

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
            node = parent.childFor(ch) ?: CtcTrieNode(
                nextNodeId++, charIdx = ci, incomingChar = ch,
                depth = parent.depth + 1, parent = parent,
            ).also { parent.addChild(ch, it) }
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
            node = node.childFor(ch) ?: return false
        }
        return node.isWord
    }

    /**
     * The stored `ln(freq + 1e-10)` for [word], or `null` when [word] is not a complete
     * word in the lexicon.
     *
     * Exposed so the frequency POLICY (which words the beam's `lambda * logFreq` term
     * favours) is directly assertable — in particular that an injected contraction alias
     * carries a ~0 term while real vocabulary carries a positive one, and that injection
     * never lowers an existing word's frequency.
     */
    fun logFrequencyOf(word: String): Double? {
        var node = root
        for (ch in word) {
            node = node.childFor(ch) ?: return null
        }
        return if (node.isWord) node.logFreq else null
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
