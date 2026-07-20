package tribixbite.cleverkeys.swipe.geometric

/**
 * Tier-A eager per-`(layout, dictionary)` template metadata: packed parallel arrays
 * of the cheap, projection-derived scalars every word contributes, plus a CSR
 * extremity-bucket index for O(bucket) candidate lookup by (start, end) key pair.
 *
 * This is built ONCE per index in [warmUp]'s single dictionary pass. During the
 * pass each word's plain-variant template IS transiently generated (and immediately
 * discarded — only the u16 path length is retained) so the Tier-A `idealPathLen`
 * stays bit-consistent with the template the scorer will materialize in Tier B
 * (the lazily-memoized full-template tier in [TemplateCache]). The index retains
 * ZERO `String` references and ZERO templates — every word's
 * identity is its ordinal rank (the array position), and the actual strings stay in
 * the caller-owned [GeometricDictionary] (NFR-2 memory math; see Cache Design).
 *
 * ## Packed layout (Structure-of-Arrays, indexed by dictionary ordinal r(w))
 *  - [firstKeyId] (1 B): the first COLLAPSED key id of the word's projection.
 *  - [lastKeyId]  (1 B): the last collapsed key id.
 *  - [collapsedLen] (1 B): number of distinct consecutive keys after collapse.
 *    **`0` is the UNTYPEABLE sentinel** (FR-4): a word whose projection failed has
 *    no template and is excluded from every bucket. Every typeable word has
 *    `collapsedLen >= 1` (single-key words are exactly `1`, the loop-primary case).
 *  - [idealPathLenQ] (u16): plain-variant path length (kw) quantized over
 *    `[0, PATH_LEN_QUANT_MAX]`. The quantization granularity
 *    (`PATH_LEN_QUANT_MAX / 65535` ≈ 0.002 kw) is three orders of magnitude below
 *    the length-ratio prune window `[0.55, 1.9]`, so it cannot alias that prune.
 *  - [bboxQ] (4 × u8 per word): the template's axis-aligned bounding box
 *    `[minU, minV, maxU, maxV]` quantized over `[0,1]`, used by the bbox-overlap
 *    prune. Single-key (loop-primary) words get a NON-degenerate bbox expanded by
 *    the half-key coarse-filter margin (which subsumes the loop extent — the loop
 *    half-side is 0.125·kw, well inside the 0.5·kw margin).
 *
 * ## Extremity buckets (CSR)
 * Word ordinals are grouped by the flat bucket key `firstKeyId * keyCount + lastKeyId`.
 * [bucketOffsets] is the CSR offsets table (size `keyCount² + 1`); [bucketEntries]
 * holds the typeable ordinals grouped contiguously by bucket, sorted ASCENDING by
 * ordinal within each bucket (so the pruner scans common words first and its output
 * is deterministic, NFR-4).
 *
 * Purity (NFR-3): `kotlin.*` only — no `android.*`.
 *
 * @property size number of words == the dictionary size (== the parallel-array length).
 * @property keyCount number of layout keys (bucket-key radix; also uint8 key-id bound).
 * @property typeableCount number of words with a template (`collapsedLen >= 1`).
 * @property deadLayout `typeableFraction < deadLayoutCoverageThreshold` (FR-4 guard).
 * @property overLengthExcludedCount words excluded (marked untypeable) because their
 *   plain-variant template exceeded [PATH_LEN_QUANT_MAX] — reachable only on very
 *   dense layouts (kw-unit lengths scale as 1/meanKeyWidth). Diagnostic counter;
 *   graceful degradation instead of a build failure (Error Handling: never throws).
 */
