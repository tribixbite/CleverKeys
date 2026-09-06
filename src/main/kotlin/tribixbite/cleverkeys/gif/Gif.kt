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
     * Get keywords as a list for display.
     *
     * Marked Giphy-ID tokens ([GIPHY_ID_MARKER]) are always excluded. When no marked
     * token exists (legacy packs), the trailing token is dropped instead — legacy packs
     * were built under the "last token is the ID" convention, so the trailing token is
     * either a (lowercased) ID or a machine-built compound keyword; neither belongs in
     * the display name. When a marked token exists the ID is unambiguous, so every
     * remaining keyword is kept.
     */
    fun getKeywords(): List<String> {
        val tokens = searchText.split(" ").filter { it.isNotBlank() }
        val unmarked = tokens.filterNot { it.startsWith(GIPHY_ID_MARKER) }
        return when {
            unmarked.size != tokens.size -> unmarked
            unmarked.size > 1 -> unmarked.dropLast(1)
            else -> unmarked
        }
    }

    /**
     * Extract the case-preserved Giphy ID from the marked search_text token.
     *
     * #149: Giphy media IDs are case-sensitive. The old convention ("last token of
     * search_text is the ID") was doubly broken — the pipeline lowercased the whole
     * slug (case-smashed ID → guaranteed 404), and keyword extraction appended
     * compound tokens after it (the reporter's dead URL carried the compound
     * "cute"+ID, not even the ID). The pipeline now appends the ID case-preserved as
     * a "gid:"-marked token; anything unmarked yields null so a dead URL can never
     * be built from ordinary keywords. Legacy imported packs have no marked token —
     * their original-case IDs are unrecoverable (the DB stores only lowercased
     * keywords and media files are named by local numeric id), so they intentionally
     * return null here; taps still work via the locally-stored media.
     */
    fun getGiphyId(): String? {
        return searchText.split(" ")
            .lastOrNull { it.startsWith(GIPHY_ID_MARKER) && it.length > GIPHY_ID_MARKER.length }
            ?.removePrefix(GIPHY_ID_MARKER)
    }

    /**
     * Construct the Giphy media URL from the embedded case-preserved Giphy ID.
     * Returns null if no marked Giphy ID is available (legacy packs — see [getGiphyId]).
     * NOTE: this URL is a share/paste artifact for the RECEIVING app; CleverKeys has
     * no INTERNET permission and never fetches it.
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
        return searchText.lowercase().contains(queryLower) ||
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
         * Prefix marking the case-preserved Giphy ID token inside search_text
         * ("gid:CdMYfhPEanE9CkV6Ys"). Written by the pack pipeline
         * (tools/gif_pipeline — make_pack.py / build_database.py / pack_builder.py);
         * keep both sides in sync. FTS4's simple tokenizer splits it into "gid" +
         * the lowercased ID, so search matching stays case-insensitive without a
         * schema change.
         */
        const val GIPHY_ID_MARKER = "gid:"

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
