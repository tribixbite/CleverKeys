package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * DICT-1 guard: the shipped English dictionary must stay V2 `CKDT` format.
 *
 * `en_enhanced.bin` is built by `scripts/build_en_wordlist.py --write` in the V2
 * `CKDT` format (canonical words + rank + accent-normalization map). The gradle
 * `generateBinaryDictionaries` task regenerates `<lang>_enhanced.bin` from json
 * via `generate_binary_dict.py`, which emits the OLDER V1 `DICT` format (no
 * accent map). en is the only bundled dict with a json in assets, so a stray
 * json mtime bump could silently downgrade it. This test fails loudly if the
 * shipped bin is ever V1, catching the regression in CI before it ships.
 */
class DictionaryBinFormatTest {

    @Test
    fun englishBinary_isV2CkdtFormat() {
        val bin = File("src/main/assets/dictionaries/en_enhanced.bin")
        assertWithMessage("en_enhanced.bin must exist").that(bin.exists()).isTrue()
        val magic = bin.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) }
        assertWithMessage(
            "en_enhanced.bin magic is '$magic' — expected V2 'CKDT'. A 'DICT' magic means the " +
                "gradle generateBinaryDictionaries task downgraded it from V2; rebuild with " +
                "scripts/build_en_wordlist.py --write."
        ).that(magic).isEqualTo("CKDT")
    }
}
