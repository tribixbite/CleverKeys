package tribixbite.cleverkeys.swipe.ctc

import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader

/**
 * Puts a CKDT v2 dictionary's rank bytes onto the frequency scale the CTC trie expects.
 *
 * A CKDT canonical record stores a uint8 **rank** (0 = most frequent), not a frequency,
 * so the CTC lexicon reads it as `freq = max(1, 255 − rank)` — an inverted, full-range
 * 1–255 scale. That is the same magnitude range as the AOSP-like scores the trie's
 * `ln(freq)` term was designed for, but a much WIDER log span than
 * `en_enhanced.json`'s compressed 134–255 scores, which is exactly why λ is
 * per-language (`CtcScoringParams.presetFor`, evidence in
 * `docs/eval/2026-08-15-ctc-per-language-lambda.md`).
 *
 * The binary parsing itself is NOT duplicated here: [CkdtDictionaryReader] already
 * parses the format for the geometric engine and is reused verbatim (pure JVM, no
 * Android imports).
 */
object CtcCkdtLexicon {

    /** Top of the CKDT-derived scale — the frequency of a rank-0 (most frequent) word. */
    const val RANK_SCALE_TOP = 255.0

    /** Floor, so `ln(freq)` stays finite for the rank-255 tail. */
    const val MIN_FREQ = 1.0

    /** `max(1, 255 − rank)` — the scale the λ sweep measured against. */
    fun freqForRank(rank: Int): Double = (RANK_SCALE_TOP - rank).coerceAtLeast(MIN_FREQ)

    /**
     * CKDT entries → `(canonical word, frequency)` pairs in the reader's order
     * (rank ascending, file order as the tie-break), ready for
     * [CtcLexiconMerge.merge] and then [CtcAzProjection.projectLexicon].
     */
    fun frequencyPairs(entries: List<CkdtDictionaryReader.Entry>): List<Pair<String, Double>> =
        entries.map { it.word to freqForRank(it.rank) }
}
