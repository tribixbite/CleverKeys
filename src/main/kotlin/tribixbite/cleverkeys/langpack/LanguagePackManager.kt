package tribixbite.cleverkeys.langpack

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import tribixbite.cleverkeys.pinyin.CkpyPhraseTable
import tribixbite.cleverkeys.swipe.ctc.CtcPackModel

/**
 * Language Pack Manager - handles import, validation, and storage of language packs.
 *
 * Language packs are ZIP files containing:
 * - manifest.json: metadata (language code, name, version, inputMethod, hasPrefixBoost)
 * - dictionary.bin: V2 binary dictionary with accent normalization
 * - phrases.bin: optional V1 `CKPY` pinyin phrase table (pinyin key -> ranked 汉字), required
 *   exactly when the manifest declares `"inputMethod": "pinyin"` (see "Composing IME packs")
 * - unigrams.txt: word frequency list for language detection
 * - contractions.json: optional apostrophe word mappings (e.g., "cest" -> "c'est")
 * - prefix_boost.bin: optional Aho-Corasick trie for prefix boosting. Its only consumer,
 *   the neural beam search, was removed on 2026-08-18. The file is still ACCEPTED and
 *   copied on import so existing packs keep installing cleanly; nothing reads it back.
 * - model.onnx: optional CTC swipe encoder for a non-Latin script (added 2026-09-10). Accepted
 *   only when the manifest DECLARES it — `"model": {"file": "model.onnx", "sha256": "…"}` —
 *   and the bytes hash to what the manifest says. See "The model member" below.
 *
 * Packs are imported via Storage Access Framework (no internet permission needed).
 * Stored in app internal storage: files/langpacks/{code}/
 *
 * ## The model member, and the two checks that are NOT the same check
 *
 * The six per-script CTC encoders (ru/el/uk/bg/mk/he) left the APK on 2026-09-10 and travel in
 * their packs instead: every one of those languages is langpack-sourced, so the model could
 * only ever run for a user who had imported the pack anyway, and shipping it to everyone else
 * was 3.1 MB of dead payload. Two independent gates stand between a pack file and ORT:
 *
 *  1. **Here, at import** — does the pack match its OWN manifest? That catches a corrupt or
 *     truncated download and reports it as a failed import, rather than installing bytes that
 *     will silently never load. It is an integrity check, and nothing more: the manifest is
 *     written by whoever wrote the pack, so a hostile pack passes this trivially.
 *  2. **At load** — [tribixbite.cleverkeys.swipe.ctc.CtcPackModel] refuses to hand ORT anything
 *     that is not byte-identical to a sha256 compiled into this APK. That is the security
 *     property, and it trusts nothing the pack says about itself.
 *
 * Hence the size cap enforced during EXTRACTION (a pack naming a multi-gigabyte `model.onnx`
 * must not fill the cache dir before anything looks at its size), and hence an UNDECLARED
 * `model.onnx` being dropped rather than rejected: nothing can verify it, gate 2 would refuse
 * it anyway, and failing the whole import would punish the user for a stray file.
 *
 * ## Composing IME packs (`inputMethod`)
 *
 * A normal pack's dictionary entries ARE the text the keyboard commits. A composing IME
 * (pinyin today) breaks that assumption: the user types keys and chooses 汉字, so the pack
 * declares how its content is consumed. The manifest's optional `inputMethod` field carries
 * that declaration:
 *
 *  - absent / `"wordfreq"` — the legacy contract. The bundled dictionaries and every pack
 *    built before this field existed parse exactly as before.
 *  - `"pinyin"` — a composing pack. It MUST carry `phrases.bin`, a V1 `CKPY` table mapping a
 *    toneless pinyin key (`"nihao"`) to ranked candidate text (`你好` / 妳好). The reader is
 *    [CkpyPhraseTable] and the format is specified in `docs/specs/pinyin-ime.md`. The pack's
 *    `dictionary.bin` still ships (the swipe path decodes pinyin spellings over it), but the
 *    keyboard's committed text comes from the phrase table, never from the key letters.
 *
 * Unknown `inputMethod` values are REFUSED rather than ignored: a pack built for a future
 * mode must fail with a reason on an app that cannot honour the mode, not silently degrade
 * into a wordfreq lexicon whose "words" are pinyin spellings. A `phrases.bin` present without
 * `"inputMethod": "pinyin"` is refused for the same reason (nothing would read it, and a
 * silent drop would turn a typo in the manifest into a broken keyboard).
 */
