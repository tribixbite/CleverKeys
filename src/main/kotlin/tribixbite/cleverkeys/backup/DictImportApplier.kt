package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import tribixbite.cleverkeys.LanguagePreferenceKeys

/**
 * Stateless apply step for `DictImportPlan`.
 *
 * Atomicity contract: ONE editor + ONE `editor.commit()` regardless of
 * language count. Replaces the legacy `importDictionaries` flow which
 * called `editor.apply()` separately per language (BackupRestoreManager.kt
 * lines 1248, 1271, 1298, 1329) — that's a pre-existing partial-state risk
 * we get to fix as a side-effect.
 */
object DictImportApplier {

    private val gson = Gson()
    private val mapType = object : TypeToken<MutableMap<String, Int>>() {}.type

    /**
     * Returns count summary as `Pair<customApplied, disabledApplied>`.
     * Caller wraps into the project's `DictionaryImportResult` type.
     */
    fun apply(
        plan: DictImportPlan,
        excludedCustom: Set<LangWord>,
        excludedDisabled: Set<LangWord>,
        prefs: SharedPreferences,
    ): Pair<Int, Int> {
        val editor = prefs.edit()
        var customApplied = 0
        var disabledApplied = 0

        // Custom words: read-modify-write per language, but write goes through
        // the SAME editor so commit() is atomic across all languages.
        for ((lang, words) in plan.mergedCustomWordsByLang) {
            val key = LanguagePreferenceKeys.customWordsKey(lang)
            val existing: MutableMap<String, Int> = try {
                gson.fromJson(prefs.getString(key, "{}"), mapType) ?: mutableMapOf()
            } catch (_: Exception) { mutableMapOf() }

            for ((word, freq) in words) {
                val lw = LangWord(lang, word)
                if (lw in excludedCustom) continue
                if (existing.containsKey(word)) continue   // already present (current state)
                existing[word] = freq
                customApplied++
            }

            editor.putString(key, gson.toJson(existing))
        }

        // Disabled words: same pattern with StringSet storage.
        for ((lang, words) in plan.mergedDisabledWordsByLang) {
            val key = LanguagePreferenceKeys.disabledWordsKey(lang)
            val existing: MutableSet<String> =
                prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()

            for (word in words) {
                val lw = LangWord(lang, word)
                if (lw in excludedDisabled) continue
                if (word in existing) continue
                existing.add(word)
                disabledApplied++
            }

            editor.putStringSet(key, existing)
        }

        editor.commit()
        return customApplied to disabledApplied
    }
}
