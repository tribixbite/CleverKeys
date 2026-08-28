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
 */
object CtcRankMerger {
    data class Item(val word: String, val score: Int, val language: String)

    fun merge(
        primaryLanguage: String,
        primaryWords: List<String>,
        secondaryLanguage: String?,
        secondaryWords: List<String>,
        limit: Int,
    ): List<Item> {
        if (limit <= 0) return emptyList()
        val ranked = ArrayList<Item>(primaryWords.size + secondaryWords.size)
        primaryWords.forEachIndexed { rank, word ->
            ranked.add(Item(word, 1000 / (rank + 1), primaryLanguage))
        }
        if (!secondaryLanguage.isNullOrBlank() && secondaryLanguage != primaryLanguage) {
            secondaryWords.forEachIndexed { rank, word ->
                ranked.add(Item(word, 920 / (rank + 1), secondaryLanguage))
            }
        }
        ranked.sortByDescending { it.score }

        val seen = HashSet<String>()
        val result = ArrayList<Item>(limit)
        for (item in ranked) {
            if (!seen.add(item.word.lowercase(Locale.ROOT))) continue
            result.add(item)
            if (result.size == limit) break
        }
        return result
    }
}
