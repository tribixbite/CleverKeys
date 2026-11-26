package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [CtcEnLexiconJson] — the English CTC lexicon read (`CtcEngineAdapter.lexiconFor`, EN_JSON).
 *
 * Candidate 2a of `docs/audit/2026-11-26-memory-analysis.md`: the read used to be
 * `readBytes()` + `JSONObject`, which holds the 1.75 MiB file, its decoded text and a 98k-entry
 * map at once in the middle of a rebuild. The reader must stream, and its pairs must be exactly
 * the old parse's: same words, same values, same order (insertion order is the beam's and the
 * contraction ordinals' tie-break).
 *
 * The reference order comes from Gson's tree parse (an insertion-ordered map, the order Android's
 * `org.json` keeps); the reference values from `org.json`'s `optInt(word, 1)`, the old call. The
 * JVM's `org.json` is HashMap-backed, so its own key order is not the device's and is not used.
 */
class CtcEnLexiconJsonTest {

    private val asset = File("src/main/assets/dictionaries/en_enhanced.json")

    @Test
    fun theStreamedPairsAreTheOldParseOfTheShippedAsset() {
        val text = asset.readText()
        val values = JSONObject(text)
        val ordered = JsonParser.parseString(text).asJsonObject.keySet().toList()
        val expected = ordered.map { it to values.optInt(it, 1).toDouble() }

        val pairs = asset.inputStream().use { CtcEnLexiconJson.readPairs(it) }

        assertWithMessage("the shipped asset's key count (98,140 when the plan was written)")
            .that(pairs.size).isEqualTo(98_140)
        // A duplicate key would make the streamed list longer than the tree's key set, and the
        // merge keeps a duplicate's FIRST value where JSONObject kept its last: pin its absence.
        assertWithMessage("the asset has no duplicate key")
            .that(pairs.map { it.first }.toHashSet().size).isEqualTo(pairs.size)
        assertThat(pairs.size).isEqualTo(values.length())
        assertThat(pairs).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun theFirstPairArrivesBeforeTheWholeFileIsRead() {
        var consumedAtFirstKey = -1L
        val counting = CountingStream(asset.inputStream())
        counting.use { stream ->
            CtcEnLexiconJson.readPairs(stream) {
                if (consumedAtFirstKey < 0) consumedAtFirstKey = counting.count
            }
        }
        assertWithMessage(
            "bytes consumed when the first key was reached, of ${asset.length()}: a whole-file " +
                "read holds the file, its text and a full map in the middle of a rebuild"
        ).that(consumedAtFirstKey).isAtMost(64L * 1024)
        assertThat(counting.count).isEqualTo(asset.length())
    }

    @Test
    fun aCancellationThrownByTheHookStopsTheRead() {
        var keys = 0
        assertThrows(InterruptedException::class.java) {
            asset.inputStream().use { stream ->
                CtcEnLexiconJson.readPairs(stream) {
                    if (++keys == 10) throw InterruptedException("Lexicon load cancelled")
                }
            }
        }
        assertThat(keys).isEqualTo(10)
    }

    @Test
    fun valuesFollowOptIntWithItsDefaultOfOne() {
        // Shapes the asset does not use today but optInt(word, 1) answers: a fraction truncates,
        // a numeric string parses, anything else falls back to 1; escapes decode.
        val json = """{"a": 12.7, "b": "40", "c": null, "d": true, "eé": 5, "f": [1], "g": {"x": 2}, "h": "x"}"""
        val reference = JSONObject(json)
        val pairs = CtcEnLexiconJson.readPairs(ByteArrayInputStream(json.toByteArray()))
        assertThat(pairs.map { it.first }).containsExactly("a", "b", "c", "d", "eé", "f", "g", "h").inOrder()
        for ((word, value) in pairs) {
            assertWithMessage(word).that(value).isEqualTo(reference.optInt(word, 1).toDouble())
        }
    }

    /** Counts the bytes the reader has pulled from the underlying stream. */
    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }
}
