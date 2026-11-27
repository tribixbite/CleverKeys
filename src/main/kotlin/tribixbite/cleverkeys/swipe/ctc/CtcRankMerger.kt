package tribixbite.cleverkeys.swipe.ctc

import java.util.Locale

/**
 * Merges primary/secondary CTC slates by rank, never by incomparable raw decoder scores.
 * Primary rank N receives `1000/(N+1)` and secondary rank N receives `920/(N+1)`.
 *
 * **`pref_secondary_prediction_weight` deliberately does NOT apply here (ARC-018).** That
 * slider is a multiplier on a TAP candidate's unified score (`WordPredictor`), where the
 * secondary candidate competes against primary candidates in the same score domain. This
 * merge has no such domain by construction: the two decodes run at per-lexicon λ scales
 * whose raw final scores are explicitly never compared, so the only quantity left is RANK
 * and the only tunable is the 920-vs-1000 head-room constant above. Scaling these rank
 * pseudo-scores by a user float would silently re-interleave the slate with a knob that
 * was never measured against swipe accuracy. If a swipe-side secondary preference is ever
 * wanted it must be its own rank-domain control with its own evidence — not this pref.
 * The slider's copy is scoped to typing accordingly (`multilang_secondary_weight_desc`).
 *
 * **The 920 head-room is a preference between DICTIONARY words, not a demotion of the user's own
 * words** (Note10+ r20, 2026-11-26). A secondary-list user word can carry an EARNED primary rank
 * (`secondaryEarnedRanks`, computed by [CtcSlateAssembly] from the rank the primary list itself
 * would give the word): it then scores `1000/(r+1)` like a primary word at rank r and sorts AHEAD
 * of the primary word at that rank — the primary list would have put it above that word — but
 * never below its plain `920/(N+1)` slot. Without earned ranks the merge is unchanged. A user word
 * that is ALSO in the primary slate (the same word in both custom lists) keeps the primary's
 * spelling and language label wherever it lands: the dedupe keeps the first entry, which is the
 * secondary copy on a tie or above its plain slot, and a secondary label would skip the primary's
 * possessive augment (audit C7). An ordinary secondary word keeps its own label as before.
 */
object CtcRankMerger {
    data class Item(val word: String, val score: Int, val language: String)

    /** A merge entry and whether it is a placed user word (ahead of an equal-score primary word). */
    private class Ranked(val item: Item, val placedUserWord: Boolean)

    /**
     * @param secondaryEarnedRanks secondary slate index → the rank that word earns in the primary
     *   slate (secondary-list user words only); empty for the plain rank merge.
     */
    fun merge(
        primaryLanguage: String,
        primaryWords: List<String>,
        secondaryLanguage: String?,
        secondaryWords: List<String>,
        limit: Int,
        secondaryEarnedRanks: Map<Int, Int> = emptyMap(),
    ): List<Item> {
        if (limit <= 0) return emptyList()
        val ranked = ArrayList<Ranked>(primaryWords.size + secondaryWords.size)
        primaryWords.forEachIndexed { rank, word ->
            ranked.add(Ranked(Item(word, 1000 / (rank + 1), primaryLanguage), placedUserWord = false))
        }
        if (!secondaryLanguage.isNullOrBlank() && secondaryLanguage != primaryLanguage) {
            // Lowercase → the primary's own spelling, only needed when something was placed.
            val primarySpelling: Map<String, String> = if (secondaryEarnedRanks.isEmpty()) {
                emptyMap()
            } else {
                HashMap<String, String>(primaryWords.size * 2).apply {
                    for (word in primaryWords) putIfAbsent(word.lowercase(Locale.ROOT), word)
                }
            }
            secondaryWords.forEachIndexed { rank, word ->
                val plain = 920 / (rank + 1)
                val earnedRank = secondaryEarnedRanks[rank]
                if (earnedRank == null) {
                    // An ordinary secondary word: the plain rank merge, label included.
                    ranked.add(Ranked(Item(word, plain, secondaryLanguage), placedUserWord = false))
                    return@forEachIndexed
                }
                val earned = 1000 / (earnedRank.coerceAtLeast(0) + 1)
                val placed = earned >= plain
                // A user word the primary slate also holds (in both custom lists) is ONE entry under
                // the primary's spelling and label, at the better of the two positions (audit C7).
                val inPrimary = primarySpelling[word.lowercase(Locale.ROOT)]
                val item = Item(
                    inPrimary ?: word,
                    if (placed) earned else plain,
                    if (inPrimary != null) primaryLanguage else secondaryLanguage,
                )
                ranked.add(Ranked(item, placedUserWord = placed))
            }
        }
        // Stable: equal scores keep insertion order (primary first), except that a placed user
        // word goes ahead of the primary word it ties with.
        ranked.sortWith(
            compareByDescending<Ranked> { it.item.score }.thenByDescending { it.placedUserWord }
        )

        val seen = HashSet<String>()
        val result = ArrayList<Item>(limit)
        for (entry in ranked) {
            if (!seen.add(entry.item.word.lowercase(Locale.ROOT))) continue
            result.add(entry.item)
            if (result.size == limit) break
        }
        return result
    }
}
