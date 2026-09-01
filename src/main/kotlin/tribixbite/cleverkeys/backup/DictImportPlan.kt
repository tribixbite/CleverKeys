package tribixbite.cleverkeys.backup

/**
 * (lang, word) pair — the unit of selection in the dictionary preview UI.
 * Case-sensitive on purpose (matches existing `importDictionaries` behavior
 * — "foo" and "FOO" are distinct entries today).
 */
data class LangWord(val lang: String, val word: String)

/**
 * Per-language deltas — only words/disabled entries that are NOT already
 * present in the user's current prefs.
 */
data class LangChanges(
    val newCustomWords: Map<String, Int>,
    val newDisabledWords: List<String>,
)

/**
 * Learned context carried by a dictionary import plan (ARC-094).
 *
 * Phrase entries merge into the current stores. A present vocabulary section replaces the
 * current personalization vocabulary even when its count is zero, so [vocabularyPresent] is a
 * separate effect flag rather than being inferred from [vocabularyWords].
 */
data class LearnedDataImportPlan(
    val bigramEntries: Int,
    val trigramEntries: Int,
    val vocabularyWords: Int,
    val vocabularyPresent: Boolean,
    /** Minimal JSON containing only the three learned-data sections; null when none apply. */
    val rawJson: String?,
) {
    val hasEffect: Boolean
        get() = bigramEntries > 0 || trigramEntries > 0 || vocabularyPresent
    val totalEntries: Int
        get() = bigramEntries + trigramEntries + vocabularyWords

    companion object {
        val NONE = LearnedDataImportPlan(0, 0, 0, false, null)
    }
}

/**
 * Output of `buildDictImportPlan`. Pure data.
 *
 * `mergedCustomWordsByLang` and `mergedDisabledWordsByLang` carry the FULL parsed merge
 * result so apply does not re-read the URI. [perLanguage] is the selectable word-delta view;
 * [learnedData] is the non-selectable learned-context summary and minimal apply payload.
 */
data class DictImportPlan(
    val sourceVersion: String,
    val perLanguage: Map<String, LangChanges>,
    val mergedCustomWordsByLang: Map<String, Map<String, Int>>,
    val mergedDisabledWordsByLang: Map<String, Set<String>>,
    val learnedData: LearnedDataImportPlan = LearnedDataImportPlan.NONE,
    /**
     * ARC-036: encrypted-vs-plaintext + export timestamp of the source file. See
     * [SettingsImportPlan.source] — same contract, same default, set by the manager after read.
     */
    val source: BackupSourceInfo = BackupSourceInfo.PLAINTEXT,
)
