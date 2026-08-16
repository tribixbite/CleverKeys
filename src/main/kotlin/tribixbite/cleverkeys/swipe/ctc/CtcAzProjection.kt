package tribixbite.cleverkeys.swipe.ctc

import java.text.Normalizer
import java.util.Locale

/**
 * Projects an accent-carrying lexicon onto the a–z surface the CTC beam emits, while
 * retaining the canonical (accented) form for display — the CTC twin of the geometric
 * engine's accent-recovery model ("returns the canonical dictionary form (with accents)",
 * `docs/specs/geometric-swipe-engine.md`).
 *
 * The beam walks a trie over the 26 emission columns and reconstructs a word from the
 * root→node path ([CtcLexiconTrie]), so the trie PATH must be a–z. Without the display
 * map "café" would commit as "cafe"; with it, "cafe" is the trie path and "café" is what
 * the user sees and commits — structurally the same overlay shape as
 * `CtcEngineAdapter.applyContractionDisplay`.
 *
 * ## Projection policy (`project`)
 *
 * NFD → drop combining marks (Mn) → drop `'`, `’`, `-` → require every remaining
 * character to be a–z, else the word is UNTYPEABLE and is dropped. This is a 1:1 port of
 * the `project_az` policy the λ sweep's lexicons were built with
 * (`docs/eval/2026-08-15-ctc-per-language-lambda.md`; CleverKeys-ML
 * `ctc/eval_altlayout.py`), so the shipped λ is calibrated against exactly this vocabulary.
 *
 * Deliberately NOT [tribixbite.cleverkeys.AccentNormalizer]: that one expands `ß`→`ss`,
 * `æ`→`ae`, `ø`→`o`. Expanding invents a surface the model never emits for that word (a
 * German `ß` key is a distinct key, not two `s` presses), so the validated policy DROPS
 * those words instead of mangling them. Both behaviours are defensible; only this one is
 * the one λ was measured on.
 */
object CtcAzProjection {

    /** Combining diacritical marks — the NFD residue to discard. */
    private val COMBINING = Regex("[\\p{Mn}]")

    /** Non-alphabet joiners removed rather than rejected (the STRIP convention). */
    private val JOINERS = charArrayOf('\'', '’', '-')

    /**
     * The a–z surface of [word], or null when it has no a–z spelling (empty after
     * stripping, or containing a character with no combining-mark decomposition such as
     * `ß`, `œ`, `æ`, `ø`, or any digit/symbol).
     */
    fun project(word: String): String? {
        if (word.isEmpty()) return null
        val decomposed = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in COMBINING.replace(decomposed, "")) {
            if (ch in JOINERS) continue
            if (ch !in 'a'..'z') return null
            sb.append(ch)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * A canonical lexicon projected onto a–z.
     *
     * @property freqs stripped surface → frequency, insertion-ordered (the trie's child
     *   ordering, which only affects beam tie-breaks). This is what the trie is built from.
     * @property display stripped surface → canonical form, ONLY for surfaces whose
     *   canonical form differs (accents/apostrophes/hyphens/case). Callers resolve with
     *   `display[word] ?: word`, so the common unaccented case costs nothing.
     * @property records number of input entries seen.
     * @property untypeable entries dropped because [project] returned null.
     * @property collisions entries that landed on an already-occupied surface
     *   (`records − untypeable − freqs.size`); the HIGHEST-frequency canonical form wins
     *   both the frequency and the display slot, so only that form is reachable by swipe.
     */
    class Projected(
        val freqs: LinkedHashMap<String, Double>,
        val display: Map<String, String>,
        val records: Int,
        val untypeable: Int,
        val collisions: Int,
    )

    /**
     * Project [canonical] (word → frequency, e.g. the output of [CtcLexiconMerge.merge])
     * onto a–z.
     *
     * Collision rule: keep the entry with the higher frequency; ties keep the FIRST seen,
     * so [canonical]'s insertion order (custom words first, then rank-ascending base) is
     * the deterministic tie-break. The winner owns both the frequency and the display
     * form — an unaccented winner clears any earlier accented display entry, so the map
     * can never disagree with the frequency it was chosen for.
     */
    fun projectLexicon(canonical: Map<String, Double>): Projected {
        val freqs = LinkedHashMap<String, Double>(canonical.size * 2)
        val display = HashMap<String, String>()
        var untypeable = 0
        var collisions = 0
        for ((word, freq) in canonical) {
            val surface = project(word)
            if (surface == null) {
                untypeable++
                continue
            }
            val existing = freqs[surface]
            if (existing != null) {
                collisions++
                if (freq <= existing) continue
            }
            freqs[surface] = freq
            if (word == surface) display.remove(surface) else display[surface] = word
        }
        return Projected(freqs, display, canonical.size, untypeable, collisions)
    }
}
