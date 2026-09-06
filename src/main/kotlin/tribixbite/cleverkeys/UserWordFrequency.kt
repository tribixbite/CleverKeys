package tribixbite.cleverkeys

/**
 * The user-word frequency scale and its calibration onto a base lexicon's scale
 * (wave U2 — maintainer report: "custom and unusual words are harder to swipe than
 * they should be — especially custom words").
 *
 * ## The stored scale
 *
 * A custom word's frequency — the value in the `custom_words_<lang>` JSON preference
 * and the platform `UserDictionary.Words.FREQUENCY` column — is a **1..255** value,
 * the AOSP user-dictionary convention. The Add/Edit Word dialogs write it, backup
 * round-trips it, and [DEFAULT] (255) is what a plain "add this word" means: the user
 * wants their word to *win* its own gesture, and can lower it deliberately.
 *
 * ## Why consumers must NOT use the stored value raw
 *
 * Each ranking consumer runs on its own base-lexicon scale, and none of them start
 * at 1:
 *
 *  - the CTC en lexicon (`en_enhanced.json`) is a compressed byte scale spanning
 *    **134..255** (measured floor over all 98,140 words), and the en preset's λ = 4.0
 *    multiplies `ln(freq)` on that scale — a raw stored 100 (the historical dialog
 *    default) carried a weaker prior than EVERY base word (`ln 100 < ln 134`);
 *  - the tap dictionary loads en as binary CKDT ranks converted to **~5.5K..1M**
 *    (`BinaryDictionaryLoader`: `1000000 − rank·3900`), or JSON scaled to
 *    **100..10000** — a raw stored 100 sat at/below the floor of both.
 *
 * So the stored value is interpreted RELATIVE to whatever scale the consumer's base
 * lexicon actually spans: [scaleOnto] maps stored `[1..255]` linearly onto
 * `[lexiconFloor..lexiconCeil]`. Properties the tests pin:
 *
 *  - **order-preserving** (strictly monotonic in the stored value);
 *  - **stored 1 → the base floor** — a custom word can tie the rarest base word but
 *    can never rank below the entire dictionary;
 *  - **stored 255 → the base ceiling**; out-of-range stored values saturate;
 *  - **identity on full-range bases** (floor 1, ceiling 255 — the CKDT swipe
 *    lexicons), so scales that were already calibrated stay byte-for-byte unchanged.
 *
 * Legacy stored values (e.g. the old default 100, or pre-fix 1..10000 dialog values)
 * are deliberately NOT rewritten in storage — this mapping lifts them at read time
 * (100 → mid-scale, ≥256 → ceiling), and rewriting would destroy the user's chosen
 * relative ordering for nothing.
 */
object UserWordFrequency {

    /** Bottom of the stored user-word scale (AOSP user-dictionary convention). */
    const val MIN = 1

    /** Top of the stored user-word scale. */
    const val MAX = 255

    /**
     * Default frequency for a newly added user word: the top of the scale. A user
     * adding a word intends it to win its own gesture; they can lower it in the
     * Dictionary Manager if they want it demoted.
     */
    const val DEFAULT = MAX

    /**
     * Map a stored user-word frequency onto the active base lexicon's scale:
     * `[MIN..MAX]` → `[lexiconFloor..lexiconCeil]`, linear and order-preserving.
     * Degenerate scales (ceiling ≤ floor, e.g. a single-frequency base) collapse to
     * the floor.
     */
    fun scaleOnto(stored: Int, lexiconFloor: Double, lexiconCeil: Double): Double {
        val s = stored.coerceIn(MIN, MAX)
        if (lexiconCeil <= lexiconFloor) return lexiconFloor
        return lexiconFloor + (s - MIN).toDouble() / (MAX - MIN) * (lexiconCeil - lexiconFloor)
    }
}
