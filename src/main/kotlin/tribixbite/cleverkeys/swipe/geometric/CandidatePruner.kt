package tribixbite.cleverkeys.swipe.geometric

import kotlin.math.abs
import kotlin.math.ln

/**
 * The three-stage candidate filter that turns a 98k-word [TemplateIndex] into the
 * ≤ `maxCandidatesScored` shortlist the scorer materializes. Every stage reads ONLY
 * the cheap Tier-A packed arrays (no template materialization) so pruning stays
 * sub-millisecond at 98k (see the Pruning Pipeline table).
 *
 * ## Stages
 *  1. **Extremity buckets** — the CSR union of `(startNearest[i], endNearest[j])`
 *     buckets: 2-nearest-start × 2-nearest-end = 4 lookups (Floris-measured 93.9%
 *     sensitivity / 99.5% specificity on QWERTY), WIDENED to 3×3 on dense layouts
 *     (`kw < denseLayoutKwThreshold`) where noisy endpoints are the real recall risk.
 *  2. **Length ratio** — keep candidates with `gesturePathLenKw / templatePathLenKw`
 *     ∈ `[lengthRatioMin, lengthRatioMax]`. No corner-count filter (corners are
 *     soft-only; collinear words have zero corners).
 *  3. **bbox overlap** + a deterministic **best-`maxCandidatesScored` cap** by
 *     `|ln ratio|` ascending, ties broken by lower ordinal rank — NEVER "first 800 in
 *     scan order" (NFR-4). At 98k the cap = 0.8% of the lexicon.
 *
 * The result is an `IntArray` of surviving dictionary ordinals; deterministic order
 * (best-`|ln ratio|` first, tie → lower ordinal) so downstream scoring/ranking is
 * reproducible.
 *
 * Purity (NFR-3): `kotlin.*` only.
 */
class CandidatePruner(private val config: GeometricEngineConfig) {

    /**
     * Prune [index] against [gesture] into the scored-candidate shortlist.
     *
     * @param gesture the preprocessed swipe (constructed directly in Phase-3 tests).
     * @param layout the geometry (its `meanKeyWidth` drives the dense-widening decision).
     * @return surviving ordinals, deterministically ordered, size ≤ `maxCandidatesScored`.
     */
    fun prune(gesture: ProcessedGesture, index: TemplateIndex, layout: LayoutGeometry): IntArray {
        if (index.typeableCount == 0) return IntArray(0)

        // ── Stage 1: extremity buckets ────────────────────────────────────────
        // Widen to 3-nearest per end on dense layouts (small kw ⇒ ≳13 columns).
        val neighbors = if (layout.meanKeyWidth < config.denseLayoutKwThreshold) {
            maxOf(3, config.extremityNeighbors)
        } else {
            config.extremityNeighbors
        }
        val starts = takeUpTo(gesture.startNearest, neighbors)
        val ends = takeUpTo(gesture.endNearest, neighbors)

        // Union the (start, end) buckets. A word can only ever land in ONE bucket
        // (its own first/last pair), so unioning distinct buckets yields disjoint
        // ordinal sets — no dedupe needed, and the total scan is bounded by the sum of
        // bucket sizes (the "~4–16k" survivors row).
        val candidates = ArrayList<Int>(256)
        for (s in starts) {
            for (e in ends) {
                val w = index.bucket(s, e)
                var p = w.from
                while (p < w.toExclusive) {
                    candidates.add(w.entries[p])
                    p++
                }
            }
        }
        if (candidates.isEmpty()) return IntArray(0)

        // ── Stage 2 + 3: length-ratio window, bbox overlap, best-K cap ─────────
        // Fuse the length-ratio prune (stage 2) with the |ln ratio| cap proxy (stage 3)
        // in one pass: compute |ln ratio| once, drop out-of-window / non-overlapping
        // candidates, and keep a bounded best-K selection.
        val gLen = gesture.pathLengthKw
        val gMinU = gesture.bbox[0]; val gMinV = gesture.bbox[1]
        val gMaxU = gesture.bbox[2]; val gMaxV = gesture.bbox[3]

        // Survivors of stages 2–3 (pre-cap), each with its |ln ratio| key.
        val survivorOrdinals = IntArray(candidates.size)
        val survivorKeys = FloatArray(candidates.size)
        var m = 0
        for (ordinal in candidates) {
            val tLen = index.idealPathLenKwOf(ordinal)
            if (tLen <= 0f) continue // defensive: a template always has positive length
            val ratio = gLen / tLen
            if (ratio < config.lengthRatioMin || ratio > config.lengthRatioMax) continue // stage 2
            // Stage 3a: bbox overlap (axis-aligned; reject disjoint boxes).
            if (index.bboxMaxU(ordinal) < gMinU || index.bboxMinU(ordinal) > gMaxU) continue
            if (index.bboxMaxV(ordinal) < gMinV || index.bboxMinV(ordinal) > gMaxV) continue
            survivorOrdinals[m] = ordinal
            survivorKeys[m] = abs(ln(ratio.toDouble())).toFloat()
            m++
        }
        if (m == 0) return IntArray(0)

        // Stage 3b: best-`maxCandidatesScored` by |ln ratio| ascending, tie → lower
        // ordinal. If already within the cap, still SORT so the scorer sees a
        // deterministic order (NFR-4).
        return selectBest(survivorOrdinals, survivorKeys, m, config.maxCandidatesScored)
    }

    /**
     * Select the best [cap] of the first [count] (ordinal, key) survivors ordered by
     * key ascending, ties broken by lower ordinal — deterministically. Returns them
     * sorted so the scorer's iteration order is fixed.
     *
     * Uses a full stable sort of the index permutation: [count] is at most the summed
     * bucket size (typically a few thousand), so an O(count·log count) sort costs far
     * less than the per-candidate scoring it precedes; a partial-select would save
     * little and complicate the deterministic tie-break.
     */
    private fun selectBest(ordinals: IntArray, keys: FloatArray, count: Int, cap: Int): IntArray {
        // Sort an index permutation by (key asc, ordinal asc).
        val perm = (0 until count).sortedWith(
            compareBy({ keys[it] }, { ordinals[it] })
        )
        val outSize = if (count < cap) count else cap
        val out = IntArray(outSize)
        for (i in 0 until outSize) out[i] = ordinals[perm[i]]
        return out
    }

    /** First [k] ids of [arr] (or all of them if shorter). */
    private fun takeUpTo(arr: IntArray, k: Int): IntArray =
        if (arr.size <= k) arr else arr.copyOf(k)
}
