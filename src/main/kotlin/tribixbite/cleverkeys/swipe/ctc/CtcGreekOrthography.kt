package tribixbite.cleverkeys.swipe.ctc

/**
 * Greek final-sigma repair for CTC lexicons.
 *
 * ## The defect this exists for
 *
 * Greek writes sigma two ways: `σ` everywhere except word-finally, where orthography requires
 * `ς`. They are distinct codepoints and — decisively for swipe — **distinct keys in different
 * rows** on `grek_qwerty`. The bundled `langpack-el` stores word-final sigma as `σ`, affecting
 * **25.7 % of the pack** (`CleverKeys-ML/ctc/PHASE_O.md` §1.3).
 *
 * Left unrepaired, a Greek swipe is scored against the wrong key in the wrong row for one word
 * in four, while the *user* swipes to `ς` because that is where Greek orthography puts it. Every
 * such word becomes undecodable, and no amount of model quality recovers it — the trie path
 * simply does not match the gesture. `PHASE_O.md` §3.3 names this one of two app-side fixes that
 * must land before ANY multi-script work.
 *
 * ## Why a repair here rather than a regenerated pack
 *
 * Both are listed as acceptable. This one is deterministic, testable without network access, and
 * — the deciding factor — it also repairs packs users have **already imported**, which a
 * regeneration cannot reach. Applying it to an already-correct pack is a no-op, so the two
 * remedies compose rather than conflict.
 *
 * ## Scope
 *
 * Word-final only. A `σ` anywhere else is correct and must be left alone: `σσ` (as in `θάλασσα`)
 * is a legitimate medial doubling, and rewriting it would break the word as thoroughly as the
 * defect this fixes.
 */
object CtcGreekOrthography {

    /** GREEK SMALL LETTER SIGMA — correct everywhere except word-finally. */
    const val SIGMA = 'σ'

    /** GREEK SMALL LETTER FINAL SIGMA — required word-finally, and its own key on the board. */
    const val FINAL_SIGMA = 'ς'

    /**
     * Rewrites a word-final [SIGMA] to [FINAL_SIGMA], leaving every other character untouched.
     *
     * Idempotent: a word already ending in `ς` is returned unchanged, so this may be applied to
     * a corrected pack without harm. Empty input and words not ending in sigma return the same
     * instance, so the common case allocates nothing.
     */
    fun repairFinalSigma(word: String): String =
        if (word.isNotEmpty() && word[word.length - 1] == SIGMA) {
            word.substring(0, word.length - 1) + FINAL_SIGMA
        } else {
            word
        }

    /**
     * Applies [repairFinalSigma] across a lexicon's word→frequency map.
     *
     * Collisions are possible in principle — a pack containing BOTH `…σ` and `…ς` forms of one
     * word would map them onto the same key — so the higher frequency wins rather than letting
     * map-insertion order decide silently. That keeps the more attested spelling's weight, which
     * is the behaviour the beam should see.
     */
    fun repairLexicon(words: Map<String, Double>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(words.size)
        for ((word, freq) in words) {
            val repaired = repairFinalSigma(word)
            val existing = out[repaired]
            out[repaired] = if (existing == null || freq > existing) freq else existing
        }
        return out
    }

    /** How many entries [repairLexicon] would rewrite — for logging and for the 25.7% pin. */
    fun affectedCount(words: Iterable<String>): Int =
        words.count { it.isNotEmpty() && it[it.length - 1] == SIGMA }
}
