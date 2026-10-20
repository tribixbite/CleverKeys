package tribixbite.cleverkeys.autocorrect

/**
 * Rule C of `docs/eval/2026-10-18-channel-autocorrect-ranking.md` §1.1: the edit cost `E(t, c)`
 * of turning typed word `t` into dictionary candidate `c`, as an optimal-string-alignment
 * (restricted Damerau) distance with keyboard-aware weights from [ChannelWeights]:
 *
 * - substitution `a → b`: `1 + distanceCost · KeyAdjacency.keyDistance(a, b)`, so a neighbour
 *   slip is cheaper than a wrong key across the board (layout-aware: the active layout's grid
 *   for non-Latin scripts);
 * - an extra letter in `t` or a missing letter of `c`: `indelCost`, or `doubleCost` when that
 *   letter repeats an adjacent identical letter (`helo` → `hello`, `gamees` → `games`);
 * - two adjacent letters swapped: `transpositionCost`;
 * - plus `shortWordDeletionCost` once when the candidate is shorter than the typed word and has
 *   at most [SHORT_WORD_MAX_LENGTH] letters (round 3, eval doc §7).
 *
 * Both words are compared as given; callers pass lowercase.
 */
object ChannelEditCost {

    /** Longest candidate the short-word deletion term ([ChannelWeights.shortWordDeletionCost]) covers. */
    const val SHORT_WORD_MAX_LENGTH = 2

    /**
     * 1 when the short-word deletion term applies to [typed] → [candidate] under [scope], else 0.
     * [ShortDeletionScope.HEBREW] limits it to typed words written in Hebrew script.
     * TODO: Arabic has the same attached one-letter prefixes (و ب ل ف); extend the scope only
     *  after an Arabic evaluation exists (eval doc §11).
     */
    fun shortDeletion(typed: String, candidate: String, scope: ShortDeletionScope = ShortDeletionScope.ALL): Float =
        if (candidate.length < typed.length && candidate.length <= SHORT_WORD_MAX_LENGTH &&
            (scope == ShortDeletionScope.ALL || isHebrewScript(typed))
        ) 1f else 0f

    /** Every letter is a Hebrew letter (U+05D0–U+05EA, final forms included). */
    fun isHebrewScript(word: String): Boolean = word.isNotEmpty() && word.all { it in '\u05D0'..'\u05EA' }

    fun cost(typed: String, candidate: String, w: ChannelWeights): Float =
        alignmentCost(typed, candidate, w) +
            w.shortWordDeletionCost * shortDeletion(typed, candidate, w.shortDeletionScope)