class LanguagePackManager(private val context: Context) {

    companion object {
        private const val TAG = "LanguagePackManager"
        private const val LANGPACKS_DIR = "langpacks"
        private const val MANIFEST_FILE = "manifest.json"
        private const val DICTIONARY_FILE = "dictionary.bin"
        private const val PHRASES_FILE = "phrases.bin"
        private const val UNIGRAMS_FILE = "unigrams.txt"
        private const val CONTRACTIONS_FILE = "contractions.json"
        private const val PREFIX_BOOST_FILE = "prefix_boost.bin"

        /**
         * `inputMethod` value for the legacy contract: dictionary entries are the committed
         * words. Every pack without the field imports as this, so old packs are untouched.
         */
        const val INPUT_METHOD_WORDFREQ = "wordfreq"

        /**
         * `inputMethod` value for a composing pinyin pack. Requires a `phrases.bin` `CKPY`
         * table; see the class KDoc ("Composing IME packs").
         */
        const val INPUT_METHOD_PINYIN = "pinyin"

        /** Every `inputMethod` this app can honour; anything else is an import error. */
        val SUPPORTED_INPUT_METHODS = setOf(INPUT_METHOD_WORDFREQ, INPUT_METHOD_PINYIN)

        /**
         * The pack's optional CTC encoder. Name and size cap come from [CtcPackModel] so the
         * importer and the loader can never disagree about which file this is or how big it is
         * allowed to be.
         */
        private val MODEL_FILE = CtcPackModel.PACK_MODEL_FILE
        private val MAX_MODEL_BYTES = CtcPackModel.MAX_PACK_MODEL_BYTES

        // V2 dictionary magic number: "CKDT"
        private const val DICT_MAGIC = 0x54444B43

        /**
         * G-1 (comprehensive audit 2026-09-06): `manifest.code` becomes a path component
         * of the install directory, so it MUST be a plain code — a hostile pack carrying
         * `"code":".."` used to make the importer `deleteRecursively()` the app's entire
         * files dir and install there. Accepted shapes cover every shipped pack
         * (`en`, `ru`, `pt` …) and the build tooling's variant names (`en-norvig-50k`,
         * `en-opensubtitles`, `pt_br`): a 2-3 letter base plus up to 4 alphanumeric
         * segments separated by `-`/`_`. No `/`, `\`, `.` or empty codes, ever.
         */
        private val VALID_PACK_CODE = Regex("^[a-z]{2,3}(?:[_-][a-z0-9]{1,16}){0,4}$")

        // Process-lifetime singleton holding only the applicationContext (see getInstance),
        // so it never leaks an Activity/Service. The reference lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LanguagePackManager? = null

        fun getInstance(context: Context): LanguagePackManager {
            return instance ?: synchronized(this) {
                instance ?: LanguagePackManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val langpacksDir: File by lazy {
        File(context.filesDir, LANGPACKS_DIR).apply { mkdirs() }
    }

    /**
     * Import a language pack from a ZIP file URI.
     *
     * @param uri URI to the ZIP file (from file picker)
     * @return ImportResult indicating success or failure with details
     */
    fun importLanguagePack(uri: Uri): ImportResult {
        Log.d(TAG, "Importing language pack from: $uri")

        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult.Error("Cannot open file")

            importFromStream(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            ImportResult.Error("Import failed: ${e.message}")
        }
    }

    /**
     * Import from an InputStream (ZIP content).
     */
    private fun importFromStream(inputStream: InputStream): ImportResult {
        val tempDir = File(context.cacheDir, "langpack_import_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // Extract ZIP contents to temp directory
            val extractedFiles = mutableSetOf<String>()
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val fileName = File(entry.name).name // Strip path for security
                        val outFile = File(tempDir, fileName)
                        if (fileName == MODEL_FILE) {
                            // Bounded, because the cap has to abort the EXTRACTION: a hash check
                            // can only reject bytes that already exist, so an unbounded copy
                            // would let a pack naming a multi-gigabyte model.onnx fill the cache
                            // dir on its way to being refused.
                            val withinCap = FileOutputStream(outFile).use { fos ->
                                copyBounded(zis, fos, MAX_MODEL_BYTES)
                            }
                            if (!withinCap) {
                                Log.w(TAG, "Rejecting pack: $MODEL_FILE exceeds the size cap")
                                return ImportResult.Error(
                                    "$MODEL_FILE exceeds the ${MAX_MODEL_BYTES / (1024 * 1024)} MiB limit"
                                )
                            }
                        } else {
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        extractedFiles.add(fileName)
                    }
                    entry = zis.nextEntry
                }
            }

            // Validate required files exist
            if (MANIFEST_FILE !in extractedFiles) {
                return ImportResult.Error("Missing manifest.json")
            }
            if (DICTIONARY_FILE !in extractedFiles) {
                return ImportResult.Error("Missing dictionary.bin")
            }

            // Parse manifest
            val manifestFile = File(tempDir, MANIFEST_FILE)
            val manifest = parseManifest(manifestFile.readText())
                ?: return ImportResult.Error("Invalid manifest.json format")

            // The pack's declared input method decides what else must be present. Unknown
            // values are refused (see "Composing IME packs") rather than treated as wordfreq.
            if (manifest.inputMethod !in SUPPORTED_INPUT_METHODS) {
                return ImportResult.Error("Unsupported inputMethod \"${manifest.inputMethod}\"")
            }
            val phrasesFile = File(tempDir, PHRASES_FILE)
            if (manifest.inputMethod == INPUT_METHOD_PINYIN) {
                if (PHRASES_FILE !in extractedFiles) {
                    return ImportResult.Error(
                        "Missing $PHRASES_FILE for inputMethod \"$INPUT_METHOD_PINYIN\""
                    )
                }
                if (!validatePhraseTable(phrasesFile)) {
                    return ImportResult.Error("Invalid $PHRASES_FILE format")
                }
            } else if (phrasesFile.exists()) {
                return ImportResult.Error(
                    "$PHRASES_FILE requires inputMethod \"$INPUT_METHOD_PINYIN\""
                )
            }

            // Validate dictionary binary
            val dictFile = File(tempDir, DICTIONARY_FILE)
            if (!validateDictionary(dictFile)) {
                return ImportResult.Error("Invalid dictionary.bin format")
            }

            // G-1: validate the code BEFORE it is used as a path component. Regex first,
            // then a canonical-path containment check as belt-and-braces — the install
            // dir must be a direct child of langpacksDir, nothing else.
            if (!VALID_PACK_CODE.matches(manifest.code)) {
                return ImportResult.Error("Invalid language code in manifest: \"${manifest.code}\"")
            }

            // Move to final location
            val packDir = File(langpacksDir, manifest.code)
            if (packDir.canonicalFile.parentFile != langpacksDir.canonicalFile) {
                return ImportResult.Error("Invalid language code in manifest: \"${manifest.code}\"")
            }

            // The model member: the pack must agree with itself. A DECLARED model that is
            // missing, misnamed or hashes to something else means a corrupt download, and
            // saying so beats installing a language whose swipe silently falls back forever.
            // An UNDECLARED model.onnx is simply not installed (see the class KDoc).
            val modelFile = File(tempDir, MODEL_FILE)
            val installModel = manifest.modelSha256 != null
            if (installModel) {
                if (manifest.modelFile != MODEL_FILE) {
                    return ImportResult.Error(
                        "Unsupported model file in manifest: \"${manifest.modelFile}\""
                    )
                }
                if (!modelFile.exists()) {
                    return ImportResult.Error("Missing $MODEL_FILE declared by manifest")
                }
                if (!sha256OfFile(modelFile).equals(manifest.modelSha256, ignoreCase = true)) {
                    return ImportResult.Error("$MODEL_FILE does not match its manifest sha256")
                }
            } else if (modelFile.exists()) {
                Log.w(TAG, "Ignoring undeclared $MODEL_FILE in pack ${manifest.code}")
            }

            // G-6 (comprehensive audit 2026-09-06): stage into a sibling dir and swap.
            // The old order (deleteRecursively the installed pack, THEN copy) meant a
            // mid-copy IO failure (disk full) destroyed the working pack and left a
            // manifest-only ghost that getInstalledPacks() listed while every dictionary
            // consumer treated it as absent. Staging first means a failure anywhere in
            // the copy phase leaves the installed pack untouched. The dot-prefixed name
            // keeps a half-built staging dir invisible to getInstalledPacks(). Within
            // the staging dir the manifest is copied LAST, so even a crash between
            // steps can never produce a manifest-without-dictionary directory.
            val stagingDir = File(langpacksDir, ".staging-${manifest.code}-${System.currentTimeMillis()}")
            stagingDir.mkdirs()
            try {
                dictFile.copyTo(File(stagingDir, DICTIONARY_FILE), overwrite = true)

                // Copy the pinyin phrase table for a composing pack (validated above).
                if (manifest.inputMethod == INPUT_METHOD_PINYIN) {
                    phrasesFile.copyTo(File(stagingDir, PHRASES_FILE), overwrite = true)
                    Log.d(TAG, "Copied $PHRASES_FILE for ${manifest.code} (${phrasesFile.length() / 1024}KB)")
                }

                // Copy unigrams if present
                val unigramsFile = File(tempDir, UNIGRAMS_FILE)
                if (unigramsFile.exists()) {
                    unigramsFile.copyTo(File(stagingDir, UNIGRAMS_FILE), overwrite = true)
                }

                // Copy contractions if present
                val contractionsFile = File(tempDir, CONTRACTIONS_FILE)
                if (contractionsFile.exists()) {
                    contractionsFile.copyTo(File(stagingDir, CONTRACTIONS_FILE), overwrite = true)
                    Log.d(TAG, "Copied contractions.json for ${manifest.code}")
                }

                // Copy prefix boost trie if present
                val prefixBoostFile = File(tempDir, PREFIX_BOOST_FILE)
                if (prefixBoostFile.exists()) {
                    prefixBoostFile.copyTo(File(stagingDir, PREFIX_BOOST_FILE), overwrite = true)
                    Log.d(TAG, "Copied prefix_boost.bin for ${manifest.code} (${prefixBoostFile.length() / 1024}KB)")
                }

                // Copy the CTC encoder if the manifest declared it and it verified above.
                if (installModel) {
                    modelFile.copyTo(File(stagingDir, MODEL_FILE), overwrite = true)
                    Log.d(TAG, "Copied $MODEL_FILE for ${manifest.code} (${modelFile.length() / 1024}KB)")
                }

                // Manifest last — a staged dir only becomes "complete" at this point.
                manifestFile.copyTo(File(stagingDir, MANIFEST_FILE), overwrite = true)

                // Swap: the old pack is deleted only once the replacement is fully staged
                // on the same filesystem, so the unprotected window is a single rename.
                if (packDir.exists()) {
                    packDir.deleteRecursively()
                }
                if (!stagingDir.renameTo(packDir)) {
                    return ImportResult.Error("Failed to install language pack (rename failed)")
                }
            } finally {
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
            }

            Log.i(TAG, "Successfully imported language pack: ${manifest.name} (${manifest.code})")
            return ImportResult.Success(manifest)

        } finally {
            // Cleanup temp directory
            tempDir.deleteRecursively()
        }
    }

    /**
     * Parse manifest.json into LanguagePackManifest.
     */
    private fun parseManifest(json: String): LanguagePackManifest? {
        return try {
            val obj = JSONObject(json)
            // `model` is absent from every pack built before 2026-09-10, and from every pack for
            // a Latin language — optJSONObject keeps those parsing exactly as they did.
            val model = obj.optJSONObject("model")
            LanguagePackManifest(
                code = obj.getString("code"),
                name = obj.getString("name"),
                version = obj.optInt("version", 1),
                author = obj.optString("author", ""),
                wordCount = obj.optInt("wordCount", 0),
                hasPrefixBoost = obj.optBoolean("hasPrefixBoost", false),
                modelFile = model?.optString("file")?.takeIf { it.isNotEmpty() },
                modelSha256 = model?.optString("sha256")?.takeIf { it.isNotEmpty() },
                // Absent in every pack built before the field existed — those must import
                // exactly as they always did, i.e. as wordfreq packs.
                inputMethod = obj.optString("inputMethod", INPUT_METHOD_WORDFREQ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse manifest", e)
            null
        }
    }

    /**
     * Copy at most [limit] bytes from [input] to [output].
     *
     * @return true when the whole stream fit, false the moment it would exceed [limit] (the
     *   output then holds a truncated prefix, which the caller discards with the temp dir).
     *
     * Streamed in fixed-size chunks for the same reason every other entry is: peak memory must
     * not scale with what the pack claims to contain.
     */
    private fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) return true
            written += read
            if (written > limit) return false
            output.write(buffer, 0, read)
        }
    }

