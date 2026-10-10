package tribixbite.cleverkeys

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * The one-time offer to raise custom words that still carry the OLD default frequency
 * (maintainer decision, 2026-10-10). Pure policy: no Android types, so `runPureTests` pins it.
 *
 * ## Which value is "legacy" — evidence from git history
 *
 * Exactly one value was ever written as a default into `custom_words_<lang>`: **100**.
 *
 *  - `f743e49f` (2025-11-28): the Dictionary Manager Add Word dialog pre-filled `"100"`, fell back
 *    to 100 for an unparseable field, and hinted a fictional "1-10000" scale.
 *  - `1ccb534e` (2026-01-12): `DictionaryManager.saveUserWords` stored `associateWith { 100 }` —
 *    every IME-side add or remove rewrote EVERY custom word of the language to 100, including
 *    values the user had chosen in the dialog.
 *  - `10930d45` (2026-01-12): the legacy `user_words` → `custom_words_<lang>` migration wrote 100.
 *  - `4525eb9c` (2026-09-06, wave U2): all three now write [UserWordFrequency.DEFAULT] (255).
 *  - Still live: `DictImportPlanBuilder.DEFAULT_USER_WORD_FREQ` gives 100 to a backup entry that
 *    carries no frequency, so an import can create new legacy entries today.
 *
 * Why it matters: the CTC merge maps the stored value onto the base scale, and at 100 a word pays
 * about −1.37 final-score nats against 255 (`docs/eval/2026-10-08-user-swipe-priority.md` §9).
 * The maintainer's `adb` at 100 lost to `an` even at Highest; after an edit to 255 it ranked first.
 *
 * ## Why an offer and not a migration
 *
 * A 100 the user typed on purpose cannot be told apart from a default, and the U2 note in
 * [UserWordFrequency] deliberately keeps stored values unrewritten. So nothing changes until the
 * user sees the list and confirms. Only the exact value [LEGACY_DEFAULT] is targeted — never
 * "anything low" — and a word edited away from 100 between listing and confirming is left alone.
 *
 * ## Dismissal
 *
 * Dismissing remembers the legacy words of that language at that moment ([withDismissed]); the
 * offer returns only when a legacy word appears that was not in that set ([shouldOffer]) — e.g.
 * after a backup import brings in entries without a frequency. The record lives in one internal
 * preference ([DISMISSED_PREF_KEY], excluded from backups as per-device UI state), as a JSON object
 * `{lang: [word, …]}`.
 */
object LegacyCustomWordFrequency {

    /** The only default value ever stored for a custom word before wave U2 (see class KDoc). */
    const val LEGACY_DEFAULT = 100

    /** Internal preference holding the dismissed legacy words per language (JSON object). */
    const val DISMISSED_PREF_KEY = "legacy_custom_freq_offer_dismissed"

    /**
     * The words of [stored] (one language's `custom_words_<lang>` map) whose frequency is exactly
     * [LEGACY_DEFAULT], in a stable display order (case-insensitive, then exact).
     */
    fun legacyWords(stored: Map<String, Int>): List<String> =
        stored.filterValues { it == LEGACY_DEFAULT }.keys
            .sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })

    /** True when some word of [legacy] was not part of the dismissed set [dismissed]. */
    fun shouldOffer(legacy: Collection<String>, dismissed: Set<String>): Boolean =
        legacy.any { it !in dismissed }

    /**
     * [stored] with each word of [words] raised to [UserWordFrequency.DEFAULT] — but only while it
     * is still at [LEGACY_DEFAULT] (a word edited or removed since the list was shown is left as
     * it now is). Insertion order is kept, so the stored JSON changes only in the raised values.
     */
    fun raise(stored: Map<String, Int>, words: Collection<String>): LinkedHashMap<String, Int> {
        val targets = words.toHashSet()
        val out = LinkedHashMap<String, Int>(stored.size * 2)
        for ((word, freq) in stored) {
            out[word] = if (word in targets && freq == LEGACY_DEFAULT) UserWordFrequency.DEFAULT else freq
        }
        return out
    }

    /** How many of [words] [raise] would change in [stored]. */
    fun raisableCount(stored: Map<String, Int>, words: Collection<String>): Int =
        words.toHashSet().count { stored[it] == LEGACY_DEFAULT }

    /**
     * Parse the [DISMISSED_PREF_KEY] value into language → dismissed words. Malformed JSON, or
     * non-string entries, degrade to "nothing dismissed" (the offer shows again) — never a throw
     * into the Dictionary Manager.
     */
    fun parseDismissed(json: String?): Map<String, Set<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        val obj = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        val out = LinkedHashMap<String, Set<String>>()
        for ((lang, el) in obj.entrySet()) {
            if (!el.isJsonArray) continue
            val words = LinkedHashSet<String>()
            for (w in el.asJsonArray) {
                if (w.isJsonPrimitive && w.asJsonPrimitive.isString) words.add(w.asString)
            }
            if (words.isNotEmpty()) out[normalizeLanguage(lang)] = words
        }
        return out
    }

    /** The dismissed words recorded for [language] in [json]. */
    fun dismissedFor(json: String?, language: String): Set<String> =
        parseDismissed(json)[normalizeLanguage(language)] ?: emptySet()

    /**
     * [json] with [language]'s dismissed set REPLACED by [words] (the legacy words shown when the
     * user dismissed); an empty [words] removes the language. Other languages are kept.
     */
    fun withDismissed(json: String?, language: String, words: Collection<String>): String {
        val current = LinkedHashMap(parseDismissed(json))
        val lang = normalizeLanguage(language)
        if (words.isEmpty()) current.remove(lang) else current[lang] = LinkedHashSet(words)
        val obj = JsonObject()
        for ((l, ws) in current) {
            val arr = JsonArray()
            for (w in ws.sorted()) arr.add(w)
            obj.add(l, arr)
        }
        return obj.toString()
    }

    private fun normalizeLanguage(language: String): String = language.lowercase(Locale.ROOT)
}
