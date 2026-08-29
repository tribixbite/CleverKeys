package tribixbite.cleverkeys.swipe

import java.security.MessageDigest
import java.util.Locale

/**
 * ARC-081 — an immutable snapshot of the PLATFORM user dictionary
 * (`android.provider.UserDictionary.Words`) rows that apply to ONE language, plus the two
 * pure operations both swipe adapters need from it.
 *
 * ## Why this type exists
 *
 * The tap path merges the provider with the app's own `custom_words_<lang>` preference
 * (`WordPredictor.loadCustomAndUserWords`); both swipe adapters read only the preference. A
 * word added through Settings → Languages → Personal dictionary therefore completed on tap and
 * could never be swiped, on either engine. Feeding the provider into the swipe lexicons needs
 * a ContentResolver, which the pure decode side must not grow a dependency on — so the Android
 * read is confined to `UserDictionaryWords` and hands back one of these, and everything the
 * decode side does with it (merge policy, memo fingerprint) lives here and is unit-tested in
 * `runPureTests`.
 *
 * ## Frequency scale
 *
 * [entries] carry the provider's OBSERVED `FREQUENCY` value, unmodified. Scaling is
 * deliberately NOT done here: each engine already has a policy for user-word frequencies and
 * the provider rows must get exactly that same treatment, no more and no less —
 * `CtcLexiconMerge.merge` clamps onto the 1..255 AOSP-like scale the tuned λ expects (so a
 * row observed at 40 stays 40 and only an out-of-range row saturates), and the geometric
 * adapter uses the value for prepend ORDER only. Blanket-clamping every provider row to 255
 * here would have thrown away the one signal the provider actually offers.
 *
 * @param entries `(word as stored, observed provider frequency)` in provider order, blanks
 *   dropped and case-folded-deduped first-wins. Original case is preserved: the CTC trie
 *   lowercases at insert anyway, while the geometric dictionary and the tap path both keep the
 *   user's capitalisation for proper nouns.
 */
class UserDictionarySnapshot private constructor(
    val entries: List<Pair<String, Int>>,
) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Stable content fingerprint of this snapshot, for the lexicon memo key
     * ([LexiconContentVersion]).
     *
     * **Load-bearing.** Without the provider snapshot in the memo key the feature works only
     * until the first cache hit: editing the system user dictionary would leave the memoized
     * trie in place and the new word would stay unswipeable for the rest of the session.
     *
     * Order-sensitive by construction — the provider returns rows in a stable order and a
     * reordering is a change worth rebuilding for, which is cheaper to accept than sorting a
     * list on every lexicon build.
     */
    val fingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (entries.isEmpty()) return@lazy EMPTY_FINGERPRINT
        val md = MessageDigest.getInstance("SHA-256")
        for ((word, freq) in entries) {
            md.update(word.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(freq.toString().toByteArray(Charsets.UTF_8))
            md.update(1)
        }
        val digest = md.digest()
        val sb = StringBuilder(FINGERPRINT_BYTES * 2)
        for (i in 0 until FINGERPRINT_BYTES) {
            val v = digest[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        sb.toString()
    }

    companion object {
        /** Distinct from any real digest prefix, so "no provider words" is its own state. */
        const val EMPTY_FINGERPRINT = "none"

        /** 64 bits of SHA-256 — the same width the lexicon memo version itself uses. */
        private const val FINGERPRINT_BYTES = 8

        private const val HEX = "0123456789abcdef"

        val EMPTY = UserDictionarySnapshot(emptyList())

        /**
         * Normalizes raw provider rows into a snapshot: blank words dropped, case-folded
         * dedupe keeping the FIRST occurrence, caller order preserved.
         */
        fun of(rows: Iterable<Pair<String, Int>>): UserDictionarySnapshot {
            val seen = HashSet<String>()
            val entries = ArrayList<Pair<String, Int>>()
            for ((word, freq) in rows) {
                if (word.isBlank()) continue
                if (!seen.add(word.lowercase(Locale.ROOT))) continue
                entries.add(word to freq)
            }
            return if (entries.isEmpty()) EMPTY else UserDictionarySnapshot(entries)
        }

        /**
         * The single user-word list both swipe adapters feed into their existing custom-word
         * path: [custom] (the `custom_words_<lang>` preference, in its own order) FIRST, then
         * every [provider] row that [custom] does not already define.
         *
         * The preference wins on a collision because it is the store the app itself manages —
         * Settings → Dictionary writes it, backup/restore round-trips it, and it is the one a
         * user can edit from inside CleverKeys. The provider row for the same word would
         * otherwise silently override a frequency the user set here.
         *
         * Both consumers case-fold their own dedupe downstream (`CtcLexiconMerge.merge`,
         * `GeometricUserWordMerge.merge`); folding here as well is what makes the
         * preference-wins rule hold for case variants too (`custom` "Kubernetes" must shadow a
         * provider "kubernetes", not sit beside it).
         */
        fun mergeWithCustom(
            custom: List<Pair<String, Int>>,
            provider: UserDictionarySnapshot,
        ): List<Pair<String, Int>> {
            if (provider.isEmpty) return custom
            if (custom.isEmpty()) return provider.entries
            val customLower = HashSet<String>(custom.size * 2)
            for ((word, _) in custom) customLower.add(word.lowercase(Locale.ROOT))
            val merged = ArrayList<Pair<String, Int>>(custom.size + provider.entries.size)
            merged.addAll(custom)
            for (entry in provider.entries) {
                if (entry.first.lowercase(Locale.ROOT) in customLower) continue
                merged.add(entry)
            }
            return merged
        }
    }
}
