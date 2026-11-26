package tribixbite.cleverkeys

import java.util.Locale

/**
 * Swiss German spelling (`ss` for `ß`), per lexicon LOAD. Pure JVM.
 *
 * Switzerland and Liechtenstein write no `ß`: `Strasse`, `gross`, `weiss`, `heisst`. The bundled
 * German list spells Germany's way since 2026-11-25 (`scripts/sharp_s.py`: `straße`, `groß`), so
 * a Swiss writer got `strasse` + space corrected to `straße` and every swipe showed `ß`
 * (`docs/eval/2026-11-25-german-sharp-s.md`, last paragraph of "Typing").
 *
 * With the preference [LanguagePrefs.GERMAN_SWISS_SPELLING] on, every German lexicon is respelled
 * as it is LOADED, so nothing downstream needs to know:
 *
 *  - the tap predictor's primary map and prefix index ([foldDictionaryKeys]) and the secondary
 *    index (`NormalizedPrefixIndex.respellCanonicals`);
 *  - the CTC swipe lexicon's base pairs ([foldFrequencyPairs]) and the geometric dictionary's
 *    word array ([foldWords]), both part of their memo key (`LexiconContentVersion.of`);
 *  - the next-word seed's output ([foldDistinct]); the static model itself keeps Germany's
 *    spelling and is reached through [sharpSAliases].
 *
 * A list without `ß` has no word for `WordPredictor.sharpSReading` to find, so the `ss`-typed-for-
 * `ß` correction is inert by construction; a typed `groß` still counts as the word `gross`
 * (`WordPredictor.sharpSDictionaryKey`) and is kept as typed.
 *
 * ## Never the user's own words
 *
 * Only the SHIPPED (or imported-pack) word list is respelled, before the user's words are merged
 * in: a custom word `Straße` stays `Straße`. The user's text in the editor is never rewritten.
 *
 * ## Dictionary Manager
 *
 * Its German list is respelled the same way while the preference is on (`DictionaryListSpelling`,
 * 2026-11-26; `MainDictionarySource` caches it per language AND spelling). A word disabled there
 * in either spelling is disabled in the Swiss one ([foldDisabled], applied by every loader above).
 *
 * ## Twins
 *
 * `Maße`/`Masse`, `Buße`/`Busse` become one word; it keeps the higher frequency (the better
 * rank), at the position of whichever came first in a ranked list.
 */
object SwissSpelling {

    private const val SHARP_S = 'ß'
    /** Capital sharp s (U+1E9E); lexicon keys are lowercase, a display form may carry it. */
    private const val CAPITAL_SHARP_S = 'ẞ'

    /**
     * The token that joins the swipe lexicons' memo key while the fold is active
     * (`LexiconContentVersion.of(spellingVariant = …)`); empty = Germany's spelling, which keeps
     * the key (and the memo) every install had before the preference existed.
     */
    const val LEXICON_VARIANT = "de-ch-ss"

    /** `de`, `de_CH`, `de-AT`, `DE` → true: the fold only ever applies to a German lexicon. */
    fun isGerman(language: String?): Boolean {
        if (language.isNullOrBlank()) return false
        return language.trim().lowercase(Locale.ROOT).substringBefore('_').substringBefore('-') == "de"
    }

    /** True when [language]'s lexicon is to be respelled: the preference is on AND it is German. */
    fun appliesTo(enabled: Boolean, language: String?): Boolean = enabled && isGerman(language)

    /** The memo-key token for [language] under the stored preference value [enabled]. */
    fun lexiconVariant(enabled: Boolean, language: String?): String =
        if (appliesTo(enabled, language)) LEXICON_VARIANT else ""

