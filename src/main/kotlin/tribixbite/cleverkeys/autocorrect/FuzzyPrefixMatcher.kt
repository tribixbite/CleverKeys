package tribixbite.cleverkeys.autocorrect

import tribixbite.cleverkeys.AccentNormalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Typo-tolerant PREFIX matching for the tap-typing suggestion bar.
 *
 * # Problem
 *
 * The bar's candidates come from a strict prefix index: typing `pka` (a fat-fingered
 * `pla`, `k` sits next to `l`) yields NO suggestions, and `pkay` never lists `play`,
 * even though the space-bar autocorrect would fix the finished word. This matcher finds
 * dictionary words whose PREFIX is within a small keyboard-aware edit budget of what was
 * typed, so the bar can offer `play`, `place`, `plan`… for `pka` and `play` for `pkay`.
 *
 * # Cost model (optimal-string-alignment distance, keyboard-weighted)
 *
 * | edit                                                   | cost |
 * |--------------------------------------------------------|------|
 * | same letter                                            | 0    |
 * | accent variant of the same letter (`e` ↔ `é`)          | [ACCENT_VARIANT_COST] |
 * | substitution by a NEIGHBOURING key on the active layout| [ADJACENT_SUBSTITUTION_COST] |
 * | swap of two adjacent letters (`teh` → `the`)           | [TRANSPOSITION_COST] |
 * | any other substitution, an insertion or a deletion     | [EDIT_COST] |
 *
 * Neighbourhood comes from [KeyAdjacency.areNeighbors], which is derived from the ACTIVE
 * layout's key geometry (`Keyboard2View.onLayout` → [KeyAdjacency.setLayout]); QWERTY is
 * only the boot-time default before the first layout pass.
 *
 * The PREFIX cost of a word is `min over L` of the distance between the typed string and
 * the word's first `L` letters — the typed text is always fully consumed, the word may
 * continue ("pka" → "pla|ce" costs one adjacent substitution).
 *
 * # Search (no dictionary scan)
 *
 * A depth-first walk over the IMPLICIT trie formed by a [PrefixSource]'s short-prefix
 * index (both the primary `WordPredictor.prefixIndex` and the secondary
 * `NormalizedPrefixIndex` index every word under its 1..3-letter prefixes, so "does a
 * word start with X" is a hash lookup for |X| ≤ 3). One DP row per trie level; a branch
 * is pruned the moment no extension can come back under budget. Below the indexed depth
 * the walk continues per word, inside the bucket of a surviving 3-letter node only.
 * A node whose prefix cost can no longer improve emits its whole bucket at once.
 * See [findMatches] for the bounds.
 *
 * Pure JVM (unit-tested in `FuzzyPrefixMatcherTest`); `WordPredictor` owns the ranking.
 */
object FuzzyPrefixMatcher {

    /**
     * Shortest typed prefix that gets fuzzy candidates. At two letters a single edit
     * reaches roughly a hundred other two-letter prefixes (2×25 substitutions, 3×26
     * insertions, 2 deletions, a swap) — most of the dictionary — so a "correction" is
     * noise, and exact two-letter completions are almost never empty anyway.
     */
    const val MIN_TYPED_LENGTH = 3

    /**
     * Longest typed prefix that gets fuzzy candidates. Past this the input is not a word
     * being typed (a pasted token, a key held down) and the per-level DP row would grow
     * for nothing.
     */
    const val MAX_TYPED_LENGTH = 24

    /** Insertion, deletion, or substitution by a key that is NOT a neighbour. */
    const val EDIT_COST = 1.0f

    /** Substitution by a neighbouring key — the fat-finger typo, priced at half an edit. */
    const val ADJACENT_SUBSTITUTION_COST = 0.5f

    /**
     * Swap of two adjacent letters. Between an adjacent-key substitution and a full edit:
     * a swap is a single slip, but it moves two letters, so it must not beat the
     * one-neighbour-key slip. Plain Levenshtein would charge 2 edits for it.
     */
    const val TRANSPOSITION_COST = 0.6f

    /**
     * Same base letter, different accent (`cafe` typed for `café`). Cheapest non-zero
     * edit: on a layout without the accented key this is not a typo at all.
     */
    const val ACCENT_VARIANT_COST = 0.25f

    /**
     * Allowed prefix cost for a typed length, or 0 when fuzzy matching is off for it.
     *
     * - 3 letters → 0.6: ONE neighbouring-key slip or one swap — a distant substitution
     *   or an insertion/deletion in a 3-letter prefix changes a third of it and floods
     *   the bar with unrelated words.
     * - 4 letters → 1.0: one edit of any kind.
     * - 5–7 letters → 1.5: one edit plus one neighbouring-key slip.
     * - 8+ letters → 2.0: two edits.
     */
    fun budgetFor(typedLength: Int): Float = when {
        typedLength < MIN_TYPED_LENGTH || typedLength > MAX_TYPED_LENGTH -> 0f
        typedLength == 3 -> 0.6f
        typedLength == 4 -> 1.0f
        typedLength <= 7 -> 1.5f
        else -> 2.0f
    }

    /**
     * Budget for the first [depth] letters of a candidate while matching a [typedLength]-letter
     * input: the full [budgetFor] is only available once the candidate is as long as the
     * input; its first 4 letters may spend at most one edit (1.0), 5–7 letters 1.5.
     *
     * Why: typing errors spread out — a word with two slips inside its first three letters
     * is not what an 8-letter input was aiming at, and without this cap an 8-letter input's
     * 2.0 budget would let nearly every 3-letter prefix of the dictionary survive the shallow
     * levels (measured: `wuestion` 26 ms per keystroke, vs ~1 ms capped). One insertion,
     * deletion, slip or swap near the start still passes (`wuestion` → `question`,
     * `pkay` → `pay`).
     *
     * Checked against the cheapest alignment of the candidate's first [depth] letters to any
     * typed prefix (allowing a swap in progress) — see [findMatches].
     */
    fun depthBudget(depth: Int, typedLength: Int): Float =
        minOf(budgetFor(typedLength), budgetFor(minOf(maxOf(depth, 4), typedLength)))

    /**
     * An index of words by short prefix — the implicit trie the matcher walks.
     *
     * Contract: [wordsWithPrefix] returns every word that starts with `prefix` (words are
     * indexed under ALL their prefixes of length 1..[indexedDepth], including the word
     * itself when it is that short), or null/empty when no word does. The returned words
     * must be in the same form the typed string is compared in (the secondary index hands
     * back accent-normalized forms and is queried with a normalized typed string).
     */
    interface PrefixSource {
        /** Longest prefix length [wordsWithPrefix] answers directly (3 for both indices). */
        val indexedDepth: Int

        /** Words starting with [prefix], `1 ≤ prefix.length ≤ indexedDepth`. */
        fun wordsWithPrefix(prefix: String): Collection<String>?

        /** Every character that can follow a prefix — the trie's branching alphabet. */
        fun alphabet(): CharArray
    }

    /**
     * The branching alphabet of an index: every distinct character of its prefix [keys],
     * sorted. All positions, not only first letters — a letter that never starts a word
     * (German `ß`, Greek final `ς`) still branches at depths 2–3. O(total key length);
     * callers cache it per index.
     */
    fun alphabetOfKeys(keys: Collection<String>): CharArray {
        val seen = HashSet<Char>()
        for (k in keys) for (c in k) seen.add(c)
        return seen.sorted().toCharArray()
    }

    /**
     * Substitution cost of typing [typed] where the word has [intended]. See the class
     * table. Case-insensitive.
     */
    fun substitutionCost(typed: Char, intended: Char): Float {
        if (typed == intended) return 0f
        val a = typed.lowercaseChar()
        val b = intended.lowercaseChar()
        if (a == b) return 0f
        // ASCII letters can never be accent variants of each other: skip the fold.
        if (!(a in 'a'..'z' && b in 'a'..'z') && baseLetter(a) == baseLetter(b)) {
            return ACCENT_VARIANT_COST
        }
        return if (KeyAdjacency.areNeighbors(a, b)) ADJACENT_SUBSTITUTION_COST else EDIT_COST
    }

    /** Memo of accent folds; the alphabet is tiny, the fold ([AccentNormalizer]) is not. */
    private val baseLetterCache = ConcurrentHashMap<Char, Char>()

    private fun baseLetter(c: Char): Char =
        baseLetterCache.getOrPut(c) { AccentNormalizer.normalize(c.toString()).firstOrNull() ?: c }

    /**
     * Full (whole-string) keyboard-weighted OSA distance — the same cost model as the
     * prefix search, used to tell a whole-word correction (`pkay` → `play`) from a prefix
     * completion (`pka` → `place`). Returns a value `> maxCost` as soon as the answer is
     * known to exceed it.
     */
    fun distance(typed: String, word: String, maxCost: Float = Float.MAX_VALUE): Float {
        val n = typed.length
        val m = word.length
        var prev2 = FloatArray(n + 1)
        var prev = FloatArray(n + 1) { it * EDIT_COST }
        var curr = FloatArray(n + 1)
        for (d in 1..m) {
            fillRow(typed, word, d, prev2, prev, curr)
            var rowMin = curr[0]
            for (j in 1..n) if (curr[j] < rowMin) rowMin = curr[j]
            if (rowMin > maxCost && (d < 2 || minOf(prev) + TRANSPOSITION_COST > maxCost)) {
                return rowMin
            }
            val t = prev2; prev2 = prev; prev = curr; curr = t
        }
        return prev[n]
    }

    /**
     * All words whose prefix cost against [typed] is within [budget], with their best
     * (lowest) prefix cost.
     *
     * Precisely: a word matches at prefix length L when the distance of its first L letters to
     * [typed] is ≤ [budget] AND, for every depth d ≤ L, the cheapest alignment of its first d
     * letters to any typed prefix (allowing a swap in progress) is ≤ [depthBudget]`(d)`. The
     * reported cost is the minimum over matching L. [typed] must already be in the source's form (lowercase;
     * accent-normalized for the secondary index).
     *
     * Bounds: at most `|alphabet|` hash probes per surviving node on levels 1..3, and one
     * short DP row per letter per word only inside surviving 3-letter buckets. Rows are
     * `typed.length + 1` floats; no allocation per probe beyond the probe key string.
     *
     * @return word → prefix cost; empty when [budget] is 0 (see [budgetFor]).
     */
    fun findMatches(typed: String, source: PrefixSource, budget: Float = budgetFor(typed.length)): Map<String, Float> {
        val out = HashMap<String, Float>()
        if (budget <= 0f || typed.isEmpty()) return out
        val n = typed.length
        // Row d = distance of the first d candidate letters to every typed prefix. The
        // search can never go deeper than n + budget/EDIT_COST letters (each extra letter
        // beyond the typed length costs a full insertion), so size the stack for that.
        val maxDepth = n + (budget / EDIT_COST).toInt() + 1
        val rows = Array(maxDepth + 1) { FloatArray(n + 1) }
        for (j in 0..n) rows[0][j] = j * EDIT_COST
        val path = CharArray(maxDepth)
        Walk(typed, source, budget, rows, path, out).descend(depth = 0, prefix = "")
        return out
    }

    /** Recursion state for one [findMatches] call (keeps the hot loop free of captures). */
    private class Walk(
        val typed: String,
        val source: PrefixSource,
        val budget: Float,
        val rows: Array<FloatArray>,
        val path: CharArray,
        val out: HashMap<String, Float>
    ) {
        private val n = typed.length
        private val alphabet = source.alphabet()

        /**
         * Substitution costs for ASCII letters, [j] = typed index, [c − 'a'] = candidate
         * letter — the DP's inner loop asks for these millions of times per search.
         */
        private val asciiSub: Array<FloatArray> = Array(n) { j ->
            FloatArray(26) { c -> substitutionCost(typed[j], 'a' + c) }
        }

        private fun sub(j: Int, c: Char): Float =
            if (c in 'a'..'z') asciiSub[j][c - 'a'] else substitutionCost(typed[j], c)

        /** Expand every child of the node spelled by [prefix] (at [depth]). */
        fun descend(depth: Int, prefix: String) {
            if (depth >= rows.size - 1) return
            for (c in alphabet) {
                val key = prefix + c
                val bucket = source.wordsWithPrefix(key)
                if (bucket.isNullOrEmpty()) continue
                path[depth] = c
                val d = depth + 1
                computeRow(d)
                val cost = rows[d][n]
                // Past the depth budget nothing under this node can match (see findMatches).
                val bound = lowerBound(d)
                if (bound > depthBudget(d, n)) continue
                if (cost <= budget) emitAll(bucket, cost)
                // Nothing deeper can beat this node's cost → the bucket already carries
                // every word under it at its best cost.
                if (cost <= bound) continue
                if (d < source.indexedDepth) {
                    descend(d, key)
                } else {
                    for (word in bucket) continueWord(word, d, cost)
                }
            }
        }

        /** Continue the DP along [word]'s own letters below the indexed depth. */
        private fun continueWord(word: String, fromDepth: Int, bestSoFar: Float) {
            var best = bestSoFar
            var d = fromDepth
            while (d < word.length && d < rows.size - 1) {
                path[d] = word[d]
                d++
                computeRow(d)
                val bound = lowerBound(d)
                if (bound > depthBudget(d, n)) break
                val cost = rows[d][n]
                if (cost < best) best = cost
                if (cost <= bound) break
            }
            if (best <= budget) offer(word, best)
        }

        private fun emitAll(bucket: Collection<String>, cost: Float) {
            for (word in bucket) offer(word, cost)
        }

        private fun offer(word: String, cost: Float) {
            val prev = out[word]
            if (prev == null || cost < prev) out[word] = cost
        }

        /**
         * Smallest cost this or any deeper row can reach: every cell of row d+1 extends a cell
         * of row d (insert/substitute/delete, all ≥ 0) or, via a swap, a cell of row
         * d−1 plus [TRANSPOSITION_COST].
         */
        private fun lowerBound(d: Int): Float {
            val here = minOf(rows[d])
            return if (d >= 2) minOf(here, minOf(rows[d - 1]) + TRANSPOSITION_COST) else here
        }

        /** Row d from rows d−1 and d−2 for candidate letter path[d−1]. */
        private fun computeRow(d: Int) {
            val curr = rows[d]
            val prev = rows[d - 1]
            val cd = path[d - 1]
            curr[0] = d * EDIT_COST
            for (j in 1..n) {
                val tj = typed[j - 1]
                var v = prev[j - 1] + sub(j - 1, cd)
                val omitted = prev[j] + EDIT_COST      // word letter the user skipped
                if (omitted < v) v = omitted
                val extra = curr[j - 1] + EDIT_COST    // typed letter the word lacks
                if (extra < v) v = extra
                if (d >= 2 && j >= 2 && cd == typed[j - 2] && path[d - 2] == tj && cd != tj) {
                    val swapped = rows[d - 2][j - 2] + TRANSPOSITION_COST
                    if (swapped < v) v = swapped
                }
                curr[j] = v
            }
        }
    }

    /** Standalone row fill for [distance] (same recurrence as [Walk.computeRow]). */
    private fun fillRow(typed: String, word: String, d: Int, prev2: FloatArray, prev: FloatArray, curr: FloatArray) {
        val cd = word[d - 1]
        curr[0] = d * EDIT_COST
        for (j in 1..typed.length) {
            val tj = typed[j - 1]
            var v = prev[j - 1] + substitutionCost(tj, cd)
            val omitted = prev[j] + EDIT_COST
            if (omitted < v) v = omitted
            val extra = curr[j - 1] + EDIT_COST
            if (extra < v) v = extra
            if (d >= 2 && j >= 2 && cd == typed[j - 2] && word[d - 2] == tj && cd != tj) {
                val swapped = prev2[j - 2] + TRANSPOSITION_COST
                if (swapped < v) v = swapped
            }
            curr[j] = v
        }
    }

    private fun minOf(row: FloatArray): Float {
        var m = row[0]
        for (i in 1 until row.size) if (row[i] < m) m = row[i]
        return m
    }
}
