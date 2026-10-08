package tribixbite.cleverkeys

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * How strongly a personal-dictionary word is preferred when SWIPING (user swipe priority,
 * 2026-10-08; measurement and the chosen bonuses: `docs/eval/2026-10-08-user-swipe-priority.md`).
 *
 * A personal-dictionary word already reaches both swipe lexicons at the top of the frequency
 * scale. Some words still lose their own gesture — the encoder cannot see a straight-through
 * interior letter (`adb` → `an`) and reads the end of a short trace through its word prior
 * (`ad` → `as`, `wet` → `we`). A raised priority adds an explicit, bounded score bonus on top
 * of the frequency cap, and the user accepts that the boosted word will then also win some
 * swipes of its near neighbours (the eval note quantifies that per level).
 *
 * Stored per language in `swipe_priority_<lang>` ([LanguagePreferenceKeys.swipePriorityKey]) as
 * a JSON object `{word: storedValue}` holding ONLY raised words — the `custom_words_<lang>`
 * format and every reader of it stay exactly as they were, and an absent key means every word
 * is [NORMAL] (no migration). Tap prediction does not read it (see the eval note §5).
 *
 * @property storedValue the integer persisted for this level (never reorder; append new ones).
 */
enum class SwipePriority(val storedValue: Int) {
    /** Default: the word gets the personal-dictionary frequency and nothing more. */
    NORMAL(0),

    /** A moderate extra preference — the level the "Prefer … when swiping?" offer raises to first. */
    HIGH(1),

    /** The strongest bounded preference. */
    HIGHEST(2);

    /** The next level up, or null at [HIGHEST]. */
    fun next(): SwipePriority? = entries.getOrNull(ordinal + 1)

    companion object {
        /** The level stored as [value]; unknown, null or negative values read as [NORMAL]. */
        fun fromStored(value: Int?): SwipePriority =
            entries.firstOrNull { it.storedValue == value } ?: NORMAL

        /**
         * Parse a `swipe_priority_<lang>` value into word → level, keeping only raised levels.
         * Malformed JSON or non-integer values are skipped — a damaged store degrades to
         * [NORMAL], it never throws into a decode or a settings screen.
         */
        fun parseMap(json: String?): Map<String, SwipePriority> {
            if (json.isNullOrBlank()) return emptyMap()
            val obj = try {
                JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            } catch (e: Exception) {
                null
            } ?: return emptyMap()
            val out = LinkedHashMap<String, SwipePriority>()
            for ((word, el) in obj.entrySet()) {
                if (word.isBlank()) continue
                val stored = try {
                    if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asInt else null
                } catch (e: Exception) {
                    null
                }
                val level = fromStored(stored)
                if (level != NORMAL) out[word] = level
            }
            return out
        }

        /** Key of the dictionaries-backup section that carries the levels (per language). */
        const val BACKUP_SECTION = "swipe_priority_by_language"

        /**
         * The dictionaries-backup section: language → `{word: storedValue}`, languages sorted,
         * languages with nothing raised omitted. Read back by `DictImportPlanBuilder`.
         */
        fun toBackupSection(byLanguage: Map<String, Map<String, SwipePriority>>): JsonObject {
            val section = JsonObject()
            for (lang in byLanguage.keys.sorted()) {
                val levels = byLanguage.getValue(lang).filterValues { it != NORMAL }
                if (levels.isEmpty()) continue
                section.add(lang, JsonParser.parseString(toJson(levels)))
            }
            return section
        }

        /** Serialize word → level for storage; [NORMAL] entries are dropped (absence = normal). */
        fun toJson(map: Map<String, SwipePriority>): String {
            val obj = JsonObject()
            for ((word, level) in map) {
                if (level != NORMAL && word.isNotBlank()) obj.addProperty(word, level.storedValue)
            }
            return obj.toString()
        }

        /**
         * [map] with [word] set to [level] ([NORMAL] removes the entry — absence is normal).
         * Every writer (Dictionary Manager source, the IME's "Prefer … when swiping?" raise,
         * backup import) edits the stored map through these three helpers.
         */
        fun withLevel(
            map: Map<String, SwipePriority>,
            word: String,
            level: SwipePriority,
        ): Map<String, SwipePriority> {
            val out = LinkedHashMap(map)
            if (level == NORMAL) out.remove(word) else out[word] = level
            return out
        }

        /** [map] without the entries for [words] (exact keys) — a removed word drops its level. */
        fun without(map: Map<String, SwipePriority>, words: Collection<String>): Map<String, SwipePriority> =
            if (words.isEmpty()) map else map.filterKeys { it !in words }

        /** [map] with [oldWord]'s level moved to [newWord] (a rename in the Dictionary Manager). */
        fun renamed(
            map: Map<String, SwipePriority>,
            oldWord: String,
            newWord: String,
        ): Map<String, SwipePriority> {
            val level = map[oldWord] ?: return map
            return withLevel(without(map, listOf(oldWord)), newWord, level)
        }

        /**
         * [priorities] restricted to words that are still personal-dictionary words (exact
         * match first, then case-insensitive), keyed by the user word as listed in [userWords].
         * A priority whose word was removed must never act — this is the one place both swipe
         * adapters apply that rule.
         */
        fun forUserWords(
            priorities: Map<String, SwipePriority>,
            userWords: Iterable<String>,
        ): Map<String, SwipePriority> {
            if (priorities.isEmpty()) return emptyMap()
            val folded = HashMap<String, SwipePriority>()
            for ((w, p) in priorities) {
                val key = w.lowercase(Locale.ROOT)
                val prev = folded[key]
                if (prev == null || p.ordinal > prev.ordinal) folded[key] = p
            }
            val out = LinkedHashMap<String, SwipePriority>()
            for (word in userWords) {
                val p = priorities[word] ?: folded[word.lowercase(Locale.ROOT)] ?: continue
                if (p != NORMAL) out[word] = p
            }
            return out
        }
    }
}
