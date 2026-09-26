package tribixbite.cleverkeys

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import tribixbite.cleverkeys.persist.LearnedDataStorage
import tribixbite.cleverkeys.persist.SharedPrefsLearnedStorage
import java.util.Locale

/**
 * Per-language swipe-correction counts behind the "Prefer “Y” when swiping?" offer
 * (learning-system audit 2026-09-26, Resolution).
 *
 * Holds, per language:
 *  - **c(Y)** — how many recorded corrections ended on word Y ([correctionCount]);
 *  - **c(X → Y)** — per rejected auto-inserted word X ([pairCount]);
 *  - the words the user **declined** to prefer ([isDeclined]) — never offered again.
 *
 * It stores words, not text: no context, no timestamps beyond one last-seen instant per word
 * (used only to choose what to evict).
 *
 * ## Bounds
 *
 * [MAX_WORDS_PER_LANGUAGE] targets (least recently corrected evicted first), at most
 * [MAX_SOURCES_PER_WORD] rejected forms per target (smallest count evicted), at most
 * [MAX_DECLINED_PER_LANGUAGE] declined words (oldest evicted). A word ACCEPTED into the personal
 * dictionary is removed ([forgetWord]) — the dictionary entry is the durable record from then on.
 * No decay: the only consumer is a threshold of 2, which a bounded LRU already keeps honest.
 *
 * ## Privacy
 *
 * Writes happen only behind `LearningGate.canLearnSwipeCorrections` + the per-field incognito
 * flag + a non-password field (enforced by the one caller, `SuggestionHandler`). Privacy's
 * "forget learned data" and the master-switch-off prompt erase it ([clearAll]).
 *
 * **Not backed up**, on purpose, like the selection-adaptation history it resembles
 * (`user_adaptation` prefs): the file is outside Android Auto Backup's allowlist
 * (`res/xml/backup_rules.xml` includes only three aesthetic prefs files) and outside the manual
 * Backup & Restore export. It is a transient nudge toward a personal-dictionary entry; the
 * durable outcome — the dictionary word — IS exported.
 *
 * Persistence is write-through (`apply()`): corrections are rare, user-paced events, so the
 * debounced write-back the n-gram stores need would buy nothing.
 *
 * Thread-safe (all public methods synchronize on the instance). Pure JVM apart from
 * [getInstance]; tests pass an in-memory [LearnedDataStorage].
 */
