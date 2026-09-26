package tribixbite.cleverkeys

import kotlin.math.exp

/**
 * The shipped static context language model — a pruned word-bigram table in the `CKLM` v1
 * binary format written by `scripts/build_static_lm.py` (see that script for the byte layout and
 * `scripts/data/PROVENANCE.md` for the corpora).
 *
 * Pure JVM (no Android imports): [parse] takes the asset's bytes, so the format, the arithmetic
 * and the shipped file itself are all exercisable in `runPureTests`. `BigramModel` does the asset
 * I/O and adapts this class to its long-standing multiplier / next-word-seed API.
 *
 * ## What it answers
 *
 * - [probability] — P(w | prev): the stored conditional for a listed pair, otherwise the
 *   previous word's BACKOFF mass spread over the unlisted words in proportion to their unigram
 *   (`alpha(prev) · P(w)`, where `alpha = (1 − Σ listed P(v|prev)) / (1 − Σ listed P(v))`).
 * - [unigram] — P(w) over the training corpus's full token stream.
 * - [contextRatio] — P(w | prev) / P(w), the association measure `BigramModel` clamps into its
 *   0.1–10 multiplier. For an unlisted word this is exactly `alpha(prev)`, which is why a word
 *   the model has never seen still gets a principled (slightly-below-one) ratio instead of a
 *   guess.
 * - [top] — the listed continuations of a previous word, best first (the next-word seed).
 *
 * ## Memory
 *
 * Primitives only — no `HashMap<String, …>`, no per-word objects. At load the front-coded
 * vocabulary is decoded ONCE into one flat UTF-8 [ByteArray] plus an [IntArray] of word starts,
 * an [IntArray] open-addressing hash over word ids is built on top, and the prev-index +
 * continuation section is copied verbatim into its own [ByteArray] (lookups decode it in place).
 * [retainedBytes] accounts for every array kept; the drift test bounds it for the shipped asset.
 *
 * Immutable after [parse] and therefore safe to share across threads. The only mutable state is
 * a single-entry decode cache, published through a volatile field of an immutable holder.
 */
