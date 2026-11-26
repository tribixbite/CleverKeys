package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.swipe.ctc.CtcEnLexiconJson
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge

/**
 * The base-word frequencies [ContractionOverlay]'s promotion rule compares a paired variant's
 * pairing frequency against — the `baseFrequency` argument of [ContractionOverlay.apply].
 *
 * ## The scale contract
 *
 * `contraction_pairings.json`'s `frequency` field is on `en_enhanced.json`'s 0..255 BYTE scale
 * (pairings 128..255, lexicon 134..255; the non-possessive values are measured through an
 * isotonic zipf→byte fit of that very file — `scripts/extract_apostrophe_words.py
 * --en-pairing-frequencies`). A base frequency is therefore only comparable when it is READ
 * FROM `en_enhanced.json`, merged with the user's words exactly as the CTC lexicon merges them
 * ([CtcLexiconMerge.merge], which calibrates a custom word's stored 1..255 frequency onto the
 * base scale). Nothing else is on that scale:
 *
 *  - a CKDT dictionary (`<lang>_enhanced.bin`, and every language pack) stores a uint8 RANK,
 *    not a frequency. For the bundled English pair the rank IS a monotone function of the JSON
 *    byte (measured 2026-09-26: 98,140 words in both, every rank maps to exactly one byte, 219
 *    ranks over 115 bytes), but the mapping is a data-dependent step table, not a formula
 *    (`id` 196 ↔ rank 118, `its` 225 ↔ rank 60) — inverting it would mean shipping and
 *    drift-pinning a second copy of information the JSON already carries;
 *  - the geometric engine's ordinal (array index) is a position, not a frequency.
 *
 * ## One derivation for both engines
 *
 * The CTC adapter already holds the merged map (it IS its lexicon) and calls [select]. The
 * geometric adapter decodes against the CKDT, so for English it reads the same bundled asset
 * the CTC path reads, through the same streaming reader, and runs the same merge via
 * [fromEnLexicon]. Both routes end in
 * [select] over a [CtcLexiconMerge.merge] result built from the same bytes and the same user
 * words, so for any base the two engines compare the SAME number — promotion parity holds by
 * construction and `PairingBaseFrequenciesTest` pins it over the shipped asset.
 *
 * Pure JVM (no Android imports).
 */
object PairingBaseFrequencies {

    /**
     * The entries of [merged] whose key is one of [bases] (the contraction manager's
     * [tribixbite.cleverkeys.ContractionManager.getPairedFrequencyBases]), truncated to Int.
     *
     * Keys are matched as STORED: every base-asset word is lowercase, and a custom word stored
     * with capitals simply has no entry here, which the overlay reads as "never promote" — the
     * conservative outcome. Restricting to the ~1.8k bases keeps the retained map tiny next to
     * the lexicon.
     */
    fun select(merged: Map<String, Double>, bases: Set<String>): Map<String, Int> {
        val out = HashMap<String, Int>(bases.size * 2)
        for (base in bases) merged[base]?.let { out[base] = it.toInt() }
        return out
    }

    /**
     * [select] over `en_enhanced.json`'s pairs ([base], file order, as
     * [CtcEnLexiconJson.readPairs] streams them for the CTC lexicon too) merged with [userWords]
     * minus [disabled] exactly as the CTC lexicon merge does it.
     *
     * The FULL base is merged, not a pre-filter of it: [CtcLexiconMerge.merge] derives the
     * custom-word calibration floor from the whole base iterable, so filtering first would
     * calibrate a user's custom `shed` differently from the CTC path and break parity. The
     * merged map is transient (one build per dictionary-memo version).
     *
     * @param userWords the user-word list in CTC merge order (custom words then platform
     *   user-dictionary rows — `UserDictionarySnapshot.mergeWithCustom`), stored 1..255.
     */
    fun fromEnLexicon(
        base: List<Pair<String, Double>>,
        userWords: List<Pair<String, Int>>,
        disabled: Set<String>,
        bases: Set<String>,
    ): Map<String, Int> = select(CtcLexiconMerge.merge(base, userWords, disabled), bases)

    /**
     * [fromEnLexicon] over the lexicon's text ([json]), parsed by the same streaming reader. For
     * callers that already hold the text (tests, evals); the adapter streams the asset instead.
     */
    fun fromEnLexiconJson(
        json: String,
        userWords: List<Pair<String, Int>>,
        disabled: Set<String>,
        bases: Set<String>,
    ): Map<String, Int> =
        fromEnLexicon(CtcEnLexiconJson.readPairs(json.byteInputStream()), userWords, disabled, bases)
}
