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
 * Manages GIF file storage with lazy-loading support.
 *
 * V2 Architecture (opt-in download model):
 * - Thumbnails: Downloaded with core pack to {filesDir}/gifs/thumbs/
 * - Full animations: Downloaded on-demand to {filesDir}/gifs/full/
 * - No assets bundled in APK (zero footprint when GIF panel is disabled)
 *
 * Storage layout:
 * - {filesDir}/gifs/thumbs/{id}.webp  (static first-frame thumbnails)
 * - {filesDir}/gifs/full/{id}.webp    (animated WebP, cached on use)
 * - {filesDir}/gif_packs/             (downloaded pack archives)
 *
 * Filenames derived from gif_id: String.format("%06d.webp", id)
 */
class GifAssetManager private constructor(private val context: Context) {

    private val fullDir: File by lazy {
        File(context.filesDir, Gif.FULL_DIR).also { it.mkdirs() }
    }

    private val thumbsDir: File by lazy {
        File(context.filesDir, Gif.THUMBS_DIR).also { it.mkdirs() }
    }

    /**
     * Check if a thumbnail exists on disk.
     */
    fun hasThumbnail(gif: Gif): Boolean {
        val file = File(thumbsDir, gif.fileName)
        return file.exists() && file.length() > 0
    }

    /**
     * Check if the full animation exists on disk (cached).
     */
    fun hasFullAnimation(gif: Gif): Boolean {
        val file = File(fullDir, gif.fileName)
        return file.exists() && file.length() > 0
    }

    /**
     * Get the URI for the thumbnail (for image loaders like Coil).
     * Returns null if thumbnail not downloaded yet.
     */
    fun getThumbnailUri(gif: Gif): Uri? {
        val file = File(thumbsDir, gif.fileName)
        return if (file.exists() && file.length() > 0) Uri.fromFile(file) else null
    }

    /**
     * Get the URI for the full animation.
     * Returns null if not available.
     */
    fun getFullAnimationUri(gif: Gif): Uri? {
        val file = File(fullDir, gif.fileName)
        return if (file.exists() && file.length() > 0) Uri.fromFile(file) else null
    }

    /**
     * Get the File for the full animation (for sharing/commitContent).
     * Returns null if not available.
     */
    fun getGifFile(gif: Gif): File? {
        val file = File(fullDir, gif.fileName)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Get the File path for the full animation (async, copies from assets fallback if needed).
     * Returns null if not available.
     */
    suspend fun getFullAnimationFile(gif: Gif): File? = withContext(Dispatchers.IO) {
        val file = File(fullDir, gif.fileName)
        if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Load thumbnail as Drawable (synchronous, for adapters).
     */
    fun loadThumbnailSync(gif: Gif): Drawable? {
        val file = File(thumbsDir, gif.fileName)
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
        val file = File(thumbsDir, gif.fileName)
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
        val file = File(fullDir, gif.fileName)
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
        val file = File(fullDir, gif.fileName)
        try {
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
        val file = File(fullDir, gif.fileName)
        try {
            FileOutputStream(file).use { output -> input.copyTo(output) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache animation: ${gif.fileName}", e)
            false
        }
    }

    /**
     * Get total cache size for full animations (bytes).
     */
    fun getFullCacheSize(): Long {
        return fullDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Get total storage used (thumbs + full animations) in bytes.
     */
    fun getTotalStorageUsed(): Long {
        val thumbsSize = thumbsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val fullSize = fullDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return thumbsSize + fullSize
    }

    /**
     * Get count of cached full animations.
     */
    fun getCachedCount(): Int {
        return fullDir.listFiles()?.count { it.isFile && it.extension == "webp" } ?: 0
    }

    /**
     * Get count of available thumbnails.
     */
    fun getThumbnailCount(): Int {
        return thumbsDir.listFiles()?.count { it.isFile && it.extension == "webp" } ?: 0
    }

    /**
     * Clear all cached full animations (thumbnails kept).
     */
    suspend fun clearFullCache() = withContext(Dispatchers.IO) {
        fullDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Clear everything (thumbnails + full animations).
     * Called when user disables GIF panel.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        fullDir.listFiles()?.forEach { it.delete() }
        thumbsDir.listFiles()?.forEach { it.delete() }
        // Also clean pack downloads
        val packDir = File(context.filesDir, "gif_packs")
        if (packDir.exists()) {
            packDir.deleteRecursively()
        }
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

        /**
         * Release singleton (call when GIF panel is disabled).
         */
        fun release() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
