package tribixbite.cleverkeys.stt.moonshine

import java.io.ByteArrayOutputStream

/**
 * Decode-only reader of Moonshine's `tokenizer.bin` (port of `BinTokenizer` in
 * moonshine-ai/moonshine `core/bin-tokenizer.cpp`, MIT).
 *
 * Format: one record per token id, in id order. A record is a length then that many bytes; the
 * length is one byte when < 128, else two bytes `b0, b1` meaning `b1 * 128 + b0 - 128`; a zero
 * length byte is an empty (unused) slot. Token bytes are UTF-8 fragments where `▁` (U+2581)
 * stands for a space; `<…>` tokens are specials and are skipped when decoding text.
 *
 * Pure (`MoonshineTokenizerTest`).
 */
class MoonshineTokenizer private constructor(private val tokens: Array<ByteArray>) {

    val size: Int get() = tokens.size

    /** The text of [ids]: specials skipped, `▁` → space, trimmed. Unknown ids are skipped. */
    fun decode(ids: IntArray): String {
        val out = ByteArrayOutputStream()
        for (id in ids) {
            val bytes = tokens.getOrNull(id) ?: continue
            if (bytes.isEmpty() || isSpecial(bytes)) continue
            out.write(bytes, 0, bytes.size)
        }
        return String(out.toByteArray(), Charsets.UTF_8).replace(SPACE_MARK, " ").trim()
    }

    private fun isSpecial(bytes: ByteArray): Boolean =
        bytes.size > 2 && bytes[0] == '<'.code.toByte() && bytes[bytes.size - 1] == '>'.code.toByte()

    companion object {
        private const val SPACE_MARK = "▁"

        /** Largest vocabulary accepted (the file is pack input; 32,768 for tiny-streaming). */
        const val MAX_TOKENS = 1 shl 18

        /**
         * Parse [data]. Throws [IllegalArgumentException] on a truncated record or an
         * implausible vocabulary, which the engine reports as an unreadable pack.
         */
        fun parse(data: ByteArray): MoonshineTokenizer {
            val out = ArrayList<ByteArray>(32_768)
            var offset = 0
            while (offset < data.size) {
                val first = data[offset++].toInt() and 0xFF
                if (first == 0) {
                    out += ByteArray(0)
                    continue
                }
                val count = if (first < 128) {
                    first
                } else {
                    require(offset < data.size) { "truncated tokenizer length at $offset" }
                    val second = data[offset++].toInt() and 0xFF
                    second * 128 + first - 128
                }
                require(count <= data.size - offset) { "truncated tokenizer record at $offset" }
                out += data.copyOfRange(offset, offset + count)
                offset += count
                require(out.size <= MAX_TOKENS) { "tokenizer has more than $MAX_TOKENS tokens" }
            }
            require(out.isNotEmpty()) { "empty tokenizer" }
            return MoonshineTokenizer(out.toTypedArray())
        }
    }
}
