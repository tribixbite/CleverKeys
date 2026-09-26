package tribixbite.cleverkeys.swipe

import java.util.Locale

/**
 * Pure contraction display overlay for swipe-decoded candidate lists (WP9 geo path).
 *
 * Every swipe dictionary stores contractions as apostrophe-free ALIASES ("theyd", "cest",
 * "dont") because the apostrophe is not a swipe key — the display forms ("they'd", "c'est",
 * "don't") exist only as runtime mappings. This overlay mirrors the (deleted) vocabulary layer's
 * emission logic (OptimizedVocabulary ~:793-850), except for variant placement:
 *
 *  1. PAIRED base (the alias IS a real word with a contraction sibling — "well"/"we'll",
 *     "its"/"it's", "girls"/"girl's"): keep the word and INJECT the variant(s) — placed
 *     per "Variant placement" below (spliced beside the base, or at the tail). Checked FIRST because the binary contraction store misclassifies paired entries
 *     into the non-paired map (OptimizedVocabulary:463 carries the same guard).
 *  2. NON-PAIRED mapping with a REAL-WORD guard: replace the alias with the display form
 *     ONLY when the alias is not a common real word of the ACTIVE language. The old guards
 *     with `frequency > 0.65`; here the equivalent is the dictionary ORDINAL (rank) —
 *     measured separation (2026-07-23 audit): junk aliases rank ≥ 1817 (en) / 1594 (fr) /
 *     1506 (it), real-word collisions rank ≤ 285 (fr la/les/dans/ma/dont…), ≤ 16 (de "im" —
 *     the 16th most common German word, which an unguarded en alias map would rewrite to
 *     "I'm"), ≤ 104 (fr "dont"). [realWordOrdinalMax] = 1200 sits in the gap with margin
 *     on both sides. A real-word alias keeps its place and gains the contraction as an
 *     injected variant (the old vocabulary's "quest" + "qu'est" behavior).
 *  3. Case-insensitive dedupe keeps the first (highest-scored) occurrence.
 *
 * ## Variant placement (rewritten 2026-09-26 — learning-system audit RC1 + RC3)
 *
 * History: b2d7b908 (2026-07-22) spliced EVERY variant right after its base; on-device,
 * "would"'s two variants pushed the distinct candidate "world" from #2 to #4. The fix
 * appended all variants after every engine candidate — which over-corrected: swiping
 * "she'd" auto-inserted "shed" and left "she'd" at slot 5-9, usually off-screen, for the
 * whole paired pronoun set (shed/id/ill/wed/shell/well/hell).
 *
 * The placement now separates the two things b2d7b908 lumped together:
 *
 *  - A **projection variant** is one whose apostrophe-free form IS the decoded surface
 *    (`shed` → `she'd`, `well` → `we'll`). The trace spelled it exactly as much as it
 *    spelled the base; only the prior can tell them apart. At most ONE per base — the one
 *    with the highest known pairing frequency — is SPLICED next to its base.
 *  - Everything else stays at the TAIL, as before: non-projection variants (`would` →
 *    `wouldn't`/`would've`, `she` → `she'd`) are completions of a DIFFERENT trace, and a
 *    projection variant with no known frequency has no evidence for a top slot. That is
 *    exactly why "would"/"world" cannot regress: would's variants are non-projections.
 *    Every fr/it pairs-file entry (`lune` → `l'une`) has no frequency, so French and
 *    Italian placement is byte-for-byte unchanged — no elision can climb over a real word
 *    (the contraction-system skill's §2 casualties).
 *
 * Order within the splice: the variant goes AHEAD of its base only when its pairing
 * frequency ([pairedVariantFrequency]) beats the base's own lexicon frequency
 * ([baseFrequency]) by at least [PROMOTION_MARGIN] — i'd 211 vs id 196, i'll 212 vs ill 198,
 * we'd 193 vs wed 178 — so it becomes rank 0 and the auto-insert target when the base was
 * rank 0. well (223 vs we'll 202), hell, shell and shed (189 vs she'd 188) stay first. The
 * pairing values are measured (wordfreq zipf mapped through the isotonic fit of
 * en_enhanced.json — `scripts/extract_apostrophe_words.py --en-pairing-frequencies`).
 * Three exceptions keep the variant BEHIND the base:
 *
 *  - The lead is under [PROMOTION_MARGIN] (a near tie such as it's 229 vs its 225). The
 *    trace is identical for both spellings, so a small prior lead is a coin flip and the
 *    literal the user traced keeps the auto-insert; the variant is still one slot away.
 *
 *  - [baseFrequency] returns null. The caller supplies it only when the lexicon is on the
 *    pairing file's 0..255 byte scale (the CTC en_enhanced.json source); the geometric
 *    engine's CKDT ranks are not, so it never promotes.
 *  - The variant is a POSSESSIVE ([isPossessive]). The shipped possessive frequencies are
 *    unreliable against the lexicon: of the 69 pairs whose raw values would promote, 24
 *    disagree with wordfreq, among them `teams`→`team's`, `ones`→`one's`, `sons`→`son's`
 *    (measured 2026-09-26). A pronoun `'s` clitic (`she's`, `he's`) is not a possessive.
 *
 * Scores stay non-increasing in list order (the context rescorer re-sorts by score with an
 * index tie-break): a spliced pair shares the base's score, and a tail variant is clamped
 * to the last score already emitted.
 *
 * Pure JVM (no Android imports) so the guard matrix is unit-testable in `runPureTests`.
 */
object ContractionOverlay {

