package tribixbite.cleverkeys.backup

/**
 * ARC-036: what the import pipeline learned about the FILE a preview was built from, as opposed
 * to its contents. Pure data — carried on the import plans so the preview dialog can render it.
 *
 * The backup-encryption design accepted the **replay** risk (§7 residual #2: an attacker holding
 * a genuine old encrypted backup can replay it, because GCM authenticates content, not freshness)
 * on the strength of two mitigations, both of which are display, not enforcement:
 *
 *  - the AAD-covered export timestamp is *shown in the UI preview* and logged on import, so a
 *    replayed backup is visibly stale before the user accepts it; and
 *  - a plaintext import carries the "consider re-exporting encrypted" advisory (§9).
 *
 * The header timestamp was already decoded (`BackupCrypto.decrypt` → `DecryptedPayload`) and then
 * dropped on the floor. This type is the wire that stops that.
 */
data class BackupSourceInfo(
    /** `true` when the file was a `CKENC1` container that decrypted and authenticated. */
    val encrypted: Boolean,
    /**
     * Epoch millis from the container header (AAD-covered, so tampering breaks the GCM tag).
     * `null` for plaintext sources, which carry no trustworthy export time.
     */
    val exportTimestampMs: Long?,
) {
    companion object {
        /** A plaintext (legacy / opted-out) backup file. */
        val PLAINTEXT: BackupSourceInfo = BackupSourceInfo(encrypted = false, exportTimestampMs = null)

        /** An encrypted container whose header carried [timestampMs]. */
        fun encrypted(timestampMs: Long): BackupSourceInfo =
            BackupSourceInfo(encrypted = true, exportTimestampMs = timestampMs)
    }
}
