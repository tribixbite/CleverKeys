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
 * - {filesDir}/gifs/thumbs/{id÷1000}/{id}.webp (static thumbnail, partitioned)
 * - {filesDir}/gifs/full/{id÷1000}/{id}.webp (animated, downloaded on-demand)
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
     * Partitioned into subdirectories by id÷1000 to keep <1000 files per dir.
     */
    fun getThumbnailPath(): String = getPartitionedPath(THUMBS_DIR, id)

    /**
     * Get the file path for the full animated GIF (relative to app files dir).
     * Partitioned into subdirectories by id÷1000 to keep <1000 files per dir.
     */
    fun getFullPath(): String = getPartitionedPath(FULL_DIR, id)

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
     * Get the display name — title-cased keywords (excluding trailing Giphy ID).
     */
    fun getDisplayName(): String {
        val keywords = getKeywords()
        return if (keywords.isNotEmpty()) {
            keywords.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        } else {
            "GIF #$id"
        }
    }

    /**
     * Get keywords as a list for display (excludes trailing Giphy ID).
     */
    fun getKeywords(): List<String> {
        val tokens = searchText.split(" ").filter { it.isNotBlank() }
        // Last token is the Giphy ID — exclude it from display keywords
        return if (tokens.size > 1) tokens.dropLast(1) else tokens
    }

    /**
     * Extract the Giphy ID embedded as the last token of search_text.
     * Pipeline stores: "keyword1 keyword2 ... giphyId" (lowercase alphanumeric).
     * Returns null if search_text is empty or has no tokens.
     */
    fun getGiphyId(): String? {
        val tokens = searchText.split(" ").filter { it.isNotBlank() }
        return tokens.lastOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Construct the Giphy media URL from the embedded Giphy ID.
     * Returns null if no Giphy ID is available.
     */
    fun getGiphyUrl(): String? {
        val giphyId = getGiphyId() ?: return null
        return "https://media.giphy.com/media/$giphyId/giphy.gif"
    }

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
         * Build a partitioned file path: "{baseDir}/{id÷1000}/{id}.webp"
         * Keeps each subdirectory under 1000 files for filesystem performance.
         */
        fun getPartitionedPath(baseDir: String, id: Long): String {
            val partition = "%03d".format(id / 1000)
            val fileName = "%06d.webp".format(id)
            return "$baseDir/$partition/$fileName"
        }

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
