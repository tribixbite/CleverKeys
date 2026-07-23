package tribixbite.cleverkeys.swipe

import java.util.Locale

/**
 * Pure contraction display overlay for swipe-decoded candidate lists (WP9 geo path).
 *
 * Every swipe dictionary stores contractions as apostrophe-free ALIASES ("theyd", "cest",
 * "dont") because the apostrophe is not a swipe key — the display forms ("they'd", "c'est",
 * "don't") exist only as runtime mappings. This overlay mirrors the neural vocab layer's
 * emission logic (OptimizedVocabulary ~:793-850) exactly:
 *
 *  1. PAIRED base (the alias IS a real word with a contraction sibling — "well"/"we'll",
 *     "its"/"it's", "girls"/"girl's"): keep the word and INJECT the variant(s) right after
 *     it. Checked FIRST because the binary contraction store misclassifies paired entries
 *     into the non-paired map (OptimizedVocabulary:463 carries the same guard).
 *  2. NON-PAIRED mapping with a REAL-WORD guard: replace the alias with the display form
 *     ONLY when the alias is not a common real word of the ACTIVE language. Neural guards
 *     with `frequency > 0.65`; here the equivalent is the dictionary ORDINAL (rank) —
 *     measured separation (2026-07-23 audit): junk aliases rank ≥ 1817 (en) / 1594 (fr) /
 *     1506 (it), real-word collisions rank ≤ 285 (fr la/les/dans/ma/dont…), ≤ 16 (de "im" —
 *     the 16th most common German word, which an unguarded en alias map would rewrite to
 *     "I'm"), ≤ 104 (fr "dont"). [realWordOrdinalMax] = 1200 sits in the gap with margin
 *     on both sides. A real-word alias keeps its place and gains the contraction as an
 *     injected variant (neural's "quest" + "qu'est" behavior).
 *  3. Case-insensitive dedupe keeps the first (highest-scored) occurrence.
 *
 * Variant placement: injected variants are APPENDED after all engine candidates (in base
 * order, scored just below their base) rather than spliced in next to the base — splicing
 * displaced genuinely distinct candidates out of the top ranks (observed on-device:
 * "would"'s variants pushed "world" from #2 to #4). This matches SuggestionHandler's
 * possessive-augment placement; replacements (rule 2b) DO keep the base's slot.
 *
 * Pure JVM (no Android imports) so the guard matrix is unit-testable in `runPureTests`.
 */
object ContractionOverlay {

    /** See class KDoc — the measured-gap threshold for "alias is a real common word". */
    const val REAL_WORD_ORDINAL_MAX = 1200

    /**
     * @param words decoded candidates, descending score order.
     * @param scores parallel scores (engine-relative).
     * @param pairedVariants alias → contraction variants when the alias is a PAIRED base.
     * @param nonPairedMapping alias → display form for non-paired contractions.
     * @param wordOrdinal lowercase word → ordinal frequency rank in the ACTIVE dictionary
     *   (0 = most frequent), or null when absent.
     * @return overlaid (words, scores) — same lists when nothing applies.
     */
    fun apply(
        words: List<String>,
        scores: List<Int>,
        pairedVariants: (String) -> List<String>?,
        nonPairedMapping: (String) -> String?,
        wordOrdinal: (String) -> Int?,
        realWordOrdinalMax: Int = REAL_WORD_ORDINAL_MAX,
    ): Pair<List<String>, List<Int>> {
        if (words.isEmpty()) return words to scores

        val outWords = ArrayList<String>(words.size + 4)
        val outScores = ArrayList<Int>(words.size + 4)
        val variantWords = ArrayList<String>(4)
        val variantScores = ArrayList<Int>(4)
        val seen = HashSet<String>(words.size * 2)

        fun emit(word: String, score: Int) {
            if (seen.add(word.lowercase(Locale.ROOT))) {
                outWords.add(word)
                outScores.add(score)
            }
        }

        fun deferVariant(word: String, score: Int) {
            variantWords.add(word)
            variantScores.add(score)
        }

        for (i in words.indices) {
            val word = words[i]
            val lower = word.lowercase(Locale.ROOT)
            val score = scores.getOrElse(i) { 0 }

            val paired = pairedVariants(lower)
            if (!paired.isNullOrEmpty()) {
                // Rule 1: real word with contraction sibling(s) — keep + append variants.
                emit(word, score)
                for (variant in paired) deferVariant(variant, score - 1)
                continue
            }

            val mapped = nonPairedMapping(lower)
            if (mapped != null) {
                val ordinal = wordOrdinal(lower)
                if (ordinal != null && ordinal < realWordOrdinalMax) {
                    // Rule 2a: alias is a common real word of this language (de "im",
                    // fr "dont") — keep it, append the contraction as a variant.
                    emit(word, score)
                    deferVariant(mapped, score - 1)
                } else {
                    // Rule 2b: junk alias ("theyd", "cest") — replace with display form,
                    // keeping the base's rank slot.
                    emit(mapped, score)
                }
                continue
            }

            emit(word, score)
        }
        // Appended AFTER all engine candidates so injections never displace distinct
        // words from the top ranks (see class KDoc).
        for (i in variantWords.indices) emit(variantWords[i], variantScores[i])
        return outWords to outScores
    }
}
