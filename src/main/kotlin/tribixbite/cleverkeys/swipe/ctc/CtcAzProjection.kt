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
 * NFD → drop combining marks (Mn) → drop `'`, `’`, `-` → expand the four common Latin
 * graphemes the a–z emission head cannot represent (`ß→ss`, `œ→oe`, `æ→ae`, `ø→o`) →
 * require a–z. The canonical dictionary form is retained in [Projected.display]. These are
 * standard keyboard fallback spellings, so users can trace the available a–z keys and still
 * receive the correct canonical word. This deliberately changes the older sweep vocabulary;
 * language evaluation must therefore be refreshed before quoting the old exact percentages.
 */
object CtcAzProjection {

    /** Combining diacritical marks — the NFD residue to discard. */
    private val COMBINING = Regex("[\\p{Mn}]")

    /** Non-alphabet joiners removed rather than rejected (the STRIP convention). */
    private val JOINERS = charArrayOf('\'', '’', '-')

    /** Multi-character/special-letter fallbacks for the fixed a–z model head. */
    private val EXPANSIONS = mapOf(
        'ß' to "ss",
        'œ' to "oe",
        'æ' to "ae",
        'ø' to "o",
    )

    /**
     * The a–z surface of [word], or null when it has no a–z spelling (empty after
     * stripping, or containing a character without an a-z decomposition/expansion, such as a
     * digit or symbol). Common Latin letters `ß`, `œ`, `æ`, and `ø` use explicit expansions.
     */
    fun project(word: String): String? {
        if (word.isEmpty()) return null
        val decomposed = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in COMBINING.replace(decomposed, "")) {
            if (ch in JOINERS) continue
            val expansion = EXPANSIONS[ch]
            if (expansion != null) {
                sb.append(expansion)
                continue
            }
            if (ch !in 'a'..'z') return null
            sb.append(ch)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * A canonical lexicon projected onto its emission alphabet.
     *
     * Shared with [CtcScriptProjection] (the non-Latin twin), which is why the name's `Az` is
     * narrower than the type: both projections resolve collisions identically and there is one
     * loop, in [CtcScriptProjection.projectLexicon]. Kept here rather than moved so the three
     * test call sites that name `CtcAzProjection.Projected` stay valid.
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
     *
     * The loop itself lives in [CtcScriptProjection.projectLexicon] — one implementation for
     * both projections. This function is the Latin binding of it and its behaviour is
     * unchanged (the delegation is a move, not a rewrite: `CtcCkdtLexiconTest` pins the
     * projected surfaces, the collision counts and the display map on the real bundled
     * dictionaries).
     */
    fun projectLexicon(canonical: Map<String, Double>): Projected =
        CtcScriptProjection.projectLexicon(canonical, ::project)
}
