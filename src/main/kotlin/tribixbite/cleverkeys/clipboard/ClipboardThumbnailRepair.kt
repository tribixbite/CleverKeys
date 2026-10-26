package tribixbite.cleverkeys.clipboard

/**
 * Regenerates clipboard media thumbnails that are missing from the database.
 *
 * Why it exists: v1.5.0's `generateThumbnail` recycled the bitmap it was about to compress
 * whenever the image already fit the 80 px box, so every small image copied under 1.x was
 * stored with `thumbnail_blob` NULL (fixed for new copies by c1bf9530). The media file itself
 * was saved correctly, and 2.0 never revisited such rows: after the upgrade they showed the
 * MIME fallback icon for good (found by `tools/upgrade-test/`, 2026-10-25). A backup round
 * trip regenerated them, which is how the gap stayed hidden.
 *
 * [tribixbite.cleverkeys.ClipboardHistoryService] runs one pass on `Dispatchers.IO` at construction, next to the
 * orphan-media reconcile. The pass is idempotent: a stored thumbnail removes the row from the
 * next query, and a row that cannot get one (decode failure) is retried on the next start, at
 * most [MAX_PER_PASS] decodes per start. Pure (no Android types) so `runPureTests` covers it;
 * the Android side supplies the lambdas.
 */
internal object ClipboardThumbnailRepair {

    /** Upper bound on decodes per pass, so a huge history cannot stall startup IO. */
    const val MAX_PER_PASS = 200

    /** Counts for logging and tests. `skipped` = unsupported MIME type or no file on disk. */
    data class Outcome(val repaired: Int, val failed: Int, val skipped: Int)

    /**
     * MIME types [tribixbite.cleverkeys.ClipboardMediaManager.generateThumbnail] has a decoder for. Keep in step with
     * its `when`: anything else always returns null, and decoding it each start is waste.
     */
    fun canHaveThumbnail(mimeType: String): Boolean =
        mimeType.startsWith("image/") || mimeType.startsWith("video/") || mimeType == "application/pdf"

    /**
     * Generate and store a thumbnail for each entry of [missing] (media_path → MIME type).
     *
     * @param fileExists whether the media file for a path is on disk (CE storage may be locked).
     * @param generate decodes the file into a thumbnail blob, or null when it cannot.
     * @param store writes the blob to every row referencing the path; returns the rows updated.
     * @param limit at most this many decodes in one pass.
     */
    fun repair(
        missing: Map<String, String>,
        fileExists: (String) -> Boolean,
        generate: (path: String, mimeType: String) -> ByteArray?,
        store: (path: String, blob: ByteArray) -> Int,
        limit: Int = MAX_PER_PASS,
    ): Outcome {
        var repaired = 0
        var failed = 0
        var skipped = 0
        var attempts = 0
        for ((path, mimeType) in missing) {
            if (!canHaveThumbnail(mimeType) || !fileExists(path)) {
                skipped++
                continue
            }
            if (attempts >= limit) break
            attempts++
            val blob = try {
                generate(path, mimeType)
            } catch (e: Exception) {
                // One bad file must not stop the rest of the pass.
                null
            }
            if (blob != null && store(path, blob) > 0) repaired++ else failed++
        }
        return Outcome(repaired, failed, skipped)
    }
}
