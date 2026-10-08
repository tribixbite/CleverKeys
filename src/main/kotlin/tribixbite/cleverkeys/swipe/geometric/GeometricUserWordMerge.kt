package tribixbite.cleverkeys.swipe.geometric

import java.util.Locale

/**
 * The geometric engine's user-word overlay on a base [GeometricDictionary] — the pure half of
 * `GeometricEngineAdapter.mergeUserWords`, extracted by ARC-081 so the merge that now also
 * carries PLATFORM user-dictionary rows can be exercised in `runPureTests` (the adapter itself
 * needs a `Context`, a `Handler` and a `Looper`).
 *
 * Policy, unchanged from the adapter-private version it replaces:
 *  - user words are PREPENDED, in (frequency desc, word asc) order. Index in the array IS the
 *    ordinal rank the engine's `−λ_f·ln(1 + r(w))` prior reads, and that prior is only
 *    logarithmic in rank, so front-loading a handful of user words is a mild deliberate boost
 *    and never buries the head of the base dictionary;
 *  - a base word that a user word already covers (case-folded) is dropped, so the user's
 *    ranking wins rather than sitting beside the base entry;
 *  - disabled words are removed from the base, and a user word OVERRIDES a disabled entry —
 *    matching `WordPredictor`'s custom-and-user-words semantics and `CtcLexiconMerge`;
 *  - a user word the user RAISED in the Dictionary Manager (swipe priority, 2026-10-08)
 *    carries its bonus on the merged dictionary ([GeometricDictionary.swipeBonus]). Rank 0
 *    is already the strongest prior this engine has, so the priority is an explicit score
 *    term rather than a reordering; with no raised word the merged dictionary carries no
 *    bonus array at all and decodes exactly as before.
 */
object GeometricUserWordMerge {

    /**
     * @param base the CKDT-loaded dictionary, already in frequency order.
     * @param userWords `(word, frequency)` — the `custom_words_<lang>` preference merged with
     *   the platform user-dictionary snapshot by
     *   [tribixbite.cleverkeys.swipe.UserDictionarySnapshot.mergeWithCustom]. Blank words are
     *   skipped; the frequency is used for ORDER only, which is why no clamping happens here.
     * @param disabled the `disabled_words_<lang>` preference set.
     * @param bonusByWord user word (exactly as listed in [userWords]) → swipe-priority bonus in
     *   `S(w)` units (`tribixbite.cleverkeys.swipe.UserSwipePriorityBonus.geometricBonusByWord`).
     *   Entries for words not in [userWords] are ignored: a priority only ever applies to a
     *   personal-dictionary word.
     * @return [base] itself when there is nothing to overlay, else a new [ArrayBackedDictionary].
     */
    fun merge(
        base: GeometricDictionary,
        userWords: List<Pair<String, Int>>,
        disabled: Set<String>,
        language: String,
        version: Long,
        bonusByWord: Map<String, Float> = emptyMap(),
    ): GeometricDictionary {
        val user = ArrayList<Pair<String, Int>>(userWords.size)
        for (entry in userWords) if (entry.first.isNotBlank()) user.add(entry)
        if (user.isEmpty() && disabled.isEmpty()) return base

        user.sortWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        val userLower = user.mapTo(HashSet()) { it.first.lowercase(Locale.ROOT) }
        val disabledLower = disabled.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

        val words = ArrayList<String>(base.size + user.size)
        user.mapTo(words) { it.first }
        for (i in 0 until base.size) {
            val w = base.word(i)
            val lower = w.lowercase(Locale.ROOT)
            if (lower in userLower || lower in disabledLower) continue
            words.add(w)
        }
        // Bonuses align with the prepended user words (ordinals 0 until user.size); null when no
        // user word carries one, so an un-raised dictionary is byte-for-byte the old merge.
        val bonuses = if (user.any { (bonusByWord[it.first] ?: 0f) > 0f }) {
            FloatArray(words.size).also { arr ->
                for (i in user.indices) arr[i] = (bonusByWord[user[i].first] ?: 0f).coerceAtLeast(0f)
            }
        } else {
            null
        }
        return ArrayBackedDictionary(language, version, words.toTypedArray(), bonuses)
    }
}
