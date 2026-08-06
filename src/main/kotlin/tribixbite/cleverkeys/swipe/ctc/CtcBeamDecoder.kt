package tribixbite.cleverkeys.swipe.ctc

/**
 * A decoded candidate word with its score components.
 *
 * @property word the surface form.
 * @property finalScore the ranked score
 *   `ctc / max(len,1)^gamma + beta*len + lambda*logFreq` (higher is better).
 * @property ctcScore the raw accumulated CTC path log-score before length norm.
 * @property length the word length (== trie depth).
 * @property logFreq the AOSP-scale log-frequency contributed to the score.
 */
data class CtcCandidate(
    val word: String,
    val finalScore: Double,
    val ctcScore: Double,
    val length: Int,
    val logFreq: Double,
)

/**
 * FUTO-style single-stream CTC trie-beam decoder.
 *
 * This is a 1:1 Kotlin port of FUTO's `beam_search.cpp` (`TrieBeamSearch`) for the
 * single-swipe case, cross-checked against the Python port's `futo_viterbi_beam`
 * (`scripts/futo_decoder_ceiling.py`). Given per-frame log-emissions ([CtcEmissions]) and
 * a lexicon [CtcLexiconTrie], it runs the trie-constrained CTC Viterbi beam and returns
 * the top-k words under FUTO's final scoring.
 *
 * ## Algorithm (integration study §3)
 * Each hypothesis is a `(score, trieNode, blankEnded)` cursor. Per frame, every
 * hypothesis expands into three CTC moves against that frame's log-probs:
 *  - **A. blank** — stay on the node, set `blankEnded`; dedup key `(id shl 1) or 1`.
 *  - **B. advance** — for each trie child, move to the child on its character; key
 *    `childId shl 1`.
 *  - **C. repeat** — re-emit the node's own character, staying (only when
 *    `!blankEnded` and the node is not the root); key `id shl 1`. Unlike textbook CTC
 *    this does NOT require a blank between distinct characters — a swipe design choice
 *    (`beam_search.cpp:241-242`).
 *
 * Dedup is a **MAX-merge** (Viterbi, not log-sum) keyed on `(nodeId shl 1) or blankEnded`.
 * Candidates are pruned to `beamWidth` by the **length-aware** key
 * `score / max(depth,1)^gammaPrune + betaPrune*depth`, which is distinct from the final
 * length normalization. Complete-word nodes are scored with the final formula, deduped by
 * surface form keeping the max, and truncated to `topK`.
 *
 * ## Parity contract
 * Beam math runs in [Double]; emission values are read as [Float] (float32) then widened,
 * exactly mirroring the Python port. Dedup uses an insertion-ordered map and pruning uses
 * a stable descending sort so tie handling matches the port. The golden test asserts
 * identical top-k words vs the port (scores within float tolerance — `Math.pow`/`ln`
 * differ from libm by at most ~1 ULP).
 */
object CtcBeamDecoder {

    /** One beam hypothesis; only [score] mutates during the per-frame MAX-merge. */
    private class Hyp(var score: Double, val node: CtcTrieNode, val blankEnded: Boolean)

    /**
     * Greedy CTC collapse (`README greedy_ctc` / the port's `greedy_ctc`): per-frame
     * argmax over ALL classes (first max index on ties), dropping blanks and consecutive
     * repeats. A pure-model baseline that ignores the lexicon.
     */
    fun greedy(emissions: CtcEmissions, alphabet: CharArray): String {
        val sb = StringBuilder()
        var prev = -1
        val nLetters = alphabet.size
        val blank = emissions.blankIndex
        for (t in 0 until emissions.frames) {
            var best = 0
            var bestVal = emissions.at(t, 0)
            for (c in 1 until emissions.numClasses) {
                val v = emissions.at(t, c)
                if (v > bestVal) { bestVal = v; best = c }
            }
            if (best != prev && best != blank && best < nLetters) sb.append(alphabet[best])
            prev = best
        }
        return sb.toString()
    }

