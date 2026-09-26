package tribixbite.cleverkeys

/**
 * THE "may this word enter the learned stores?" predicate (typo hygiene, learning-system
 * audit 2026-09-26 — user report: "I've noticed typos in the learned bigrams").
 *
 * Pure JVM; the production instance lives in `WordPredictor`, which owns the lexicon. Every
 * consumer asks this one object, so the write gate, the next-word membership filter and the
 * learned-data purge can never disagree about what counts as a real word:
 *
 * | Consumer | Use |
 * |---|---|
 * | context LM write (`ContextModel.recordCommit`) | an n-gram is recorded only when EVERY word in it [isLearnable] |
 * | context LM rollback (`ContextModel.rollbackCommit`) | same predicate, so only what was recorded is un-recorded |
 * | next-word membership (`WordPredictor.isInUserVocabulary`) | an out-of-lexicon word counts as the user's only once [isRepeatedlyObserved] |
 * | purge (`ContextModel.purgeUnlearnable`) | low-frequency n-grams containing an unlearnable word are deleted |
 *
 * ## "Known" — any one of
 *
 * 1. in the active language's lexicon (the dictionary map, which already carries the
 *    contraction alias keys such as `dont`), or the secondary bilingual lexicon;
 * 2. in the user's own dictionary — custom words (`custom_words_<lang>`) and the platform
 *    UserDictionary are merged into the same lexicon map AND tracked in the user-word set;
 * 3. **observed repeatedly**: committed at least [REPEAT_OBSERVATIONS_TO_LEARN] times
 *    according to the personalization vocabulary's usage counter.
 *
 * Apostrophe forms are resolved against their base: `don't` is known when `dont` (the alias
 * key) or `don't` is, and a possessive `git's` is known when `git` is.
 *
 * ## Why N = 3 repeated commits
 *
 * A typo is overwhelmingly a one-off (an adjacent-key slip or a dropped letter lands on a
 * different string each time), and the vocabulary's own stale-cleanup already discards
 * single-use words after 30 days. A HABITUAL slip can repeat, which is why 2 is not enough;
 * three independent commits of the same unknown string is the smallest count that separates
 * "the user means this" (a name, jargon, a new word) from noise, and it costs a genuine new
 * word only its first two n-gram observations — after that it learns exactly like a
 * dictionary word. The vocabulary is the right counter because it already tallies every
 * committed word under the same master + per-field gates, so no new store of typed text is
 * introduced. Consequence, stated honestly: with Personalized Learning switched OFF there is
 * no repeat tally, so an out-of-lexicon word reaches the context LM only via the user
 * dictionary (Dictionary Manager / add-to-dictionary prompt).
 *
 * ## Lexicon not loaded yet
 *
 * While the lexicon is empty (the async load has not landed, or a language with no
 * dictionary) nothing can be judged, so [isLearnable] fails OPEN — learning behaves exactly
 * as before this policy. The periodic purge catches anything that slipped in, and it only
 * runs once the lexicon is ready.
 *
 * @param lexiconReady false while the lexicon cannot answer (see above)
 * @param isKnownWord lexicon + user-dictionary membership for a lowercase word
 * @param observationCount how many times the user has committed the (lowercase) word
 */
class LearnableWordPolicy(
    private val lexiconReady: () -> Boolean,
    private val isKnownWord: (String) -> Boolean,
    private val observationCount: (String) -> Int
) {
    companion object {
        /** Commits of an out-of-lexicon word before it may be learned (see class doc). */
        const val REPEAT_OBSERVATIONS_TO_LEARN = 3

        /**
         * The purge only deletes n-grams observed at most this often. The write gate keeps
         * new typos out; this bound keeps the purge from deleting a real phrase the user has
         * typed many times (jargon learned before the gate existed, a name whose vocabulary
         * entry aged out) just because one word is not in a dictionary.
         */
        const val PURGE_MAX_FREQUENCY = 2

        /**
         * Purge cadence per language. The first purge after upgrade runs as soon as the
         * lexicon is loaded (no stamp yet — the one-time migration); afterwards weekly
         * catches entries that became unlearnable later (a word removed from the user
         * dictionary, a vocabulary entry that aged out, a restored backup).
         */
        const val PURGE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L

        /**
         * Is a purge due? Never run ([lastRunMs] null) ⇒ due. A stamp in the future (clock
         * moved backwards) is treated as due rather than postponing the purge indefinitely.
         */
        fun isPurgeDue(lastRunMs: Long?, nowMs: Long): Boolean =
            lastRunMs == null || nowMs < lastRunMs || nowMs - lastRunMs >= PURGE_INTERVAL_MS

        /** Does an n-gram observed [frequency] times over [words] qualify for the purge? */
        fun shouldPurge(frequency: Int, words: List<String>, isLearnable: (String) -> Boolean): Boolean =
            frequency <= PURGE_MAX_FREQUENCY && words.any { !isLearnable(it) }

        /** Lowercase, trim, and fold the typographic apostrophe onto the ASCII one. */
        internal fun normalize(word: String): String =
            word.trim().lowercase().replace('’', '\'')
    }

    /** May [word] be recorded into the learned context LM? (See the class doc.) */
    fun isLearnable(word: String): Boolean {
        val w = normalize(word)
        if (w.isEmpty()) return false
        if (!lexiconReady()) return true
        return isKnown(w) || observationCount(w) >= REPEAT_OBSERVATIONS_TO_LEARN
    }

    /**
     * Has the user committed [word] often enough to treat it as theirs, regardless of the
     * lexicon? This is the membership test for the personalization vocabulary on READ paths
     * (next-word allow-list): the vocabulary tallies every committed word, typos included,
     * so bare membership would let a once-typed typo through.
     */
    fun isRepeatedlyObserved(word: String): Boolean {
        val w = normalize(word)
        return w.isNotEmpty() && observationCount(w) >= REPEAT_OBSERVATIONS_TO_LEARN
    }

    private fun isKnown(w: String): Boolean {
        if (isKnownWord(w)) return true
        if ('\'' !in w) return false
        // Contraction alias key ("don't" -> "dont") — the lexicon stores the apostrophe-free form.
        if (isKnownWord(w.replace("'", ""))) return true
        // Possessive of a known base ("git's" -> "git").
        return w.endsWith("'s") && w.length > 2 && isKnownWord(w.dropLast(2))
    }
}
