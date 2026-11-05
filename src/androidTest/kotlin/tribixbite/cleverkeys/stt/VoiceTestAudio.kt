package tribixbite.cleverkeys.stt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audio and scoring helpers shared by the voice instrumented tests. */
internal object VoiceTestAudio {

    /** The samples of a 16-bit PCM WAV, scaled to [-1, 1). */
    fun readWav(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(12)
        while (b.remaining() >= 8) {
            val id = String(ByteArray(4).also { b.get(it) })
            val size = b.int
            if (id == "data") return FloatArray(size / 2) { b.short / 32768f }
            b.position(b.position() + size + (size and 1))
        }
        error("no data chunk")
    }

    /** Lower-cased words without punctuation (LibriSpeech references are upper-case, unpunctuated). */
    fun norm(text: String): List<String> =
        text.lowercase().replace('-', ' ').replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Word-level edit distance between [reference] and [hypothesis]. */
    fun wordErrors(reference: String, hypothesis: String): Int {
        val r = norm(reference)
        val h = norm(hypothesis)
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[h.size]
    }
}
