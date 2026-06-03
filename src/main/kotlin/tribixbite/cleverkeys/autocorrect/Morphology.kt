package tribixbite.cleverkeys.autocorrect

/**
 * Lightweight English inflectional stemmer used by the autocorrect
 * morphological guard.
 *
 * # Why
 *
 * The bundled dictionary has incomplete inflection coverage — e.g. it
 * contains `immunization` but not the perfectly valid plural
 * `immunizations`. Without a guard, autocorrect treats the missing plural
 * as a typo and "corrects" it to a distant same-length word that *is* in
 * the dictionary (`organizations`), which is worse than doing nothing.
 *
 * `inflectionStems(word)` returns the candidate base forms a word could be
 * a regular inflection of. The caller checks whether any candidate stem is
 * in the dictionary; if so, the typed word is a legitimate inflection and
 * must not be autocorrected.
 *
 * This is deliberately a *generator of candidates*, not a real lemmatizer:
 * it over-produces (e.g. for `boxes` it offers both `box` and `boxe`), and
 * the dictionary membership check downstream filters out the spurious ones.
 * Pure + dependency-free so it unit-tests on the fast JVM path.
 */
object Morphology {

    /**
     * Candidate base forms for the regular English inflectional suffixes
     * (-s/-es/-ies, -ed/-ied, -ing, -er/-est, -ly). All returned stems are
     * lowercase and at least 2 characters. Returns an empty list when the
     * word has no recognizable inflectional suffix.
     */
    fun inflectionStems(word: String): List<String> {
        val w = word.lowercase()
        if (w.length < 3) return emptyList()
        val stems = LinkedHashSet<String>()

        // ── Plural / 3rd-person singular: -s / -es / -ies ──────────────
        if (w.endsWith("ies") && w.length >= 4) {
            stems += w.dropLast(3) + "y"          // parties → party, cities → city
        }
        if (w.endsWith("es") && w.length >= 3) {
            stems += w.dropLast(2)                // boxes → box, watches → watch
        }
        // Bare -s, but NOT -ss (class, kiss are not plurals of clas/kis).
        if (w.endsWith("s") && !w.endsWith("ss")) {
            stems += w.dropLast(1)                // cats → cat, immunizations → immunization
        }

        // ── Past tense / participle: -ed / -ied ────────────────────────
        if (w.endsWith("ied") && w.length >= 4) {
            stems += w.dropLast(3) + "y"          // tried → try
        }
        if (w.endsWith("ed") && w.length >= 3) {
            stems += w.dropLast(2)                // walked → walk
            stems += w.dropLast(1)                // used → use
        }

        // ── Gerund / present participle: -ing ──────────────────────────
        if (w.endsWith("ing") && w.length >= 4) {
            stems += w.dropLast(3)                // walking → walk
            stems += w.dropLast(3) + "e"          // using → use
        }

        // ── Comparative / superlative: -er / -est ──────────────────────
        if (w.endsWith("est") && w.length >= 4) {
            stems += w.dropLast(3)                // fastest → fast
            stems += w.dropLast(2)                // largest → large
        }
        if (w.endsWith("er") && w.length >= 3) {
            stems += w.dropLast(2)                // faster → fast
            stems += w.dropLast(1)                // larger → large
        }

        // ── Adverb: -ly / -ily ─────────────────────────────────────────
        if (w.endsWith("ily") && w.length >= 4) {
            stems += w.dropLast(3) + "y"          // happily → happy
        }
        if (w.endsWith("ly") && w.length >= 3) {
            stems += w.dropLast(2)                // quickly → quick
        }

        return stems.filter { it.length >= 2 }
    }
}
