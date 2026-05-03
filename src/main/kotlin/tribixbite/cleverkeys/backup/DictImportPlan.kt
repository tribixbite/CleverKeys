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
 * Output of `buildDictImportPlan`. Pure data.
 *
 * `mergedCustomWordsByLang` and `mergedDisabledWordsByLang` carry the FULL
 * parsed merge result (v2 + legacy combined, first-writer-wins) so apply
 * doesn't re-read the URI. `perLanguage` is the deltas-only view used by
 * the preview UI.
 */
data class DictImportPlan(
    val sourceVersion: String,
    val perLanguage: Map<String, LangChanges>,
    val mergedCustomWordsByLang: Map<String, Map<String, Int>>,
    val mergedDisabledWordsByLang: Map<String, Set<String>>,
)
