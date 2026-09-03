package tribixbite.cleverkeys.langpack

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The shipped promise behind every "downloadable language packs" release note, end to end
 * through the REAL [LanguagePackManager].
 *
 * Rows pinned (release record, `docs/RELEASE_RECORD.md`):
 *
 * | version | note |
 * |---|---|
 * | v1.1.95 | "Downloadable language packs (NL, ID, MS, SW, TL)" |
 * | v1.1.96 | "Fix crash when importing large language packs (Spanish 236k words caused OOM)" |
 * | v1.1.97 | "Downloadable language packs (Dutch, Indonesian, Malay, Swahili, Tagalog)" |
 * | v1.1.97 | "FIXED: OOM crash on large language packs" |
 * | v1.2.6  | "Language packs added — sv ships bundled, el and tr as importable packs" |
 * | v1.2.8  | "Language packs added (re-published from v1.2.6)" |
 *
 * ## Why mock tier and not pure
 *
 * [LanguagePackManager] takes a `Context` (for `filesDir` / `cacheDir` / `contentResolver`),
 * logs through `android.util.Log`, and parses the manifest with `org.json.JSONObject`. Under
 * `runMockTests` the android.jar stubs supply the types, MockK supplies the behaviour, and the
 * REAL `org.json` (a `testImplementation` dependency, ahead of android.jar on the classpath —
 * see the comment at `build.gradle:108`) supplies a working JSON parser. Nothing here is
 * re-implemented: every assertion is about a value the production class produced.
 *
 * ## What "no OOM on a large pack" means TODAY
 *
 * The v1.1.96 note bundled two fixes. The second — "limited secondary dictionary trie
 * insertions to top 30k most frequent words" — lived in the neural beam search and went away
 * with it (ADR-011, 2026-08-18). What survives, and what a user importing a 236k-word pack
 * still depends on, is that **the import path never materialises the dictionary in memory**:
 * the ZIP entry is streamed to disk with `copyTo` and validation reads an 8-byte header.
 * [aPackFarLargerThanAnyBufferImportsByStreaming] proves that with a dictionary an order of
 * magnitude bigger than the Spanish one that originally crashed, and
 * [theImportPathNeverReadsAWholeEntryIntoMemory] is the anti-regression guard: a refactor to
 * `outFile.writeBytes(zis.readBytes())` would restore the crash while every behavioural test
 * above stayed green, so the shape of the copy is pinned at the source.
 */
class LanguagePackImportTest {

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var scratch: File
    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var manager: LanguagePackManager

    /** V2 `CKDT` magic + version, little-endian, exactly as `validateDictionary` decodes it. */
    private val ckdtV2Header = byteArrayOf(
        0x43, 0x4B, 0x44, 0x54, // "CKDT"
        0x02, 0x00, 0x00, 0x00, // version 2
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        scratch = Files.createTempDirectory("ck-langpack").toFile()
        filesDir = File(scratch, "files").apply { mkdirs() }
        cacheDir = File(scratch, "cache").apply { mkdirs() }

        resolver = mockk()
        context = mockk()
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.contentResolver } returns resolver
        every { context.applicationContext } returns context

