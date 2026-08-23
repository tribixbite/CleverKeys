package tribixbite.cleverkeys.swipe.ctc

import kotlin.math.abs

/**
 * Bounded dictionary rescue for a greedy CTC surface that the constrained beam did not return.
 * It never replaces the beam's rank one; callers insert matches below a non-empty beam slate.
 */
class CtcFuzzyRescue private constructor(
    private val byLengthAndInitial: Map<Pair<Int, Char>, List<Entry>>,
) {
    data class Entry(val word: String, val frequency: Double)

    fun find(
        greedy: String,
        existing: Set<String>,
        limit: Int = 2,
        scanBudget: Int = MAX_SCAN_WORDS,
    ): List<String> {
        if (limit <= 0 || greedy.length < MIN_WORD_LENGTH || greedy.any { it !in 'a'..'z' }) {
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

        fun fromFrequencies(frequencies: Map<String, Double>): CtcFuzzyRescue {
            val buckets = frequencies.entries
                .filter { (word, _) -> word.isNotEmpty() && word.all { it in 'a'..'z' } }
                .groupBy(
                    keySelector = { it.key.length to it.key.first() },
                    valueTransform = { Entry(it.key, it.value) },
                )
                .mapValues { (_, entries) ->
                    entries.sortedWith(compareByDescending<Entry> { it.frequency }.thenBy { it.word })
                }
            return CtcFuzzyRescue(buckets)
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
