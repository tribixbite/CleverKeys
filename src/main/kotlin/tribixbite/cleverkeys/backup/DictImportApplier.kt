package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import tribixbite.cleverkeys.LanguagePreferenceKeys
import tribixbite.cleverkeys.SwipePriority

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

    private const val TAG = "DictImportApplier"
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

        // The custom-word map each language ends up with — what swipe priorities are
        // checked against below.
        val finalCustom = HashMap<String, Set<String>>()

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
            finalCustom[lang] = existing.keys.toSet()
        }

        // Swipe priorities (2026-10-08): a level is applied only to a word that IS a
        // personal-dictionary word once this import lands (so a deselected word gets none),
        // and only where this device has no level for it yet — an import adds, never lowers.
        var prioritiesApplied = 0
        for ((lang, levels) in plan.mergedSwipePrioritiesByLang) {
            val words = finalCustom[lang] ?: readCustomWordKeys(prefs, lang)
            val key = LanguagePreferenceKeys.swipePriorityKey(lang)
            var current = SwipePriority.parseMap(prefs.getString(key, null))
            var changed = false
            for ((word, level) in levels) {
                if (word !in words || word in current) continue
                current = SwipePriority.withLevel(current, word, level)
                changed = true
                prioritiesApplied++
            }
            if (changed) editor.putString(key, SwipePriority.toJson(current))
        }
        if (prioritiesApplied > 0) Log.i(TAG, "Applied $prioritiesApplied swipe priorities")

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

        if (!editor.commit()) {
            Log.w(TAG, "editor.commit() returned false — disk full or IPC failure")
        }
        return customApplied to disabledApplied
    }

    /** The stored custom-word keys of [lang] (a language the plan carries no words for). */
    private fun readCustomWordKeys(prefs: SharedPreferences, lang: String): Set<String> = try {
        gson.fromJson<MutableMap<String, Int>>(
            prefs.getString(LanguagePreferenceKeys.customWordsKey(lang), "{}"), mapType
        )?.keys ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }
}
