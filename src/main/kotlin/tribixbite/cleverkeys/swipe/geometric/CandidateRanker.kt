package tribixbite.cleverkeys.swipe.geometric

import kotlin.math.exp
import tribixbite.cleverkeys.PredictionResult

/**
 * Turns the scored candidate shortlist into the final [PredictionResult] (Algorithm
 * Specification §7): partial-select the top `maxResults` by `S(w)`, dedupe
 * lowercase-keep-best, and map scores through a FIXED-temperature softmax to the
 * 0–1000 integer range the suggestion bar expects.
 *
 * ## Deterministic ordering (NFR-4)
 * Candidates are ordered by:
 *  1. `S(w)` descending (best score first);
 *  2. ties → lower ordinal frequency rank (`r(w)`) — the common word wins;
 *  3. remaining ties → lexicographic on the canonical word.
 * This exactly matches the spec's "ties in score break by ordinal frequency rank,
 * then lexicographic" and the confusables requirement (template-colliding pairs both
 * appear in order).
 *
 * ## Softmax → 0–1000 (NOT min-max)
 * `score_i = round(1000 · softmax(S_i / softmaxTemperature))`. Min-max is deliberately
 * rejected: a garbage decode where all candidates tie must yield flat mid scores, not a
 * fake 1000. The softmax is computed over the SELECTED top-K (after dedupe) so the
 * emitted integers sum to ~1000 and are a proper posterior. **Scores are
 * engine-relative** (documented on [SwipeDecodingEngine]) — never comparable to
 * the CTC decoder's log-probabilities.
 *
 * Purity (NFR-3): `kotlin.*` only + the pure [PredictionResult].
 */
class CandidateRanker(private val config: GeometricEngineConfig) {

    /**
     * One scored candidate: the dictionary ordinal (== r(w)), its canonical word, and
     * its combined score `S(w)`. Constructed by the engine facade after scoring.
     */
    class Scored(val ordinal: Int, val word: String, val score: Float)

    /**
     * Rank [scored] into a [PredictionResult].
     *
     * @param scored the scored survivors (order-independent — this method sorts).
     * @param reversalCount the gesture's direction-reversal count (OQ-11 / ARC-029):
     *   scales the softmax temperature via [effectiveTemperature] so a messy trace
     *   emits a FLATTER (less confident) posterior. Affects the 0–1000 confidence
     *   integers ONLY — a temperature change is monotone on the scores, so the ranked
     *   word ORDER is identical for any value (and bit-identical end to end at the
     *   default `reversalConfidenceTempSlope = 0`).
     * @return words + 0–1000 scores, descending, deduped lowercase-keep-best, ≤
     *   `maxResults` entries. Empty input → empty result.
     */
    fun rank(scored: List<Scored>, reversalCount: Int = 0): PredictionResult {
        if (scored.isEmpty()) return EMPTY

        // 1) Deterministic sort: score desc, ordinal asc, word lexicographic.
        val ordered = scored.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { it.ordinal }
                .thenBy { it.word }
        )

        // 2) Dedupe by lowercase, keeping the best-scoring (== first, since sorted)
        //    representative. Preserves canonical display form + insertion order (which
        //    is already the ranked order).
        val byLower = LinkedHashMap<String, Scored>()
        for (c in ordered) {
            val key = c.word.lowercase()
            val existing = byLower[key]
            // `ordered` is sorted best-first, so the first occurrence is already the
            // best; only replace if a later duplicate somehow scores strictly higher
            // (defensive — cannot happen given the sort, but keeps this total).
            if (existing == null || c.score > existing.score) byLower[key] = c
        }

        // 3) Take the top `maxResults` after dedupe (insertion order == ranked order).
        val top = byLower.values.take(config.maxResults)

        // 4) Fixed-temperature softmax over the selected scores → 0–1000 integers
        //    (temperature scaled by the OQ-11 reversal-count quality signal).
        val scores = softmaxTo1000(top.map { it.score }, effectiveTemperature(reversalCount))
        val words = ArrayList<String>(top.size)
        for (c in top) words.add(c.word)
        return PredictionResult(words, scores)
    }

    /**
     * Effective softmax temperature for a decode with [reversalCount] direction
     * reversals (OQ-11 / ARC-029):
     * `T_eff = softmaxTemperature · min(1 + slope · reversals, reversalConfidenceTempMax)`.
     * At the default slope `0` the multiplier is exactly 1 (bit-identical no-op).
     * Confidence-only by construction — see [rank]'s KDoc.
     */
    internal fun effectiveTemperature(reversalCount: Int): Float {
        val slope = config.reversalConfidenceTempSlope
        if (slope <= 0f || reversalCount <= 0) return config.softmaxTemperature
        val multiplier = (1f + slope * reversalCount).coerceAtMost(config.reversalConfidenceTempMax)
        return config.softmaxTemperature * multiplier
    }

    /**
     * Map raw scores through `softmax(S_i / T)` and scale to `round(1000 · p_i)`.
     * Numerically stable (subtract the max before exp). A single candidate maps to
     * 1000; an all-equal set maps to flat `round(1000/K)` (the anti-fake-confidence
     * property).
     */
    internal fun softmaxTo1000(
        rawScores: List<Float>,
        temperature: Float = config.softmaxTemperature,
    ): List<Int> {
        if (rawScores.isEmpty()) return emptyList()
        val t = temperature
        var maxS = Float.NEGATIVE_INFINITY
        for (s in rawScores) if (s > maxS) maxS = s
        // exp of (S - max)/T; max term becomes 1.0, others ≤ 1.0.
        val exps = DoubleArray(rawScores.size)
        var sum = 0.0
        for (i in rawScores.indices) {
            val e = exp(((rawScores[i] - maxS) / t).toDouble())
            exps[i] = e
            sum += e
        }
        val out = ArrayList<Int>(rawScores.size)
        if (sum <= 0.0) {
            // Degenerate (all -inf, impossible for finite scores) → flat split.
            val flat = 1000 / rawScores.size
            for (i in rawScores.indices) out.add(flat)
            return out
        }
        for (i in rawScores.indices) out.add(Math.round(1000.0 * exps[i] / sum).toInt())
        return out
    }

    companion object {
        private val EMPTY = PredictionResult(emptyList(), emptyList())
    }
}
