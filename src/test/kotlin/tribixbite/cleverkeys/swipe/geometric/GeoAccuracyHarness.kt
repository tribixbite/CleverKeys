package tribixbite.cleverkeys.swipe.geometric

import java.util.Random

/**
 * TEST-ONLY shared driver for the Phase-5 accuracy classes (one per layout). Owns the
 * word stratification, the synthetic decode grid, top-K accounting, per-stage prune
 * recall, and the tail-canary measurement — so each `GeoAccuracy*Test` is a thin
 * per-layout wrapper that only supplies its layout + dictionary and asserts the
 * PROVISIONAL floors.
 *
 * Carries no `Test` suffix so `TestRunnerListDriftTest` skips it (test hygiene).
 * Purity (NFR-3): `kotlin.*` + `java.util.Random` + the pure engine only.
 *
 * ## Decode contract (spec)
 * Decode is ALWAYS against the FULL dictionary (98k en / 50k ru / full fr/de) — never
 * against the sampled wordlist, which would gut the ambiguity ceiling. The sample only
 * selects WHICH words to type; every decode ranks them against everything.
 *
 * ## Default in-suite grid (spec M25)
 * The default grid is intentionally small (`DEFAULT_SAMPLE_SIZE` stratified words ×
 * TYPICAL × `DEFAULT_SEEDS` seeds) so the full 6-layout suite fits the < 90 s budget.
 * Only two layouts (qwerty-en, jcuken-ru) run the TYPICAL grid on every `runPureTests`;
 * the rest run a smaller smoke unless `-PgeoFull` is set (`geoFull` system property).
 */