    /** See class KDoc — the measured-gap threshold for "alias is a real common word". */
    const val REAL_WORD_ORDINAL_MAX = 1200

    /**
     * Minimum lead, in en_enhanced.json byte units, a spliced projection variant needs over its
     * base's lexicon frequency to go AHEAD of it (become rank 0 / the auto-insert target).
     *
     * 6 bytes ≈ 0.3 zipf ≈ the variant being at least 2x as frequent in wordfreq: the fitted
     * zipf→byte map runs at 18–19 bytes per zipf unit over zipf 4–6.5, where every pronoun pair
     * lives. Chosen from the measured gaps, not tuned to them: every promotion the data makes
     * clears it with room (smallest: c'mon 13, i'll 14, i'd/we'd 15; he's/she's 31–32), and the
     * one pair under it is its/it's (4 bytes, 1.55x) — the classic grammatical confusable that
     * only syntax can resolve, where silently auto-inserting the apostrophe form over the traced
     * literal is the error users notice. Changing it moves auto-insert behaviour for the whole
     * pronoun set: re-read the table in `CtcContractionDisplayTest` first.
     */
    const val PROMOTION_MARGIN = 6

    /**
     * @param words decoded candidates, descending score order.
     * @param scores parallel scores (engine-relative).
     * @param pairedVariants alias → contraction variants when the alias is a PAIRED base.
     * @param nonPairedMapping alias → display form for non-paired contractions.
     * @param wordOrdinal lowercase word → ordinal frequency rank in the ACTIVE dictionary
     *   (0 = most frequent), or null when absent.
     * @param pairedVariantFrequency (lowercase base, variant) → the pairing frequency listed
     *   for that pair ([tribixbite.cleverkeys.ContractionManager.getPairedVariantFrequency]),
     *   or null when unknown. Default: none known — every paired variant goes to the tail.
     * @param baseFrequency lowercase word → its lexicon frequency ON THE PAIRING FILE'S 0..255
     *   BYTE SCALE, or null. Supply it only for a lexicon on that scale (CTC en); null means
     *   "not comparable", and a spliced variant then never goes ahead of its base.
     * @return overlaid (words, scores) — same lists when nothing applies.
     */
    fun apply(
        words: List<String>,
        scores: List<Int>,
        pairedVariants: (String) -> List<String>?,
        nonPairedMapping: (String) -> String?,
        wordOrdinal: (String) -> Int?,
        realWordOrdinalMax: Int = REAL_WORD_ORDINAL_MAX,
        pairedVariantFrequency: (base: String, variant: String) -> Int? = { _, _ -> null },
        baseFrequency: (String) -> Int? = { null },
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
                // Rule 1: real word with contraction sibling(s) — keep it; splice at most one
                // projection variant beside it and defer the rest (see class KDoc).
                val spliced = splicedVariant(lower, paired, pairedVariantFrequency)
                if (spliced == null) {
                    emit(word, score)
                } else {
                    val (variant, variantFreq) = spliced
                    val baseFreq = baseFrequency(lower)
                    val ahead = baseFreq != null &&
                        variantFreq - baseFreq >= PROMOTION_MARGIN &&
                        !isPossessive(variant)
                    if (ahead) {
                        emit(variant, score)
                        emit(word, score)
                    } else {
                        emit(word, score)
                        emit(variant, score)
                    }
                }
                for (variant in paired) {
                    if (variant != spliced?.first) deferVariant(variant, score - 1)
                }
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
        // Appended AFTER all engine candidates so they never displace distinct words from
        // the top ranks (see class KDoc); clamped so scores stay non-increasing.
        for (i in variantWords.indices) {
            val floor = outScores.lastOrNull() ?: variantScores[i]
            emit(variantWords[i], minOf(variantScores[i], floor))
        }
        return outWords to outScores
    }

    /**
     * The single variant of [base] to splice beside it: among [variants] whose
     * apostrophe-free form equals [base] AND whose pairing frequency is known, the most
     * frequent (earliest on a tie). Null when none qualifies — everything goes to the tail.
     */
    internal fun splicedVariant(
        base: String,
        variants: List<String>,
        pairedVariantFrequency: (base: String, variant: String) -> Int?,
    ): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        for (variant in variants) {
            if (!isProjectionOf(base, variant)) continue
            val freq = pairedVariantFrequency(base, variant) ?: continue
            if (best == null || freq > best.second) best = variant to freq
        }
        return best
    }

    /** True when [variant] minus its apostrophes (ASCII or typographic) spells [base]. */
    internal fun isProjectionOf(base: String, variant: String): Boolean =
        variant.filterNot { it == '\'' || it == '’' }.lowercase(Locale.ROOT) == base

    /**
     * Hosts whose `'s` is the clitic "is"/"has", not a possessive — a closed class of
     * pronouns and wh/deictic words (`she's`, `that's`, `where's`, `let's`).
     */
    private val CLITIC_S_HOSTS = setOf(
        "he", "she", "it", "that", "this", "what", "who", "where", "when", "why", "how",
        "there", "here", "let",
    )

    /**
     * True for an English possessive display form: ends in `'s` or `s'` and its host is not a
     * [CLITIC_S_HOSTS] pronoun. `team's`, `girls'`, `one's` → true; `she's`, `she'd` → false.
     */
    internal fun isPossessive(variant: String): Boolean {
        val v = variant.lowercase(Locale.ROOT).replace('’', '\'')
        return when {
            v.endsWith("'s") -> v.dropLast(2) !in CLITIC_S_HOSTS
            v.endsWith("s'") -> true
            else -> false
        }
    }
}
