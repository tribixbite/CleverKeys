package tribixbite.cleverkeys

import java.util.Locale

/**
 * Lookup-key folding of Arabic-script letter variants, per language. Pure JVM.
 *
 * Persian text arrives with Arabic code points that Persian spells differently: Arabic yeh
 * `ي` (U+064A) and kaf `ك` (U+0643) from Arabic keyboards, pasted text and old corpora, the
 * alef maksura `ى`, and the three ways a final heh is encoded (`ۀ` U+06C0 heh-with-yeh-above,
 * Arabic `ة` teh marbuta, Kurdish `ە` U+06D5). Unicode keeps all of them canonically distinct
 * (NFC/NFKC leave them alone), so `كتاب` never matches the dictionary's `کتاب`. CLDR's `fa`
 * collation treats them as SECONDARY (accent-level) differences; this table is that
 * equivalence (research `docs/research/2026-10-21-persian-zwnj.md` §4, decision (c)).
 *
 * ## Lookup keys only
 *
 * The fold is applied to dictionary KEYS at load and to the typed word at lookup — never to the
 * user's text. A pasted `ك` stays `ك` in the editor; it only finds `کتاب` in the dictionary, and
 * autocorrect leaves a word whose folded key is a dictionary word untouched.
 *
 * Never folded: `آ` (a separate primary letter in CLDR: `آب` water vs `اب`) and the hamza
 * carriers `ء أ إ ؤ ئ` (different words). Every fold is one code unit to one code unit, so a
 * folded key has the same length and indices as the text it came from.
 *
 * ## Language scoping
 *
 * The table is chosen by the language code of the dictionary being loaded or queried (the
 * predictor's primary language, the secondary dictionary's language): `fa` gets [PERSIAN];
 * Sorani Kurdish `ckb` gets only ي→ی and ك→ک ([SORANI]) because ە and ه are distinct letters
 * there and ة/ى are not Sorani letters; every other language (Arabic itself, Urdu, all Latin,
 * Cyrillic, Greek, Hebrew packs) gets none and [fold] is the identity. Region and script
 * suffixes are ignored (`fa_IR`, `fa-Arab` → `fa`).
 *
 * The pack builder must fold the same way; the exact table is [PERSIAN].
 */
object ArabicScriptFold {

    /** Persian (`fa`) lookup fold. Keep in sync with the `fa` pack builder. */
    val PERSIAN: Map<Char, Char> = mapOf(
        'ي' to 'ی', // ARABIC LETTER YEH            → FARSI YEH ی
        'ك' to 'ک', // ARABIC LETTER KAF            → KEHEH ک
        'ى' to 'ی', // ARABIC LETTER ALEF MAKSURA   → FARSI YEH ی
        'ۀ' to 'ه', // HEH WITH YEH ABOVE ۀ         → HEH ه
        'ة' to 'ه', // TEH MARBUTA ة                → HEH ه
        'ە' to 'ه', // AE ە (Kurdish letter)        → HEH ه
    )

    /** Sorani Kurdish (`ckb`): only the Arabic yeh/kaf, which Sorani never spells. */
    val SORANI: Map<Char, Char> = mapOf(
        'ي' to 'ی',
        'ك' to 'ک',
    )

    /** The fold table for [language], or null when the language has none (identity). */
    fun tableFor(language: String?): Map<Char, Char>? = when (baseCode(language)) {
        "fa" -> PERSIAN
        "ckb" -> SORANI
        else -> null
    }

    /** True when [language] folds anything. */
    fun isActive(language: String?): Boolean = tableFor(language) != null

    /**
     * [text] with every mapped code unit replaced; [text] itself (same instance) when the
     * language has no table or nothing changes.
     */
    fun fold(text: String, language: String?): String {
        val table = tableFor(language) ?: return text
        return fold(text, table)
    }

    /** [fold] with an explicit table. */
    fun fold(text: String, table: Map<Char, Char>): String {
        var out: CharArray? = null
        for (i in text.indices) {
            val mapped = table[text[i]] ?: continue
            if (out == null) out = text.toCharArray()
            out[i] = mapped
        }
        return if (out == null) text else String(out)
    }

    /**
     * Re-key a loaded dictionary through [language]'s fold, so loading and lookup use the same
     * key. [dictionary] maps lowercase keys to frequencies; [prefixIndex] (may be null) holds
     * every key under its first 1..[prefixLength] characters. When two keys fold together
     * (`كتاب` and `کتاب`) the higher frequency is kept. No-op for a language without a table.
     *
     * @return the number of keys rewritten
     */
    fun foldDictionaryKeys(
        dictionary: MutableMap<String, Int>,
        prefixIndex: MutableMap<String, MutableSet<String>>?,
        prefixLength: Int,
        language: String?,
    ): Int {
        val table = tableFor(language) ?: return 0
        val changed = dictionary.keys.filter { key -> key.any { it in table } }
        for (key in changed) {
            val frequency = dictionary.remove(key) ?: continue
            val folded = fold(key, table)
            val existing = dictionary[folded]
            dictionary[folded] = if (existing == null) frequency else maxOf(existing, frequency)
            if (prefixIndex != null) {
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
        }
        return changed.size
    }

    /** `fa_IR` / `fa-Arab` / `FA` → `fa`; null and blank stay null. */
    private fun baseCode(language: String?): String? {
        if (language.isNullOrBlank()) return null
        return language.trim().lowercase(Locale.ROOT).substringBefore('_').substringBefore('-')
    }
}
