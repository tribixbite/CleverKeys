package tribixbite.cleverkeys

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Test-side CKLM v1 writer — a Kotlin mirror of `scripts/build_static_lm.py`'s `encode()`.
 *
 * Exists so the loader's contract can be pinned against hand-built models whose every probability
 * is known, instead of only against the shipped asset (whose values move with each rebuild).
 * [StaticLmAssetDriftTest] separately pins the shipped file against its sidecar.
 */
object StaticContextLmFixtures {

    /** −ln p in 1/16-nat steps, clamped to a byte — identical to the Python `quantise`. */
    fun quantise(p: Double): Int =
        if (p <= 0.0) 255 else (-ln(p) * StaticContextLm.QUANT_STEPS_PER_NAT).roundToInt().coerceIn(0, 255)

    /** The probability the loader will report for a value written as [p]. */
    fun dequantised(p: Double): Float =
        kotlin.math.exp(-quantise(p).toDouble() / StaticContextLm.QUANT_STEPS_PER_NAT).toFloat()

    /**
     * Encode a model.
     *
     * @param unigrams word → P(w) for EVERY word the table names (missing words get P = 0 → byte 255)
     * @param table prev → (next → P(next|prev)); at most [StaticContextLm.MAX_CONTINUATIONS] each
     */
    fun encode(
        unigrams: Map<String, Double>,
        table: Map<String, Map<String, Double>>,
        language: String = "en",
        version: Int = StaticContextLm.FORMAT_VERSION,
        magic: String = StaticContextLm.MAGIC,
    ): ByteArray {
        val words = (table.keys + table.values.flatMap { it.keys }).toSortedSet(
            Comparator { a, b -> compareUtf8(a.toByteArray(), b.toByteArray()) }
        ).toList()
        val id = words.withIndex().associate { it.value to it.index }

        val vocab = ByteArrayOutputStream()
        var prev = ByteArray(0)
        for (w in words) {
            val wb = w.toByteArray(Charsets.UTF_8)
            var common = 0
            val limit = minOf(prev.size, wb.size, 255)
            while (common < limit && prev[common] == wb[common]) common++
            vocab.write(common)
            vocab.write(wb.size - common)
            vocab.write(wb, common, wb.size - common)
            vocab.write(quantise(unigrams[w] ?: 0.0))
            prev = wb
        }

        val index = ByteArrayOutputStream()
        val conts = ByteArrayOutputStream()
        var pairs = 0
        for (p in table.keys.sortedBy { id.getValue(it) }) {
            val entries = table.getValue(p).entries.sortedBy { id.getValue(it.key) }
            index.write(le32(id.getValue(p)))
            index.write(le32(conts.size()))
            index.write(le16(entries.size))
            var last = 0
            for ((next, prob) in entries) {
                val nid = id.getValue(next)
                writeVarint(conts, nid - last)
                conts.write(quantise(prob))
                last = nid
                pairs++
            }
        }

        val prevIndexOffset = StaticContextLm.HEADER_BYTES + vocab.size()
        val contsOffset = prevIndexOffset + index.size()
        val header = ByteBuffer.allocate(StaticContextLm.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put(magic.toByteArray(Charsets.US_ASCII))
        header.putShort(version.toShort())
        header.putShort(0)
        header.put(language.toByteArray(Charsets.US_ASCII).copyOf(4))
        header.putInt(words.size)
        header.putInt(table.size)
        header.putInt(pairs)
        header.putInt(prevIndexOffset)
        header.putInt(contsOffset)
        return header.array() + vocab.toByteArray() + index.toByteArray() + conts.toByteArray()
    }

    private fun compareUtf8(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var n = value
        while (true) {
            val b = n and 0x7F
            n = n ushr 7
            if (n != 0) out.write(b or 0x80) else { out.write(b); return }
        }
    }
}
