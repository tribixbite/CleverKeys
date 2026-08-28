package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import java.io.DataInputStream
import java.io.File
import org.junit.Test

/**
 * DICT-1 guard: the shipped English dictionary must stay V2 `CKDT` format.
 *
 * `en_enhanced.bin` is built by `scripts/build_wordlist.py --write` in the V2
 * `CKDT` format (canonical words + rank + accent-normalization map). The gradle
 * `generateBinaryDictionaries` task regenerates `<lang>_enhanced.bin` from json
 * via `generate_binary_dict.py`, which emits the OLDER V1 `DICT` format (no
 * accent map). en is the only bundled dict with a json in assets, so a stray
 * json mtime bump could silently downgrade it. This test fails loudly if the
 * shipped bin is ever V1, catching the regression in CI before it ships.
 *
 * 2026-07-20 extension: EVERY bundled `*_enhanced.bin` (en es fr de it pt sv)
 * is now produced by the same classifier + `build_dictionary.py` V2 writer, so
 * the magic+version guard sweeps all of them, not just English.
 */
class DictionaryBinFormatTest {

    private val assetsDir = File("src/main/assets/dictionaries")

    @Test
    fun englishBinary_isV2CkdtFormat() {
        val bin = File(assetsDir, "en_enhanced.bin")
        assertWithMessage("en_enhanced.bin must exist").that(bin.exists()).isTrue()
        val magic = bin.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) }
        assertWithMessage(
            "en_enhanced.bin magic is '$magic' — expected V2 'CKDT'. A 'DICT' magic means the " +
                "gradle generateBinaryDictionaries task downgraded it from V2; rebuild with " +
                "scripts/build_wordlist.py --write."
        ).that(magic).isEqualTo("CKDT")
    }

    @Test
    fun everyBundledBinary_isV2CkdtWithPlausibleWordCount() {
        val bins = assetsDir.listFiles { f -> f.name.endsWith("_enhanced.bin") }
        assertWithMessage("bundled *_enhanced.bin dictionaries must exist in $assetsDir")
            .that(bins).isNotNull()
        assertWithMessage("expected the 7 bundled dictionaries (en es fr de it pt sv)")
            .that(bins!!.size).isAtLeast(7)
        for (bin in bins) {
            DataInputStream(bin.inputStream().buffered()).use { ds ->
                val magic = String(ByteArray(4).also { ds.readFully(it) }, Charsets.US_ASCII)
                assertWithMessage("${bin.name} magic is '$magic' — expected V2 'CKDT' " +
                        "(rebuild with scripts/build_wordlist.py --lang <code> --write)")
                    .that(magic).isEqualTo("CKDT")
                val version = readLeInt(ds)
                assertWithMessage("${bin.name} header version").that(version).isEqualTo(2)
                ds.skipBytes(4) // lang tag (utf-8, NUL-padded)
                val wordCount = readLeInt(ds)
                assertWithMessage("${bin.name} wordCount must be a real lexicon, not a " +
                        "truncated or unigram-sized list")
                    .that(wordCount).isAtLeast(20_000)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // ARC-039: the binary readers must read-fully, not `available()`-size a single read.
    //
    // `BinaryDictionaryLoader` / `BinaryContractionLoader` used to allocate a ByteBuffer of
    // `inputStream.available()` bytes, issue ONE `channel.read(buffer)`, and never check the
    // return value. Both halves are unsound: `available()` is an estimate, and a channel read
    // may legally return short. The result was a buffer whose limit stopped inside the file, so
    // a 2.5 MB dictionary loaded as a truncated one with no error anywhere.
    //
    // These tests pin the replacement seam, `readBinaryAssetFully`, which is pure (InputStream
    // in, ByteBuffer out) precisely so it can be exercised without a Context.
    // ---------------------------------------------------------------------------------------

    /**
     * A stream that hands back at most [chunk] bytes per `read` call — the behaviour a real
     * asset/channel stream is allowed to exhibit and the old code assumed away.
     */
    private class DribblingInputStream(data: ByteArray, private val chunk: Int) :
        java.io.FilterInputStream(java.io.ByteArrayInputStream(data)) {
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, minOf(len, chunk))
    }

    @Test
    fun readBinaryAssetFully_drainsAStreamThatReturnsShortReads() {
        val payload = ByteArray(8192) { (it % 251).toByte() }
        val buffer = readBinaryAssetFully(
            DribblingInputStream(payload, chunk = 7), "dribble-test", MIN_BINARY_DICT_BYTES
        )
        assertWithMessage(
            "readBinaryAssetFully must loop until EOF; a single available()-sized read would " +
                "have stopped after the first short chunk"
        ).that(buffer.limit()).isEqualTo(payload.size)
        val roundTrip = ByteArray(payload.size).also { buffer.get(it) }
        assertWithMessage("bytes must survive the read unchanged").that(roundTrip)
            .isEqualTo(payload)
    }

    @Test
    fun readBinaryAssetFully_isLittleEndian() {
        // CKDT magic, little-endian on disk.
        val bytes = ByteArray(MIN_BINARY_DICT_BYTES)
        "CKDT".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        val buffer = readBinaryAssetFully(
            java.io.ByteArrayInputStream(bytes), "endian-test", MIN_BINARY_DICT_BYTES
        )
        assertWithMessage("headers are little-endian; a big-endian buffer misreads every int")
            .that(buffer.int).isEqualTo(0x54444B43)
    }

    @Test
    fun readBinaryAssetFully_rejectsATruncatedStreamLoudly() {
        val truncated = ByteArray(MIN_BINARY_DICT_BYTES - 1)
        val thrown = try {
            readBinaryAssetFully(
                java.io.ByteArrayInputStream(truncated), "truncated.bin", MIN_BINARY_DICT_BYTES
            )
            null
        } catch (e: java.io.IOException) {
            e
        }
        assertWithMessage(
            "a stream too short to hold a header must raise IOException, not return a buffer " +
                "the parser will walk off the end of"
        ).that(thrown).isNotNull()
        assertWithMessage("the failure must name the source so the log is actionable")
            .that(thrown!!.message).contains("truncated.bin")
    }

    @Test
    fun seekSection_rejectsAnOffsetPastTheBuffer() {
        val buffer = java.nio.ByteBuffer.allocate(64)
        for ((label, offset) in listOf("past end" to 65, "negative" to -1)) {
            val thrown = try {
                buffer.seekSection(offset, "canonical")
                null
            } catch (e: java.io.IOException) {
                e
            }
            assertWithMessage(
                "a $label section offset must raise IOException (which the loaders catch), not " +
                    "IllegalArgumentException (which they do not, so it would crash the app)"
            ).that(thrown).isNotNull()
        }
        // The in-bounds case must still position normally.
        buffer.seekSection(16, "canonical")
        assertWithMessage("a valid offset must position the buffer").that(buffer.position())
            .isEqualTo(16)
    }

    /** CKDT headers are little-endian; DataInputStream reads big-endian. */
    private fun readLeInt(ds: DataInputStream): Int = Integer.reverseBytes(ds.readInt())
}
