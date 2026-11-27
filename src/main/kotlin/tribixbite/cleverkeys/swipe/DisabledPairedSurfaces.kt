package tribixbite.cleverkeys.swipe

import java.util.Locale

/**
 * Which DISABLED words a swipe lexicon must keep as decodable surfaces, and at what frequency.
 * Pure JVM.
 *
 * ## Why (Saga release r21, 2026-11-26, case 10)
 *
 * A paired contraction is shown only for the surface the beam decodes: `we'll` is the display of
 * the surface `well` (a PAIRED key — `well` is a word of its own, `contraction-system.md` §1 and
 * §6c rule 1). Disabling `well` in Dictionary Manager drops it from the merged lexicon, and the
 * alias-key injection puts the key back only at the FLOOR frequency (`CtcContractionKeys`), where
 * `ell`, `will`, `wall` and `wel` outrank it: the w-e-l-l path then offered neither `well` nor
 * `we'll` (`ell | wl | el | will | elk | wel | wall | …`, twice in a row). Disabling a word must
 * remove that word's OWN display only, so a disabled paired key whose contraction is spelled on
 * the same path keeps its surface at the frequency it had; the slate filter
 * (`CtcSlateAssembly`) then removes the base word and the overlay's variant stays in its slot
 * (`well | we'll | wells` → `we'll | wells`).
 *
 * ## Which words
 *
 * A disabled word is kept when it is a paired key with at least one NON-POSSESSIVE PROJECTION
 * variant ([ContractionOverlay.isProjectionOf], not [ContractionOverlay.isPossessive]): `well` →
 * `we'll`, `were` → `we're`, `ill` → `i'll`, `hell` → `he'll`, `shed` → `she'd`, `its` → `it's`.
 * Not kept, so they stay at the floor as before:
 *  - a key whose only projection is a possessive (`teams` → `team's`): the possessive's swipe
 *    shape IS the base's, and it must not inherit the disabled word's rank one (§6c rule 4);
 *  - a key whose variants are other surfaces (`you` → `you're` is the path `youre` and `world` →
 *    `world's` the path `worlds`, each with its own key; `would` → `wouldn't`);
 *  - a word not spelled as its own surface (an accented `été` decodes as `ete`, which the display
 *    map would no longer restore once the word is disabled), and a word the base lexicon lacks.
 *
 * The reverse needs nothing here: disabling `we'll` removes only the display form, since no
 * lexicon holds apostrophe words and the slate filter matches the exact word.
 */
object DisabledPairedSurfaces {

    /**
     * The disabled words of [excluded] (lowercase, disabled minus the user's own words) to keep,
     * each mapped to its largest frequency in [base] (the lexicon before the disabled filter, on
     * the trie's own scale).
     *
     * @param pairedVariants lowercase key → its paired display forms (`ContractionManager.getPairedContractions`).
     * @param surfaceOf the surface the trie files a word under (the en a–z strip, a CKDT projection);
     *   a word is kept only when it IS its own surface.
     */
    fun keep(
        excluded: Set<String>,
        base: Iterable<Pair<String, Double>>,
        pairedVariants: (String) -> List<String>?,
        surfaceOf: (String) -> String?,
    ): Map<String, Double> {
        if (excluded.isEmpty()) return emptyMap()
        val wanted = excluded.filterTo(HashSet()) { word ->
            surfaceOf(word) == word &&
                pairedVariants(word)?.any {
                    ContractionOverlay.isProjectionOf(word, it) && !ContractionOverlay.isPossessive(it)
                } == true
        }
        if (wanted.isEmpty()) return emptyMap()
        val kept = HashMap<String, Double>(wanted.size * 2)
        for ((word, frequency) in base) {
            val lower = word.lowercase(Locale.ROOT)
            if (lower !in wanted) continue
            val previous = kept[lower]
            if (previous == null || frequency > previous) kept[lower] = frequency
        }
        return kept
    }
}
