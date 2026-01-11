package tribixbite.cleverkeys.langpack

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Language Pack Manager - handles import, validation, and storage of language packs.
 *
 * Language packs are ZIP files containing:
 * - manifest.json: metadata (language code, name, version, hasPrefixBoost)
 * - dictionary.bin: V2 binary dictionary with accent normalization
 * - unigrams.txt: word frequency list for language detection
 * - contractions.json: optional apostrophe word mappings (e.g., "cest" -> "c'est")
 * - prefix_boost.bin: optional Aho-Corasick trie for prefix boosting (non-English)
 *
 * Packs are imported via Storage Access Framework (no internet permission needed).
 * Stored in app internal storage: files/langpacks/{code}/
 */
class LanguagePackManager(private val context: Context) {

    companion object {
        private const val TAG = "LanguagePackManager"
        private const val LANGPACKS_DIR = "langpacks"
        private const val MANIFEST_FILE = "manifest.json"
        private const val DICTIONARY_FILE = "dictionary.bin"
        private const val UNIGRAMS_FILE = "unigrams.txt"
        private const val CONTRACTIONS_FILE = "contractions.json"
        private const val PREFIX_BOOST_FILE = "prefix_boost.bin"

        // V2 dictionary magic number: "CKDT"
        private const val DICT_MAGIC = 0x54444B43

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
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
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

            // Validate dictionary binary
            val dictFile = File(tempDir, DICTIONARY_FILE)
            if (!validateDictionary(dictFile)) {
                return ImportResult.Error("Invalid dictionary.bin format")
            }

            // Move to final location
            val packDir = File(langpacksDir, manifest.code)
            if (packDir.exists()) {
                packDir.deleteRecursively()
            }
            packDir.mkdirs()

            // Copy files
            manifestFile.copyTo(File(packDir, MANIFEST_FILE), overwrite = true)
            dictFile.copyTo(File(packDir, DICTIONARY_FILE), overwrite = true)

            // Copy unigrams if present
            val unigramsFile = File(tempDir, UNIGRAMS_FILE)
            if (unigramsFile.exists()) {
                unigramsFile.copyTo(File(packDir, UNIGRAMS_FILE), overwrite = true)
            }

            // Copy contractions if present
            val contractionsFile = File(tempDir, CONTRACTIONS_FILE)
            if (contractionsFile.exists()) {
                contractionsFile.copyTo(File(packDir, CONTRACTIONS_FILE), overwrite = true)
                Log.d(TAG, "Copied contractions.json for ${manifest.code}")
            }

            // Copy prefix boost trie if present
            val prefixBoostFile = File(tempDir, PREFIX_BOOST_FILE)
            if (prefixBoostFile.exists()) {
                prefixBoostFile.copyTo(File(packDir, PREFIX_BOOST_FILE), overwrite = true)
                Log.d(TAG, "Copied prefix_boost.bin for ${manifest.code} (${prefixBoostFile.length() / 1024}KB)")
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
            LanguagePackManifest(
                code = obj.getString("code"),
                name = obj.getString("name"),
                version = obj.optInt("version", 1),
                author = obj.optString("author", ""),
                wordCount = obj.optInt("wordCount", 0),
                hasPrefixBoost = obj.optBoolean("hasPrefixBoost", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse manifest", e)
            null
        }
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
     * Get list of installed language packs.
     */
    fun getInstalledPacks(): List<LanguagePackManifest> {
        val packs = mutableListOf<LanguagePackManifest>()

        langpacksDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
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
     * Get prefix boost trie file path for a language code.
     * Returns null if not available.
     */
    fun getPrefixBoostPath(code: String): File? {
        val prefixBoostFile = File(langpacksDir, "$code/$PREFIX_BOOST_FILE")
        return if (prefixBoostFile.exists()) prefixBoostFile else null
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
    val hasPrefixBoost: Boolean = false  // Whether pack includes prefix boost trie
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
