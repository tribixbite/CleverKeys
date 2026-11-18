package tribixbite.cleverkeys.swipe.ctc

/**
 * One lower-frequency canonical form that shares its emission surface with a more frequent
 * one — German `schön` under `schon`, French `là` under `la`, Spanish `él` under `el`.
 *
 * @property word the canonical dictionary form, shown and committed as is.
 * @property logFreq `ln(freq + 1e-10)` on the lexicon's own scale — the same quantity the trie
 *   stores for a word ([CtcLexiconTrie.insert]), so it drops straight into the beam's
 *   `lambda * logFreq` term.
 */
class CtcAlternateForm(val word: String, val logFreq: Double)

/**
 * Turns the beam's a–z SURFACES into the words the user sees, offering every canonical form
 * that shares a surface (known issue "`schön`/`schon`-type collisions keep only the more
 * frequent word", fixed 2026-11-18).
 *
 * The trie holds one node per surface ([CtcScriptProjection.projectLexicon]): `schon` and
 * `schön` both project to `schon`, so the beam can return that surface once. Before this the
 * surface showed only its most frequent form, and `schön` could never be swiped. Now:
 *
 *  - the surface's most frequent form keeps the beam's own score and position — exactly what
 *    it had before, so a slate with no collisions is unchanged;
 *  - each other form of that surface is a candidate of its own, scored as if it had been a
 *    separate trie word on the same path: the emission evidence is identical (it IS the same
 *    path), only the frequency term differs, so its score is
 *    `final − lambda · (node logFreq − its own logFreq)`. That is the ranking the geometric
 *    engine already gives these words — it keeps every canonical form as its own entry;
 *  - the slate is re-sorted by score and cut to `topK`. A winner always sorts above its own
 *    alternates (their score is lower or equal), and on an exact tie a beam word sorts above
 *    any alternate, so the top candidate never changes.
 *
 * The display rewrite (`cafe` → `café`) happens here as well, rather than after the fuzzy
 * rescue as before: a surface's alternate can itself be the bare surface (`ware` under
 * `wäre`), and after this step the two can no longer be told apart by their spelling.
 */
object CtcSurfaceForms {

    /**
     * @param candidates the beam's slate, best first, words being a–z surfaces.
     * @param display surface → most-frequent canonical form, only where they differ.
     * @param alternates surface → its other canonical forms, most frequent first.
     * @param lambda the preset's frequency weight ([CtcScoringParams.lambda]).
     * @param topK slate length after expansion.
     * @return candidates with display words, best first; alternates carry their own
     *   [CtcCandidate.logFreq] and the adjusted [CtcCandidate.finalScore].
     */
    fun expand(
        candidates: List<CtcCandidate>,
        display: Map<String, String>,
        alternates: Map<String, Array<CtcAlternateForm>>,
        lambda: Double,
        topK: Int,
    ): List<CtcCandidate> {
        if (candidates.isEmpty()) return candidates
        if (alternates.isEmpty() || candidates.none { alternates.containsKey(it.word) }) {
            if (display.isEmpty() || candidates.none { display.containsKey(it.word) }) {
                return candidates.take(topK)
            }
            return candidates.take(topK).map { c -> display[c.word]?.let { c.copy(word = it) } ?: c }
        }
        // (candidate, isAlternate, beam position) — sorted by score, then beam words first,
        // then beam order, so ties resolve the way the beam already resolved them.
        class Entry(val candidate: CtcCandidate, val alternate: Boolean, val order: Int)
        val entries = ArrayList<Entry>(candidates.size * 2)
        for ((order, c) in candidates.withIndex()) {
            entries.add(Entry(display[c.word]?.let { c.copy(word = it) } ?: c, false, order))
            val forms = alternates[c.word] ?: continue
            for (form in forms) {
                val score = c.finalScore - lambda * (c.logFreq - form.logFreq)
                entries.add(Entry(c.copy(word = form.word, finalScore = score, logFreq = form.logFreq), true, order))
            }
        }
        entries.sortWith(
            compareByDescending<Entry> { it.candidate.finalScore }
                .thenBy { it.alternate }
                .thenBy { it.order }
        )
        val out = ArrayList<CtcCandidate>(minOf(topK, entries.size))
        val seen = HashSet<String>()
        for (e in entries) {
            if (out.size >= topK) break
            // Defensive: two surfaces never share a canonical form (a form projects to exactly
            // one surface), but a duplicate word on the bar would be a visible bug.
            if (seen.add(e.candidate.word)) out.add(e.candidate)
        }
        return out
    }
}
