package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Manages clipboard media file storage, thumbnail generation, and cleanup.
 *
 * Storage layout (partitioned to keep <1000 files per directory):
 *   {filesDir}/clipboard_media/{partition}/{hash}.{ext}
 *
 * Thumbnails are 80×80 WebP, capped at 10KB for SQLite BLOB storage.
 * Larger thumbnails are saved as files instead to avoid CursorWindow overflow.
 *
 * MIME-agnostic: handles images, video, PDF, and arbitrary file types.
 * External content:// URIs are copied to internal storage immediately
 * because Android revokes clipboard URI access when the clipboard changes.
 */
class ClipboardMediaManager(private val context: Context) {

    /** Result of saving media from a content URI */
    data class MediaSaveResult(
        val mediaPath: String,          // relative path within filesDir
        val thumbnailBlob: ByteArray?,  // ≤10KB WebP for BLOB, or null
        val mimeType: String,
        val displayName: String,        // original filename or URI segment
        val contentHash: String,        // hex SHA-256 of file bytes
        val fileSizeBytes: Long
    )

    private val mediaBaseDir: File by lazy {
        File(context.filesDir, MEDIA_DIR).also { it.mkdirs() }
    }

    // ─── Public API ───────────────────────────────────────────────────

    /**
     * Save media from a content:// URI to internal storage.
     * Streams via ContentResolver (bypasses Binder IPC limit).
     *
     * @param uri The content URI to read from
     * @param mimeType MIME type from ContentResolver.getType()
     * @param maxSizeBytes Maximum file size in bytes (default 10MB)
     * @return MediaSaveResult or null if save failed / exceeded size limit
     */
    fun saveMedia(uri: Uri, mimeType: String, maxSizeBytes: Long = DEFAULT_MAX_MEDIA_BYTES): MediaSaveResult? {
        return try {
            val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "media"
            val ext = extensionForMime(mimeType)

            // Stream URI content to a temporary file, enforcing size limit
            val tempFile = File.createTempFile("clip_", ".$ext", mediaBaseDir)
            var totalBytes = 0L

            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                tempFile.delete()
                Log.w(TAG, "Failed to open input stream for URI: $uri")
                return null
            }

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead = input.read(buffer)
                    while (bytesRead != -1) {
                        totalBytes += bytesRead
                        if (totalBytes > maxSizeBytes) {
                            tempFile.delete()
                            Log.w(TAG, "Media too large: $totalBytes bytes > $maxSizeBytes limit")
                            return null
                        }
                        output.write(buffer, 0, bytesRead)
                        bytesRead = input.read(buffer)
                    }
                }
            }

            if (totalBytes == 0L) {
                tempFile.delete()
                return null
            }

            // Compute content hash for dedup and filename
            val contentHash = hashFile(tempFile)

            // Move to partitioned storage: clipboard_media/{partition}/{hash}.{ext}
            val partition = (contentHash.hashCode().toLong().and(0xFFFFFFFFL) % 1000).toInt()
            val partitionDir = File(mediaBaseDir, "%03d".format(partition))
            partitionDir.mkdirs()
            val destFile = File(partitionDir, "$contentHash.$ext")

            if (destFile.exists()) {
                // Duplicate file — reuse existing, delete temp
                tempFile.delete()
            } else {
                tempFile.renameTo(destFile)
            }

            val mediaPath = "$MEDIA_DIR/%03d/$contentHash.$ext".format(partition)

            // Generate thumbnail (MIME-aware, ≤10KB target)
            val thumbnailBlob = generateThumbnail(destFile.absolutePath, mimeType)

            MediaSaveResult(
                mediaPath = mediaPath,
                thumbnailBlob = thumbnailBlob,
                mimeType = mimeType,
                displayName = displayName,
                contentHash = contentHash,
                fileSizeBytes = totalBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save media from URI: ${e.message}", e)
            null
        }
    }

    /**
     * Generate an 80×80 WebP thumbnail for the given media file.
     * Returns ByteArray ≤10KB for BLOB storage, or null if generation fails.
     *
     * Thumbnail strategy by MIME type:
     * - image: BitmapFactory with inSampleSize (first frame for animated)
     * - video: MediaMetadataRetriever frame at time 0 (internal path only)
     * - application/pdf: PdfRenderer first page (API 21+)
     * - other: null (UI shows MIME-type icon instead)
     */
    fun generateThumbnail(path: String, mimeType: String, maxDim: Int = THUMBNAIL_SIZE): ByteArray? {
        return try {
            val bitmap = when {
                mimeType.startsWith("image/") -> decodeImageThumbnail(path, maxDim)
                mimeType.startsWith("video/") -> decodeVideoThumbnail(path)
                mimeType == "application/pdf" -> decodePdfThumbnail(path, maxDim)
                else -> null
            } ?: return null

            // Scale to maxDim × maxDim preserving aspect ratio
            val scaled = scaleBitmap(bitmap, maxDim)
            bitmap.recycle()

            // Compress to WebP, targeting ≤10KB
            val blob = compressToWebP(scaled, MAX_THUMBNAIL_BYTES)
            scaled.recycle()

            blob
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail generation failed for $mimeType: ${e.message}")
            null
        }
    }

    /**
     * Detect if a media file contains animation (GIF or animated WebP).
     * Checks file headers directly — no full decode needed.
     */
    fun isAnimated(path: String, mimeType: String): Boolean {
        return try {
            when {
                mimeType == "image/gif" -> isAnimatedGif(path)
                mimeType == "image/webp" -> isAnimatedWebP(path)
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Delete the media file at the given relative path */
    fun deleteMedia(mediaPath: String) {
        try {
            val file = File(context.filesDir, mediaPath)
            if (file.exists()) {
                val parentDir = file.parentFile
                file.delete()
                // Clean up empty partition directory
                if (parentDir != null && parentDir.isDirectory &&
                    (parentDir.listFiles()?.isEmpty() == true)) {
                    parentDir.delete()
                }
                Log.d(TAG, "Deleted media: $mediaPath")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete media: $mediaPath: ${e.message}")
        }
    }

    /** Get the File object for a media entry (for commitContent/paste) */
    fun getMediaFile(mediaPath: String): File = File(context.filesDir, mediaPath)

    /**
     * Delete orphan media files not referenced by any DB table.
     * Call on service startup and after import to reconcile file system with DB.
     */
    fun cleanupOrphans(referencedPaths: Set<String>) {
        try {
            var deleted = 0
            mediaBaseDir.listFiles()?.forEach { partitionDir ->
                if (!partitionDir.isDirectory) return@forEach
                partitionDir.listFiles()?.forEach { file ->
                    val relativePath = "$MEDIA_DIR/${partitionDir.name}/${file.name}"
                    if (relativePath !in referencedPaths) {
                        file.delete()
                        deleted++
                    }
                }
                // Clean up empty partition dirs
                if (partitionDir.isDirectory && (partitionDir.listFiles()?.isEmpty() == true)) {
                    partitionDir.delete()
                }
            }
            if (deleted > 0) Log.d(TAG, "Cleaned up $deleted orphan media files")
        } catch (e: Exception) {
            Log.w(TAG, "Orphan cleanup failed: ${e.message}")
        }
    }

    /** Get total size of all media files on disk */
    fun getTotalMediaSizeBytes(): Long {
        return try {
            mediaBaseDir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } catch (e: Exception) {
            0L
        }
    }

    /** Get count of media files on disk */
    fun getMediaFileCount(): Int {
        return try {
            mediaBaseDir.walkTopDown()
                .filter { it.isFile }
                .count()
        } catch (e: Exception) {
            0
        }
    }

    /** Delete all clipboard media files */
    fun clearAll() {
        try {
            mediaBaseDir.deleteRecursively()
            mediaBaseDir.mkdirs()
            Log.d(TAG, "Cleared all clipboard media")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear media: ${e.message}")
        }
    }

    // ─── Private: Thumbnail generation ────────────────────────────────

    /**
     * Decode image thumbnail using BitmapFactory with subsampling.
     * For animated GIF/WebP, only the first frame is decoded (BitmapFactory default).
     */
    private fun decodeImageThumbnail(path: String, maxDim: Int): Bitmap? {
        // First pass: get dimensions without allocating bitmap
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)

        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        // Calculate inSampleSize to downsample (power of 2)
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxDim)
        opts.inJustDecodeBounds = false

        return BitmapFactory.decodeFile(path, opts)
    }

    /**
     * Extract first frame from video using MediaMetadataRetriever.
     * Only accepts internal file paths (never external URIs — native codec can SIGSEGV).
     */
    private fun decodeVideoThumbnail(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.w(TAG, "Video thumbnail extraction failed: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Render first page of PDF to bitmap using PdfRenderer (API 21+).
     * Catches SecurityException for password-protected PDFs.
     */
    private fun decodePdfThumbnail(path: String, maxDim: Int): Bitmap? {
        return try {
            val file = File(path)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)

            if (renderer.pageCount == 0) {
                renderer.close()
                pfd.close()
                return null
            }

            val page = renderer.openPage(0)

            // Render directly to target size (not full-page then scale)
            val scale = maxDim.toFloat() / maxOf(page.width, page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            page.close()
            renderer.close()
            pfd.close()
            bitmap
        } catch (e: SecurityException) {
            // Password-protected PDF — fall back to MIME icon
            Log.w(TAG, "PDF is password-protected, skipping thumbnail")
            null
        } catch (e: Exception) {
            Log.w(TAG, "PDF thumbnail failed: ${e.message}")
            null
        }
    }

    // ─── Private: Animation detection ─────────────────────────────────

    /** Check for NETSCAPE2.0 application extension block (indicates GIF animation) */
    private fun isAnimatedGif(path: String): Boolean {
        File(path).inputStream().use { input ->
            val header = ByteArray(6)
            if (input.read(header) != 6) return false
            val sig = String(header)
            if (sig != "GIF87a" && sig != "GIF89a") return false

            // Scan for application extension with NETSCAPE2.0 (animation loop marker)
            val buf = ByteArray(4096)
            val bytesRead = input.read(buf)
            val content = String(buf, 0, bytesRead, Charsets.ISO_8859_1)
            return content.contains("NETSCAPE2.0") || content.contains("ANIMEXTS1.0")
        }
    }

    /**
     * Parse WebP RIFF header to check VP8X chunk for ANIM flag.
     * VP8X extended header byte offset 20 (0-indexed), bit 1 = animation.
     */
    private fun isAnimatedWebP(path: String): Boolean {
        File(path).inputStream().use { input ->
            val header = ByteArray(30)
            if (input.read(header) < 30) return false

            // RIFF....WEBP
            if (header[0].toInt().toChar() != 'R' || header[1].toInt().toChar() != 'I' ||
                header[2].toInt().toChar() != 'F' || header[3].toInt().toChar() != 'F') return false
            if (header[8].toInt().toChar() != 'W' || header[9].toInt().toChar() != 'E' ||
                header[10].toInt().toChar() != 'B' || header[11].toInt().toChar() != 'P') return false

            // Check if chunk at offset 12 is VP8X (extended format)
            val chunkType = String(header, 12, 4, Charsets.US_ASCII)
            if (chunkType != "VP8X") return false

            // VP8X flags byte at offset 20: bit 1 (0x02) = animation
            val flags = header[20].toInt() and 0xFF
            return (flags and 0x02) != 0
        }
    }

    // ─── Private: Utility ─────────────────────────────────────────────

    /** Query the display name from a content URI via ContentResolver */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Compute SHA-256 hash of a file, return as hex string */
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Calculate power-of-2 inSampleSize for BitmapFactory subsampling */
    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sampleSize = 1
        val maxSide = maxOf(width, height)
        while (maxSide / sampleSize > maxDim * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /** Scale bitmap to fit within maxDim × maxDim preserving aspect ratio */
    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap

        val scale = maxDim.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /**
     * Compress bitmap to WebP format, targeting maxBytes size.
     * Starts at quality 80, steps down if too large. Returns null if can't fit.
     */
    private fun compressToWebP(bitmap: Bitmap, maxBytes: Int): ByteArray? {
        var quality = 80
        while (quality >= 10) {
            val baos = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, baos)
            val bytes = baos.toByteArray()
            if (bytes.size <= maxBytes) return bytes
            quality -= 15
        }
        // Last resort: lowest quality
        val baos = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        bitmap.compress(Bitmap.CompressFormat.WEBP, 1, baos)
        val bytes = baos.toByteArray()
        return if (bytes.size <= maxBytes) bytes else null
    }

    /** Map MIME type to file extension */
    private fun extensionForMime(mimeType: String): String {
        return when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/svg+xml" -> "svg"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "application/pdf" -> "pdf"
            "application/zip" -> "zip"
            "text/plain" -> "txt"
            "text/html" -> "html"
            else -> {
                // Extract subtype: "image/heic" → "heic", "application/json" → "json"
                val subtype = mimeType.substringAfter('/', "bin")
                // Strip parameters: "text/plain;charset=utf-8" → "plain"
                subtype.substringBefore(';').substringBefore('+').take(8)
            }
        }
    }

    companion object {
        private const val TAG = "ClipboardMedia"
        const val MEDIA_DIR = "clipboard_media"

        // Thumbnail constraints
        const val THUMBNAIL_SIZE = 80        // px — matches GIF thumbnails
        const val MAX_THUMBNAIL_BYTES = 10240 // 10KB — CursorWindow safety

        // Default max media file size: 10MB
        const val DEFAULT_MAX_MEDIA_BYTES = 10L * 1024 * 1024

        /** Human-readable MIME type label for UI display */
        fun mimeTypeLabel(mimeType: String): String {
            return when {
                mimeType.startsWith("image/") -> {
                    val subtype = mimeType.substringAfter("image/").uppercase()
                    when (subtype) {
                        "JPEG" -> "JPEG"
                        "PNG" -> "PNG"
                        "WEBP" -> "WebP"
                        "GIF" -> "GIF"
                        "SVG+XML" -> "SVG"
                        else -> subtype
                    }
                }
                mimeType.startsWith("video/") -> {
                    val subtype = mimeType.substringAfter("video/").uppercase()
                    when (subtype) {
                        "MP4" -> "MP4"
                        "QUICKTIME" -> "MOV"
                        "X-MATROSKA" -> "MKV"
                        "WEBM" -> "WebM"
                        else -> subtype
                    }
                }
                mimeType == "application/pdf" -> "PDF"
                mimeType == "application/zip" -> "ZIP"
                else -> mimeType.substringAfter('/').uppercase().take(8)
            }
        }
    }
}
