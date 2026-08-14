package tribixbite.cleverkeys.swipe.ctc

import java.util.Locale

/**
 * Pure user-dictionary merge + frequency-ordinal computation for the CTC lexicon
 * (G5 audit H1/L3). Extracted from `CtcEngineAdapter.trieFor` so the merge policy
 * and the ordinal ranking that feeds `ContractionOverlay`'s real-word guard are
 * unit-testable in `runPureTests` (the adapter only does the Android I/O — asset
 * JSON parse and SharedPreferences reads — and delegates here).
 *
 * Merge policy (mirrors `GeometricEngineAdapter.mergeUserWords` semantics):
 *  - custom words FIRST, frequency clamped onto the 1..255 AOSP-like scale;
 *    custom overrides disabled (matching WordPredictor's customAndUserWords);
 *  - base words minus the disabled set;
 *  - dedupe on `lowercase(Locale.ROOT)` on BOTH steps (audit L3: a custom
 *    "Hello" must shadow the base "hello" — the trie lowercases at insert, so
 *    without case-folded dedupe the base frequency would silently win via the
 *    trie's max-retention rule);
 *  - insertion order is (custom in caller order, then base in caller order) —
 *    deterministic, and only affects beam tie-breaks.
 */
object CtcLexiconMerge {

    /** Frequency bounds of the AOSP-like scale the tuned λ expects (spec NFR-4). */
    const val MIN_FREQ = 1.0
    const val MAX_FREQ = 255.0

    /**
     * Merge [base] `{word: freq}` entries with user [custom] words (freq clamped to
     * [MIN_FREQ]..[MAX_FREQ]; blank words skipped) minus [disabled] words.
     * Case-insensitive dedupe: the first occurrence (custom before base, then
     * caller iteration order) wins.
     */
    fun merge(
        base: Iterable<Pair<String, Double>>,
        custom: Iterable<Pair<String, Int>>,
        disabled: Set<String>,
    ): LinkedHashMap<String, Double> {
        val disabledLower = disabled.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        val seenLower = HashSet<String>()
        val merged = LinkedHashMap<String, Double>()
        for ((word, freq) in custom) {
            if (word.isBlank()) continue
            if (!seenLower.add(word.lowercase(Locale.ROOT))) continue
            merged[word] = freq.toDouble().coerceIn(MIN_FREQ, MAX_FREQ)
        }
        for ((word, freq) in base) {
            val lower = word.lowercase(Locale.ROOT)
            if (lower in disabledLower) continue
            if (!seenLower.add(lower)) continue
            merged[word] = freq.coerceAtLeast(MIN_FREQ)
        }
        return merged
    }

    /**
     * Lowercase word → ordinal frequency rank (0 = most frequent) over [merged],
     * for `ContractionOverlay`'s real-word guard. Rank order is frequency
     * descending with a STABLE tie-break on [merged]'s insertion order (so
     * clamped-to-255 custom words rank ahead of equal-frequency base words —
     * the same "user words get favorable rank" bias the geometric merge gets by
     * prepending). First case-folded occurrence wins, matching the geometric
     * adapter's `putIfAbsent` ordinal build.
     *
     * Threshold sanity (measured 2026-08-14 on the shipped `en_enhanced.json`,
     * locked in by `CtcContractionDisplayTest`): under this ranking the en
     * non-paired junk aliases sit well above `REAL_WORD_ORDINAL_MAX` = 1200
     * (dont=1745, im=1994, cant=3895, theyd=64061) while real-word paired bases
     * stay far below it (were=54, its=76, well=102, hell=745) — the same
     * separation the geometric CKDT ordinals show.
     */
    fun ordinals(merged: LinkedHashMap<String, Double>): HashMap<String, Int> {
        val entries = merged.entries.toList()
        val order = entries.indices.sortedWith(
            compareByDescending<Int> { entries[it].value }.thenBy { it }
        )
        val ordinals = HashMap<String, Int>(entries.size * 2)
        for ((rank, idx) in order.withIndex()) {
            ordinals.putIfAbsent(entries[idx].key.lowercase(Locale.ROOT), rank)
        }
        return ordinals
    }
}