    /**
     * Lowercase hex sha256 of [file], read in fixed-size chunks.
     *
     * Streaming rather than `readBytes()` keeps the importer's one invariant intact — no pack
     * entry is ever materialised whole, whatever its declared size (the v1.1.96/v1.1.97 OOM
     * fix, pinned by `theImportPathNeverReadsAWholeEntryIntoMemory`).
     */
    private fun sha256OfFile(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Validate dictionary binary has correct magic number and version.
     */
    private fun validateDictionary(file: File): Boolean {
        if (!file.exists() || file.length() < 48) {
            return false
        }

        return try {
            file.inputStream().use { fis ->
                val header = ByteArray(8)
                if (fis.read(header) != 8) return false

                // Check magic (little-endian)
                val magic = (header[0].toInt() and 0xFF) or
                           ((header[1].toInt() and 0xFF) shl 8) or
                           ((header[2].toInt() and 0xFF) shl 16) or
                           ((header[3].toInt() and 0xFF) shl 24)

                // Check version
                val version = (header[4].toInt() and 0xFF) or
                             ((header[5].toInt() and 0xFF) shl 8) or
                             ((header[6].toInt() and 0xFF) shl 16) or
                             ((header[7].toInt() and 0xFF) shl 24)

                magic == DICT_MAGIC && version == 2
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary validation failed", e)
            false
        }
    }

    /**
     * Validate a `phrases.bin` pinyin phrase table: V1 `CKPY` magic + version.
     *
     * The importer checks the fixed header only — exactly like [validateDictionary] — because
     * the pack entry must never be materialised whole (the v1.1.96/v1.1.97 OOM fix). Full
     * structural parsing belongs to [CkpyPhraseTable], which streams with its own bounds.
     */
    private fun validatePhraseTable(file: File): Boolean {
        if (!file.exists() || file.length() < CkpyPhraseTable.HEADER_SIZE) {
            return false
        }

        return try {
            file.inputStream().use { fis ->
                val header = ByteArray(8)
                if (fis.read(header) != 8) return false

                val magic = (header[0].toInt() and 0xFF) or
                           ((header[1].toInt() and 0xFF) shl 8) or
                           ((header[2].toInt() and 0xFF) shl 16) or
                           ((header[3].toInt() and 0xFF) shl 24)

                val version = (header[4].toInt() and 0xFF) or
                             ((header[5].toInt() and 0xFF) shl 8) or
                             ((header[6].toInt() and 0xFF) shl 16) or
                             ((header[7].toInt() and 0xFF) shl 24)

                magic == CkpyPhraseTable.MAGIC && version == CkpyPhraseTable.VERSION
            }
        } catch (e: Exception) {
            Log.e(TAG, "Phrase table validation failed", e)
            false
        }
    }

    /**
     * Get list of installed language packs.
     */
    fun getInstalledPacks(): List<LanguagePackManifest> {
        val packs = mutableListOf<LanguagePackManifest>()

        langpacksDir.listFiles()?.forEach { dir ->
            // Dot-prefixed dirs are in-flight import staging (G-6) — never installed packs.
            if (dir.isDirectory && !dir.name.startsWith(".")) {
                val manifestFile = File(dir, MANIFEST_FILE)
                if (manifestFile.exists()) {
                    parseManifest(manifestFile.readText())?.let { packs.add(it) }
                }
            }
        }

        return packs.sortedBy { it.name }
    }

    /**
     * Get dictionary file path for a language code.
     * Returns null if pack not installed.
     */
    fun getDictionaryPath(code: String): File? {
        val dictFile = File(langpacksDir, "$code/$DICTIONARY_FILE")
        return if (dictFile.exists()) dictFile else null
    }

    /**
     * Path to an installed pinyin pack's `phrases.bin` phrase table, or null when the pack
     * has none (every non-pinyin pack today). The reader is [CkpyPhraseTable.read].
     *
     * Like [getModelPath], this is a storage accessor, not a loader: it says the file is
     * present, not that the app will use it (that decision belongs to the composing engine
     * once one exists — see `docs/specs/pinyin-ime.md`).
     */
    fun getPhrasesPath(code: String): File? {
        val phrasesFile = File(langpacksDir, "$code/$PHRASES_FILE")
        return if (phrasesFile.exists()) phrasesFile else null
    }

    /**
     * Get unigrams file path for a language code.
     * Returns null if not available.
     */
    fun getUnigramsPath(code: String): File? {
        val unigramsFile = File(langpacksDir, "$code/$UNIGRAMS_FILE")
        return if (unigramsFile.exists()) unigramsFile else null
    }

    /**
     * Get contractions file path for a language code.
     * Returns null if not available.
     */
    fun getContractionsPath(code: String): File? {
        val contractionsFile = File(langpacksDir, "$code/$CONTRACTIONS_FILE")
        return if (contractionsFile.exists()) contractionsFile else null
    }

    /**
     * Path to an installed pack's prefix-boost trie, or null if the pack has none.
     *
     * No live consumer since 2026-08-18: the neural beam search that applied these boosts was
     * removed. Kept (with [PREFIX_BOOST_FILE] still copied on import) so that packs built
     * against the old format continue to install without error, and so a future re-use of the
     * data does not require a pack-format change. Marked @Suppress rather than deleted for
     * exactly that reason.
     */
    @Suppress("unused")
    fun getPrefixBoostPath(code: String): File? {
        val prefixBoostFile = File(langpacksDir, "$code/$PREFIX_BOOST_FILE")
        return if (prefixBoostFile.exists()) prefixBoostFile else null
    }

    /**
     * Path to an installed pack's CTC encoder, or null when the pack carries none.
     *
     * Present only for the non-Latin scripts whose model is pack-delivered, and present at ALL
     * only when the pack declared it and the bytes matched that declaration on import. This is
     * the same path [CtcPackModel.packModelFile] resolves, and it is deliberately NOT the
     * loading API: the loader re-hashes the file against the app's own pin, because this
     * class's check only established that the pack agrees with itself.
     */
    fun getModelPath(code: String): File? {
        val modelFile = File(langpacksDir, "$code/$MODEL_FILE")
        return if (modelFile.exists()) modelFile else null
    }

    /**
     * Check if a language pack is installed.
     */
    fun isInstalled(code: String): Boolean {
        return getDictionaryPath(code) != null
    }

    /**
     * Delete a language pack.
     */
    fun deletePack(code: String): Boolean {
        val packDir = File(langpacksDir, code)
        return if (packDir.exists()) {
            packDir.deleteRecursively()
        } else {
            false
        }
    }

    /**
     * Get all available languages (bundled + installed packs).
     */
    fun getAllAvailableLanguages(): List<LanguageInfo> {
        val languages = mutableListOf<LanguageInfo>()

        // Bundled languages (always available)
        languages.add(LanguageInfo("en", "English", LanguageSource.BUNDLED))
        languages.add(LanguageInfo("es", "Spanish", LanguageSource.BUNDLED))

        // Installed language packs
        getInstalledPacks().forEach { pack ->
            // Don't duplicate bundled languages
            if (languages.none { it.code == pack.code }) {
                languages.add(LanguageInfo(pack.code, pack.name, LanguageSource.PACK))
            }
        }

        return languages.sortedBy { it.name }
    }
}

/**
 * Language pack manifest data.
 */
data class LanguagePackManifest(
    val code: String,              // ISO 639-1 code (e.g., "fr", "de")
    val name: String,              // Display name (e.g., "French", "German")
    val version: Int = 1,          // Pack version
    val author: String = "",       // Pack author
    val wordCount: Int = 0,        // Number of words in dictionary
    val hasPrefixBoost: Boolean = false, // Whether pack includes prefix boost trie
    /**
     * `model.file` — the pack member holding the CTC encoder, when the pack declares one.
     * Only `model.onnx` is supported; anything else is refused with its own message rather
     * than silently ignored, so a pack cannot declare a hash for a file nothing reads.
     */
    val modelFile: String? = null,
    /**
     * `model.sha256` — the pack's own statement of its encoder's hash. Checked at import to
     * catch a corrupt download. NOT a permission to load: that decision belongs to the app's
     * pinned hash (`CtcPackModel`), which trusts nothing in this field.
     */
    val modelSha256: String? = null,
    /**
     * How this pack's content is consumed. [LanguagePackManager.INPUT_METHOD_WORDFREQ]
     * (the default, including every pack built before the field existed) means dictionary
     * entries are the committed words; [LanguagePackManager.INPUT_METHOD_PINYIN] means a
     * composing pack whose committed text comes from a `phrases.bin` `CKPY` table.
     */
    val inputMethod: String = LanguagePackManager.INPUT_METHOD_WORDFREQ,
)

/**
 * Import result sealed class.
 */
sealed class ImportResult {
    data class Success(val manifest: LanguagePackManifest) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

/**
 * Language source enum.
 */
enum class LanguageSource {
    BUNDLED,  // Included in app assets
    PACK      // From imported language pack
}

/**
 * Language info for display.
 */
data class LanguageInfo(
    val code: String,
    val name: String,
    val source: LanguageSource
)
