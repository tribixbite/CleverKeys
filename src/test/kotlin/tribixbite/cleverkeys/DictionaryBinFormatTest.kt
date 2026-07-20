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

    /** CKDT headers are little-endian; DataInputStream reads big-endian. */
    private fun readLeInt(ds: DataInputStream): Int = Integer.reverseBytes(ds.readInt())
}