        manager = LanguagePackManager(context)
    }

    @After
    fun teardown() {
        unmockkAll()
        scratch.deleteRecursively()
    }

    // ------------------------------------------------------------------ fixtures

    /** A manifest JSON with the fields [LanguagePackManager.parseManifest] reads. */
    private fun manifestJson(
        code: String,
        name: String,
        version: Int = 1,
        author: String = "",
        wordCount: Int = 0,
        hasPrefixBoost: Boolean = false,
    ): String = """
        {"code":"$code","name":"$name","version":$version,"author":"$author",
         "wordCount":$wordCount,"hasPrefixBoost":$hasPrefixBoost}
    """.trimIndent()

    /** A V2-valid dictionary body of [size] bytes: real header, deterministic filler. */
    private fun dictionaryBytes(size: Int = 64): ByteArray {
        require(size >= ckdtV2Header.size)
        val out = ByteArray(size)
        ckdtV2Header.copyInto(out)
        for (i in ckdtV2Header.size until size) out[i] = (i % 251).toByte()
        return out
    }

    /** Build a pack ZIP on disk from `entry name -> bytes`. */
    private fun packZip(fileName: String, entries: List<Pair<String, ByteArray>>): File {
        val zip = File(scratch, fileName)
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return zip
    }

    /** A complete, valid pack: manifest + dictionary (+ optional extras). */
    private fun validPack(
        code: String,
        name: String,
        wordCount: Int = 0,
        dictionarySize: Int = 64,
        extras: List<Pair<String, ByteArray>> = emptyList(),
    ): File = packZip(
        "$code.zip",
        listOf(
            "manifest.json" to manifestJson(code, name, wordCount = wordCount).toByteArray(),
            "dictionary.bin" to dictionaryBytes(dictionarySize),
        ) + extras
    )

    /** Run the production entry point against [zip]. */
    private fun import(zip: File): ImportResult {
        val uri = mockk<Uri>()
        every { resolver.openInputStream(uri) } returns zip.inputStream()
        return manager.importLanguagePack(uri)
    }

    private fun installedDir(code: String) = File(File(filesDir, "langpacks"), code)

    // ---------------------------------------------- v1.1.95 / v1.1.97: the five packs

    /**
     * The exact five language codes both notes named. A pack is imported per code and then
     * asked for back through the public surface — install location, `isInstalled`,
     * `getDictionaryPath` and `getInstalledPacks` all have to agree.
     */
    @Test
    fun theFiveAnnouncedPacksImportAndAreThenInstalled() {
        val announced = listOf(
            "nl" to "Dutch",
            "id" to "Indonesian",
            "ms" to "Malay",
            "sw" to "Swahili",
            "tl" to "Tagalog",
        )

        for ((code, name) in announced) {
            val result = import(validPack(code, name, wordCount = 1234))
            assertWithMessage("importing the $name pack").that(result)
                .isEqualTo(ImportResult.Success(LanguagePackManifest(code, name, 1, "", 1234, false)))

            assertWithMessage("$code must land in files/langpacks/$code/dictionary.bin")
                .that(File(installedDir(code), "dictionary.bin").exists()).isTrue()
            assertWithMessage("$code must report installed").that(manager.isInstalled(code)).isTrue()
            assertThat(manager.getDictionaryPath(code)?.absolutePath)
                .isEqualTo(File(installedDir(code), "dictionary.bin").absolutePath)
        }

        // getInstalledPacks() enumerates every one of them, sorted by DISPLAY NAME (not code,
        // not filesystem order — `listFiles()` order is undefined and must not leak through).
        assertThat(manager.getInstalledPacks().map { it.name })
            .containsExactly("Dutch", "Indonesian", "Malay", "Swahili", "Tagalog").inOrder()
        assertThat(manager.getInstalledPacks().map { it.code })
            .containsExactly("nl", "id", "ms", "sw", "tl").inOrder()
    }

    /**
     * A language NOT in the app's bundled `CtcLanguageSupport.SUPPORTED` table still imports —
     * that is precisely what "importable pack" means in the v1.2.6 note for `el` and `tr`.
     */
    @Test
    fun greekAndTurkishImportAsPacksEvenThoughOnlySvIsBundled() {
        assertThat(import(validPack("el", "Greek", wordCount = 90_000)))
            .isEqualTo(ImportResult.Success(LanguagePackManifest("el", "Greek", 1, "", 90_000, false)))
        assertThat(import(validPack("tr", "Turkish", wordCount = 120_000)))
            .isEqualTo(ImportResult.Success(LanguagePackManifest("tr", "Turkish", 1, "", 120_000, false)))

        assertThat(manager.getInstalledPacks().map { it.code }).containsExactly("el", "tr")

        // `sv` is the bundled half of the same note: it ships as an asset, so it must NOT need
        // an import to exist. Asserted against the shipped file, not against a mock.
        val bundledSwedish = File("src/main/assets/dictionaries/sv_enhanced.bin")
        assertWithMessage(
            "sv is announced as BUNDLED — ${bundledSwedish.path} must ship in the APK assets " +
                "(run from project root)"
        ).that(bundledSwedish.isFile).isTrue()
        assertWithMessage("the bundled Swedish dictionary must be the same V2 CKDT format the " +
            "importer validates")
            .that(bundledSwedish.inputStream().use { it.readNBytes(8) }.toList())
            .isEqualTo(ckdtV2Header.toList())
    }

    /**
     * The REAL shipped Swedish dictionary, wrapped as a pack, passes the importer's own
     * validation. Proves the validator and the dictionary builder still agree on the format —
     * a drift there would reject every pack built by `scripts/build_wordlist.py`.
     */
    @Test
    fun theShippedSwedishDictionaryValidatesAsAPackDictionary() {
        val bundled = File("src/main/assets/dictionaries/sv_enhanced.bin")
        assertWithMessage("expected ${bundled.path} (run from project root)")
            .that(bundled.isFile).isTrue()

        val zip = packZip(
            "sv-real.zip",
            listOf(
                "manifest.json" to manifestJson("sv", "Swedish", wordCount = 40_000).toByteArray(),
                "dictionary.bin" to bundled.readBytes(),
            )
        )

        assertThat(import(zip))
            .isEqualTo(ImportResult.Success(LanguagePackManifest("sv", "Swedish", 1, "", 40_000, false)))
        assertWithMessage("the installed copy must be byte-identical to the shipped dictionary")
            .that(File(installedDir("sv"), "dictionary.bin").length())
            .isEqualTo(bundled.length())
    }

    // ------------------------------------------------- optional pack members are carried

    @Test
    fun unigramsContractionsAndPrefixBoostAreCopiedWhenPresent() {
        val zip = packZip(
            "nl-full.zip",
            listOf(
                "manifest.json" to manifestJson("nl", "Dutch", wordCount = 7).toByteArray(),
                "dictionary.bin" to dictionaryBytes(),
                "unigrams.txt" to "de 100\nhet 90\n".toByteArray(),
                "contractions.json" to """{"cest":"c'est"}""".toByteArray(),
                "prefix_boost.bin" to ByteArray(512) { 7 },
            )
        )

        assertThat(import(zip)).isInstanceOf(ImportResult.Success::class.java)

        assertThat(manager.getUnigramsPath("nl")?.readText()).isEqualTo("de 100\nhet 90\n")
        assertThat(manager.getContractionsPath("nl")?.readText()).isEqualTo("""{"cest":"c'est"}""")
        // prefix_boost.bin has had no consumer since ADR-011, but is still ACCEPTED and copied
        // so packs built against the old format keep installing. Pinning the length proves the
        // copy is real, not a create-empty.
        assertThat(manager.getPrefixBoostPath("nl")?.length()).isEqualTo(512L)
    }

    @Test
    fun aPackWithoutOptionalMembersInstallsAndReportsThemAbsent() {
        assertThat(import(validPack("ms", "Malay"))).isInstanceOf(ImportResult.Success::class.java)

        assertThat(manager.getUnigramsPath("ms")).isNull()
        assertThat(manager.getContractionsPath("ms")).isNull()
        assertThat(manager.getPrefixBoostPath("ms")).isNull()
        assertThat(manager.isInstalled("ms")).isTrue()
    }

    // ------------------------------------------------------------- rejection surface

    @Test
    fun aPackMissingItsManifestIsRejectedWithThatReason() {
        val zip = packZip("bad.zip", listOf("dictionary.bin" to dictionaryBytes()))
        assertThat(import(zip)).isEqualTo(ImportResult.Error("Missing manifest.json"))
        assertWithMessage("a rejected pack must install nothing")
            .that(File(filesDir, "langpacks").listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun aPackMissingItsDictionaryIsRejectedWithThatReason() {
        val zip = packZip(
            "bad.zip",
            listOf("manifest.json" to manifestJson("nl", "Dutch").toByteArray())
        )
        assertThat(import(zip)).isEqualTo(ImportResult.Error("Missing dictionary.bin"))
    }

    @Test
    fun aManifestWithoutTheRequiredFieldsIsRejected() {
        val zip = packZip(
            "bad.zip",
            listOf(
                "manifest.json" to """{"name":"Nameless"}""".toByteArray(), // no "code"
                "dictionary.bin" to dictionaryBytes(),
            )
        )
        assertThat(import(zip)).isEqualTo(ImportResult.Error("Invalid manifest.json format"))
    }

    @Test
    fun aDictionaryWithTheWrongMagicOrVersionIsRejected() {
        val wrongMagic = dictionaryBytes().also { it[0] = 'X'.code.toByte() }
        assertThat(
            import(packZip("m.zip", listOf(
                "manifest.json" to manifestJson("nl", "Dutch").toByteArray(),
                "dictionary.bin" to wrongMagic,
            )))
        ).isEqualTo(ImportResult.Error("Invalid dictionary.bin format"))

        val v1 = dictionaryBytes().also { it[4] = 1 }
        assertWithMessage("the pre-V2 'DICT'-era layout must not be accepted").that(
            import(packZip("v.zip", listOf(
                "manifest.json" to manifestJson("nl", "Dutch").toByteArray(),
                "dictionary.bin" to v1,
            )))
        ).isEqualTo(ImportResult.Error("Invalid dictionary.bin format"))
    }

    @Test
    fun aTruncatedDictionaryIsRejected() {
        // `validateDictionary` requires at least 48 bytes: a 40-byte header-only file is not a
        // lexicon and must not be installed as one.
        assertThat(
            import(packZip("t.zip", listOf(
                "manifest.json" to manifestJson("nl", "Dutch").toByteArray(),
                "dictionary.bin" to dictionaryBytes(40),
            )))
        ).isEqualTo(ImportResult.Error("Invalid dictionary.bin format"))
    }

    @Test
    fun anUnreadableUriIsRejectedWithoutThrowing() {
        val uri = mockk<Uri>()
        every { resolver.openInputStream(uri) } returns null
        assertThat(manager.importLanguagePack(uri)).isEqualTo(ImportResult.Error("Cannot open file"))
    }

    /**
     * A ZIP entry naming a traversal path must not escape the install directory: the importer
     * keeps only `File(entry.name).name`.
     */
    @Test
    fun aTraversalEntryNameCannotEscapeTheTempDirectory() {
        val zip = packZip(
            "evil.zip",
            listOf(
                "../../manifest.json" to manifestJson("nl", "Dutch").toByteArray(),
                "../../../dictionary.bin" to dictionaryBytes(),
                "../../../../pwned.txt" to "owned".toByteArray(),
            )
        )

        assertWithMessage("the basenames still satisfy the required-file check, so the pack imports")
            .that(import(zip)).isInstanceOf(ImportResult.Success::class.java)
        assertWithMessage("nothing may be written above the app's cache/files roots")
            .that(File(scratch.parentFile, "pwned.txt").exists()).isFalse()
        assertThat(File(scratch, "pwned.txt").exists()).isFalse()
    }

    // ------------------------------------------------------- re-import and enumeration

    @Test
    fun reimportingAPackReplacesItRatherThanMerging() {
        import(packZip("v1.zip", listOf(
            "manifest.json" to manifestJson("nl", "Dutch", version = 1, wordCount = 10).toByteArray(),
            "dictionary.bin" to dictionaryBytes(),
            "unigrams.txt" to "old\n".toByteArray(),
        )))
        assertThat(manager.getUnigramsPath("nl")?.readText()).isEqualTo("old\n")

        // v2 of the same pack ships WITHOUT unigrams — the stale file must not survive.
        import(packZip("v2.zip", listOf(
            "manifest.json" to manifestJson("nl", "Dutch", version = 2, wordCount = 20).toByteArray(),
            "dictionary.bin" to dictionaryBytes(),
        )))

        assertThat(manager.getInstalledPacks())
            .containsExactly(LanguagePackManifest("nl", "Dutch", 2, "", 20, false))
        assertWithMessage("the previous version's unigrams must be gone, not merged")
            .that(manager.getUnigramsPath("nl")).isNull()
    }

    @Test
    fun deletingAPackRemovesItFromEveryQuery() {
        import(validPack("sw", "Swahili"))
        assertThat(manager.isInstalled("sw")).isTrue()

        assertThat(manager.deletePack("sw")).isTrue()

        assertThat(manager.isInstalled("sw")).isFalse()
        assertThat(manager.getDictionaryPath("sw")).isNull()
        assertThat(manager.getInstalledPacks()).isEmpty()
        assertWithMessage("deleting a pack that was never installed reports false, not a crash")
            .that(manager.deletePack("sw")).isFalse()
    }

    @Test
    fun availableLanguagesAreTheBundledPairPlusEveryInstalledPackSortedByName() {
        import(validPack("nl", "Dutch"))
        import(validPack("tl", "Tagalog"))

        assertThat(manager.getAllAvailableLanguages()).containsExactly(
            LanguageInfo("nl", "Dutch", LanguageSource.PACK),
            LanguageInfo("en", "English", LanguageSource.BUNDLED),
            LanguageInfo("es", "Spanish", LanguageSource.BUNDLED),
            LanguageInfo("tl", "Tagalog", LanguageSource.PACK),
        ).inOrder()
    }

    @Test
    fun aPackThatShadowsABundledLanguageDoesNotDuplicateTheEntry() {
        import(validPack("es", "Spanish"))

        assertWithMessage("es is bundled; an imported es pack must not produce two Spanish rows")
            .that(manager.getAllAvailableLanguages().count { it.code == "es" }).isEqualTo(1)
        assertThat(manager.getAllAvailableLanguages().single { it.code == "es" }.source)
            .isEqualTo(LanguageSource.BUNDLED)
    }

    // --------------------------------------------------------- v1.1.96 / v1.1.97: OOM

    /**
     * 12 MB of dictionary — roughly ten times the 236k-word Spanish pack that originally
     * crashed the importer — imports and arrives byte-exact.
     *
     * The ZIP is deflate-compressed to a few KB, so the only thing this can exercise is the
     * DECOMPRESSED path: if any step buffered the whole entry, the peak would be the full 12 MB
     * rather than `copyTo`'s 8 KB window.
     */
    @Test
    fun aPackFarLargerThanAnyBufferImportsByStreaming() {
        val bigSize = 12 * 1024 * 1024
        val zip = packZip(
            "big.zip",
            listOf(
                "manifest.json" to manifestJson("es", "Spanish", wordCount = 236_000).toByteArray(),
                "dictionary.bin" to dictionaryBytes(bigSize),
            )
        )

        assertThat(import(zip)).isEqualTo(
            ImportResult.Success(LanguagePackManifest("es", "Spanish", 1, "", 236_000, false))
        )

        val installed = File(installedDir("es"), "dictionary.bin")
        assertWithMessage("every byte of the pack must reach disk")
            .that(installed.length()).isEqualTo(bigSize.toLong())

        // Header intact and an interior byte at 11 MB matches the generator — proves the copy
        // is complete and in order, not truncated at some buffer boundary.
        RandomAccessFile(installed, "r").use { raf ->
            val head = ByteArray(8)
            raf.readFully(head)
            assertThat(head.toList()).isEqualTo(ckdtV2Header.toList())

            val probe = 11 * 1024 * 1024
            raf.seek(probe.toLong())
            assertThat(raf.readByte()).isEqualTo((probe % 251).toByte())
        }

        assertWithMessage("the extraction scratch directory must be cleaned up afterwards")
            .that(cacheDir.listFiles()?.toList().orEmpty()).isEmpty()
    }

    /**
     * Anti-regression guard for the OOM fix itself.
     *
     * The behavioural test above passes on a machine with enough heap even if the importer
     * slurped the entry — the crash it guards is memory pressure on a phone, which no JVM test
     * can reproduce faithfully. What CAN be pinned is the shape that makes the crash
     * impossible: a streaming `copyTo` and no whole-entry read anywhere in the import path.
     */
    @Test
    fun theImportPathNeverReadsAWholeEntryIntoMemory() {
        val source = File("src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt")
        assertWithMessage("expected ${source.path} (run from project root)")
            .that(source.isFile).isTrue()
        val text = source.readText()

        val body = text.substringAfter("private fun importFromStream(")
            .substringBefore("private fun parseManifest(")
        assertWithMessage("importFromStream was renamed or moved — re-point this guard")
            .that(body).isNotEmpty()

        assertWithMessage(
            "the ZIP entry must be streamed to disk. `zis.copyTo(fos)` is what keeps peak " +
                "memory at one 8KB buffer regardless of pack size — the whole point of the " +
                "v1.1.96 / v1.1.97 OOM fix."
        ).that(body).contains("zis.copyTo(fos)")

        for (slurp in listOf("readBytes()", "readAllBytes()", "zis.readText()")) {
            assertWithMessage(
                "found `$slurp` in importFromStream — that materialises an entire pack entry " +
                    "in memory and reintroduces the OOM on large packs (Spanish, 236k words)."
            ).that(body).doesNotContain(slurp)
        }

        // The validator only ever needs the header; reading more would scale with pack size.
        val validator = text.substringAfter("private fun validateDictionary(")
            .substringBefore("fun getInstalledPacks(")
        assertWithMessage("validateDictionary must read a fixed-size header, not the file")
            .that(validator).contains("ByteArray(8)")
        assertThat(validator).doesNotContain("readBytes()")
    }
}