class StaticContextLm private constructor(
    /** ISO language code from the header, e.g. `en`. */
    val language: String,
    /** Distinct words the model names (previous words ∪ continuations). */
    val vocabSize: Int,
    /** Previous words with at least one continuation. */
    val prevCount: Int,
    /** Total (previous, next) pairs. */
    val pairCount: Int,
    /** Concatenated UTF-8 bytes of every word, in id (sorted) order. */
    private val wordBytes: ByteArray,
    /** Start offset of word i in [wordBytes]; `wordStart[vocabSize]` is the end sentinel. */
    private val wordStart: IntArray,
    /** Quantised −ln P(w) per word id. */
    private val unigramQ: ByteArray,
    /** Open-addressing table of `id + 1` (0 = empty), size a power of two. */
    private val hashTable: IntArray,
    /** The prev index followed by the continuation stream, copied verbatim from the asset. */
    private val graph: ByteArray,
    /** Offset of the continuation stream inside [graph] (= prevCount × PREV_ENTRY_BYTES). */
    private val continuationsBase: Int,
) {

    /** One listed continuation of a previous word. */
    data class Continuation(val word: String, val probability: Float)

    /** A previous word's decoded continuations, plus its backoff mass. */
    private class Decoded(
        val prevId: Int,
        val ids: IntArray,
        val q: IntArray,
        /** alpha(prev) — the ratio every UNLISTED word gets. */
        val backoff: Float,
    )

    /** Single-entry cache: the tap hot loop scores hundreds of candidates against ONE previous word. */
    @Volatile
    private var lastDecoded: Decoded? = null

    // ── lookups ─────────────────────────────────────────────────────────────────────────────

    /** Word id of [word] (lowercased), or −1 when the model does not name it. */
    fun wordId(word: String): Int {
        if (word.isEmpty()) return -1
        val lower = word.lowercase()
        val ascii = lower.all { it.code < 0x80 }
        val bytes = if (ascii) null else lower.toByteArray(Charsets.UTF_8)
        val hash = if (bytes == null) hashAscii(lower) else hashBytes(bytes, 0, bytes.size)
        val mask = hashTable.size - 1
        var slot = hash and mask
        while (true) {
            val entry = hashTable[slot]
            if (entry == 0) return -1
            val id = entry - 1
            if (if (bytes == null) equalsAscii(id, lower) else equalsBytes(id, bytes)) return id
            slot = (slot + 1) and mask
        }
    }

    /** Does the model name [word] at all (as a previous word or a continuation)? */
    fun contains(word: String): Boolean = wordId(word) >= 0

    /** Does [prev] have listed continuations? */
    fun hasContext(prev: String): Boolean = decode(prev) != null

    /** P(w) — the quantised corpus marginal; 0 for a word the model does not name. */
    fun unigram(word: String): Float {
        val id = wordId(word)
        return if (id < 0) 0f else dequantise(unigramQ[id].toInt() and 0xFF)
    }

    /**
     * P([word] | [prev]). A listed pair returns its stored conditional; an unlisted word with a
     * known marginal returns `alpha(prev) · P(word)`. Returns 0 when [prev] has no continuations
     * or [word] is unknown (there is then no marginal to spread the backoff mass over).
     */
    fun probability(prev: String, word: String): Float {
        val d = decode(prev) ?: return 0f
        val id = wordId(word)
        if (id < 0) return 0f
        val listed = listedQ(d, id)
        return if (listed >= 0) dequantise(listed) else d.backoff * dequantise(unigramQ[id].toInt() and 0xFF)
    }

    /**
     * P([word] | [prev]) only when the pair is LISTED (one of [prev]'s stored continuations),
     * else 0 — the "is there positive corpus evidence for this pair" question, without backoff.
     */
    fun listedProbability(prev: String, word: String): Float {
        val d = decode(prev) ?: return 0f
        val id = wordId(word)
        if (id < 0) return 0f
        val listed = listedQ(d, id)
        return if (listed >= 0) dequantise(listed) else 0f
    }

    /**
     * P([word] | [prev]) / P([word]) — how much the previous word raises (> 1) or lowers (< 1)
     * the odds of [word]. 1.0 (neutral) when [prev] has no continuations. An unlisted word —
     * including one the model does not name — gets `alpha(prev)`.
     */
    fun contextRatio(prev: String, word: String): Float {
        val d = decode(prev) ?: return 1f
        val id = wordId(word)
        if (id < 0) return d.backoff
        val listed = listedQ(d, id)
        if (listed < 0) return d.backoff
        val marginal = dequantise(unigramQ[id].toInt() and 0xFF)
        return if (marginal > 0f) dequantise(listed) / marginal else d.backoff
    }

    /** The listed continuations of [prev], best first, ties by word; at most [k]. */
    fun top(prev: String, k: Int): List<Continuation> {
        if (k <= 0) return emptyList()
        val d = decode(prev) ?: return emptyList()
        val order = d.ids.indices.sortedWith(
            compareBy<Int> { d.q[it] }.thenBy { d.ids[it] } // ids are UTF-8 sorted: ties by word
        )
        val n = minOf(k, order.size)
        return List(n) { i -> Continuation(wordAt(d.ids[order[i]]), dequantise(d.q[order[i]])) }
    }

    /**
     * Visit every word with its unigram, in id order. Allocates one String per word — for
     * evaluation and diagnostics, never the typing path.
     */
    fun forEachWord(action: (word: String, unigram: Float) -> Unit) {
        for (id in 0 until vocabSize) action(wordAt(id), dequantise(unigramQ[id].toInt() and 0xFF))
    }

    /** Heap held by this model's arrays, in bytes (array headers included; object fields not). */
    fun retainedBytes(): Long =
        arrayBytes(wordBytes.size, 1) + arrayBytes(wordStart.size, 4) + arrayBytes(unigramQ.size, 1) +
            arrayBytes(hashTable.size, 4) + arrayBytes(graph.size, 1)

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private fun decode(prev: String): Decoded? {
        val prevId = wordId(prev)
        if (prevId < 0) return null
        lastDecoded?.let { if (it.prevId == prevId) return it }
        val slot = prevSlot(prevId)
        if (slot < 0) return null
        val entry = slot * PREV_ENTRY_BYTES
        var pos = continuationsBase + readInt(graph, entry + 4)
        val count = readShort(graph, entry + 8)
        val ids = IntArray(count)
        val q = IntArray(count)
        var last = 0
        var condMass = 0.0
        var margMass = 0.0
        for (i in 0 until count) {
            var shift = 0
            var delta = 0
            while (true) {
                val b = graph[pos++].toInt() and 0xFF
                delta = delta or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            last += delta
            ids[i] = last
            q[i] = graph[pos++].toInt() and 0xFF
            condMass += dequantise(q[i])
            margMass += dequantise(unigramQ[last].toInt() and 0xFF)
        }
        // Quantisation can push the listed mass a hair past 1; never let the backoff go to 0 or
        // negative, which would turn "unlisted" into "impossible".
        val num = (1.0 - condMass).coerceAtLeast(MIN_BACKOFF_MASS)
        val den = (1.0 - margMass).coerceAtLeast(MIN_BACKOFF_MASS)
        val d = Decoded(prevId, ids, q, (num / den).toFloat())
        lastDecoded = d
        return d
    }

    private fun listedQ(d: Decoded, id: Int): Int {
        val i = d.ids.binarySearch(id)
        return if (i >= 0) d.q[i] else -1
    }

    private fun prevSlot(prevId: Int): Int {
        var lo = 0
        var hi = prevCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val id = readInt(graph, mid * PREV_ENTRY_BYTES)
            when {
                id < prevId -> lo = mid + 1
                id > prevId -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun wordAt(id: Int): String =
        String(wordBytes, wordStart[id], wordStart[id + 1] - wordStart[id], Charsets.UTF_8)

    private fun equalsAscii(id: Int, s: String): Boolean {
        val start = wordStart[id]
        if (wordStart[id + 1] - start != s.length) return false
        for (i in s.indices) if (wordBytes[start + i].toInt() != s[i].code) return false
        return true
    }

    private fun equalsBytes(id: Int, b: ByteArray): Boolean {
        val start = wordStart[id]
        if (wordStart[id + 1] - start != b.size) return false
        for (i in b.indices) if (wordBytes[start + i] != b[i]) return false
        return true
    }

    companion object {
        const val MAGIC = "CKLM"
        const val FORMAT_VERSION = 1
        const val HEADER_BYTES = 32

        /** Quantisation step: probabilities are stored as round(−ln p × 16), one byte. */
        const val QUANT_STEPS_PER_NAT = 16

        /** The builder's per-previous-word cap; the drift test holds the asset to it. */
        const val MAX_CONTINUATIONS = 20

        /** u32 id + u32 offset + u16 count. */
        const val PREV_ENTRY_BYTES = 10

        /** Asset path for a language, e.g. `lm/en.cklm`. */
        @JvmStatic
        fun assetNameFor(language: String): String = "lm/$language.cklm"

        private const val MIN_BACKOFF_MASS = 1e-3

        /** 256 dequantised values, so a lookup is an array read instead of an exp(). */
        private val DEQUANT = FloatArray(256) { exp(-it.toDouble() / QUANT_STEPS_PER_NAT).toFloat() }

        private fun dequantise(q: Int): Float = DEQUANT[q]

        private fun arrayBytes(length: Int, width: Int): Long = ARRAY_HEADER_BYTES + length.toLong() * width

        /** Typical 64-bit JVM/ART array header, rounded up. */
        private const val ARRAY_HEADER_BYTES = 16L

        /**
         * Parse and fully validate a CKLM v1 model.
         *
         * Every structural property the lookups rely on is checked here, once, so a truncated or
         * corrupt asset is REJECTED rather than half-loaded: offsets in bounds, ids in range and
         * strictly ascending (prev index and each continuation run), varints terminated inside
         * the section, the header's pair count matched exactly, and the stream fully consumed.
         *
         * @throws IllegalArgumentException on any malformation
         */
        @JvmStatic
        fun parse(bytes: ByteArray): StaticContextLm {
            require(bytes.size >= HEADER_BYTES) { "CKLM: ${bytes.size} bytes is shorter than the header" }
            val magic = String(bytes, 0, 4, Charsets.US_ASCII)
            require(magic == MAGIC) { "CKLM: bad magic '$magic'" }
            val version = readShort(bytes, 4)
            require(version == FORMAT_VERSION) { "CKLM: unsupported version $version" }
            val language = String(bytes, 8, 4, Charsets.US_ASCII).trimEnd('\u0000')
            val vocabSize = readInt(bytes, 12)
            val prevCount = readInt(bytes, 16)
            val pairCount = readInt(bytes, 20)
            val prevIndexOffset = readInt(bytes, 24)
            val contsOffset = readInt(bytes, 28)
            require(vocabSize > 0 && prevCount in 1..vocabSize && pairCount >= prevCount) {
                "CKLM: implausible counts vocab=$vocabSize prevs=$prevCount pairs=$pairCount"
            }
            require(prevIndexOffset in HEADER_BYTES..bytes.size &&
                contsOffset.toLong() == prevIndexOffset.toLong() + prevCount.toLong() * PREV_ENTRY_BYTES &&
                contsOffset <= bytes.size) {
                "CKLM: section offsets out of bounds ($prevIndexOffset, $contsOffset, size ${bytes.size})"
            }

            // Vocabulary: front-coded → flat UTF-8 + starts + unigram bytes. Two passes so the
            // flat array is allocated once at its exact size (no per-word allocation).
            val wordStart = IntArray(vocabSize + 1)
            val unigramQ = ByteArray(vocabSize)
            var pos = HEADER_BYTES
            var total = 0L
            var prevLen = 0
            for (i in 0 until vocabSize) {
                require(pos + 2 <= prevIndexOffset) { "CKLM: vocabulary truncated at word $i" }
                val common = bytes[pos].toInt() and 0xFF
                val suffixLen = bytes[pos + 1].toInt() and 0xFF
                require(common <= prevLen && pos + 2 + suffixLen + 1 <= prevIndexOffset) {
                    "CKLM: vocabulary entry $i out of bounds"
                }
                prevLen = common + suffixLen
                require(prevLen > 0) { "CKLM: empty word at $i" }
                total += prevLen
                pos += 2 + suffixLen + 1
            }
            require(pos == prevIndexOffset) { "CKLM: vocabulary does not end at the prev index" }
            require(total <= Int.MAX_VALUE) { "CKLM: vocabulary too large" }
            val wordBytes = ByteArray(total.toInt())
            pos = HEADER_BYTES
            var out = 0
            for (i in 0 until vocabSize) {
                val common = bytes[pos].toInt() and 0xFF
                val suffixLen = bytes[pos + 1].toInt() and 0xFF
                pos += 2
                wordStart[i] = out
                if (common > 0) System.arraycopy(wordBytes, wordStart[i - 1], wordBytes, out, common)
                System.arraycopy(bytes, pos, wordBytes, out + common, suffixLen)
                pos += suffixLen
                out += common + suffixLen
                require(i == 0 || compareRegions(wordBytes, wordStart[i - 1], wordStart[i], out) < 0) {
                    "CKLM: vocabulary not strictly sorted at $i"
                }
                unigramQ[i] = bytes[pos++]
            }
            wordStart[vocabSize] = out

            // The graph (prev index + continuations), validated then kept verbatim.
            val graph = bytes.copyOfRange(prevIndexOffset, bytes.size)
            val base = contsOffset - prevIndexOffset
            var lastPrev = -1
            var expectedOffset = 0
            var pairs = 0
            for (s in 0 until prevCount) {
                val e = s * PREV_ENTRY_BYTES
                val id = readInt(graph, e)
                val off = readInt(graph, e + 4)
                val count = readShort(graph, e + 8)
                require(id in 0 until vocabSize && id > lastPrev) { "CKLM: prev index not ascending at $s" }
                require(off == expectedOffset) { "CKLM: continuation offset gap at prev $s" }
                require(count in 1..MAX_CONTINUATIONS) { "CKLM: prev $s has $count continuations" }
                var p = base + off
                var last = 0
                for (c in 0 until count) {
                    var shift = 0
                    var delta = 0
                    while (true) {
                        require(p < graph.size && shift <= 28) { "CKLM: continuation stream truncated" }
                        val b = graph[p++].toInt() and 0xFF
                        delta = delta or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    require(c == 0 || delta > 0) { "CKLM: continuation ids not ascending under prev $s" }
                    last += delta
                    require(last in 0 until vocabSize) { "CKLM: continuation id out of range" }
                    require(p < graph.size) { "CKLM: continuation stream truncated" }
                    p++ // logp byte
                }
                expectedOffset = p - base
                pairs += count
                lastPrev = id
            }
            require(pairs == pairCount) { "CKLM: header says $pairCount pairs, stream holds $pairs" }
            require(base + expectedOffset == graph.size) { "CKLM: trailing bytes after the continuation stream" }

            // Open-addressing hash over ids (load factor ≤ 0.5).
            var capacity = 1
            while (capacity < vocabSize * 2) capacity = capacity shl 1
            val table = IntArray(capacity)
            val mask = capacity - 1
            for (id in 0 until vocabSize) {
                var slot = hashBytes(wordBytes, wordStart[id], wordStart[id + 1]) and mask
                while (table[slot] != 0) slot = (slot + 1) and mask
                table[slot] = id + 1
            }

            return StaticContextLm(
                language, vocabSize, prevCount, pairCount,
                wordBytes, wordStart, unigramQ, table, graph, base,
            )
        }

        // FNV-1a over UTF-8 bytes; the ASCII variant hashes chars without encoding them.
        private const val FNV_OFFSET = -0x7ee3623b // 0x811C9DC5
        private const val FNV_PRIME = 0x01000193

        private fun hashBytes(b: ByteArray, from: Int, to: Int): Int {
            var h = FNV_OFFSET
            for (i in from until to) h = (h xor (b[i].toInt() and 0xFF)) * FNV_PRIME
            return mix(h)
        }

        private fun hashAscii(s: String): Int {
            var h = FNV_OFFSET
            for (c in s) h = (h xor c.code) * FNV_PRIME
            return mix(h)
        }

        /** Final avalanche so the low bits used by the power-of-two mask are well spread. */
        private fun mix(h0: Int): Int {
            var h = h0
            h = h xor (h ushr 16)
            h *= -0x7a143595
            h = h xor (h ushr 13)
            return h
        }

        /** Unsigned comparison of `b[aFrom, aTo)` with `b[aTo, bTo)` — two adjacent words. */
        private fun compareRegions(b: ByteArray, aFrom: Int, aTo: Int, bTo: Int): Int {
            val aLen = aTo - aFrom
            val bLen = bTo - aTo
            for (i in 0 until minOf(aLen, bLen)) {
                val d = (b[aFrom + i].toInt() and 0xFF) - (b[aTo + i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return aLen - bLen
        }

        private fun readInt(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        private fun readShort(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }
}
