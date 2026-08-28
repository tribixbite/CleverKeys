package tribixbite.cleverkeys.gif

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Manages GIF pack import, removal, and lifecycle.
 *
 * No internet permission required — packs are distributed as ZIP files via
 * GitHub Releases. Users download in their browser, then import via file picker
 * or Android share intent.
 *
 * Pack ZIP format:
 *   manifest.json     — {pack_id, name, version, gif_count, description}
 *   pack.db           — Mini SQLite with this pack's gifs/categories/FTS rows
 *   thumbs/{part}/    — Thumbnails partitioned by id÷1000, named `{id:06d}.webp`.
 *                       REQUIRED whenever the pack carries GIFs: the grid renders
 *                       thumbnails, so a pack without them installs to blank tiles
 *                       (#149). Enforced at import — see [ERROR_MISSING_THUMBNAILS].
 *
 * Follows the same pattern as LanguagePackManager for consistency.
 */
class GifPackManager private constructor(private val context: Context) {

    private val database: GifDatabase by lazy { GifDatabase.getInstance(context) }
    private val assetManager: GifAssetManager by lazy { GifAssetManager.getInstance(context) }

    /**
     * Import a GIF pack from a ZIP file URI (from file picker or share intent).
     *
     * @param uri Content URI to the ZIP file
     * @param replaceExisting If true, removes existing pack with same ID before importing
     * @return Import result with details
     */
    suspend fun importPackFromUri(
        uri: Uri,
        replaceExisting: Boolean = false
    ): GifPackImportResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Importing GIF pack from: $uri")

        val tempDir = File(context.cacheDir, "gif_pack_import_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // Step 1: Extract ZIP to temp directory
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext GifPackImportResult.Error("Cannot open file")

            extractZip(inputStream, tempDir)

            // Step 2: Validate manifest.json
            val manifestFile = File(tempDir, MANIFEST_FILE)
            if (!manifestFile.exists()) {
                return@withContext GifPackImportResult.Error("Missing manifest.json — not a valid GIF pack")
            }

            val manifest = parseManifest(manifestFile.readText())
                ?: return@withContext GifPackImportResult.Error("Invalid manifest.json format")

            // Step 3: Validate pack.db
            val packDbFile = File(tempDir, PACK_DB_FILE)
            if (!packDbFile.exists() || packDbFile.length() == 0L) {
                return@withContext GifPackImportResult.Error("Missing or empty pack.db")
            }

            // Step 3b: Validate thumbs/ (ARC-038 / #149).
            // The grid renders THUMBNAILS; a pack whose DB rows have no thumbnail file behind
            // them installs "successfully" and then shows a wall of blank tiles, with no way for
            // the user to tell that the pack — not the app — is the problem. Checked HERE, before
            // the duplicate check and before Step 5's replace-removal, so a rejected import never
            // writes a DB row and never destroys an already-installed pack.
            val thumbsDir = File(tempDir, THUMBS_DIR)
            if (manifest.gifCount > 0 && !hasImportableThumbnails(thumbsDir)) {
                Log.w(TAG, "Pack '${manifest.packId}' declares ${manifest.gifCount} GIFs but carries no thumbnails")
                return@withContext GifPackImportResult.Error(ERROR_MISSING_THUMBNAILS)
            }

            // Step 4: Check for duplicate
            if (database.isPackInstalled(manifest.packId) && !replaceExisting) {
                return@withContext GifPackImportResult.AlreadyInstalled(
                    manifest.packId, manifest.name
                )
            }

            // Step 5: If replacing, remove existing pack first
            if (replaceExisting && database.isPackInstalled(manifest.packId)) {
                val result = database.removePack(manifest.packId)
                assetManager.removeThumbnails(result.orphanedThumbIds)
                assetManager.removeFullGifs(result.orphanedFullIds)
            }

            // Step 6: Detect if pack includes full animated GIFs
            val fullDir = File(tempDir, FULL_DIR)
            val hasFullGifs = fullDir.exists() && fullDir.walkTopDown().any {
                it.isFile && it.extension == "webp"
            }

            // Step 7: Import database entries
            val imported = database.importPack(
                packDbFile = packDbFile,
                packId = manifest.packId,
                packName = manifest.name,
                gifCount = manifest.gifCount,
                sizeBytes = calculateExtractedSize(tempDir),
                hasFullGifs = hasFullGifs
            )

            // Step 8: Copy thumbnails to app storage
            val thumbCount = if (thumbsDir.exists()) {
                assetManager.importThumbnails(thumbsDir)
            } else {
                0
            }

            // Step 8b (ARC-038 safety net): Step 3b can only act on what the manifest DECLARES,
            // and `gif_count` is optional (`optInt(…, 0)`) — a hand-built or truncated manifest
            // that omits it reads as an empty pack and slips through. Here the real numbers are
            // known, so catch the same defect from the other side and UNDO the DB write rather
            // than leave rows the grid cannot render.
            if (imported > 0 && thumbCount == 0) {
                Log.w(TAG, "Pack '${manifest.packId}' imported $imported rows with 0 thumbnails — rolling back")
                val removal = database.removePack(manifest.packId)
                assetManager.removeThumbnails(removal.orphanedThumbIds)
                assetManager.removeFullGifs(removal.orphanedFullIds)
                return@withContext GifPackImportResult.Error(ERROR_MISSING_THUMBNAILS)
            }

            // Step 9: Copy full animated GIFs if present
            val fullCount = if (hasFullGifs) {
                assetManager.importFullGifs(fullDir)
            } else {
                0
            }

            Log.i(TAG, "Pack '${manifest.packId}' imported: $imported DB, $thumbCount thumbs, $fullCount full GIFs")

            GifPackImportResult.Success(
                packId = manifest.packId,
                name = manifest.name,
                gifCount = imported
            )
        } catch (e: Exception) {
            Log.e(TAG, "Pack import failed", e)
            GifPackImportResult.Error("Import failed: ${e.message}")
        } finally {
            // Clean up temp directory
            tempDir.deleteRecursively()
        }
    }

    /**
     * Open the browser to the GitHub Releases page for downloading packs.
     * No INTERNET permission needed — delegates to the system browser.
     */
    fun openDownloadsPage(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open browser: ${e.message}")
        }
    }

    /**
     * Remove a specific pack — deletes DB entries and orphaned files.
     * Multi-pack safe: only deletes files for gifs not claimed by other packs.
     */
    suspend fun removePack(packId: String) = withContext(Dispatchers.IO) {
        val result = database.removePack(packId)
        assetManager.removeThumbnails(result.orphanedThumbIds)
        if (result.orphanedFullIds.isNotEmpty()) {
            assetManager.removeFullGifs(result.orphanedFullIds)
        }
        Log.i(TAG, "Removed pack '$packId': ${result.orphanedThumbIds.size} thumbs, " +
            "${result.orphanedFullIds.size} full GIFs cleaned")
    }

    /**
     * Remove ALL GIF data — database, thumbnails, everything.
     * Resets gif_enabled to false in Config.
     */
    suspend fun removeAll() = withContext(Dispatchers.IO) {
        // Delete all GIF files first
        assetManager.clearAll()

        // Delete the database
        database.removeAllData()

        // Release singletons
        GifDatabase.release()
        GifAssetManager.release()

        // Disable GIF panel in config
        try {
            tribixbite.cleverkeys.Config.globalConfig().set_gif_enabled(false)
        } catch (e: Exception) {
            Log.w(TAG, "Could not reset gif_enabled: ${e.message}")
        }

        Log.i(TAG, "All GIF data removed")
    }

    /**
     * Get list of installed packs.
     */
    fun getInstalledPacks(): List<InstalledPackInfo> {
        return try {
            database.getInstalledPacks()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read installed packs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get total storage used by GIF data (database + files).
     */
    fun getTotalStorageUsed(): Long {
        val dbSize = context.getDatabasePath(GifDatabase.DATABASE_NAME).let {
            if (it.exists()) it.length() else 0L
        }
        val fileSize = assetManager.getTotalStorageUsed()
        return dbSize + fileSize
    }

    // ==================== INTERNAL ====================

    /**
     * Extract ZIP from InputStream to destination directory.
     * Includes path traversal protection.
     */
    private fun extractZip(inputStream: java.io.InputStream, destDir: File) {
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val destFile = File(destDir, entry.name)

                // Path traversal protection
                if (!destFile.canonicalFile.path.startsWith(destDir.canonicalPath)) {
                    Log.w(TAG, "Skipping path traversal attempt: ${entry.name}")
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Parse manifest.json into GifPackManifest.
     */
    private fun parseManifest(json: String): GifPackManifest? {
        return try {
            val obj = JSONObject(json)
            GifPackManifest(
                packId = obj.getString("pack_id"),
                name = obj.getString("name"),
                version = obj.optInt("version", 1),
                gifCount = obj.optInt("gif_count", 0),
                description = obj.optString("description", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse manifest", e)
            null
        }
    }

    /**
     * Calculate total size of extracted files in a directory.
     */
    private fun calculateExtractedSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        private const val TAG = "GifPackManager"
        private const val MANIFEST_FILE = "manifest.json"
        private const val PACK_DB_FILE = "pack.db"
        private const val THUMBS_DIR = "thumbs"
        private const val FULL_DIR = "full"

        /**
         * ARC-038 / #149: rejection message for a pack that declares GIFs but ships no
         * importable thumbnails. Named so the wording is pinned by test rather than
         * duplicated at the two rejection sites.
         */
        /**
         * ARC-038: does [thumbsDir] hold at least one thumbnail
         * [GifAssetManager.importThumbnails] would actually copy?
         *
         * The predicate mirrors that importer EXACTLY — a `.webp` file whose base name parses
         * as a numeric id (`thumbs/{partition}/{id:06d}.webp`, per
         * `tools/gif_pipeline/make_pack.py`). An approximation would be worse than nothing
         * here: "the directory exists" and "the directory is non-empty" both pass for a pack
         * whose thumbnails are named in some other scheme, which imports zero and lands the
         * user right back at the blank tiles this is meant to prevent.
         *
         * `walkTopDown().any {}` short-circuits on the first hit, so this does not enumerate a
         * multi-thousand-file pack. In the companion (and `internal`) so the rule is testable
         * against real directory layouts without constructing a manager.
         */
        @androidx.annotation.VisibleForTesting
        internal fun hasImportableThumbnails(thumbsDir: File): Boolean =
            thumbsDir.isDirectory && thumbsDir.walkTopDown().any {
                it.isFile && it.extension == "webp" && it.nameWithoutExtension.toLongOrNull() != null
            }

        const val ERROR_MISSING_THUMBNAILS =
            "Legacy pack format — this pack has no thumbnails, so its GIFs cannot be shown. " +
                "Download a current pack."

        const val GITHUB_RELEASES_URL =
            "https://github.com/tribixbite/CleverKeys/releases/tag/CleverKeys-GIF"

        // Process-lifetime singleton holding only the applicationContext (see getInstance),
        // so it never leaks an Activity/Service. release() clears it when GIF is disabled.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: GifPackManager? = null

        fun getInstance(context: Context): GifPackManager {
            return instance ?: synchronized(this) {
                instance ?: GifPackManager(context.applicationContext).also { instance = it }
            }
        }

        fun release() {
            synchronized(this) {
                instance = null
            }
        }

        /** Format bytes as human-readable string. */
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}

/** GIF pack manifest data (from manifest.json inside ZIP). */
data class GifPackManifest(
    val packId: String,
    val name: String,
    val version: Int = 1,
    val gifCount: Int = 0,
    val description: String = ""
)

/** Result of a pack import operation. */
sealed class GifPackImportResult {
    data class Success(val packId: String, val name: String, val gifCount: Int) : GifPackImportResult()
    data class AlreadyInstalled(val packId: String, val name: String) : GifPackImportResult()
    data class Error(val message: String) : GifPackImportResult()
}