class TemplateIndex private constructor(
    val size: Int,
    val keyCount: Int,
    val typeableCount: Int,
    val deadLayout: Boolean,
    val overLengthExcludedCount: Int,
    // SoA scalars (indexed by ordinal). Stored as raw byte/short arrays for the NFR-2
    // budget; accessors below unpack the unsigned view.
    private val firstKeyId: ByteArray,
    private val lastKeyId: ByteArray,
    private val collapsedLen: ByteArray,
    private val idealPathLenQ: ShortArray,
    private val bboxQ: ByteArray, // 4 bytes/word: minU, minV, maxU, maxV
    // CSR extremity buckets.
    private val bucketOffsets: IntArray,
    private val bucketEntries: IntArray,
) {
    /** True iff word [i] projects to at least one key (has a template). */
    fun isTypeable(i: Int): Boolean = (collapsedLen[i].toInt() and 0xFF) != 0

    /** Collapsed key count of word [i]; `0` means untypeable (no template). */
    fun collapsedLenOf(i: Int): Int = collapsedLen[i].toInt() and 0xFF

    /** First collapsed key id of word [i] (only meaningful when typeable). */
    fun firstKeyOf(i: Int): Int = firstKeyId[i].toInt() and 0xFF

    /** Last collapsed key id of word [i] (only meaningful when typeable). */
    fun lastKeyOf(i: Int): Int = lastKeyId[i].toInt() and 0xFF

    /** Plain-variant path length (kw) of word [i], dequantized. */
    fun idealPathLenKwOf(i: Int): Float = dequantLen(idealPathLenQ[i])

    /** Template bbox minU of word [i] (normalized [0,1]). */
    fun bboxMinU(i: Int): Float = dequantUnit(bboxQ[i * 4])
    /** Template bbox minV of word [i]. */
    fun bboxMinV(i: Int): Float = dequantUnit(bboxQ[i * 4 + 1])
    /** Template bbox maxU of word [i]. */
    fun bboxMaxU(i: Int): Float = dequantUnit(bboxQ[i * 4 + 2])
    /** Template bbox maxV of word [i]. */
    fun bboxMaxV(i: Int): Float = dequantUnit(bboxQ[i * 4 + 3])

    /**
     * The CSR bucket of word ordinals whose (firstKey, lastKey) == ([first], [last]).
     * Returns a `(entries, from, toExclusive)` window into [bucketEntries] to avoid
     * copying on the hot path — the pruner iterates `entries[from until toExclusive]`.
     */
    fun bucket(first: Int, last: Int): BucketWindow {
        val k = first * keyCount + last
        return BucketWindow(bucketEntries, bucketOffsets[k], bucketOffsets[k + 1])
    }

    /** A zero-copy view of one extremity bucket's ordinals within [bucketEntries]. */
    class BucketWindow(val entries: IntArray, val from: Int, val toExclusive: Int) {
        val count: Int get() = toExclusive - from
    }

    /**
     * Honest structural byte cost of the Tier-A arrays (NFR-2). Counts the packed
     * scalar arrays + the CSR tables + array-header overhead — NOT the caller-owned
     * dictionary strings (which the index never references) and NOT the Tier-B memo
     * (owned/estimated by [TemplateCache]).
     */
    fun estimatedBytes(): Long {
        // JVM array header ≈ 16 B each; primitive payloads are exact.
        val hdr = 16L
        var total = 0L
        total += hdr + firstKeyId.size          // 1 B/word
        total += hdr + lastKeyId.size            // 1 B/word
        total += hdr + collapsedLen.size         // 1 B/word
        total += hdr + idealPathLenQ.size.toLong() * 2 // 2 B/word
        total += hdr + bboxQ.size                // 4 B/word
        total += hdr + bucketOffsets.size.toLong() * 4 // (keyCount²+1) ints
        total += hdr + bucketEntries.size.toLong() * 4 // typeableCount ints
        return total
    }

    companion object {
        /**
         * Upper bound (kw) of the u16 idealPathLength quantization range.
         *
         * Verified against the real shipped dictionaries (Phase-3 probe, 2026-07-20):
         * the longest plain-variant template is ~85.6 kw ("nationalsozialistischen"
         * on QWERTZ-de; en ~75, fr ~72, ru ~58). The spec's draft `[0, 64 kw]` range
         * would CLAMP those long words to a single saturated value — aliasing them in
         * the length-ratio prune. Widening to **128 kw** clears the measured max with
         * headroom; granularity 128/65535 ≈ 0.00195 kw stays three orders of magnitude
         * below the length-ratio window, so it still cannot alias the prune.
         *
         * Because kw-unit lengths scale as `1/meanKeyWidth`, a legitimate very dense
         * layout (~17+ columns) CAN push a long word past this bound. Such words are
         * gracefully excluded from the index (marked untypeable, surfaced via
         * [overLengthExcludedCount]) rather than aliased or crashed on — `build` runs
         * under both `warmUp` and `decode`'s synchronous fallback, where the spec's
         * Error-Handling contract forbids throwing on caller-supplied data.
         */
        const val PATH_LEN_QUANT_MAX = 128f

        /** Quantize a kw path length into u16 over [0, PATH_LEN_QUANT_MAX]. */
        internal fun quantLen(lenKw: Float): Short {
            val clamped = if (lenKw < 0f) 0f else if (lenKw > PATH_LEN_QUANT_MAX) PATH_LEN_QUANT_MAX else lenKw
            return (clamped / PATH_LEN_QUANT_MAX * 65535f + 0.5f).toInt().toShort()
        }

        /** Inverse of [quantLen]. */
        internal fun dequantLen(q: Short): Float =
            (q.toInt() and 0xFFFF) / 65535f * PATH_LEN_QUANT_MAX

        /** Quantize a normalized [0,1] coordinate into u8. */
        internal fun quantUnit(v: Float): Byte {
            val clamped = if (v < 0f) 0f else if (v > 1f) 1f else v
            return (clamped * 255f + 0.5f).toInt().toByte()
        }

        /** Inverse of [quantUnit]. */
        internal fun dequantUnit(b: Byte): Float = (b.toInt() and 0xFF) / 255f

        /**
         * Build a [TemplateIndex] over [dictionary] on [layout] in a single pass —
         * the Tier-A `warmUp` work. Projects each word, collapses duplicate keys, and
         * records the extremity/length/bbox scalars; words that fail projection are
         * flagged untypeable (`collapsedLen == 0`) and excluded from the buckets.
         *
         * @param generator supplies the plain-variant path length and loop half-side
         *   (reuses the exact resample/anisotropy math the scorer will see, so the
         *   Tier-A `idealPathLen` never disagrees with the materialized template).
         */
        fun build(
            layout: LayoutGeometry,
            dictionary: GeometricDictionary,
            config: GeometricEngineConfig,
            generator: TemplateGenerator = TemplateGenerator(config),
        ): TemplateIndex {
            val n = dictionary.size
            val keyCount = layout.keys.size
            require(keyCount in 1..256) {
                // uint8 key-id fields require <=256 keys; every real + fixture layout
                // is well under this (JCUKEN+bottom row = 38). A layout beyond this
                // bound would need a wider id encoding — surfaced loudly, not clipped.
                "TemplateIndex supports 1..256 keys; layout has $keyCount"
            }

            val firstKeyId = ByteArray(n)
            val lastKeyId = ByteArray(n)
            val collapsedLen = ByteArray(n)
            val idealPathLenQ = ShortArray(n)
            val bboxQ = ByteArray(n * 4)

            // Half-key bbox-overlap margin (normalized units). Horizontal = half a mean
            // key width; vertical = the same PHYSICAL distance mapped back through the
            // aspect (a normalized-v extent of marginU·aspect covers the same key-width
            // fraction as marginU in u). Coarse-filter margin — see the inflate note below.
            val marginU = layout.meanKeyWidth * 0.5f
            val marginV = marginU * layout.aspect
            val keys = layout.keys

            // First pass: fill the SoA scalars + count words per (first,last) bucket.
            val bucketCounts = IntArray(keyCount * keyCount)
            var typeable = 0
            var overLenExcluded = 0
            for (i in 0 until n) {
                val pre = LayoutProjection.project(dictionary.word(i), layout)
                if (pre == null) {
                    collapsedLen[i] = 0 // untypeable sentinel
                    continue
                }
                // Collapse consecutive duplicates by tracking the previous key id only
                // (no scratch buffer — the collapsed sequence itself is never needed
                // here, just its length, last id, and the bbox over its centroids).
                // A plain `prevId` local handles ANY collapsed length; the earlier
                // fixed-size scratch overflowed (silently mis-recording `last` at 65
                // collapsed keys, AIOOBE at 66+) for pathological caller dictionaries.
                var cLen = 0
                var prevId = -1
                var minU = Float.POSITIVE_INFINITY; var minV = Float.POSITIVE_INFINITY
                var maxU = Float.NEGATIVE_INFINITY; var maxV = Float.NEGATIVE_INFINITY
                for (id in pre) {
                    if (cLen == 0 || prevId != id) {
                        prevId = id
                        cLen++
                        val k = keys[id]
                        if (k.cx < minU) minU = k.cx; if (k.cx > maxU) maxU = k.cx
                        if (k.cy < minV) minV = k.cy; if (k.cy > maxV) maxV = k.cy
                    }
                }
                // Inflate the raw centroid box by a half-key margin so the bbox-overlap
                // prune is a genuinely COARSE filter: a finger within ~half a key of the
                // ideal box still overlaps. This (a) keeps single-key (loop-primary) and
                // axis-aligned (e.g. "to", both on the top row → zero V-extent) boxes
                // non-degenerate, (b) absorbs the u8 quantization step (1/255 ≈ 0.004)
                // that would otherwise clip an IDEAL trace's own box off a hairline
                // boundary, and (c) tolerates the noisy real traces of Phase 4/5. The
                // vertical margin uses the physical half-key (marginU · aspect) so the
                // inflation is isotropic in key-width units, matching `d_kw`.
                minU -= marginU; maxU += marginU
                minV -= marginV; maxV += marginV

                // Plain-variant path length via the SAME generator the scorer uses.
                val template = generator.fromKeyIds(pre, i, layout)
                val plainLen = template.pathLengthsKw[0]
                if (plainLen > PATH_LEN_QUANT_MAX) {
                    // Graceful degradation (Error Handling: build never throws): a
                    // template beyond the u16 quant range would saturate and alias the
                    // length-ratio prune, so the word is marked untypeable on this
                    // (very dense) layout instead — surfaced via overLengthExcludedCount.
                    collapsedLen[i] = 0
                    overLenExcluded++
                    continue
                }
                idealPathLenQ[i] = quantLen(plainLen)

                val first = pre[0]
                val last = prevId // last collapsed key id (cLen >= 1 ⇒ prevId is set)
                firstKeyId[i] = first.toByte()
                lastKeyId[i] = last.toByte()
                collapsedLen[i] = (if (cLen > 255) 255 else cLen).toByte()

                bboxQ[i * 4] = quantUnit(minU)
                bboxQ[i * 4 + 1] = quantUnit(minV)
                bboxQ[i * 4 + 2] = quantUnit(maxU)
                bboxQ[i * 4 + 3] = quantUnit(maxV)

                bucketCounts[first * keyCount + last]++
                typeable++
            }

            // Build CSR offsets by prefix-summing the bucket counts.
            val nBuckets = keyCount * keyCount
            val bucketOffsets = IntArray(nBuckets + 1)
            var acc = 0
            for (b in 0 until nBuckets) {
                bucketOffsets[b] = acc
                acc += bucketCounts[b]
            }
            bucketOffsets[nBuckets] = acc // == typeable

            // Second pass (counting sort): scatter each typeable ordinal into its
            // bucket slot. Because we walk ordinals ascending and fill each bucket from
            // its offset forward, entries within a bucket stay ordinal-ascending.
            val bucketEntries = IntArray(typeable)
            val cursor = bucketOffsets.copyOf(nBuckets) // per-bucket write cursor
            for (i in 0 until n) {
                if ((collapsedLen[i].toInt() and 0xFF) == 0) continue
                val b = (firstKeyId[i].toInt() and 0xFF) * keyCount + (lastKeyId[i].toInt() and 0xFF)
                bucketEntries[cursor[b]++] = i
            }

            val typeableFraction = if (n > 0) typeable.toFloat() / n else 0f
            val dead = typeableFraction < config.deadLayoutCoverageThreshold

            return TemplateIndex(
                size = n,
                keyCount = keyCount,
                typeableCount = typeable,
                deadLayout = dead,
                overLengthExcludedCount = overLenExcluded,
                firstKeyId = firstKeyId,
                lastKeyId = lastKeyId,
                collapsedLen = collapsedLen,
                idealPathLenQ = idealPathLenQ,
                bboxQ = bboxQ,
                bucketOffsets = bucketOffsets,
                bucketEntries = bucketEntries,
            )
        }
    }
}
