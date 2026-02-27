package tribixbite.cleverkeys.gif

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages GIF file storage with partitioned subdirectory layout.
 *
 * Storage layout (partitioned by id÷1000 to stay under 1000 files/dir):
 * - {filesDir}/gifs/thumbs/{partition}/{id}.webp  (static first-frame thumbnails)
 * - {filesDir}/gifs/full/{partition}/{id}.webp    (animated WebP, cached on use)
 *
 * No assets bundled in APK — all data imported from user-downloaded pack ZIPs.
 */
class GifAssetManager private constructor(private val context: Context) {

    private val gifsBaseDir: File by lazy {
        File(context.filesDir, "gifs").also { it.mkdirs() }
    }

    // Resolve a partitioned file from a Gif object
    private fun thumbFile(gif: Gif): File = File(context.filesDir, gif.getThumbnailPath())
    private fun fullFile(gif: Gif): File = File(context.filesDir, gif.getFullPath())

    // Resolve a partitioned file from a raw gif ID
    private fun thumbFileById(id: Long): File = File(context.filesDir, Gif.getPartitionedPath(Gif.THUMBS_DIR, id))
    private fun fullFileById(id: Long): File = File(context.filesDir, Gif.getPartitionedPath(Gif.FULL_DIR, id))

    /**
     * Check if a thumbnail exists on disk.
     */
    fun hasThumbnail(gif: Gif): Boolean {
        val file = thumbFile(gif)
        return file.exists() && file.length() > 0
    }

    /**
     * Check if the full animation exists on disk (cached).
     */
    fun hasFullAnimation(gif: Gif): Boolean {
        val file = fullFile(gif)
        return file.exists() && file.length() > 0
    }

    /**
     * Get the URI for the thumbnail (for image loaders like Coil).
     * Returns null if thumbnail not downloaded yet.
     */
    fun getThumbnailUri(gif: Gif): Uri? {
        val file = thumbFile(gif)
        return if (file.exists() && file.length() > 0) Uri.fromFile(file) else null
    }

    /**
     * Get the URI for the full animation.
     * Returns null if not available.
     */
    fun getFullAnimationUri(gif: Gif): Uri? {
        val file = fullFile(gif)
        return if (file.exists() && file.length() > 0) Uri.fromFile(file) else null
    }