    /**
     * [word] with every `ß` written `ss` (and `ẞ` written `SS`); [word] itself (same instance)
     * when it has neither. Idempotent.
     */
    fun fold(word: String): String {
        if (word.indexOf(SHARP_S) < 0 && word.indexOf(CAPITAL_SHARP_S) < 0) return word
        val out = StringBuilder(word.length + 2)
        for (ch in word) {
            when (ch) {
                SHARP_S -> out.append("ss")
                CAPITAL_SHARP_S -> out.append("SS")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * Re-key a freshly loaded tap dictionary: every key with `ß` moves to its `ss` spelling.
     * [dictionary] maps lowercase keys to frequencies (higher = more common); [prefixIndex] (may
     * be null) holds every key under its first 1..[prefixLength] characters and is re-keyed with
     * it (`maße` leaves the bucket `maß` and joins `mas`). Twins keep the higher frequency.
     *
     * Call it BEFORE the user's words are merged into [dictionary] (class doc).
     *
     * @return the number of keys rewritten (0 for a list without `ß`, so a second call is a no-op)
     */
    fun foldDictionaryKeys(
        dictionary: MutableMap<String, Int>,
        prefixIndex: MutableMap<String, MutableSet<String>>?,
        prefixLength: Int,
    ): Int {
        val changed = dictionary.keys.filter { it.indexOf(SHARP_S) >= 0 || it.indexOf(CAPITAL_SHARP_S) >= 0 }
        for (key in changed) {
            val frequency = dictionary.remove(key) ?: continue
            val folded = fold(key)
            val existing = dictionary[folded]
            dictionary[folded] = if (existing == null) frequency else maxOf(existing, frequency)
            if (prefixIndex == null) continue
            for (len in 1..minOf(prefixLength, key.length)) {
                val prefix = key.substring(0, len)
                val bucket = prefixIndex[prefix] ?: continue
                bucket.remove(key)
                if (bucket.isEmpty()) prefixIndex.remove(prefix)
            }
            for (len in 1..minOf(prefixLength, folded.length)) {
                prefixIndex.getOrPut(folded.substring(0, len)) { HashSet() }.add(folded)
            }
        }
        return changed.size
    }

    /**
     * `(word, frequency)` pairs respelled, twins merged to the higher frequency at the position of
     * the first of them. The order of the rest is unchanged (the CTC lexicon's insertion order is
     * the beam's tie-break). [pairs] itself when no word has a `ß`.
     */
    fun foldFrequencyPairs(pairs: List<Pair<String, Double>>): List<Pair<String, Double>> {
        if (pairs.none { it.first.indexOf(SHARP_S) >= 0 || it.first.indexOf(CAPITAL_SHARP_S) >= 0 }) return pairs
        val merged = LinkedHashMap<String, Double>(pairs.size * 2)
        for ((word, frequency) in pairs) {
            val folded = fold(word)
            val existing = merged[folded]
            if (existing == null || frequency > existing) merged[folded] = frequency
        }
        return merged.map { it.key to it.value }
    }

    /**
     * The disabled-word set a respelled list is filtered with: [disabled] plus the `ss` spelling
     * of each of its `ß` words. Dictionary Manager lists (and so disables) the shipped spelling,
     * `straße`; the loaded list holds `strasse`, which must be disabled with it. [disabled]
     * itself when not [active] or when it has no `ß` word.
     */
    fun foldDisabled(active: Boolean, disabled: Set<String>): Set<String> {
        if (!active || disabled.none { it.indexOf(SHARP_S) >= 0 || it.indexOf(CAPITAL_SHARP_S) >= 0 }) return disabled
        val out = HashSet<String>(disabled.size * 2)
        for (word in disabled) {
            out.add(word)
            out.add(fold(word))
        }
        return out
    }

    /** [foldFrequencyPairs] when [active] (the CTC adapter's one call), else [pairs] untouched. */
    fun foldBaseLexicon(active: Boolean, pairs: List<Pair<String, Double>>): List<Pair<String, Double>> =
        if (active) foldFrequencyPairs(pairs) else pairs

    /**
     * A best-first word list respelled, a twin kept once at its better (earlier) position — the
     * geometric dictionary's ordinal array. [words] itself when no word has a `ß`.
     */
    fun foldWords(words: List<String>): List<String> {
        if (words.none { it.indexOf(SHARP_S) >= 0 || it.indexOf(CAPITAL_SHARP_S) >= 0 }) return words
        val out = LinkedHashSet<String>(words.size * 2)
        for (word in words) out.add(fold(word))
        return out.toList()
    }

    /**
     * A best-first list of items carrying a word (next-word continuations), respelled and
     * de-duplicated: the first item of each respelled word wins, so `straße` and a later
     * `strasse` show once, at the better rank.
     *
     * @param wordOf the word an item shows
     * @param respell the item with its word replaced (called only when the word changed)
     */
    fun <T> foldDistinct(items: List<T>, wordOf: (T) -> String, respell: (T, String) -> T): List<T> {
        val seen = HashSet<String>(items.size * 2)
        val out = ArrayList<T>(items.size)
        for (item in items) {
            val word = wordOf(item)
            val folded = fold(word)
            if (!seen.add(folded.lowercase(Locale.ROOT))) continue
            out.add(if (folded === word) item else respell(item, folded))
        }
        return out
    }

    /**
     * Lookup aliases that let a model spelling Germany's way answer for Swiss spellings:
     * `strasse → straße`, `weiss → weiß`. One alias per `ß` word of [words] whose `ss` spelling
     * the model does not name itself ([names]): `masse` stays the word `Masse` beside `Maße`.
     * When two `ß` words share an `ss` spelling, the one with the higher [weight] is the target.
     *
     * @param words the model's vocabulary with a weight (its unigram probability)
     * @param names whether the model's OWN vocabulary holds a word
     */
    fun sharpSAliases(words: Iterable<Pair<String, Float>>, names: (String) -> Boolean): Map<String, String> {
        val best = HashMap<String, Pair<String, Float>>()
        for ((word, weight) in words) {
            if (word.indexOf(SHARP_S) < 0) continue
            val key = fold(word)
            if (names(key)) continue
            val current = best[key]
            if (current == null || weight > current.second) best[key] = word to weight
        }
        return best.mapValues { it.value.first }
    }

    /**
     * The value the preference starts with on a device whose system languages are [locales]: on
     * when any of them is German as written in Switzerland or Liechtenstein (`de-CH`, `de-LI`).
     * Austria, Germany, Luxembourg and Belgium write `ß`.
     */
    fun seedDefault(locales: List<Locale>): Boolean = locales.any { locale ->
        locale.language.equals("de", ignoreCase = true) &&
            locale.country.uppercase(Locale.ROOT) in SS_ONLY_REGIONS
    }

    private val SS_ONLY_REGIONS = setOf("CH", "LI")
}
