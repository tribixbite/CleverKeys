package tribixbite.cleverkeys.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure JSON → `DictImportPlan` builder. No Android deps.
 *
 * Three-source merge priority (matches the legacy `importDictionaries`
 * `!containsKey` semantics):
 *   1. v2 `custom_words_by_language`
 *   2. legacy `custom_words` (routed to English)
 *   3. legacy `user_words` (routed to English)
 *
 * First-writer-wins via `LinkedHashMap.putIfAbsent`. `Map.plus` would give
 * last-writer-wins — opposite of current behavior — so don't refactor to that.
 */
object DictImportPlanBuilder {

    /**
     * Default frequency for legacy `user_words` entries that lack an explicit
     * frequency (bare-string entries, or object form missing the `frequency`
     * field). Mirrors the legacy `BackupRestoreManager.importPreference`
     * constant — preserved here so external/older backups round-trip without
     * silent narrowing.
     */
    private const val DEFAULT_USER_WORD_FREQ = 100

    fun fromJson(
        jsonString: String,
        currentCustomByLang: Map<String, Map<String, Int>>,
        currentDisabledByLang: Map<String, Set<String>>,
    ): DictImportPlan {
        val root = JsonParser.parseString(jsonString).asJsonObject
        val sourceVersion = root.getAsJsonObject("metadata")
            ?.get("app_version")?.asString ?: "unknown"

        val mergedCustom = mergeCustomWords(root)
        val mergedDisabled = mergeDisabledWords(root)

        // Compute deltas: subtract current state from merged.
        val perLanguage = HashMap<String, LangChanges>()
        for ((lang, words) in mergedCustom) {
            val current = currentCustomByLang[lang] ?: emptyMap()
            val newOnly = words.filterKeys { it !in current }
            val currentDisabled = currentDisabledByLang[lang] ?: emptySet()
            val newDisabled = (mergedDisabled[lang] ?: emptySet())
                .filter { it !in currentDisabled }
            if (newOnly.isNotEmpty() || newDisabled.isNotEmpty()) {
                perLanguage[lang] = LangChanges(newOnly, newDisabled)
            }
        }
        // Languages that have ONLY disabled deltas (no custom)
        for ((lang, disabled) in mergedDisabled) {
            if (lang in perLanguage) continue
            val currentDisabled = currentDisabledByLang[lang] ?: emptySet()
            val newDisabled = disabled.filter { it !in currentDisabled }
            if (newDisabled.isNotEmpty()) {
                perLanguage[lang] = LangChanges(emptyMap(), newDisabled)
            }
        }

        return DictImportPlan(
            sourceVersion = sourceVersion,
            perLanguage = perLanguage,
            mergedCustomWordsByLang = mergedCustom,
            mergedDisabledWordsByLang = mergedDisabled,
        )
    }

    private fun mergeCustomWords(root: JsonObject): Map<String, Map<String, Int>> {
        // LinkedHashMap preserves insertion order — first-writer-wins via
        // putIfAbsent. Per-language LinkedHashMap so within a language, the
        // first source's frequency wins.
        val merged: MutableMap<String, MutableMap<String, Int>> = LinkedHashMap()

        // 1. v2 first
        root.getAsJsonObject("custom_words_by_language")?.entrySet()?.forEach { (lang, wordsEl) ->
            if (wordsEl.isJsonObject) {
                val map = merged.getOrPut(lang) { LinkedHashMap() }
                wordsEl.asJsonObject.entrySet().forEach { (w, freqEl) ->
                    map.putIfAbsent(w, freqEl.asInt)
                }
            }
        }

        // 2. legacy custom_words → English
        root.getAsJsonObject("custom_words")?.entrySet()?.forEach { (w, freqEl) ->
            val map = merged.getOrPut("en") { LinkedHashMap() }
            map.putIfAbsent(w, freqEl.asInt)
        }

        // 3. legacy user_words → English (array of strings OR array of
        // {word, frequency} objects). Bare-string entries default to
        // DEFAULT_USER_WORD_FREQ; object form missing `frequency` likewise —
        // matches legacy `BackupRestoreManager.importPreference` behavior.
        root.getAsJsonArray("user_words")?.forEach { entry ->
            val (word, freq) = when {
                entry.isJsonObject -> {
                    val obj = entry.asJsonObject
                    val w = obj.get("word")?.asString ?: return@forEach
                    val f = obj.get("frequency")?.asInt ?: DEFAULT_USER_WORD_FREQ
                    w to f
                }
                entry.isJsonPrimitive && entry.asJsonPrimitive.isString ->
                    entry.asString to DEFAULT_USER_WORD_FREQ
                else -> return@forEach
            }
            val map = merged.getOrPut("en") { LinkedHashMap() }
            map.putIfAbsent(word, freq)
        }

        return merged
    }

    private fun mergeDisabledWords(root: JsonObject): Map<String, Set<String>> {
        val merged: MutableMap<String, LinkedHashSet<String>> = LinkedHashMap()

        // 1. v2 disabled_words_by_language
        root.getAsJsonObject("disabled_words_by_language")?.entrySet()?.forEach { (lang, wordsEl) ->
            if (wordsEl.isJsonArray) {
                val set = merged.getOrPut(lang) { LinkedHashSet() }
                wordsEl.asJsonArray.forEach { set.add(it.asString) }
            }
        }

        // 2. legacy disabled_words → English
        root.getAsJsonArray("disabled_words")?.forEach {
            val set = merged.getOrPut("en") { LinkedHashSet() }
            set.add(it.asString)
        }

        return merged
    }
}