class SwipeCorrectionStore(
    private val storage: LearnedDataStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class WordRecord(var count: Int, var lastMs: Long, val from: LinkedHashMap<String, Int>)

    private class LanguageRecord(
        val words: LinkedHashMap<String, WordRecord> = LinkedHashMap(),
        val declined: LinkedHashSet<String> = LinkedHashSet(),
    )

    private val cache = HashMap<String, LanguageRecord>()

    /**
     * Record one correction toward [chosen] that rejected [rejected] (each an auto-inserted
     * word). c([chosen]) grows by ONE per call however long the rejected chain is — it counts
     * the user's corrections, not the decoder's failed attempts.
     *
     * @return the new c([chosen])
     */
    @Synchronized
    fun recordCorrection(language: String, chosen: String, rejected: List<String>): Int {
        val y = norm(chosen)
        if (y.isEmpty()) return 0
        val lang = normLang(language)
        val record = load(lang)
        val entry = record.words.remove(y) ?: WordRecord(0, 0L, LinkedHashMap())
        entry.count += 1
        entry.lastMs = clock()
        for (x in rejected) {
            val key = norm(x)
            if (key.isEmpty() || key == y) continue
            entry.from[key] = (entry.from[key] ?: 0) + 1
        }
        while (entry.from.size > MAX_SOURCES_PER_WORD) {
            val weakest = entry.from.entries.minByOrNull { it.value }?.key ?: break
            entry.from.remove(weakest)
        }
        // Re-inserted last: map order is least → most recently corrected, so eviction is O(1).
        record.words[y] = entry
        while (record.words.size > MAX_WORDS_PER_LANGUAGE) {
            val oldest = record.words.keys.first()
            record.words.remove(oldest)
        }
        save(lang, record)
        return entry.count
    }

    /** c([word]) in [language] — 0 when never recorded. */
    @Synchronized
    fun correctionCount(language: String, word: String): Int =
        load(normLang(language)).words[norm(word)]?.count ?: 0

    /** c([from] → [to]) in [language] — 0 when never recorded (or evicted). */
    @Synchronized
    fun pairCount(language: String, from: String, to: String): Int =
        load(normLang(language)).words[norm(to)]?.from?.get(norm(from)) ?: 0

    /** Has the user declined to prefer [word] in [language]? */
    @Synchronized
    fun isDeclined(language: String, word: String): Boolean = norm(word) in load(normLang(language)).declined

    /** The user declined the offer for [word]: never offer it again (until [clearAll]). */
    @Synchronized
    fun decline(language: String, word: String) {
        val w = norm(word)
        if (w.isEmpty()) return
        val lang = normLang(language)
        val record = load(lang)
        record.declined.remove(w)
        record.declined.add(w)
        while (record.declined.size > MAX_DECLINED_PER_LANGUAGE) {
            record.declined.remove(record.declined.first())
        }
        record.words.remove(w)
        save(lang, record)
    }

    /** Drop [word]'s counts (the user accepted it into the personal dictionary). */
    @Synchronized
    fun forgetWord(language: String, word: String) {
        val lang = normLang(language)
        val record = load(lang)
        if (record.words.remove(norm(word)) != null) save(lang, record)
    }

    /** Number of target words tracked in [language] (bounded; for tests and diagnostics). */
    @Synchronized
    fun trackedWordCount(language: String): Int = load(normLang(language)).words.size

    /** Erase every language's counts AND declines (Privacy → forget learned data). */
    @Synchronized
    fun clearAll() {
        cache.clear()
        for (key in storage.keys()) {
            if (key.startsWith(KEY_PREFIX)) storage.remove(key)
        }
    }

    // ------------------------------------------------------------------ persistence

    private fun load(lang: String): LanguageRecord {
        cache[lang]?.let { return it }
        val record = storage.getString(KEY_PREFIX + lang)?.let { parse(it) } ?: LanguageRecord()
        cache[lang] = record
        return record
    }

    private fun save(lang: String, record: LanguageRecord) {
        storage.putString(KEY_PREFIX + lang, serialize(record))
    }

    private fun serialize(record: LanguageRecord): String {
        val root = JsonObject()
        val words = JsonObject()
        for ((word, entry) in record.words) {
            val obj = JsonObject()
            obj.addProperty(FIELD_COUNT, entry.count)
            obj.addProperty(FIELD_LAST, entry.lastMs)
            val from = JsonObject()
            for ((x, n) in entry.from) from.addProperty(x, n)
            obj.add(FIELD_FROM, from)
            words.add(word, obj)
        }
        root.add(FIELD_WORDS, words)
        val declined = JsonArray()
        for (w in record.declined) declined.add(w)
        root.add(FIELD_DECLINED, declined)
        return root.toString()
    }

    /** Lenient parse: a malformed value (hand-edited, truncated) degrades to empty, never throws. */
    private fun parse(json: String): LanguageRecord? = try {
        val root = JsonParser.parseString(json).asJsonObject
        val record = LanguageRecord()
        val words = root.getAsJsonObject(FIELD_WORDS)
        if (words != null) {
            // Stored oldest → newest; keep that order for eviction.
            val entries = words.entrySet().mapNotNull { (word, value) ->
                val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val count = obj.get(FIELD_COUNT)?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
                if (count <= 0) return@mapNotNull null
                val lastMs = obj.get(FIELD_LAST)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                val from = LinkedHashMap<String, Int>()
                obj.getAsJsonObject(FIELD_FROM)?.entrySet()?.forEach { (x, n) ->
                    if (n.isJsonPrimitive) from[x] = n.asInt
                }
                word to WordRecord(count, lastMs, from)
            }
            for ((word, entry) in entries.sortedBy { it.second.lastMs }) record.words[word] = entry
        }
        root.getAsJsonArray(FIELD_DECLINED)?.forEach { if (it.isJsonPrimitive) record.declined.add(it.asString) }
        record
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Target words tracked per language. */
        const val MAX_WORDS_PER_LANGUAGE = 200

        /** Rejected forms kept per target word. */
        const val MAX_SOURCES_PER_WORD = 8

        /** Declined words remembered per language. */
        const val MAX_DECLINED_PER_LANGUAGE = 500

        /** SharedPreferences file (own file — see the backup note in the class KDoc). */
        const val PREFS_NAME = "swipe_corrections"

        /** Storage key prefix; one JSON value per language. */
        const val KEY_PREFIX = "corrections_"

        private const val FIELD_WORDS = "words"
        private const val FIELD_DECLINED = "declined"
        private const val FIELD_COUNT = "c"
        private const val FIELD_LAST = "t"
        private const val FIELD_FROM = "from"

        private fun norm(word: String): String = word.trim().lowercase(Locale.ROOT)

        private fun normLang(language: String): String = language.trim().lowercase(Locale.ROOT).ifEmpty { "en" }

        @Volatile
        private var instance: SwipeCorrectionStore? = null

        /** Process singleton over the app's `swipe_corrections` preferences file. */
        fun getInstance(context: Context): SwipeCorrectionStore =
            instance ?: synchronized(this) {
                instance ?: SwipeCorrectionStore(
                    SharedPrefsLearnedStorage(
                        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    )
                ).also { instance = it }
            }
    }
}