    /** The optimal-string-alignment part of [cost]. */
    private fun alignmentCost(typed: String, candidate: String, w: ChannelWeights): Float {
        val n = typed.length
        val m = candidate.length
        // Three rolling rows: transposition reads two rows back.
        var prev2 = FloatArray(m + 1)
        var prev = FloatArray(m + 1)
        var cur = FloatArray(m + 1)
        for (j in 1..m) prev[j] = prev[j - 1] + letterCost(candidate, j - 1, w)
        for (i in 1..n) {
            val del = letterCost(typed, i - 1, w)
            cur[0] = prev[0] + del
            val a = typed[i - 1]
            for (j in 1..m) {
                val b = candidate[j - 1]
                var best = prev[j] + del                                   // extra letter in typed
                val ins = cur[j - 1] + letterCost(candidate, j - 1, w)     // letter missing from typed
                if (ins < best) best = ins
                val sub = prev[j - 1] + if (a == b) 0f else 1f + w.distanceCost * KeyAdjacency.keyDistance(a, b)
                if (sub < best) best = sub
                if (i > 1 && j > 1 && a == candidate[j - 2] && typed[i - 2] == b && a != b) {
                    val swap = prev2[j - 2] + w.transpositionCost
                    if (swap < best) best = swap
                }
                cur[j] = best
            }
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** Index of each component in [alignmentFeatures]' result. */
    const val F_SUBSTITUTIONS = 0
    const val F_KEY_DISTANCE = 1
    const val F_INDELS = 2
    const val F_DOUBLES = 3
    const val F_SWAPS = 4
    const val F_SHORT_DELETION = 5
    const val FEATURE_COUNT = 6

    /**
     * The edit counts of one optimal alignment under [w]: substitutions, their summed key
     * distance, plain insertions/deletions, doubled-letter insertions/deletions, swaps, and the
     * 0/1 short-word deletion indicator ([shortDeletion]).
     * `cost(t, c, w)` equals [priced] of this vector under the same [w]. Used by
     * `ChannelRankingEval` to price the same alignment under many weight vectors without
     * re-running the DP; shares the recurrence with [cost] (same order of the min terms).
     */
    internal fun alignmentFeatures(typed: String, candidate: String, w: ChannelWeights): FloatArray {
        val n = typed.length
        val m = candidate.length
        val d = Array(n + 1) { FloatArray(m + 1) }
        // Back-pointer per cell: 0 start, 1 deletion, 2 insertion, 3 match/substitution, 4 swap.
        val op = Array(n + 1) { ByteArray(m + 1) }
        for (j in 1..m) { d[0][j] = d[0][j - 1] + letterCost(candidate, j - 1, w); op[0][j] = 2 }
        for (i in 1..n) {
            val del = letterCost(typed, i - 1, w)
            d[i][0] = d[i - 1][0] + del; op[i][0] = 1
            val a = typed[i - 1]
            for (j in 1..m) {
                val b = candidate[j - 1]
                var best = d[i - 1][j] + del
                var o: Byte = 1
                val ins = d[i][j - 1] + letterCost(candidate, j - 1, w)
                if (ins < best) { best = ins; o = 2 }
                val sub = d[i - 1][j - 1] + if (a == b) 0f else 1f + w.distanceCost * KeyAdjacency.keyDistance(a, b)
                if (sub < best) { best = sub; o = 3 }
                if (i > 1 && j > 1 && a == candidate[j - 2] && typed[i - 2] == b && a != b) {
                    val swap = d[i - 2][j - 2] + w.transpositionCost
                    if (swap < best) { best = swap; o = 4 }
                }
                d[i][j] = best
                op[i][j] = o
            }
        }
        val f = FloatArray(FEATURE_COUNT)
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when (op[i][j].toInt()) {
                1 -> { f[if (isDoubled(typed, i - 1)) F_DOUBLES else F_INDELS] += 1f; i-- }
                2 -> { f[if (isDoubled(candidate, j - 1)) F_DOUBLES else F_INDELS] += 1f; j-- }
                3 -> {
                    if (typed[i - 1] != candidate[j - 1]) {
                        f[F_SUBSTITUTIONS] += 1f
                        f[F_KEY_DISTANCE] += KeyAdjacency.keyDistance(typed[i - 1], candidate[j - 1])
                    }
                    i--; j--
                }
                4 -> { f[F_SWAPS] += 1f; i -= 2; j -= 2 }
                else -> error("alignment back-pointer missing at ($i, $j)")
            }
        }
        f[F_SHORT_DELETION] = shortDeletion(typed, candidate, w.shortDeletionScope)
        return f
    }

    /** The edit cost of an [alignmentFeatures] vector under [w]. */
    fun priced(f: FloatArray, w: ChannelWeights): Float =
        f[F_SUBSTITUTIONS] + w.distanceCost * f[F_KEY_DISTANCE] + w.indelCost * f[F_INDELS] +
            w.doubleCost * f[F_DOUBLES] + w.transpositionCost * f[F_SWAPS] +
            w.shortWordDeletionCost * f[F_SHORT_DELETION]

    private fun isDoubled(s: String, k: Int): Boolean {
        val c = s[k]
        return (k > 0 && s[k - 1] == c) || (k + 1 < s.length && s[k + 1] == c)
    }

    /** Cost of inserting or deleting `s[k]`: the doubled-letter cost when it repeats a neighbour. */
    private fun letterCost(s: String, k: Int, w: ChannelWeights): Float {
        return if (isDoubled(s, k)) w.doubleCost else w.indelCost
    }
}

/**
 * True when [typed] is [word] with one or more doubled letters reduced to a single letter: deleting
 * letters of [word], each equal to the letter before it, gives [typed] (`helo` ← `hello`,
 * `spaming` ← `spamming`). Rule C2's guard bypass (eval doc §3.1) — structural, so it does not
 * depend on any weight.
 */
fun isDoubleReduction(typed: String, word: String): Boolean {
    if (word.length <= typed.length) return false
    var i = 0
    var j = 0
    while (j < word.length) {
        if (i < typed.length && typed[i] == word[j]) { i++; j++; continue }
        if (j > 0 && word[j] == word[j - 1]) { j++; continue }
        return false
    }
    return i == typed.length
}

/** A gated autocorrect candidate as rule C sees it. */
data class ChannelCandidate(val word: String, val frequency: Int, val isAlias: Boolean)

/**
 * Rule C's choice among gated candidates: minimum `E + λ · rank`. Ties within [TIE_EPSILON]
 * go to an alias key, then the higher frequency, then the lexicographically smaller word — a
 * total order, so the winner never depends on dictionary iteration order (the band fold's
 * pairwise rules do).
 */
object ChannelRanker {

    /** Costs closer than this are equal (float noise from the DP sums). */
    const val TIE_EPSILON = 1e-4f

    /**
     * Rarity of [frequency] in rank steps of `maxFrequency / 255`, the unit of the shipped
     * `.bin` rank byte (0 = the most frequent word) and of the DD-M margin.
     */
    fun rankSteps(frequency: Int, maxFrequency: Int): Float =
        if (maxFrequency <= 0) 0f
        else (maxFrequency - frequency).coerceAtLeast(0).toFloat() * 255f / maxFrequency

    /** `E + λ · rank − (alias ? β : 0)` for one candidate (β: round 2, eval doc §3.1). */
    fun totalCost(
        editCost: Float, frequency: Int, maxFrequency: Int, frequencyWeight: Float,
        isAlias: Boolean = false, aliasBonus: Float = 0f,
    ): Float = editCost + frequencyWeight * rankSteps(frequency, maxFrequency) - if (isAlias) aliasBonus else 0f

    /**
     * Index of the winning candidate in [candidates] given each one's edit cost in
     * [editCosts] (same order), or −1 when there are none.
     */
    fun pick(
        candidates: List<ChannelCandidate>, editCosts: FloatArray, maxFrequency: Int, frequencyWeight: Float,
        aliasBonus: Float = 0f,
    ): Int {
        var best = -1
        var bestCost = Float.POSITIVE_INFINITY
        for (k in candidates.indices) {
            val c = candidates[k]
            val cost = totalCost(editCosts[k], c.frequency, maxFrequency, frequencyWeight, c.isAlias, aliasBonus)
            val better = when {
                best < 0 -> true
                cost < bestCost - TIE_EPSILON -> true
                cost > bestCost + TIE_EPSILON -> false
                else -> {
                    val b = candidates[best]
                    when {
                        c.isAlias != b.isAlias -> c.isAlias
                        c.frequency != b.frequency -> c.frequency > b.frequency
                        else -> c.word < b.word
                    }
                }
            }
            if (better) { best = k; bestCost = cost }
        }
        return best
    }
}
