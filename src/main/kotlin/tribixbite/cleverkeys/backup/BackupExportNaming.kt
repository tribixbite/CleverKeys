package tribixbite.cleverkeys.backup

/**
 * ARC-035: the file name + MIME type a SAF "create document" picker should be seeded with for a
 * backup export. Pure JVM so the naming rule is testable without an Activity.
 *
 * The troubleshooting wiki has told users since backup encryption shipped that "encrypted files
 * get a `.ckenc` suffix appended to their normal name" — nothing produced it. Two things are
 * needed to actually deliver that, and only one of them is the file name:
 *
 *  1. the suggested name gains the suffix, and
 *  2. the picker's MIME type changes to `application/octet-stream`.
 *
 * (2) is not cosmetic. `DocumentsProvider.createDocument` runs the requested display name through
 * AOSP's `FileUtils.splitFileName`, which **rewrites the extension to match the requested MIME
 * type** whenever the two disagree: asking for `application/json` while suggesting
 * `cleverkeys-config.json.ckenc` yields a file literally named `cleverkeys-config.json.ckenc.json`.
 * `application/octet-stream` is `ContentResolver.MIME_TYPE_DEFAULT`, which that same function
 * treats as "no extension is implied", so an unknown extension like `.ckenc` is preserved verbatim.
 *
 * Import is unaffected: detection sniffs the `CKENC1` magic bytes
 * ([tribixbite.cleverkeys.backup.crypto.EncryptedBackupFormat.sniff]), never the extension, and
 * every import picker already passes a wildcard MIME alongside its typed one, so a `.ckenc` file
 * (which has no registered MIME mapping) stays selectable.
 */
data class BackupExportName(val fileName: String, val mimeType: String)

object BackupExportNaming {

    /** Suffix appended to the plaintext name when the export will be encrypted. */
    const val CKENC_SUFFIX: String = ".ckenc"

    /**
     * `ContentResolver.MIME_TYPE_DEFAULT`. Hardcoded rather than referenced so this stays pure
     * JVM (the constant lives in `android.content.ContentResolver`).
     */
    const val CKENC_MIME: String = "application/octet-stream"

    /**
     * @param plainName the name used when the export is written as plaintext
     *   (e.g. `cleverkeys-config.json`).
     * @param plainMime the MIME type matching [plainName]'s extension.
     * @param willEncrypt whether this export will actually produce a `CKENC1` container —
     *   i.e. a backup password is set AND the one-shot plaintext opt-out is not armed. This must
     *   mirror `BackupRestoreManager.resolveExportEncryption`'s `willEncrypt`, which is likewise
     *   "policy wants encryption AND a passphrase exists": a UI_DEFAULT export with no passphrase
     *   configured writes plaintext, so it must NOT be named `.ckenc`.
     */
    fun forExport(plainName: String, plainMime: String, willEncrypt: Boolean): BackupExportName =
        if (willEncrypt) BackupExportName(plainName + CKENC_SUFFIX, CKENC_MIME)
        else BackupExportName(plainName, plainMime)
}
