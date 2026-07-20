package tribixbite.cleverkeys.swipe.geometric

import java.io.File

/**
 * Shared TEST-ONLY loaders for the real shipped dictionaries the geometric-engine
 * tests decode against — the 98,140-word English CKDT/JSON lexicons and the 50k
 * Russian langpack. Carries no `Test` suffix so `TestRunnerListDriftTest` skips it.
 *
 * Dictionaries are loaded once and memoized (they are ~1–2 MB each and parsing all
 * of English on every test method would blow the < 90 s suite budget). The pure-test
 * CWD is the project root, so the shipped assets are reachable by relative `File`
 * paths (confirmed by `DictionaryBinFormatTest`); the langpack zip lives under
 * `scripts/dictionaries/`.
 */
object GeoTestFixtures {

    /** Shipped English CKDT binary (98,140 canonical words). */
    private val EN_BIN = File("src/main/assets/dictionaries/en_enhanced.bin")

    /** Flat `{word: byteScore}` English JSON (same 98,140 words) — CKDT cross-check. */
    private val EN_JSON = File("src/main/assets/dictionaries/en_enhanced.json")

    /** French CKDT binary. */
    private val FR_BIN = File("src/main/assets/dictionaries/fr_enhanced.bin")

    /** German CKDT binary. */
    private val DE_BIN = File("src/main/assets/dictionaries/de_enhanced.bin")

    /** Russian language-pack zip; `dictionary.bin` inside is CKDT, 50k words. */
    private val RU_ZIP = File("scripts/dictionaries/langpack-ru.zip")

    // Memoized dictionaries (lazy so a test that only needs `en` never parses `ru`).
    private val enCkdt: GeometricDictionary by lazy {
        CkdtDictionaryReader.read(EN_BIN.inputStream(), "en", version = 1L)
    }
    private val enJson: GeometricDictionary by lazy {
        FlatJsonDictionaryLoader.read(EN_JSON.inputStream(), "en", version = 1L)
    }
    private val frCkdt: GeometricDictionary by lazy {
        CkdtDictionaryReader.read(FR_BIN.inputStream(), "fr", version = 1L)
    }
    private val deCkdt: GeometricDictionary by lazy {
        CkdtDictionaryReader.read(DE_BIN.inputStream(), "de", version = 1L)
    }
    private val ruCkdt: GeometricDictionary by lazy {
        CkdtDictionaryReader.readFromZip(RU_ZIP, "ru", version = 1L)
    }

    /** English via the shipped CKDT binary (the primary English lexicon). */
    fun englishCkdt(): GeometricDictionary = enCkdt

    /** English via the flat JSON (the CKDT cross-check). */
    fun englishJson(): GeometricDictionary = enJson

    /** French via CKDT. */
    fun frenchCkdt(): GeometricDictionary = frCkdt

    /** German via CKDT. */
    fun germanCkdt(): GeometricDictionary = deCkdt

    /** Russian via the langpack-ru.zip CKDT route. */
    fun russianCkdt(): GeometricDictionary = ruCkdt

    /** True iff every shipped fixture dictionary is present on disk. */
    fun allDictionariesPresent(): Boolean =
        EN_BIN.exists() && EN_JSON.exists() && FR_BIN.exists() && DE_BIN.exists() && RU_ZIP.exists()
}
