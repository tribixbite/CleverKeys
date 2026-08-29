package tribixbite.cleverkeys.swipe.ctc

import kotlin.math.abs

/**
 * Bounded dictionary rescue for a greedy CTC surface that the constrained beam did not return.
 * [find] locates the matches; [Companion.mergeIntoBeam] merges them into a slate by APPENDING
 * them below every real beam word, so a rescue can never displace or outrank one.
 *
 * ## The index is alphabet-scoped, and that used to be hard-coded
 *
 * Both the index build ([Companion.fromFrequencies]) and the greedy admissibility check in
 * [find] filter on the ACTIVE EMISSION ALPHABET. Until the multi-script wiring both filtered on
 * `'a'..'z'` literally, which for a Cyrillic/Greek/Hebrew lexicon drops **every** word: the index
 * builds empty and rescue is silently inert — no exception, no log, just a feature that never
 * fires. The alphabet is therefore a required constructor input, not a defaulted one, so a new
 * script cannot forget it.
 */
class CtcFuzzyRescue private constructor(
    private val byLengthAndInitial: Map<Pair<Int, Char>, List<Entry>>,
    private val alphabet: Set<Char>,
) {
    data class Entry(val word: String, val frequency: Double)

    fun find(
        greedy: String,
        existing: Set<String>,
        limit: Int = 2,
        scanBudget: Int = MAX_SCAN_WORDS,
    ): List<String> {
        if (limit <= 0 || greedy.length < MIN_WORD_LENGTH || greedy.any { it !in alphabet }) {
            return emptyList()
        }
        val maxDistance = if (greedy.length <= 5) 1 else 2
        val matches = ArrayList<Pair<Entry, Int>>()
        var scanned = 0
        val lengths = ((greedy.length - maxDistance).coerceAtLeast(1)..greedy.length + maxDistance)
            .sortedWith(compareBy<Int> { abs(it - greedy.length) }.thenBy { it })
        for (length in lengths) {
            for (entry in byLengthAndInitial[length to greedy.first()].orEmpty()) {
                if (++scanned > scanBudget) break
                if (entry.word in existing) continue
                val distance = editDistanceAtMost(entry.word, greedy, maxDistance)
                if (distance <= maxDistance) matches.add(entry to distance)
            }
            if (scanned > scanBudget) break
        }
        return matches
            .sortedWith(
                compareBy<Pair<Entry, Int>> { it.second }
                    .thenByDescending { it.first.frequency }
                    .thenBy { it.first.word }
            )
            .asSequence()
            .map { it.first.word }
            .distinct()
            .take(limit)
            .toList()
    }

    companion object {
        const val MAX_SCAN_WORDS = 4_096
        private const val MIN_WORD_LENGTH = 3

        /**
         * Merges bounded dictionary [rescued] matches into a decoded beam slate, returning the
         * merged `(words, scores)` pair truncated to [topK] and always word/score aligned.
         *
         * **Semantics — rescued words APPEND BELOW the real beam.** They never interleave above
         * rank two, never displace a real beam word and never outrank one:
         *
         *  - **Empty beam** — the rescued words fill the slate directly with `1000 / (i + 1)`.
         *    A rescue is the only content there is, so there is nothing to rank against.
         *  - **Non-empty beam** — every real word and its score are kept untouched and in order.
         *    Rescued words are appended only into spare [topK] slots; a beam that already holds
         *    [topK] words gets no rescue at all (a full slate does not need one). Their scores
         *    descend strictly from `min(lastRealScore - 1, (topScore - 1) / 2)`, floored at 1.
         *    Ties at that floor are fine — the slate's order is positional, not score-derived.
         *
         * **Why append-below rather than the former rank-two interpolation** (CK-150-025):
         *
         *  a. A rescued word can never reach the context rescorer's rank-one promotion threshold.
         *     That guard is a score *ratio* (`SwipeContextRescorer.R_MIN = 0.5`, i.e. promotion
         *     needs `score >= topScore / 2.0`), and `(topScore - 1) / 2 < topScore / 2.0` holds
         *     for every positive integer — for even `topScore = 2k` it is `k - 1 < k`, for odd
         *     `2k + 1` it is `k < k + 0.5`. The old ceiling `topScore / 2` was integer division
         *     compared against a float, so eligibility turned on the PARITY of the top score
         *     (884 → `442 >= 442.0` promotable, 913 → `456 >= 456.5` blocked). That is gone.
         *     The floor of 1 cannot reintroduce it: it only ever RAISES a score to 1, and
         *     `1 < topScore / 2.0` for every `topScore >= 3` — on a real slate `topScore` is the
         *     maximum of a softmax over at most [topK] candidates scaled to 0..1000, hence at
         *     least `1000 / topK`. The floor can still tie or edge past a beam TAIL that itself
         *     rounded to 0, which is a distinction with no consequence: both are far below any
         *     threshold and the rescued word still sits last positionally.
         *  b. The beam's runner-up/top-1 ratio distribution — which downstream confidence logic
         *     reads — is no longer reshaped by rescue. The former insertion moved the measured
         *     median ratio 0.254 → 0.500, invalidating replay measurements taken before it.
         *  c. Rescue keeps its actual purpose: filling thin or empty slates with a bounded
         *     dictionary match the constrained beam missed.
         *
         * Pure and side-effect free so the invariants above are unit-testable; the Android
         * adapter and the pure-JVM replay engine both delegate here so there is one implementation.
         */
        fun mergeIntoBeam(
            words: List<String>,
            scores: List<Int>,
            rescued: List<String>,
            topK: Int,
        ): Pair<List<String>, List<Int>> {
            val limit = topK.coerceAtLeast(0)
            if (words.isEmpty()) {
                val fill = rescued.distinct().take(limit)
                return fill to fill.indices.map { 1000 / (it + 1) }
            }
            val mergedWords = ArrayList<String>(limit)
            val mergedScores = ArrayList<Int>(limit)
            for (i in 0 until minOf(words.size, limit)) {
                mergedWords.add(words[i])
                mergedScores.add(scores.getOrElse(i) { 0 })
            }
            if (rescued.isEmpty() || mergedWords.size >= limit) return mergedWords to mergedScores

            val topScore = mergedScores.first()
            val lastRealScore = mergedScores.last()
            var nextScore = minOf(lastRealScore - 1, (topScore - 1) / 2)
            val seen = HashSet(mergedWords)
            for (word in rescued) {
                if (mergedWords.size >= limit) break
                if (!seen.add(word)) continue
                mergedWords.add(word)
                mergedScores.add(nextScore.coerceAtLeast(MIN_RESCUE_SCORE))
                nextScore--
            }
            return mergedWords to mergedScores
        }

        /** Floor for an appended rescue score; see [mergeIntoBeam]'s tie note. */
        private const val MIN_RESCUE_SCORE = 1

        /**
         * Builds the rescue index over [frequencies], keeping only words spelled entirely in
         * [alphabet] — the emission alphabet the beam actually decodes over. See the class KDoc
         * for why this is required rather than defaulted to a–z.
         */
        fun fromFrequencies(
            frequencies: Map<String, Double>,
            alphabet: Set<Char>,
        ): CtcFuzzyRescue {
            val buckets = frequencies.entries
                .filter { (word, _) -> word.isNotEmpty() && word.all { it in alphabet } }
                .groupBy(
                    keySelector = { it.key.length to it.key.first() },
                    valueTransform = { Entry(it.key, it.value) },
                )
                .mapValues { (_, entries) ->
                    entries.sortedWith(compareByDescending<Entry> { it.frequency }.thenBy { it.word })
                }
            return CtcFuzzyRescue(buckets, alphabet)
        }

        /** Levenshtein distance with row-min early exit. */
        internal fun editDistanceAtMost(a: String, b: String, cap: Int): Int {
            if (abs(a.length - b.length) > cap) return cap + 1
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in a.indices) {
                current[0] = i + 1
                var rowMin = current[0]
                for (j in b.indices) {
                    current[j + 1] = minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + if (a[i] == b[j]) 0 else 1,
                    )
                    rowMin = minOf(rowMin, current[j + 1])
                }
                if (rowMin > cap) return cap + 1
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}