    /**
     * Decode [emissions] against [trie] into the top-[CtcScoringParams.topK] candidate
     * words under [params].
     *
     * The [trie]'s alphabet MUST match the emission-column ordering (class column `c` ↔
     * `trie.alphabet[c]`), which also aligns each trie node's `charIdx` to the emissions.
     */
    fun decode(emissions: CtcEmissions, trie: CtcLexiconTrie, params: CtcScoringParams): List<CtcCandidate> {
        require(emissions.alphabetSize == trie.alphabet.size) {
            "emissions alphabet size ${emissions.alphabetSize} != trie alphabet size ${trie.alphabet.size}"
        }
        val frames = emissions.frames
        val blank = emissions.blankIndex
        val beamWidth = params.beamWidth
        // lp_only: gamma_prune == beta_prune == 0 → pruning falls back to the raw score
        // (FUTO's non-single-stream case). Faithful to length_prune_score's guard.
        val lpOnly = params.gammaPrune == 0.0 && params.betaPrune == 0.0

        var beam = ArrayList<Hyp>(2)
        beam.add(Hyp(0.0, trie.root, false))

        for (t in 0 until frames) {
            val blankLp = emissions.at(t, blank).toDouble()
            val nxt = LinkedHashMap<Long, Hyp>()

            for (h in beam) {
                val node = h.node
                val score = h.score

                // A: emit blank -> stay on node, blankEnded = true.
                run {
                    val key = (node.id.toLong() shl 1) or 1L
                    val s = score + blankLp
                    val cur = nxt[key]
                    if (cur == null) nxt[key] = Hyp(s, node, true)
                    else if (cur.score < s) cur.score = s
                }

                // B: emit character -> advance to each child, blankEnded = false.
                for (child in node.children) {
                    val s = score + emissions.at(t, child.charIdx).toDouble()
                    val key = child.id.toLong() shl 1
                    val cur = nxt[key]
                    if (cur == null) nxt[key] = Hyp(s, child, false)
                    else if (cur.score < s) cur.score = s
                }

                // C: repeat the node's own character -> stay, blankEnded = false.
                //    Only when the previous move was not a blank and the node is real.
                if (!h.blankEnded && node.depth > 0) {
                    val s = score + emissions.at(t, node.charIdx).toDouble()
                    val key = node.id.toLong() shl 1
                    val cur = nxt[key]
                    if (cur == null) nxt[key] = Hyp(s, node, false)
                    else if (cur.score < s) cur.score = s
                }
            }

            var cand = ArrayList(nxt.values)
            if (cand.size > beamWidth) {
                // Stable descending sort by the length-aware prune key, keep the top beamWidth.
                cand = ArrayList(cand.sortedByDescending { pruneScore(it.score, it.node.depth, params, lpOnly) })
                cand.subList(beamWidth, cand.size).clear()
            }
            beam = cand
            if (beam.isEmpty()) break
        }

        // Final scoring over complete-word nodes; dedup by surface form keeping the max.
        val best = LinkedHashMap<String, CtcCandidate>()
        for (h in beam) {
            val node = h.node
            if (!node.isWord || node.depth == 0) continue
            val len = node.depth
            val l = if (len > 1) len else 1 // max(len, 1)
            val ctc = h.score
            val finalScore = ctc / Math.pow(l.toDouble(), params.gamma) +
                params.beta * len + params.lambda * node.logFreq
            val word = node.word()
            val prev = best[word]
            if (prev == null || prev.finalScore < finalScore) {
                best[word] = CtcCandidate(word, finalScore, ctc, len, node.logFreq)
            }
        }
        return best.values.sortedByDescending { it.finalScore }.take(params.topK)
    }

    /**
     * Length-aware pruning key (`length_prune_score`):
     * `score / max(depth,1)^gammaPrune + betaPrune*depth`. Returns the raw score in the
     * `lpOnly` fallback (`gammaPrune == betaPrune == 0`).
     */
    private fun pruneScore(score: Double, depth: Int, params: CtcScoringParams, lpOnly: Boolean): Double {
        if (lpOnly) return score
        val d = if (depth > 0) depth else 1
        return score / Math.pow(d.toDouble(), params.gammaPrune) + params.betaPrune * depth
    }
}
