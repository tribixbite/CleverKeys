package tribixbite.cleverkeys.gif

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File

/**
 * Represents a single GIF entry in the offline database.
 * Provides access to thumbnail and full animated versions.
 *
 * Asset filenames are derived from gif_id: String.format("%06d.webp", id)
 * Stored as WebP files:
 * - {filesDir}/gifs/thumbs/{id}.webp (static thumbnail)
 * - {filesDir}/gifs/full/{id}.webp (animated, downloaded on-demand)
 */
data class Gif(
    val id: Long,
    val width: Int,
    val height: Int,
    val durationMs: Int = 0,
    val fileSize: Int = 0,
    val packId: Int = 0,             // Pack this GIF belongs to (0 = default/core)
    val isAvailable: Boolean = true,  // Whether GIF data has been downloaded
    val searchText: String = "",     // Concatenated keywords for display
    val categories: List<GifCategory> = emptyList()
) {
    /** Derived filename from id: "000001.webp" */
    val fileName: String get() = "%06d.webp".format(id)

    /**
     * Get the file path for the static thumbnail (relative to app files dir).
     */
    fun getThumbnailPath(): String = "$THUMBS_DIR/$fileName"

    /**
     * Get the file path for the full animated GIF (relative to app files dir).
     */
    fun getFullPath(): String = "$FULL_DIR/$fileName"

    /**
     * Get thumbnail as Uri for image loaders (file:// scheme).
     */
    fun getThumbnailUri(context: Context): Uri {
        val file = File(context.filesDir, getThumbnailPath())
        return Uri.fromFile(file)
    }

    /**
     * Get full GIF as Uri for image loaders (file:// scheme).
     */
    fun getFullUri(context: Context): Uri {
        val file = File(context.filesDir, getFullPath())
        return Uri.fromFile(file)
    }

    /**
     * Get the primary display name (first keyword or ID).
     */
    fun getDisplayName(): String {
        return searchText.split(" ").firstOrNull()?.replaceFirstChar { it.uppercase() }
            ?: "GIF #$id"
    }

    /**
     * Get keywords as a list for display.
     */
    fun getKeywords(): List<String> = searchText.split(" ").filter { it.isNotBlank() }

    /**
     * Check if this GIF matches a search query.
     */
    fun matchesQuery(query: String): Boolean {
        val queryLower = query.lowercase().trim()
        return searchText.contains(queryLower) ||
               categories.any { cat -> cat.keywords.any { it.contains(queryLower) } }
    }

    /**
     * Get the aspect ratio for layout calculations.
     */
    fun getAspectRatio(): Float = if (height > 0) width.toFloat() / height else 1f

    companion object {
        // Storage directory paths (relative to context.filesDir)
        const val THUMBS_DIR = "gifs/thumbs"
        const val FULL_DIR = "gifs/full"

        /**
         * Get the file for a GIF in the cache directory.
         * Used when GIFs need to be shared to other apps via FileProvider.
         */
        fun getCacheFile(context: Context, gif: Gif): File {
            val cacheDir = File(context.cacheDir, "gifs")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            return File(cacheDir, gif.fileName)
        }
    }
}