    /**
     * Get the File for the full animation (for sharing/commitContent).
     * Returns null if not available.
     */
    fun getGifFile(gif: Gif): File? {
        val file = fullFile(gif)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Load thumbnail as Drawable (synchronous, for adapters).
     */
    fun loadThumbnailSync(gif: Gif): Drawable? {
        val file = thumbFile(gif)
        return if (file.exists()) {
            try {
                Drawable.createFromPath(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    /**
     * Load thumbnail as InputStream.
     */
    fun openThumbnail(gif: Gif): InputStream? {
        val file = thumbFile(gif)
        return try {
            if (file.exists()) file.inputStream() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load full animation as InputStream.
     */
    fun openFullAnimation(gif: Gif): InputStream? {
        val file = fullFile(gif)
        return try {
            if (file.exists()) file.inputStream() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save a downloaded animation to the full dir.
     */
    suspend fun cacheFullAnimation(gif: Gif, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val file = fullFile(gif)
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(data) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache animation: ${gif.fileName}", e)
            false
        }
    }

    /**
     * Save a downloaded animation from InputStream.
     */
    suspend fun cacheFullAnimation(gif: Gif, input: InputStream): Boolean = withContext(Dispatchers.IO) {
        val file = fullFile(gif)
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output -> input.copyTo(output) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache animation: ${gif.fileName}", e)
            false
        }
    }

    /**
     * Import thumbnails from an extracted pack directory.
     * Handles both flat layout (thumbs/000001.webp) and partitioned layout (thumbs/000/000001.webp).
     *
     * @return number of thumbnails imported
     */
    suspend fun importThumbnails(sourceThumbsDir: File): Int = withContext(Dispatchers.IO) {
        if (!sourceThumbsDir.exists() || !sourceThumbsDir.isDirectory) return@withContext 0

        var count = 0
        sourceThumbsDir.walkTopDown()
            .filter { it.isFile && it.extension == "webp" }
            .forEach { srcFile ->
                val idStr = srcFile.nameWithoutExtension
                val id = idStr.toLongOrNull() ?: return@forEach
                val destFile = thumbFileById(id)
                destFile.parentFile?.mkdirs()
                try {
                    srcFile.copyTo(destFile, overwrite = true)
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import thumbnail $idStr: ${e.message}")
                }
            }
        Log.i(TAG, "Imported $count thumbnails from ${sourceThumbsDir.absolutePath}")
        count
    }

    /**
     * Import full animated GIF files from an extracted pack directory.
     * Handles both flat layout (full/000001.webp) and partitioned layout (full/000/000001.webp).
     *
     * @return number of full GIFs imported
     */
    suspend fun importFullGifs(sourceFullDir: File): Int = withContext(Dispatchers.IO) {
        if (!sourceFullDir.exists() || !sourceFullDir.isDirectory) return@withContext 0

        var count = 0
        sourceFullDir.walkTopDown()
            .filter { it.isFile && it.extension == "webp" }
            .forEach { srcFile ->
                val idStr = srcFile.nameWithoutExtension
                val id = idStr.toLongOrNull() ?: return@forEach
                val destFile = fullFileById(id)
                destFile.parentFile?.mkdirs()
                try {
                    srcFile.copyTo(destFile, overwrite = true)
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import full GIF $idStr: ${e.message}")
                }
            }
        Log.i(TAG, "Imported $count full GIFs from ${sourceFullDir.absolutePath}")
        count
    }

    /**
     * Remove thumbnail and full animation files for specific GIF IDs.
     * Cleans up empty partition directories afterward.
     */
    suspend fun removeThumbnails(gifIds: List<Long>) = withContext(Dispatchers.IO) {
        removeFiles(gifIds, thumb = true, full = true)
    }

    /**
     * Remove only full animated GIF files (keep thumbnails).
     * Used when a pack providing full GIFs is removed but another pack still
     * references the same gif_ids for thumbnail browsing.
     */
    suspend fun removeFullGifs(gifIds: List<Long>) = withContext(Dispatchers.IO) {
        removeFiles(gifIds, thumb = false, full = true)
    }

    private fun removeFiles(gifIds: List<Long>, thumb: Boolean, full: Boolean) {
        val partitionDirs = mutableSetOf<File>()
        for (id in gifIds) {
            if (thumb) {
                val thumbF = thumbFileById(id)
                if (thumbF.exists()) {
                    thumbF.parentFile?.let { partitionDirs.add(it) }
                    thumbF.delete()
                }
            }
            if (full) {
                val fullF = fullFileById(id)
                if (fullF.exists()) {
                    fullF.parentFile?.let { partitionDirs.add(it) }
                    fullF.delete()
                }
            }
        }
        // Clean up empty partition subdirectories
        for (dir in partitionDirs) {
            if (dir.exists() && dir.isDirectory && (dir.listFiles()?.isEmpty() == true)) {
                dir.delete()
            }
        }
        Log.i(TAG, "Removed files for ${gifIds.size} GIFs (thumb=$thumb, full=$full)")
    }

    /**
     * Get total storage used (thumbs + full animations) in bytes.
     */
    fun getTotalStorageUsed(): Long {
        return gifsBaseDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Get count of available thumbnails (walks partitioned subdirectories).
     */
    fun getThumbnailCount(): Int {
        val thumbsBase = File(context.filesDir, Gif.THUMBS_DIR)
        return thumbsBase.walkTopDown().filter { it.isFile && it.extension == "webp" }.count()
    }

    /**
     * Get count of cached full animations (walks partitioned subdirectories).
     */
    fun getCachedCount(): Int {
        val fullBase = File(context.filesDir, Gif.FULL_DIR)
        return fullBase.walkTopDown().filter { it.isFile && it.extension == "webp" }.count()
    }

    /**
     * Clear everything — thumbnails, full animations, pack downloads.
     * Called when user removes all GIF data.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        gifsBaseDir.deleteRecursively()
        val packDir = File(context.filesDir, "gif_packs")
        if (packDir.exists()) packDir.deleteRecursively()
        Log.i(TAG, "Cleared all GIF storage")
    }

    companion object {
        private const val TAG = "GifAssetManager"

        @Volatile
        private var instance: GifAssetManager? = null

        fun getInstance(context: Context): GifAssetManager {
            return instance ?: synchronized(this) {
                instance ?: GifAssetManager(context.applicationContext).also { instance = it }
            }
        }

        /** Release singleton (call when GIF panel is disabled or all data removed). */
        fun release() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