class GeoAccuracyHarness(
    private val layout: LayoutGeometry,
    private val dict: GeometricDictionary,
    private val label: String,
    private val config: GeometricEngineConfig = GeometricEngineConfig(),
) {
    private val synthesizer = GeoTraceSynthesizer(config)
    private val generator = TemplateGenerator(config)
    private val preprocessor = GesturePreprocessor(config)
    private val pruner = CandidatePruner(config)

    // Fixed px frame (aspect must equal the layout's build-time aspect for a faithful
    // px→norm round-trip; the fixture loader's DEFAULT_ASPECT is 2.0 → 1000×500).
    private val keyAreaWidthPx = 1000f
    private val keyAreaHeightPx = keyAreaWidthPx / layout.aspect

    // Warmed engine + index (built once; every decode reuses them).
    private val engine = GeometricSwipeEngine(config)
    private val index by lazy { TemplateIndex.build(layout, dict, config, generator) }

    init {
        engine.warmUp(layout, dict)
    }

    /** One typeable dictionary word tagged with its ordinal + stratum bucket. */
    data class SampleWord(val ordinal: Int, val word: String, val freqStratum: FreqStratum, val lenStratum: LenStratum)

    /** Frequency strata (ordinal rank buckets). */
    enum class FreqStratum { TOP_1K, RANK_1K_10K, TAIL }

    /** Length strata (codepoint count of the canonical word). */
    enum class LenStratum { SHORT_2_3, MID_4_6, LONG_7_PLUS }

    /** Aggregate top-K accuracy over a decode grid. */
    data class Accuracy(val decodes: Int, val top1: Double, val top3: Double, val top5: Double)

    /** True iff the full-grid flag `-PgeoFull` (→ `geoFull` system property) is set. */
    fun geoFull(): Boolean = System.getProperty("geoFull") == "true"

    // ── word stratification ─────────────────────────────────────────────────────

    /**
     * Build a stratified, deterministic sample of [sampleSize] TYPEABLE words spread
     * across the frequency × length strata. Sampling is seeded so the same layout/dict
     * always yields the same sample (NFR-4). Words that are untypeable or whose length
     * falls outside 2+ codepoints are skipped.
     */
    fun stratifiedSample(sampleSize: Int, seed: Long = STRATA_SEED): List<SampleWord> {
        val n = dict.size
        // Frequency-stratum boundaries by ordinal rank.
        val top1kEnd = minOf(1000, n)
        val rank10kEnd = minOf(10_000, n)

        val byBucket = HashMap<Pair<FreqStratum, LenStratum>, ArrayList<SampleWord>>()
        for (i in 0 until n) {
            val w = dict.word(i)
            val cpLen = w.codePointCount(0, w.length)
            if (cpLen < 2) continue // 1-codepoint words have no meaningful swipe path
            // Only include typeable words (an untypeable word can never be a decode target).
            if (LayoutProjection.project(w, layout) == null) continue
            val freq = when {
                i < top1kEnd -> FreqStratum.TOP_1K
                i < rank10kEnd -> FreqStratum.RANK_1K_10K
                else -> FreqStratum.TAIL
            }
            val len = when {
                cpLen <= 3 -> LenStratum.SHORT_2_3
                cpLen <= 6 -> LenStratum.MID_4_6
                else -> LenStratum.LONG_7_PLUS
            }
            byBucket.getOrPut(freq to len) { ArrayList() }.add(SampleWord(i, w, freq, len))
        }

        // Round-robin fill from the buckets so every stratum is represented, shuffling
        // each bucket deterministically first.
        //
        // NFR-4 (cross-JVM determinism): iterate the buckets in a FIXED order keyed by
        // the (freq, len) enum ordinals — NOT `byBucket.values` HashMap-iteration order.
        // `Enum.hashCode()` is identity-based (`Object.hashCode`), so a `Pair<enum,enum>`
        // HashMap iterates in a JVM-invocation-dependent order; that would make both the
        // shuffle-RNG consumption order and the round-robin fill order (hence the whole
        // sample and every accuracy number) differ between JVM runs. Sorting on the enum
        // ordinals makes the sample bit-identical across invocations.
        val rng = Random(seed)
        val buckets = byBucket.entries
            .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second.ordinal }))
            .map { it.value }
        for (b in buckets) shuffleDeterministic(b, rng)
        val out = ArrayList<SampleWord>(sampleSize)
        val cursors = IntArray(buckets.size)
        var progressed = true
        while (out.size < sampleSize && progressed) {
            progressed = false
            for ((bi, bucket) in buckets.withIndex()) {
                if (out.size >= sampleSize) break
                val c = cursors[bi]
                if (c < bucket.size) {
                    out.add(bucket[c]); cursors[bi] = c + 1; progressed = true
                }
            }
        }
        return out
    }

    /** In-place Fisher–Yates shuffle with a seeded RNG (deterministic sampling). */
    private fun <T> shuffleDeterministic(list: MutableList<T>, rng: Random) {
        for (i in list.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        }
    }

    // ── decode grid ─────────────────────────────────────────────────────────────

    /**
     * Decode every [words] entry [seeds] times at [tier]/[mode] against the FULL
     * dictionary and accumulate top-1/3/5 accuracy. A word whose synthetic trace is
     * untypeable (null) or degenerate (< 3 points) is skipped — it contributes no
     * decode. Prints the aggregate for diagnosis.
     */
    fun runGrid(
        words: List<SampleWord>,
        tier: GeoTraceSynthesizer.Tier,
        seeds: Int,
        mode: GeoTraceSynthesizer.DoubleLetterMode = GeoTraceSynthesizer.DoubleLetterMode.NONE,
    ): Accuracy {
        var decodes = 0
        var t1 = 0; var t3 = 0; var t5 = 0
        for (sw in words) {
            for (s in 0 until seeds) {
                val seed = traceSeed(sw.ordinal, s, tier, mode)
                val trace = synthesizer.synthesize(
                    sw.word, layout, keyAreaWidthPx, keyAreaHeightPx, tier, mode, seed,
                ) ?: continue
                if (trace.size < 3) continue
                val result = engine.decode(
                    GeometricSwipeRequest(trace, keyAreaWidthPx, keyAreaHeightPx, layout, dict),
                )
                decodes++
                val rank = rankOf(sw.word, result)
                if (rank in 0 until 1) t1++
                if (rank in 0 until 3) t3++
                if (rank in 0 until 5) t5++
            }
        }
        val acc = Accuracy(
            decodes = decodes,
            top1 = if (decodes > 0) t1.toDouble() / decodes else 0.0,
            top3 = if (decodes > 0) t3.toDouble() / decodes else 0.0,
            top5 = if (decodes > 0) t5.toDouble() / decodes else 0.0,
        )
        println("[accuracy] $label $tier mode=$mode n=$decodes " +
            "top1=${pct(acc.top1)} top3=${pct(acc.top3)} top5=${pct(acc.top5)}")
        return acc
    }

    /**
     * Per-stage PRUNE RECALL at [tier]: the fraction of [words] whose true ordinal
     * survives the full pruner shortlist for its synthetic trace. Separates a pruning
     * loss (word never reaches the scorer) from a scoring loss (word scored but
     * out-ranked). Uses the SAME preprocessor + pruner the engine runs.
     */
    fun pruneRecall(
        words: List<SampleWord>,
        tier: GeoTraceSynthesizer.Tier,
        seeds: Int,
        mode: GeoTraceSynthesizer.DoubleLetterMode = GeoTraceSynthesizer.DoubleLetterMode.NONE,
    ): Double {
        var tested = 0; var survived = 0
        for (sw in words) {
            for (s in 0 until seeds) {
                val seed = traceSeed(sw.ordinal, s, tier, mode)
                val trace = synthesizer.synthesize(
                    sw.word, layout, keyAreaWidthPx, keyAreaHeightPx, tier, mode, seed,
                ) ?: continue
                if (trace.size < 3) continue
                val gesture = preprocessor.process(trace, keyAreaWidthPx, keyAreaHeightPx, layout)
                if (gesture.pathLengthKw <= 0f) continue
                tested++
                val survivors = pruner.prune(gesture, index, layout)
                var found = false
                for (o in survivors) if (o == sw.ordinal) { found = true; break }
                if (found) survived++
            }
        }
        val recall = if (tested > 0) survived.toDouble() / tested else 0.0
        println("[prune-recall] $label $tier mode=$mode tested=$tested recall=${pct(recall)}")
        return recall
    }

    /**
     * The tail-canary measurement: CLEAN top-3 restricted to the TAIL frequency stratum
     * vs the TOP_1K stratum, over [words]. Returns `(top1kTop3, tailTop3)` so the caller
     * can assert the tail does not trail the head by more than the allowed gap (the
     * prior-drowning guard).
     */
    fun tailCanary(words: List<SampleWord>, seeds: Int): Pair<Double, Double> {
        val head = words.filter { it.freqStratum == FreqStratum.TOP_1K }
        val tail = words.filter { it.freqStratum == FreqStratum.TAIL }
        val headAcc = runGrid(head, GeoTraceSynthesizer.Tier.CLEAN, seeds)
        val tailAcc = runGrid(tail, GeoTraceSynthesizer.Tier.CLEAN, seeds)
        return headAcc.top3 to tailAcc.top3
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** 0-based rank of [word] in [result]'s ordered words, or -1 if absent. */
    private fun rankOf(word: String, result: tribixbite.cleverkeys.PredictionResult): Int {
        for (k in result.words.indices) if (result.words[k] == word) return k
        return -1
    }

    /** Deterministic per-(word, seedIndex, tier, mode) RNG seed (NFR-4 reproducible). */
    private fun traceSeed(
        ordinal: Int, seedIndex: Int, tier: GeoTraceSynthesizer.Tier, mode: GeoTraceSynthesizer.DoubleLetterMode,
    ): Long {
        // Mix the components into a stable 64-bit seed (splitmix-ish avalanche).
        var z = (ordinal.toLong() shl 20) xor
            (seedIndex.toLong() shl 8) xor
            (tier.ordinal.toLong() shl 4) xor mode.ordinal.toLong()
        z = z xor SEED_SALT
        z *= -0x61c8864680b583ebL
        z = z xor (z ushr 31)
        return z
    }

    private fun pct(x: Double): String = "%.1f%%".format(x * 100)

    companion object {
        /** Default in-suite sample size per accuracy layout (spec M25: ~150). */
        const val DEFAULT_SAMPLE_SIZE = 150

        /** Default in-suite seeds per word (spec M25: K=3). */
        const val DEFAULT_SEEDS = 3

        /** Smaller smoke sample for the non-default layouts (run every suite). */
        const val SMOKE_SAMPLE_SIZE = 40

        /** Full-grid sample size (spec: 500 words) under `-PgeoFull`. */
        const val FULL_SAMPLE_SIZE = 500

        /** Full-grid seeds (spec: K=5) under `-PgeoFull`. */
        const val FULL_SEEDS = 5

        /** Fixed stratification RNG seed (deterministic sampling). */
        const val STRATA_SEED = 0x5EEDL

        /** Salt mixed into every trace seed (the golden-ratio constant 0x9E3779B97F4A7C15
         *  expressed as its two's-complement signed Long so it stays a compile-time const). */
        private const val SEED_SALT = -0x61C8864680B583EBL
    }
}
