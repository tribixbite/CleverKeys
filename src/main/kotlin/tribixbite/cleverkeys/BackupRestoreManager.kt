package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import android.provider.UserDictionary
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.backup.DictImportApplier
import tribixbite.cleverkeys.backup.DictImportPlan
import tribixbite.cleverkeys.backup.DictImportPlanBuilder
import tribixbite.cleverkeys.backup.LangWord
import tribixbite.cleverkeys.backup.RealShortSwipeImporter
import tribixbite.cleverkeys.backup.ScreenMetrics
import tribixbite.cleverkeys.backup.SettingsImportApplier
import tribixbite.cleverkeys.backup.SettingsImportPlan
import tribixbite.cleverkeys.backup.SETTINGS_DEFAULTS
import tribixbite.cleverkeys.backup.SettingsImportPlanBuilder
import tribixbite.cleverkeys.backup.SettingsValidation
import tribixbite.cleverkeys.backup.toExportableValue
import tribixbite.cleverkeys.backup.ShortSwipeImportMode
import tribixbite.cleverkeys.backup.ShortSwipeImporter
import tribixbite.cleverkeys.backup.crypto.BackupCrypto
import tribixbite.cleverkeys.backup.crypto.BackupFormatException
import tribixbite.cleverkeys.backup.crypto.BackupPassphraseStore
import tribixbite.cleverkeys.backup.crypto.EncryptedBackupFormat
import kotlinx.coroutines.runBlocking

/**
 * Manages backup and restore of keyboard configuration
 * Uses Storage Access Framework (SAF) for Android 15+ compatibility
 */
open class BackupRestoreManager(
    private val context: Context,
    private val shortSwipeImporter: ShortSwipeImporter = RealShortSwipeImporter(
        ShortSwipeCustomizationManager.getInstance(context)
    ),
    /**
     * Backup-passphrase source (design §4.2). Injectable so MockK tests can
     * supply a deterministic passphrase without touching the Android Keystore.
     */
    private val passphraseStore: BackupPassphraseStore = BackupPassphraseStore(context),
    /** Injectable only so archive-limit tests can exercise aggregate/container ceilings cheaply. */
    private val importLimits: ImportLimits = ImportLimits(),
) {
    // Lazy init to avoid circular dependency issues
    private val shortSwipeManager: ShortSwipeCustomizationManager by lazy {
        ShortSwipeCustomizationManager.getInstance(context)
    }
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Encryption enforcement for the current operation (design §4.3). Callers set
     * this before invoking an export/import:
     *  - [EncryptionPolicy.HEADLESS_MANDATORY]: the exported-activity path — encryption
     *    is non-negotiable (export always encrypts; a missing passphrase / plaintext
     *    import is a fail-closed error decided by the Activity before it ever calls in).
     *  - [EncryptionPolicy.UI_DEFAULT]: interactive path — encrypt when a passphrase exists.
     *  - [EncryptionPolicy.UI_PLAINTEXT_OPTOUT]: interactive path — the user explicitly
     *    chose "Export unencrypted"; write plaintext.
     *
     * Defaults to [EncryptionPolicy.UI_DEFAULT] so any caller that forgets to set it
     * still encrypts-by-default whenever a passphrase is configured (fail-safe).
     */
    @Volatile
    var encryptionPolicy: EncryptionPolicy = EncryptionPolicy.UI_DEFAULT

    /**
     * One-shot passphrase override for a single IMPORT operation (the interactive
     * "enter password" retry, and the gated headless `--es passphrase` escape hatch).
     * When null, imports resolve the passphrase from [passphraseStore]. NEVER consulted
     * by any export path — exports use only the stored passphrase (design §4.2, the
     * single most important rule). Cleared by [consumeImportPassphraseOverride].
     */
    @Volatile
    private var importPassphraseOverride: CharArray? = null

    /** Set the one-shot import passphrase override (see [importPassphraseOverride]). */
    fun setImportPassphraseOverride(passphrase: CharArray?) {
        importPassphraseOverride = passphrase
    }

    /** `true` if a backup passphrase is configured (delegates to the store). */
    fun hasBackupPassphrase(): Boolean = passphraseStore.hasPassphrase()

    /** Resolve the import passphrase: one-shot override first, else the stored one. */
    private fun resolveImportPassphrase(): CharArray? =
        importPassphraseOverride ?: passphraseStore.getPassphrase()

    /** Resolve the export passphrase — ALWAYS the stored one; overrides are ignored. */
    private fun resolveExportPassphrase(): CharArray? = passphraseStore.getPassphrase()

    /**
     * Encryption enforcement policy threaded from the caller (design §4.3).
     */
    enum class EncryptionPolicy { HEADLESS_MANDATORY, UI_DEFAULT, UI_PLAINTEXT_OPTOUT }

    /**
     * `true` when the current [encryptionPolicy] wants the payload encrypted:
     * anything except the explicit interactive plaintext opt-out. (For
     * HEADLESS_MANDATORY the Activity has already fail-closed on a missing
     * passphrase, so by the time we get here a passphrase must exist.)
     */
    private fun shouldEncrypt(): Boolean =
        encryptionPolicy != EncryptionPolicy.UI_PLAINTEXT_OPTOUT

    /**
     * Design §4.3 fix (TOCTOU-hardening, 2026-07-18): enforce the mandatory-encryption
     * policy on IMPORT at the SAME seam that hands bytes to the parser — not via a
     * separate up-front sniff in the Activity, which opened a *different* stream and
     * could be raced (serve CKENC on the sniff, plaintext on the import read).
     *
     * When [encryptionPolicy] is [EncryptionPolicy.HEADLESS_MANDATORY] and the freshly-
     * read [kind] is anything other than [EncryptedBackupFormat.PayloadKind.ENCRYPTED]
     * (plaintext JSON/ZIP, or an unsniffable/UNKNOWN payload), throw so nothing is
     * applied. Genuinely-encrypted payloads pass through to decrypt+authenticate.
     *
     * Interactive (UI_*) policies are unaffected — user-driven imports legitimately
     * accept plaintext backups.
     *
     * The check runs on bytes already pulled from the import stream, so a hostile
     * ContentProvider cannot serve encrypted bytes to the check and plaintext to the
     * reader: there is only one read.
     */
    private fun enforceHeadlessEncryptionPolicy(kind: EncryptedBackupFormat.PayloadKind) {
        if (encryptionPolicy == EncryptionPolicy.HEADLESS_MANDATORY &&
            kind != EncryptedBackupFormat.PayloadKind.ENCRYPTED
        ) {
            throw BackupDecryptException(
                "Plaintext backups are not accepted via automation. Under the mandatory-" +
                    "encryption (headless) policy, only encrypted (CKENC) backups may be imported. " +
                    "Use the app's Import button, or supply an encrypted backup."
            )
        }
    }

    private class ExportEncryption(
        @JvmField val passphrase: CharArray?,
        @JvmField val willEncrypt: Boolean,
    ) {
        /** Best-effort scrub of the resolved passphrase — call once in the export's `finally`. */
        fun zero() {
            passphrase?.let { java.util.Arrays.fill(it, '\u0000') }
        }
    }

    /**
     * #156 F7 (double-resolution fix, 2026-07-18): resolve the export passphrase ONCE and derive both
     * the encryption decision AND the encryption input from the single resolved [CharArray]. Previously
     * `willEncryptExport()` fully unwrapped the passphrase (Base64 decode + AndroidKeystore AES unwrap
     * via binder) just to null-check-and-zero it, then [encryptIfRequired] / [writeZipEncryptingIfRequired]
     * resolved it AGAIN — two Keystore round-trips + an extra plaintext heap copy per export, in a
     * feature whose whole point is minimizing plaintext copies.
     *
     * Enforces the fail-closed HEADLESS_MANDATORY invariant up front: reaching an export under that
     * policy with no stored passphrase is a programming error (the Activity must fail closed before
     * dispatch), so we throw rather than let a private-dropping plaintext export proceed. The caller
     * MUST call [ExportEncryption.zero] in a `finally`.
     *
     * [ExportEncryption.willEncrypt] is `true` only when the policy wants encryption AND a passphrase
     * exists — strictly stronger than [shouldEncrypt] (UI_DEFAULT with no passphrase "wants"
     * encryption but writes plaintext, so private entries MUST still be excluded there). It is used as
     * the `includePrivate` gate for clipboard exports so private rows land only in genuinely-encrypted
     * (CKENC) output and never in a plaintext file.
     */
    private fun resolveExportEncryption(): ExportEncryption {
        if (!shouldEncrypt()) return ExportEncryption(passphrase = null, willEncrypt = false)
        val passphrase = resolveExportPassphrase()
        if (passphrase == null) {
            check(encryptionPolicy != EncryptionPolicy.HEADLESS_MANDATORY) {
                "HEADLESS_MANDATORY export reached resolveExportEncryption without a stored " +
                    "passphrase — the Activity must fail closed before dispatch."
            }
            // UI_DEFAULT, no passphrase configured yet → legacy plaintext behavior.
            return ExportEncryption(passphrase = null, willEncrypt = false)
        }
        return ExportEncryption(passphrase = passphrase, willEncrypt = true)
    }

    /**
     * Encrypt [plaintext] into a `CKENC1` container using the pre-resolved [enc] when it will encrypt;
     * otherwise return [plaintext] unchanged (plaintext opt-out, or UI_DEFAULT with no passphrase).
     * Does NOT re-resolve or zero the passphrase — [enc] owns its lifecycle (the export op zeroes
     * it once in a `finally`). [contentType] binds the file to its import action via the AEAD header.
     */
    private fun encryptIfRequired(
        plaintext: ByteArray,
        contentType: Byte,
        enc: ExportEncryption,
    ): ByteArray {
        val passphrase = enc.passphrase
        if (!enc.willEncrypt || passphrase == null) return plaintext
        return BackupCrypto.encrypt(plaintext, passphrase, contentType, System.currentTimeMillis())
    }

    /**
     * Decrypt a `CKENC1` [container] to bytes, resolving the passphrase from the
     * one-shot import override or the stored passphrase. Throws
     * [BackupDecryptException] on wrong passphrase / tamper (mapped by callers to a
     * single user-facing message) or when no passphrase is available.
     */
    private fun decryptContainer(
        container: ByteArray,
        expectedContentTypes: Set<Byte>,
    ): BackupCrypto.DecryptedPayload {
        val passphrase = resolveImportPassphrase()
            ?: throw BackupDecryptException(
                "This backup is encrypted but no backup password is available. " +
                    "Set one in Settings → Backup & Restore, or enter it when prompted."
            )
        return try {
            val payload = BackupCrypto.decrypt(container, passphrase)
            if (expectedContentTypes.isNotEmpty() && payload.contentType !in expectedContentTypes) {
                throw BackupDecryptException(
                    "Encrypted backup content-type ${payload.contentType} does not match this " +
                        "import action (expected one of $expectedContentTypes)."
                )
            }
            payload
        } catch (e: javax.crypto.AEADBadTagException) {
            throw BackupDecryptException(WRONG_PASSWORD_OR_CORRUPT, e)
        } catch (e: BackupFormatException) {
            throw BackupDecryptException(e.message ?: WRONG_PASSWORD_OR_CORRUPT, e)
        } finally {
            java.util.Arrays.fill(passphrase, '\u0000')
        }
    }

    /**
     * Stream-decrypt an encrypted ZIP [container] (content type 4/5) to a fresh
     * temp file in [Context.getCacheDir], returning it ONLY after the GCM tag has
     * verified (authenticate-then-parse, design §6). The header's content-type is
     * validated against [expectedContentTypes]. The temp file is the caller's to
     * delete (they do so in a `finally`). Throws [BackupDecryptException] on wrong
     * passphrase / tamper.
     */
    private fun decryptZipToTempFile(
        container: InputStream,
        expectedContentTypes: Set<Byte>,
    ): File {
        val passphrase = resolveImportPassphrase()
            ?: throw BackupDecryptException(
                "This backup is encrypted but no backup password is available. " +
                    "Set one in Settings → Backup & Restore, or enter it when prompted."
            )
        val tempFile = File(context.cacheDir, "ck_decrypt_${System.currentTimeMillis()}.zip")
        try {
            val header = FileOutputStream(tempFile).use { out ->
                // doFinal inside decryptToStream verifies the tag; if it throws,
                // the temp file holds unauthenticated bytes we delete below and
                // never hand to the ZIP parser.
                BackupCrypto.decryptToStream(
                    container = container,
                    output = out,
                    passphrase = passphrase,
                    maxContainerBytes = importLimits.archiveContainerBytes,
                    maxPlaintextBytes = importLimits.archiveContainerBytes,
                )
            }
            if (expectedContentTypes.isNotEmpty() && header.contentType !in expectedContentTypes) {
                throw BackupDecryptException(
                    "Encrypted backup content-type ${header.contentType} does not match this " +
                        "import action (expected one of $expectedContentTypes)."
                )
            }
            return tempFile
        } catch (e: javax.crypto.AEADBadTagException) {
            tempFile.delete()
            throw BackupDecryptException(WRONG_PASSWORD_OR_CORRUPT, e)
        } catch (e: BackupFormatException) {
            tempFile.delete()
            throw BackupDecryptException(e.message ?: WRONG_PASSWORD_OR_CORRUPT, e)
        } catch (e: BackupDecryptException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        } finally {
            java.util.Arrays.fill(passphrase, '\u0000')
        }
    }

    /**
     * Write a (potentially large, media-carrying) ZIP to [uri], encrypting the whole
     * container when the policy requires it (design §6, "on export stream-encrypt").
     *
     * [writeZip] receives the OutputStream to build the ZIP into. When encryption is
     * off (plaintext opt-out, or UI_DEFAULT with no passphrase) the ZIP is written
     * straight to [uri]. When on, the ZIP is first materialized to a cache temp file,
     * then [BackupCrypto.encryptStream]-ed into [uri] — the temp is deleted in a
     * `finally`. Streaming keeps peak memory bounded even for large media backups.
     */
    private fun writeZipEncryptingIfRequired(
        uri: Uri,
        contentType: Byte,
        enc: ExportEncryption,
        writeZip: (OutputStream) -> Unit,
    ) {
        // #156 F7: the passphrase was resolved ONCE by the calling export op ([enc]); we never
        // re-resolve here. [enc] owns the array's lifecycle and zeroes it in the caller's `finally`.
        val passphrase = enc.passphrase
        if (!enc.willEncrypt || passphrase == null) {
            // Plaintext path (opt-out, or UI_DEFAULT with no passphrase configured). The fail-closed
            // HEADLESS_MANDATORY-with-no-passphrase invariant is already enforced by
            // [resolveExportEncryption], so reaching here with willEncrypt=false is legitimate.
            openOutputStream(uri)?.use { out -> writeZip(out) }
            return
        }

        val tempZip = File(context.cacheDir, "ck_export_${System.currentTimeMillis()}.zip")
        try {
            FileOutputStream(tempZip).use { tmpOut -> writeZip(tmpOut) }
            openOutputStream(uri)?.use { out ->
                FileInputStream(tempZip).use { tmpIn ->
                    BackupCrypto.encryptStream(
                        tmpIn, out, passphrase, contentType, System.currentTimeMillis()
                    )
                }
            }
        } finally {
            tempZip.delete()
        }
    }

    /**
     * Open a plaintext ZIP [InputStream] for import, transparently decrypting a
     * `CKENC1` container to a cache temp file first (authenticate-then-parse). Returns
     * the stream plus an optional temp file the caller MUST delete in a `finally`
     * (null when the source was already plaintext). [expectedContentTypes] binds the
     * container to the import action. Throws [BackupDecryptException] on decrypt failure.
     */
    private fun openZipForImport(
        uri: Uri,
        expectedContentTypes: Set<Byte>,
    ): Pair<InputStream, File?> {
        // Open ONCE and peek the header via mark/reset so a non-re-openable content://
        // stream (and single-instance test mocks) isn't consumed by the sniff.
        val raw = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open ZIP file")
        val buffered = java.io.BufferedInputStream(raw, 64 * 1024)
        buffered.mark(EncryptedBackupFormat.HEADER_LEN + 8)
        val head = ByteArray(EncryptedBackupFormat.HEADER_LEN)
        var filled = 0
        while (filled < head.size) {
            val read = buffered.read(head, filled, head.size - filled)
            if (read < 0) break
            filled += read
        }
        buffered.reset()
        val headSlice = if (filled == head.size) head else head.copyOf(filled)

        val kind = EncryptedBackupFormat.sniff(headSlice)
        // Fail-closed policy gate on the SAME stream we're about to parse (TOCTOU fix).
        // Close the stream before throwing so a hostile content:// source can't leak it.
        try {
            enforceHeadlessEncryptionPolicy(kind)
        } catch (e: BackupDecryptException) {
            buffered.close()
            throw e
        }

        return if (kind == EncryptedBackupFormat.PayloadKind.ENCRYPTED) {
            val tempFile = buffered.use { decryptZipToTempFile(it, expectedContentTypes) }
            FileInputStream(tempFile) to tempFile
        } else {
            // Plaintext ZIP: hand back the SAME (reset) buffered stream so no bytes are lost.
            buffered to null
        }
    }

    /**
     * Thrown when an encrypted backup cannot be decrypted (wrong passphrase, tamper,
     * missing passphrase, header problem, or content-type mismatch). Callers map this
     * to the single user-facing message and guarantee no partial apply — the throw
     * happens before any parse/preview/write.
     */
    class BackupDecryptException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * Open an OutputStream for writing to a URI. Handles both content:// (SAF)
     * and file:// (Termux/automation) schemes.
     *
     * Fallback chain for file:// URIs on Android 11+ scoped storage:
     * 1. Direct FileOutputStream (works if user granted MANAGE_EXTERNAL_STORAGE)
     * 2. Downloads/ via MediaStore (no permissions needed, visible in file managers)
     * 3. App-private external dir (always writable, but hidden from most file managers)
     *
     * The actual output path is stored in [lastOutputPath] for caller feedback.
     */
    var lastOutputPath: String? = null
        private set

    private fun openOutputStream(uri: Uri): OutputStream? {
        lastOutputPath = null
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val fileName = File(path).name
            // Try 1: Direct write to requested path (works with MANAGE_EXTERNAL_STORAGE)
            return try {
                val file = File(path)
                file.parentFile?.mkdirs()
                FileOutputStream(file).also {
                    lastOutputPath = file.absolutePath
                }
            } catch (e: Exception) {
                // EPERM or SecurityException — scoped storage blocks /sdcard/ writes
                if (e.message?.contains("EPERM") != true &&
                    e !is SecurityException) throw e

                // Try 2: Downloads/ via MediaStore (visible to user, no permissions)
                writeToDownloads(fileName)
                    // Try 3: App-private external dir (always writable)
                    ?: writeToAppExternalDir(fileName)
            }
        }
        return context.contentResolver.openOutputStream(uri)
    }

    /**
     * Write to Downloads/ via MediaStore. Works on Android 10+ without permissions.
     * Files appear in Downloads folder in all file managers.
     * @return OutputStream or null if MediaStore insert fails
     */
    // The returned OutputStream is owned by the caller: openOutputStream() hands it up to
    // callers that always wrap it in `.use { }` (see lines ~318/325/698/1001/1222), so it is
    // closed exactly once. Lint can't see that cross-function ownership transfer.
    @SuppressLint("Recycle")
    private fun writeToDownloads(fileName: String): OutputStream? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                // Place in CleverKeys subfolder within Downloads
                put(MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/CleverKeys")
            }
            val contentUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            val stream = context.contentResolver.openOutputStream(contentUri)
            if (stream != null) {
                lastOutputPath = "${Environment.DIRECTORY_DOWNLOADS}/CleverKeys/$fileName"
                Log.i(TAG, "Writing to Downloads/CleverKeys/$fileName via MediaStore")
            }
            stream
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore Downloads write failed: ${e.message}")
            null
        }
    }

    /**
     * Last-resort fallback: write to app-private external dir.
     * Always writable but requires a file manager with Android/data/ access.
     */
    private fun writeToAppExternalDir(fileName: String): OutputStream {
        val extDir = context.getExternalFilesDir(null)
            ?: throw java.io.IOException("No external files directory available")
        val outputFile = File(extDir, fileName)
        Log.i(TAG, "Fallback write to app dir: ${outputFile.absolutePath}")
        lastOutputPath = outputFile.absolutePath
        return FileOutputStream(outputFile)
    }

    /**
     * Open an InputStream for reading from a URI. Same file:// handling as above.
     *
     * Search order for file:// URIs:
     * 1. Exact path via direct FileInputStream (try without canRead — FUSE may lie)
     * 2. Downloads/CleverKeys/ via direct FileInputStream
     * 3. Downloads/CleverKeys/ via MediaStore query (app's own exports)
     * 4. Force MediaStore scan + retry (handles cp/vim/external edits)
     * 5. Broader MediaStore query by filename only (any Downloads subfolder)
     * 6. App-private external dir (where exports land as last resort)
     *
     * #70: On Android 10+, scoped storage restricts file access to files the app
     * created. Files modified by external tools (cp, vim, etc.) get a new owner UID,
     * making them inaccessible via both File API and MediaStore without storage perms.
     */
    private fun openInputStream(uri: Uri): InputStream? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            val fileName = file.name

            // Try 1: Exact path — attempt direct read (FUSE canRead() may lie)
            try {
                return FileInputStream(file)
            } catch (e: Exception) {
                Log.d(TAG, "Direct read failed for $path: ${e.message}")
            }

            // Try 2: Downloads/CleverKeys/ direct
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val downloadsFile = File(downloadsDir, "CleverKeys/$fileName")
            if (downloadsFile != file) {
                try {
                    return FileInputStream(downloadsFile)
                } catch (e: Exception) {
                    Log.d(TAG, "Downloads dir read failed for $fileName: ${e.message}")
                }
            }

            // Try 3: MediaStore query by filename in Downloads/CleverKeys/ (app's own files)
            readFromDownloadsMediaStore(fileName, "%CleverKeys%")?.let { return it }

            // Try 4: Force MediaStore scan of the exact path, then retry query
            // This indexes files created/modified by external tools (cp, vim, etc.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                scanFileIntoMediaStore(path)
                readFromDownloadsMediaStore(fileName, "%CleverKeys%")?.let { return it }
            }

            // Try 5: Broader MediaStore query — any file with this name in Downloads/
            readFromDownloadsMediaStore(fileName, "%")?.let { return it }

            // Try 6: App-private external dir
            val extFile = File(context.getExternalFilesDir(null), fileName)
            try {
                return FileInputStream(extFile)
            } catch (e: Exception) {
                Log.d(TAG, "App-private dir read failed for $fileName: ${e.message}")
            }

            return null
        }
        return context.contentResolver.openInputStream(uri)
    }

    /**
     * Force MediaStore to scan a file path so it becomes queryable.
     * Synchronous: blocks up to 5 seconds for the scan to complete.
     * After scanning, the file entry is owned by the system (not this app),
     * so it may still be unreadable without storage permissions on Android 13+.
     */
    private fun scanFileIntoMediaStore(filePath: String) {
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(filePath), arrayOf("application/json")
            ) { scannedPath, scannedUri ->
                Log.i(TAG, "MediaScanner indexed: $scannedPath → $scannedUri")
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner scan failed for $filePath: ${e.message}")
        }
    }

    /**
     * Read from Downloads/ via MediaStore query. Finds files by display name
     * and optional relative path pattern.
     *
     * Note: On Android 10+ without storage permissions, MediaStore only returns
     * files owned by this app. Files created/copied by other processes (Termux, etc.)
     * may not be visible even after scanning.
     *
     * @param pathPattern LIKE pattern for RELATIVE_PATH (e.g., "%CleverKeys%" or "%")
     */
    private fun readFromDownloadsMediaStore(fileName: String, pathPattern: String): InputStream? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(fileName, pathPattern),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    val contentUri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    )
                    Log.i(TAG, "Reading $fileName via MediaStore (id=$id, path=$pathPattern)")
                    context.contentResolver.openInputStream(contentUri)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore Downloads read failed for $fileName: ${e.message}")
            null
        }
    }

    /**
     * Read entire JSON string from a URI. Throws IOException with a clear message
     * if the file cannot be read (e.g., scoped storage blocks access to files
     * created/modified by other apps like Termux).
     *
     * #70: On Android 10+, files copied/edited by external apps (cp, vim) become
     * owned by that app's UID. Without storage permissions, CleverKeys can only
     * read files it created. Use --es json_base64 intent extra as workaround.
     */
    fun readJsonFromUri(uri: Uri): String = readJsonFromUri(uri, expectedContentTypes = emptySet())

    /**
     * Read a settings/dictionary/clipboard JSON string from a URI, transparently
     * decrypting a `CKENC1` container first (design §6). The bytes are read fully,
     * sniffed, and:
     *  - [EncryptedBackupFormat.PayloadKind.ENCRYPTED] → decrypt+authenticate (before
     *    any parse), then UTF-8 decode. Callers (`buildSettingsImportPlan`,
     *    `buildDictImportPlan`, `importClipboardHistory`) receive the SAME JSON string
     *    they would for a plaintext file, so the pure diff/preview engine is untouched.
     *  - PLAINTEXT_JSON → today's behavior (UTF-8 string).
     *  - PLAINTEXT_ZIP / UNKNOWN → let the downstream JSON parser fail as before (a ZIP
     *    isn't valid single-file JSON; this seam is JSON-only).
     *
     * @param expectedContentTypes if non-empty, the decrypted container's content-type
     *   must be one of these (binds a settings backup to IMPORT_SETTINGS, etc.).
     */
    fun readJsonFromUri(uri: Uri, expectedContentTypes: Set<Byte>): String {
        val bytes = readAllBytesFromUri(uri)
        val kind = EncryptedBackupFormat.sniff(bytes)
        // Fail-closed policy gate on the SAME bytes we're about to parse (TOCTOU fix).
        enforceHeadlessEncryptionPolicy(kind)
        return when (kind) {
            EncryptedBackupFormat.PayloadKind.ENCRYPTED -> {
                // Cap enforced by BackupCrypto.decrypt (container-length guard).
                val payload = decryptContainer(bytes, expectedContentTypes)
                String(payload.bytes, Charsets.UTF_8)
            }
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    /** Read the entire contents of a URI as bytes, applying the scoped-storage fallbacks. */
    private fun readAllBytesFromUri(uri: Uri): ByteArray {
        val inputStream = openInputStream(uri)
            ?: throw java.io.IOException(
                "Cannot read file: ${uri.lastPathSegment ?: uri}\n\n" +
                "On Android 10+, files modified by external apps (cp, vim, etc.) " +
                "become inaccessible due to scoped storage restrictions.\n\n" +
                "Workarounds:\n" +
                "• Use the Import button in the UI (file picker grants access)\n" +
                "• Pass file content directly: --es json_base64 \"\$(base64 < file.json)\"\n" +
                "• Import the original exported file without modification"
            )
        return inputStream.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                // Cap the in-memory JSON read (types 1-3) — mirrors the crypto
                // substrate's guard so a giant plaintext JSON can't OOM either.
                if (total > BackupCrypto.MAX_IN_MEMORY_BYTES.toLong() + EncryptedBackupFormat.HEADER_LEN) {
                    throw java.io.IOException(
                        "Backup file exceeds ${BackupCrypto.MAX_IN_MEMORY_BYTES / (1024 * 1024)} MB " +
                            "in-memory limit — refusing to load."
                    )
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    /**
     * Export all preferences to a JSON file, including defaults for documentation.
     *
     * Returns the count of preferences written so the SAF picker flow
     * (`BackupRestoreActivity.performExport`) can surface a real number in
     * the success dialog. The legacy `Boolean` return is gone — every
     * non-throwing path now returns the count; failures throw.
     *
     * Marked `open` so [BackupRestoreActivityImportPreviewTest]'s hand-rolled
     * fake can stub the count without performing real IO.
     *
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     * @return number of preferences written (defaults + stored, internal keys excluded)
     */
    open fun exportConfig(uri: Uri, prefs: SharedPreferences): Int {
        try {
            val (root, count) = buildConfigJson(prefs)

            // Serialize, then encrypt-if-required (design §4/§5) before writing.
            // #156 F7: resolve the passphrase ONCE for this export op.
            val jsonBytes = gson.toJson(root).toByteArray(Charsets.UTF_8)
            val enc = resolveExportEncryption()
            try {
                val outBytes = encryptIfRequired(jsonBytes, EncryptedBackupFormat.SETTINGS_JSON, enc)
                openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(outBytes)
                    outputStream.flush()
                }
            } finally {
                enc.zero()
            }

            Log.i(TAG, "Exported $count preferences (${prefs.all.size} stored + defaults)")
            return count
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            throw Exception("Export failed: ${e.message}", e)
        }
    }

    /**
     * Build the config-export JSON tree without writing it anywhere. Pure helper
     * used by [exportConfig] and [exportFullBackup] to share serialization
     * (metadata + defaulted preferences + short-swipe customizations).
     *
     * Returns the root [JsonObject] and the preference count for caller telemetry.
     */
    private fun buildConfigJson(prefs: SharedPreferences): Pair<JsonObject, Int> {
        // Collect metadata
        val root = JsonObject()
        val metadata = JsonObject()

        // App version
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName
        val versionCode = packageInfo.versionCode

        metadata.addProperty("app_version", versionName)
        metadata.addProperty("version_code", versionCode)
        metadata.addProperty(
            "export_date",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        )

        // Screen dimensions
        val dm = context.resources.displayMetrics
        metadata.addProperty("screen_width", dm.widthPixels)
        metadata.addProperty("screen_height", dm.heightPixels)
        metadata.addProperty("screen_density", dm.density)
        metadata.addProperty("android_version", android.os.Build.VERSION.SDK_INT)

        root.add("metadata", metadata)

        // Get all defaults first, then override with stored preferences
        val allDefaults = getAllDefaultPreferences()
        val storedPrefs = prefs.all
        val preferences = JsonObject()

        // First add all defaults
        for ((key, value) in allDefaults) {
            if (!isInternalPreference(key)) {
                preferences.add(key, gson.toJsonTree(value))
            }
        }

        // Then override with stored preferences (these take precedence)
        for ((key, value) in storedPrefs) {
            // Preserve JSON-string preferences (layouts, extra_keys, custom_extra_keys)
            // These are already stored as JSON strings and should be preserved as-is
            when {
                isJsonStringPreference(key) && value is String -> {
                    try {
                        // Parse the JSON string and add as JsonElement to avoid double-encoding
                        preferences.add(key, JsonParser.parseString(value))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse JSON preference: $key", e)
                        // Fall back to regular serialization if parsing fails
                        preferences.add(key, gson.toJsonTree(value))
                    }
                }
                isInternalPreference(key) -> {
                    // Skip internal state preferences
                    Log.i(TAG, "Skipping internal preference on export: $key")
                }
                else -> {
                    preferences.add(key, gson.toJsonTree(value))
                }
            }
        }

        root.add("preferences", preferences)

        // Export short swipe customizations (stored in separate file, not SharedPreferences)
        try {
            runBlocking { shortSwipeManager.loadMappings() }
            val shortSwipeJson = shortSwipeManager.exportToJson()
            if (shortSwipeJson.isNotBlank() && shortSwipeJson != "{}") {
                root.add("short_swipe_customizations", JsonParser.parseString(shortSwipeJson))
                Log.i(TAG, "Exported short swipe customizations")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export short swipe customizations (non-fatal)", e)
        }

        return root to preferences.size()
    }

    /**
     * Returns the export-seed defaults map. The single source of truth is
     * `SETTINGS_DEFAULTS` in `backup/SettingsDefaults.kt` (typed
     * `Map<String, PrefValue>`). This function unwraps it to `Map<String, Any>`
     * for Gson's `toJsonTree`.
     *
     * History: this function used to be a 151-line literal map that parallel-
     * tracked SETTINGS_DEFAULTS. The two drifted — `getAllDefaultPreferences`
     * had 8 orphan entries (`enable_multilang`, `primary_language`,
     * `auto_detect_language`, `language_detection_sensitivity`,
     * `double_tap_lock_shift`, `autocorrect_min_frequency`,
     * `keyboard_height_percent`, `extra_key_switch_greekmath`) that no code
     * read; they polluted every backup file as "(unset) → default" noise
     * rows on import. Consolidated 2026-05-14 (see SettingsValidation.
     * DEPRECATED_KEYS for the filter that suppresses them in legacy
     * backups).
     */
    private fun getAllDefaultPreferences(): Map<String, Any> =
        SETTINGS_DEFAULTS.mapValues { (_, v) -> v.toExportableValue() }


    /**
     * Import preferences from JSON file with version-tolerant parsing
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return ImportResult with statistics
     */
    /**
     * Build a `SettingsImportPlan` for the given URI without applying any changes.
     * Reads the JSON, snapshots current prefs, and diffs to produce the plan that
     * the SAF-flow preview UI displays before the user accepts.
     */
    open fun buildSettingsImportPlan(uri: Uri, prefs: SharedPreferences): SettingsImportPlan {
        val jsonString = readJsonFromUri(uri, setOf(EncryptedBackupFormat.SETTINGS_JSON))
        val snapshot: Map<String, Any?> = prefs.all.toMap()
        val dm = context.resources.displayMetrics
        val screen = ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.density)
        // Snapshot the current short-swipe state so the preview dialog can
        // render a structured diff against the import's short-swipe section.
        // loadMappings() is suspend; we runBlocking on the IO dispatcher this
        // is already on (called via withContext(Dispatchers.IO) by the SAF
        // pathway). Failure tolerated — null disables the diff section.
        val currentShortSwipeJson: String? = try {
            runBlocking { shortSwipeManager.loadMappings() }
            shortSwipeManager.exportToJson()
        } catch (e: Exception) {
            Log.w(TAG, "Short-swipe snapshot failed; preview will skip diff section", e)
            null
        }
        // SETTINGS_DEFAULTS suppresses preview rows where the proposed value
        // equals the compile-time default the user already experiences on
        // unset keys (fresh-install over-report fix, 2026-05-14).
        return SettingsImportPlanBuilder.fromJson(
            jsonString,
            currentSnapshot = snapshot,
            screen = screen,
            defaultSnapshot = SETTINGS_DEFAULTS,
            currentShortSwipeRawJson = currentShortSwipeJson,
        )
    }

    /**
     * Apply a previously-built `SettingsImportPlan` against the current prefs.
     * Thin delegator to `SettingsImportApplier.apply` — owns the editor + commit
     * lifecycle and short-swipe importer routing centrally.
     */
    fun applySettingsImportPlan(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
        prefs: SharedPreferences,
    ): ImportResult = runBlocking {
        val result = SettingsImportApplier.apply(
            plan, excludedKeys, shortSwipeMode, prefs, shortSwipeImporter
        )
        // #156 F5: an import can write `clipboard_private_copy_toolbar_enabled` (a normal exportable
        // key) WITHOUT flipping the manifest-disabled PrivateCopyProcessTextActivity component. The
        // Settings-load path reconciles it, but the headless (Termux-intent) restore never re-enters
        // loadCurrentSettings — so reconcile here, at the manager apply seam, covering EVERY import
        // entry point (headless + inline UI). Idempotent-guarded in the reconciler, so the UI path's
        // subsequent loadCurrentSettings() call is a harmless no-op.
        reconcilePrivateCopyToolbarFromPrefs(prefs)
        result
    }

    /**
     * #156 F5: derive the private-copy toolbar component's OS-enabled state from the (freshly-imported)
     * `clipboard_private_copy_toolbar_enabled` pref value. Best-effort — a component-reconcile failure
     * (or a headless context without a package manager in tests) must never abort an import.
     */
    private fun reconcilePrivateCopyToolbarFromPrefs(prefs: SharedPreferences) {
        try {
            val enabled = prefs.getBoolean(PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED, false)
            reconcilePrivateCopyToolbarComponent(context, enabled)
        } catch (e: Exception) {
            Log.w(TAG, "Private-copy toolbar reconcile after import failed: ${e.message}")
        }
    }

    /**
     * Legacy headless entry point. Termux automation callers depend on this
     * signature and the destructive `merge=false` short-swipe semantics — both
     * are preserved by routing through `applySettingsImportPlan` with
     * `ShortSwipeImportMode.REPLACE`. The SAF-flow UI uses MERGE by default
     * (see `BackupRestoreActivity.applyPlannedSettings`); flipping the headless
     * default is intentionally out of scope (tracked in `memory/todo.md`).
     */
    fun importConfig(uri: Uri, prefs: SharedPreferences): ImportResult {
        return try {
            val plan = buildSettingsImportPlan(uri, prefs)
            applySettingsImportPlan(plan, emptySet(), ShortSwipeImportMode.REPLACE, prefs)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            throw Exception("Import failed: ${e.message}", e)
        }
    }

    /**
     * Check if a preference stores data as a JSON string
     * These preferences use ListGroupPreference which stores data as JSON-encoded strings
     */
    private fun isJsonStringPreference(key: String): Boolean {
        return when (key) {
            // LayoutsPreference - stores List<Layout> as JSON string
            "layouts",
            // ExtraKeysPreference - stores Map<KeyValue, PreferredPos> as JSON string
            "extra_keys",
            // CustomExtraKeysPreference - stores Map<KeyValue, PreferredPos> as JSON string
            "custom_extra_keys" -> true
            else -> false
        }
    }

    /**
     * Check if a preference is internal state that shouldn't be exported/imported.
     * Delegates to SettingsValidation — single source of truth.
     */
    private fun isInternalPreference(key: String): Boolean =
        SettingsValidation.isInternalPreference(key)

    /**
     * Result of import operation
     */
    data class ImportResult(
        @JvmField var importedCount: Int = 0,
        @JvmField var skippedCount: Int = 0,
        @JvmField var excludedByUserCount: Int = 0,    // NEW: user-deselected in preview
        @JvmField var driftCount: Int = 0,              // NEW: changed between build and apply
        @JvmField var sourceVersion: String = "unknown",
        @JvmField var sourceScreenWidth: Int = 0,
        @JvmField var sourceScreenHeight: Int = 0,
        @JvmField var currentScreenWidth: Int = 0,
        @JvmField var currentScreenHeight: Int = 0,
        @JvmField val importedKeys: MutableSet<String> = mutableSetOf(),
        @JvmField val skippedKeys: MutableSet<String> = mutableSetOf(),
        @JvmField var shortSwipeCustomizationsImported: Int = 0
    ) {
        fun hasScreenSizeMismatch(): Boolean {
            if (sourceScreenWidth == 0 || sourceScreenHeight == 0)
                return false // No source dimensions available

            val widthDiff = abs(currentScreenWidth - sourceScreenWidth)
            val heightDiff = abs(currentScreenHeight - sourceScreenHeight)

            // Consider it a mismatch if either dimension differs by more than 20%
            return (widthDiff > currentScreenWidth * 0.2) ||
                (heightDiff > currentScreenHeight * 0.2)
        }
    }

    /**
     * Counts surfaced from a successful [exportDictionaries] call so the
     * SAF picker flow can render real numbers in the success dialog.
     */
    data class DictionaryExportSummary(
        @JvmField val customWordsCount: Int,
        @JvmField val disabledWordsCount: Int,
        @JvmField val languageCount: Int,
    )

    /**
     * Export user dictionaries to JSON file.
     *
     * Returns a [DictionaryExportSummary] with the actual counts, replacing
     * the legacy `Unit` return. Existing callers that ignored the return
     * value continue to compile unchanged.
     *
     * v1.1.88: Exports in language-specific format (custom_words_${lang}, disabled_words_${lang})
     * Also includes legacy format for backwards compatibility with older app versions.
     *
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     */
    fun exportDictionaries(uri: Uri): DictionaryExportSummary {
        try {
            val (root, summary) = buildDictionariesJson()

            // #156 F7: resolve the passphrase ONCE for this export op.
            val jsonBytes = gson.toJson(root).toByteArray(Charsets.UTF_8)
            val enc = resolveExportEncryption()
            try {
                val outBytes = encryptIfRequired(jsonBytes, EncryptedBackupFormat.DICTIONARIES_JSON, enc)
                openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(outBytes)
                    outputStream.flush()
                }
            } finally {
                enc.zero()
            }

            Log.i(TAG, "Exported dictionaries: ${summary.customWordsCount} custom + " +
                "${summary.disabledWordsCount} disabled across ${summary.languageCount} languages")
            return summary
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary export failed", e)
            throw Exception("Dictionary export failed: ${e.message}", e)
        }
    }

    /**
     * Build the dictionary-export JSON tree without writing it anywhere. Pure
     * helper shared by [exportDictionaries] and [exportFullBackup]. Returns the
     * root [JsonObject] plus a [DictionaryExportSummary] for caller telemetry.
     */
    private fun buildDictionariesJson(): Pair<JsonObject, DictionaryExportSummary> {
        val languagesWithData = mutableSetOf<String>()
        var totalCustomWords = 0
        var totalDisabledWords = 0

        val root = JsonObject()
        val metadata = JsonObject()

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        metadata.addProperty("app_version", packageInfo.versionName)
        metadata.addProperty("export_date",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        metadata.addProperty("type", "dictionaries")
        metadata.addProperty("format_version", 2) // v2 = language-specific format
        root.add("metadata", metadata)

        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

        // Run migration first to ensure all words are in new format
        LanguagePreferenceKeys.migrateToLanguageSpecific(prefs)
        // NOTE: Legacy user_dictionary migration is handled by DictionaryManager.migrateLegacyCustomWords()

        // Export custom words per language (new format)
        val customWordsPerLang = JsonObject()
        val languages = LanguagePreferenceKeys.getLanguagesWithCustomWords(prefs)

        for (lang in languages) {
            val langKey = LanguagePreferenceKeys.customWordsKey(lang)
            val wordsJson = prefs.getString(langKey, "{}")
            if (wordsJson != null && wordsJson != "{}") {
                customWordsPerLang.add(lang, JsonParser.parseString(wordsJson))
                // Count words for logging + summary
                try {
                    val wordsMap = JsonParser.parseString(wordsJson).asJsonObject
                    if (wordsMap.size() > 0) {
                        totalCustomWords += wordsMap.size()
                        languagesWithData += lang
                    }
                } catch (e: Exception) { /* ignore count errors */ }
            }
        }
        root.add("custom_words_by_language", customWordsPerLang)

        // Export disabled words per language (new format)
        val disabledWordsPerLang = JsonObject()
        val disabledLanguages = LanguagePreferenceKeys.getLanguagesWithDisabledWords(prefs)

        for (lang in disabledLanguages) {
            val langKey = LanguagePreferenceKeys.disabledWordsKey(lang)
            val wordsSet = prefs.getStringSet(langKey, emptySet()) ?: emptySet()
            if (wordsSet.isNotEmpty()) {
                val wordsArray = JsonArray()
                for (word in wordsSet) {
                    wordsArray.add(word)
                }
                disabledWordsPerLang.add(lang, wordsArray)
                totalDisabledWords += wordsSet.size
                languagesWithData += lang
            }
        }
        root.add("disabled_words_by_language", disabledWordsPerLang)

        // Also export in legacy format for backwards compatibility
        // Use English words if available, otherwise empty
        val enCustomWordsJson = prefs.getString(LanguagePreferenceKeys.customWordsKey("en"), "{}")
        if (enCustomWordsJson != null && enCustomWordsJson != "{}") {
            try {
                val enWordsMap = JsonParser.parseString(enCustomWordsJson).asJsonObject
                val userWords = JsonArray()
                for ((word, freq) in enWordsMap.entrySet()) {
                    val wordObj = JsonObject()
                    wordObj.addProperty("word", word)
                    wordObj.addProperty("frequency", freq.asInt)
                    userWords.add(wordObj)
                }
                root.add("user_words", userWords) // Legacy format
            } catch (e: Exception) {
                Log.w(TAG, "Failed to export legacy format", e)
            }
        }

        val enDisabledWords = prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey("en"), emptySet()) ?: emptySet()
        val disabledWords = JsonArray()
        for (word in enDisabledWords) {
            disabledWords.add(word)
        }
        root.add("disabled_words", disabledWords) // Legacy format

        // Learned data (audit 2026-08-06 §3.2-6: backup blind spot) — the context-LM
        // bigrams and personalization vocabulary now ride the standard dictionaries
        // payload so a device migration keeps everything the keyboard has learned.
        try {
            val bigramStore = tribixbite.cleverkeys.contextaware.BigramStore.getInstance(context)
            bigramStore.flush() // checkpoint any debounced in-RAM learning first
            val learnedBigrams = JsonObject()
            for (lang in bigramStore.getKnownLanguages().sorted()) {
                val arr = JsonParser.parseString(bigramStore.exportToJson(lang))
                if (arr.isJsonArray && arr.asJsonArray.size() > 0) {
                    learnedBigrams.add(lang, arr)
                }
            }
            root.add("learned_bigrams_by_language", learnedBigrams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export learned bigrams", e)
        }
        try {
            val vocabulary = tribixbite.cleverkeys.personalization.UserVocabulary.getInstance(context)
            vocabulary.flush()
            val vocabJson = JsonParser.parseString(vocabulary.exportToJson())
            if (vocabJson.isJsonArray) {
                root.add("user_vocabulary", vocabJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export user vocabulary", e)
        }

        val summary = DictionaryExportSummary(totalCustomWords, totalDisabledWords, languagesWithData.size)
        return root to summary
    }

    /**
     * Import the learned-data sections of a dictionaries payload
     * (`learned_bigrams_by_language` + `user_vocabulary`, exported by
     * [buildDictionariesJson]). Bigrams merge into the existing store
     * (frequencies add); the user vocabulary is REPLACED (the documented
     * semantics of `UserVocabulary.importFromJson`).
     *
     * Absent keys (older backups) are a silent no-op.
     *
     * @return (bigram entry count imported, vocabulary word count imported)
     */
    private fun importLearnedDataFromJson(jsonString: String): Pair<Int, Int> {
        var bigramEntries = 0
        var vocabularyWords = 0
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject

            if (root.has("learned_bigrams_by_language")) {
                val byLang = root.getAsJsonObject("learned_bigrams_by_language")
                val store = tribixbite.cleverkeys.contextaware.BigramStore.getInstance(context)
                for ((lang, element) in byLang.entrySet()) {
                    if (!element.isJsonArray) continue
                    store.importFromJson(lang, element.toString())
                    bigramEntries += element.asJsonArray.size()
                }
            }

            if (root.has("user_vocabulary") && root.get("user_vocabulary").isJsonArray) {
                vocabularyWords = tribixbite.cleverkeys.personalization.UserVocabulary
                    .getInstance(context)
                    .importFromJson(root.getAsJsonArray("user_vocabulary").toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Learned-data import skipped (invalid or absent sections)", e)
        }
        if (bigramEntries > 0 || vocabularyWords > 0) {
            Log.i(TAG, "Imported learned data: $bigramEntries bigram entries, $vocabularyWords vocabulary words")
        }
        return bigramEntries to vocabularyWords
    }

    /**
     * Build a `DictImportPlan` for the given URI without applying any changes.
     * Pure IO + delegation to the pure planner — UI calls this on a background
     * thread to populate the preview dialog before the user confirms.
     */
    fun buildDictImportPlan(uri: Uri, prefs: SharedPreferences): DictImportPlan {
        val jsonString = readJsonFromUri(uri, setOf(EncryptedBackupFormat.DICTIONARIES_JSON))
        val currentCustom = readCurrentCustomWordsByLang(prefs)
        val currentDisabled = readCurrentDisabledWordsByLang(prefs)
        return DictImportPlanBuilder.fromJson(jsonString, currentCustom, currentDisabled)
    }

    /**
     * Apply a previously-built `DictImportPlan` against the current prefs.
     * Thin delegator to `DictImportApplier.apply` — returns a populated
     * `DictionaryImportResult` for caller telemetry/UI display.
     *
     * Atomicity: a single `editor.commit()` covers all languages — see
     * DictImportApplier.apply contract.
     */
    fun applyDictImportPlan(
        plan: DictImportPlan,
        excludedCustom: Set<LangWord>,
        excludedDisabled: Set<LangWord>,
        prefs: SharedPreferences,
    ): DictionaryImportResult {
        val (customApplied, disabledApplied) = DictImportApplier.apply(
            plan, excludedCustom, excludedDisabled, prefs
        )
        return DictionaryImportResult().apply {
            sourceVersion = plan.sourceVersion
            userWordsImported = customApplied
            disabledWordsImported = disabledApplied
            excludedByUserCount = excludedCustom.size + excludedDisabled.size
        }
    }

    /**
     * Read the user's current per-language custom words from prefs.
     * Scans for keys matching `custom_words_<lang>` via reverse helper.
     */
    private fun readCurrentCustomWordsByLang(prefs: SharedPreferences): Map<String, Map<String, Int>> {
        val out = mutableMapOf<String, Map<String, Int>>()
        val gson = Gson()
        val mapType = object : com.google.gson.reflect.TypeToken<Map<String, Int>>() {}.type
        for ((key, value) in prefs.all) {
            val lang = LanguagePreferenceKeys.languageFromCustomWordsKey(key) ?: continue
            if (value !is String) continue
            try {
                out[lang] = gson.fromJson(value, mapType) ?: emptyMap()
            } catch (_: Exception) { /* skip malformed */ }
        }
        return out
    }

    /**
     * Read the user's current per-language disabled words from prefs.
     */
    private fun readCurrentDisabledWordsByLang(prefs: SharedPreferences): Map<String, Set<String>> {
        val out = mutableMapOf<String, Set<String>>()
        for ((key, value) in prefs.all) {
            val lang = LanguagePreferenceKeys.languageFromDisabledWordsKey(key) ?: continue
            @Suppress("UNCHECKED_CAST")
            if (value is Set<*>) out[lang] = value as Set<String>
        }
        return out
    }

    /**
     * Import user dictionaries from JSON file
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return DictionaryImportResult with statistics
     *
     * v1.1.88: Supports both old format (user_words, disabled_words) and new language-specific format
     * (custom_words_by_language, disabled_words_by_language). Old format is automatically migrated
     * to English language-specific keys.
     */
    fun importDictionaries(uri: Uri): DictionaryImportResult {
        return try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            // Read once, feed both the word-plan machinery and the learned-data importer.
            val jsonString = readJsonFromUri(uri, setOf(EncryptedBackupFormat.DICTIONARIES_JSON))
            val currentCustom = readCurrentCustomWordsByLang(prefs)
            val currentDisabled = readCurrentDisabledWordsByLang(prefs)
            val plan = DictImportPlanBuilder.fromJson(jsonString, currentCustom, currentDisabled)
            val result = applyDictImportPlan(plan, emptySet(), emptySet(), prefs)

            // Learned data (context-LM bigrams + user vocabulary) — audit 2026-08-06 §3.2-6.
            // TODO: surface learned-data counts in the interactive import-preview dialog
            // (DictImportPlan currently only models custom/disabled words).
            val (bigrams, vocab) = importLearnedDataFromJson(jsonString)
            result.learnedBigramsImported = bigrams
            result.learnedVocabularyImported = vocab

            Log.i(TAG, "Imported dictionaries: ${result.userWordsImported} custom words, ${result.disabledWordsImported} disabled words")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary import failed", e)
            throw Exception("Dictionary import failed: ${e.message}", e)
        }
    }

    /**
     * Export clipboard history to JSON file (text-only, lightweight).
     * Media entries are skipped — use exportClipboardHistoryZip for full backup.
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     * @return ClipboardExportResult with statistics
     */
    fun exportClipboardHistory(uri: Uri): ClipboardExportResult {
        // #156 F7: resolve the passphrase ONCE; willEncrypt gates includePrivate AND the encryption.
        val enc = resolveExportEncryption()
        try {
            val clipboardDb = ClipboardDatabase.getInstance(context)
            // #156 option B: include private entries only when the output is genuinely encrypted.
            val exportData = clipboardDb.exportToJSON(textOnly = true, includePrivate = enc.willEncrypt)
                ?: throw Exception("Failed to export clipboard data")

            val jsonBytes = exportData.toString(2).toByteArray(Charsets.UTF_8)
            val outBytes = encryptIfRequired(jsonBytes, EncryptedBackupFormat.CLIPBOARD_JSON, enc)
            openOutputStream(uri)?.use { outputStream ->
                outputStream.write(outBytes)
                outputStream.flush()
            }

            val activeCount = exportData.optInt("total_active", 0)
            val pinnedCount = exportData.optInt("total_pinned", 0)
            val todoCount = exportData.optInt("total_todo", 0)
            val mediaSkipped = exportData.optInt("media_skipped", 0)
            val privateSkipped = exportData.optInt("private_skipped", 0)

            Log.i(TAG, "Exported clipboard (text-only): $activeCount active, $pinnedCount pinned, $todoCount todo, $mediaSkipped media skipped, $privateSkipped private excluded")
            return ClipboardExportResult(activeCount + pinnedCount + todoCount, mediaSkipped, privateSkipped = privateSkipped)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard export failed", e)
            throw Exception("Clipboard export failed: ${e.message}", e)
        } finally {
            enc.zero()
        }
    }

    /**
     * Export clipboard history to ZIP file (full backup with media files).
     * ZIP contains: clipboard_data.json + clipboard_media/{files}
     * Streams media files directly — never loads all into memory (OOM safe).
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     * @return ClipboardExportResult with statistics
     */
    fun exportClipboardHistoryZip(uri: Uri): ClipboardExportResult {
        // #156 F7: resolve the passphrase ONCE; willEncrypt gates includePrivate AND encryption.
        val enc = resolveExportEncryption()
        try {
            val clipboardDb = ClipboardDatabase.getInstance(context)
            // Export JSON manifest with all entries including media metadata.
            // #156 option B: include private entries only when the ZIP is genuinely encrypted.
            val exportData = clipboardDb.exportToJSON(textOnly = false, includePrivate = enc.willEncrypt)
                ?: throw Exception("Failed to export clipboard data")

            val mediaManager = ClipboardMediaManager(context)
            var mediaFileCount = 0

            writeZipEncryptingIfRequired(uri, EncryptedBackupFormat.CLIPBOARD_ZIP, enc) { outputStream ->
                java.util.zip.ZipOutputStream(outputStream).use { zipOut ->
                    // Write JSON manifest as first entry
                    val jsonEntry = java.util.zip.ZipEntry("clipboard_data.json")
                    zipOut.putNextEntry(jsonEntry)
                    zipOut.write(exportData.toString(2).toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()

                    // Collect unique media paths and stream files into ZIP.
                    // #156 F6: in a PLAINTEXT export (!willEncrypt) exclude files referenced ONLY by
                    // private rows, mirroring exportToJSON's row filter — otherwise a private media
                    // row's raw bytes would land in the unencrypted ZIP even though the manifest
                    // dropped its entry (leak reachable via sticky-dedup flipping is_private on media).
                    val mediaPaths = clipboardDb.getReferencedMediaPaths(includePrivate = enc.willEncrypt)
                    for (mediaPath in mediaPaths) {
                        val file = mediaManager.getMediaFile(mediaPath)
                        if (!file.exists()) {
                            Log.w(TAG, "Media file not found during export, skipping: $mediaPath")
                            continue
                        }
                        // mediaPath already contains "clipboard_media/" prefix from DB
                        val zipMediaEntry = java.util.zip.ZipEntry(mediaPath)
                        zipOut.putNextEntry(zipMediaEntry)
                        file.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        mediaFileCount++
                    }
                }
            }

            val activeCount = exportData.optInt("total_active", 0)
            val pinnedCount = exportData.optInt("total_pinned", 0)
            val todoCount = exportData.optInt("total_todo", 0)
            val privateSkipped = exportData.optInt("private_skipped", 0)

            Log.i(TAG, "Exported clipboard ZIP: $activeCount active, $pinnedCount pinned, $todoCount todo, $mediaFileCount media files, $privateSkipped private excluded")
            return ClipboardExportResult(activeCount + pinnedCount + todoCount, 0, mediaFileCount, privateSkipped)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard ZIP export failed", e)
            throw Exception("Clipboard ZIP export failed: ${e.message}", e)
        } finally {
            enc.zero()
        }
    }

    /**
     * Import clipboard history from ZIP file (full backup with media files).
     * Extracts media files to internal storage, regenerates thumbnails, imports JSON.
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return ClipboardImportResult with statistics
     */
    fun importClipboardHistoryZip(uri: Uri): ClipboardImportResult {
        // Transparently decrypt a CKENC1 container to a verified temp file first
        // (authenticate-then-parse) — decryptTemp is deleted in the finally.
        var decryptTemp: File? = null
        var stagingDir: File? = null
        var mediaCommit: MediaCommit? = null
        return try {
            val clipboardDb = ClipboardDatabase.getInstance(context)
            val mediaManager = ClipboardMediaManager(context)
            val stage = createImportStagingDir().also { stagingDir = it }
            val stagedMedia = ArrayList<StagedMedia>()
            val seenEntries = HashSet<String>()
            val budget = ImportBudget(importLimits.importTotalBytes)

            val (zipStream, temp) = openZipForImport(uri, setOf(EncryptedBackupFormat.CLIPBOARD_ZIP))
            decryptTemp = temp
            // Parse the complete untrusted archive into an isolated cache directory. No live
            // DB/media mutation is permitted before the manifest and every size/path/duplicate
            // check succeeds.
            zipStream.use { inputStream ->
                java.util.zip.ZipInputStream(inputStream).use { zipIn ->
                    var jsonData: org.json.JSONObject? = null
                    var entryCount = 0
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        // Entry-count cap: reject archives with an implausible number of
                        // entries (per-entry work × count DoS) before processing this one.
                        if (++entryCount > MAX_IMPORT_ENTRIES) {
                            throw java.io.IOException(
                                "Backup ZIP has more than $MAX_IMPORT_ENTRIES entries — refusing to import."
                            )
                        }
                        // CK-150-021: every named member participates in the duplicate guard,
                        // directory entries included — exempting them let an archive repeat a
                        // name unbounded.
                        if (!seenEntries.add(entry.name)) {
                            throw java.io.IOException(
                                "Backup ZIP contains duplicate entry '${entry.name}'"
                            )
                        }
                        when {
                            entry.name == "clipboard_data.json" -> {
                                // Read JSON manifest (bounded — zip-bomb defense)
                                val jsonBytes = readBoundedBytes(zipIn, budget = budget)
                                jsonData = org.json.JSONObject(String(jsonBytes, Charsets.UTF_8))
                            }
                            entry.name.startsWith("clipboard_media/") -> {
                                // CK-150-021: a directory member ("clipboard_media/x/") carries no
                                // payload. Staging one produced an empty file that the commit then
                                // copied OVER the live directory of the same name. Skip it — the
                                // real media entries below it create their own parent directories.
                                if (entry.isDirectory) {
                                    Log.d(TAG, "Skipping media directory entry: ${entry.name}")
                                } else {
                                    // Unsafe paths abort the archive rather than silently producing
                                    // a partially-restored backup whose JSON references missing media.
                                    stagedMedia.add(
                                        stageMediaEntry(
                                            zipIn, entry.name, mediaManager, stage,
                                            stagedMedia.size, budget,
                                        )
                                    )
                                }
                            }
                            else -> drainBoundedEntry(zipIn, entry.name, budget)
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }

                    if (jsonData == null) {
                        throw Exception("ZIP does not contain clipboard_data.json manifest")
                    }

                    // Commit staged media reversibly, then run the transactional DB import. A DB
                    // failure restores every pre-existing media file and removes every new one.
                    mediaCommit = commitStagedMedia(stagedMedia, mediaManager, stage)
                    val importResult = try {
                        clipboardDb.importFromJSON(jsonData)
                    } catch (e: Exception) {
                        mediaCommit?.rollback()
                        mediaCommit = null
                        throw e
                    }
                    val mediaFilesRestored = stagedMedia.size
                    mediaCommit?.finish()
                    mediaCommit = null
                    stagingDir = null

                    // Regenerate thumbnails for imported media entries
                    val referencedPaths = clipboardDb.getAllReferencedMediaPaths()
                    var thumbnailsRegenerated = 0
                    try {
                        for (path in referencedPaths) {
                            val file = mediaManager.getMediaFile(path)
                            if (!file.exists()) continue
                            val ext = file.extension.lowercase()
                            val mimeType = when (ext) {
                                "jpg", "jpeg" -> "image/jpeg"
                                "png" -> "image/png"
                                "webp" -> "image/webp"
                                "gif" -> "image/gif"
                                "mp4" -> "video/mp4"
                                "pdf" -> "application/pdf"
                                else -> "application/octet-stream"
                            }
                            val thumbnail = mediaManager.generateThumbnail(file.absolutePath, mimeType)
                            if (thumbnail != null) {
                                updateThumbnailForMediaPath(clipboardDb, path, thumbnail)
                                thumbnailsRegenerated++
                            }
                        }
                        mediaManager.cleanupOrphans(referencedPaths)
                    } catch (e: Exception) {
                        // Import is already committed. Thumbnail/orphan housekeeping is repairable
                        // and must not turn a successful transactional import into a false failure.
                        Log.w(TAG, "Post-import media housekeeping failed: ${e.message}")
                    }
                    Log.d(TAG, "Regenerated $thumbnailsRegenerated thumbnails after ZIP import")

                    val result = ClipboardImportResult()
                    result.importedCount = importResult[0] + importResult[1] + importResult[2]
                    result.skippedCount = importResult[3]
                    result.mediaFilesRestored = mediaFilesRestored
                    if (jsonData.has("export_date")) {
                        result.sourceVersion = jsonData.getString("export_date")
                    }
                    result
                }
            }
        } catch (e: BackupDecryptException) {
            // Preserve the decrypt-specific message (wrong password / tamper) verbatim.
            Log.e(TAG, "Clipboard ZIP import decrypt failed", e)
            throw e
        } catch (e: Exception) {
            mediaCommit?.rollback()
            mediaCommit = null
            Log.e(TAG, "Clipboard ZIP import failed", e)
            throw Exception("Clipboard ZIP import failed: ${e.message}", e)
        } finally {
            stagingDir?.deleteRecursively()
            decryptTemp?.delete()
        }
    }

    /** Update thumbnail_blob for all rows referencing a given media_path across all tables */
    private fun updateThumbnailForMediaPath(db: ClipboardDatabase, mediaPath: String, thumbnail: ByteArray) {
        try {
            val sqliteDb = db.writableDatabase
            val values = android.content.ContentValues().apply {
                put("thumbnail_blob", thumbnail)
            }
            for (table in listOf("clipboard_entries", "pinned_entries", "todo_entries")) {
                sqliteDb.update(table, values, "media_path = ?", arrayOf(mediaPath))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update thumbnail for $mediaPath: ${e.message}")
        }
    }

    /**
     * Import clipboard history from JSON file
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return ClipboardImportResult with statistics
     */
    fun importClipboardHistory(uri: Uri): ClipboardImportResult {
        return try {
            // #70: Read JSON with scoped-storage fallbacks (+ transparent decrypt).
            val jsonContent = readJsonFromUri(uri, setOf(EncryptedBackupFormat.CLIPBOARD_JSON))
            val importData = org.json.JSONObject(jsonContent)
            val clipboardDb = ClipboardDatabase.getInstance(context)
            // CK-150-019: importFromJSON now propagates DB failures instead of returning
            // partial counts. There is no media commit on this path, so the only work is to
            // attribute the failure before it converts into this function's failure shape
            // (the wrapped rethrow in the catch below).
            val importResult = try {
                clipboardDb.importFromJSON(importData)
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard JSON import failed while writing the database", e)
                throw e
            }

            // importResult = [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped]
            val result = ClipboardImportResult()
            result.importedCount = importResult[0] + importResult[1] + importResult[2]  // active + pinned + todo
            result.skippedCount = importResult[3]  // duplicates skipped

            if (importData.has("export_date")) {
                result.sourceVersion = importData.getString("export_date")
            }

            Log.i(TAG, "Imported clipboard history: ${result.importedCount} imported, ${result.skippedCount} skipped")
            result
        } catch (e: BackupDecryptException) {
            // Preserve the decrypt-specific type so the UI can prompt for a passphrase.
            Log.e(TAG, "Clipboard import decrypt failed", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard import failed", e)
            throw Exception("Clipboard import failed: ${e.message}", e)
        }
    }

    /**
     * Result of dictionary import operation
     */
    data class DictionaryImportResult(
        @JvmField var userWordsImported: Int = 0,
        @JvmField var disabledWordsImported: Int = 0,
        @JvmField var sourceVersion: String = "unknown",
        @JvmField var excludedByUserCount: Int = 0,    // NEW: user-deselected in preview
        @JvmField var learnedBigramsImported: Int = 0,     // context-LM entries (2026-08-06)
        @JvmField var learnedVocabularyImported: Int = 0,  // personalization words (2026-08-06)
    )

    /**
     * Result of clipboard import operation
     */
    data class ClipboardImportResult(
        @JvmField var importedCount: Int = 0,
        @JvmField var skippedCount: Int = 0,
        @JvmField var sourceVersion: String = "unknown",
        @JvmField var mediaFilesRestored: Int = 0
    )

    /**
     * Result of clipboard export operation
     */
    data class ClipboardExportResult(
        @JvmField var exportedCount: Int = 0,
        @JvmField var mediaSkipped: Int = 0,
        @JvmField var mediaFilesIncluded: Int = 0,
        // #156: private entries omitted from a plaintext export (0 when the export was encrypted).
        @JvmField var privateSkipped: Int = 0
    )

    /**
     * Result of a full backup ZIP export. Tracks which sections were included so
     * the UI can render an accurate success dialog (and so tests can assert
     * coverage). [totalBytes] is best-effort; it counts bytes streamed to the
     * ZIP, not the compressed file size on disk.
     *
     * GitHub #142: one-click full backup containing manifest + config + dicts +
     * clipboard JSON + clipboard media files in a single dated ZIP.
     */
    data class FullBackupResult(
        @JvmField val success: Boolean,
        @JvmField val configIncluded: Boolean,
        @JvmField val dictionaryCount: Int,
        @JvmField val clipboardEntryCount: Int,
        @JvmField val mediaFileCount: Int,
        @JvmField val errorMessage: String? = null,
        @JvmField val totalBytes: Long = 0,
        // #156 F2: private clipboard entries dropped from a PLAINTEXT full backup (0 when encrypted).
        // Surfaced so the success dialog warns the user their private entries were NOT included,
        // matching the clipboard-only export paths — otherwise a plaintext full backup silently
        // loses privately-copied entries and the user only discovers it after wiping the device.
        @JvmField val privateSkipped: Int = 0,
    )

    /**
     * Result of a full backup ZIP import. Aggregates per-section counts from
     * the existing [SettingsImportApplier], [DictImportApplier], and
     * [ClipboardDatabase.importFromJSON] outputs.
     */
    data class FullBackupImportResult(
        @JvmField val success: Boolean,
        @JvmField val configImported: Boolean,
        @JvmField val configKeysApplied: Int,
        @JvmField val customWordsImported: Int,
        @JvmField val disabledWordsImported: Int,
        @JvmField val clipboardEntriesImported: Int,
        @JvmField val clipboardEntriesSkipped: Int,
        @JvmField val mediaFilesRestored: Int,
        @JvmField val sourceAppVersion: String? = null,
        @JvmField val errorMessage: String? = null,
    )

    /**
     * Export EVERYTHING in one dated ZIP file (GitHub #142). The ZIP layout is:
     *
     *   manifest.json          — top-level metadata (app version, export date,
     *                            section inventory)
     *   config.json            — same JSON that [exportConfig] produces
     *   dictionaries.json      — same JSON that [exportDictionaries] produces
     *   clipboard_history.json — same JSON that [exportClipboardHistory] produces
     *                            (textOnly = false so media references stay)
     *   clipboard_media/...    — media file blobs referenced by the clipboard JSON,
     *                            paths match the in-DB media_path values (matches
     *                            [exportClipboardHistoryZip] verbatim so existing
     *                            ZIP importer code can be reused).
     *
     * Reuses the per-section JSON-builder helpers ([buildConfigJson],
     * [buildDictionariesJson]) and the media-streaming pattern from
     * [exportClipboardHistoryZip] — no duplicate serialization logic.
     */
    fun exportFullBackup(uri: Uri, prefs: SharedPreferences): FullBackupResult {
        var configIncluded = false
        var dictionaryCount = 0
        var clipboardEntryCount = 0
        var mediaFileCount = 0
        var totalBytes = 0L
        var privateSkipped = 0
        // #156 F7: resolve the passphrase ONCE for the whole full-backup op.
        val enc = resolveExportEncryption()
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName
            val versionCode = packageInfo.versionCode
            val exportDateIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

            // Pre-build all JSON payloads (so manifest can carry final counts).
            val (configRoot, configCount) = buildConfigJson(prefs)
            val configBytes = gson.toJson(configRoot).toByteArray(Charsets.UTF_8)
            configIncluded = configCount > 0

            val (dictRoot, dictSummary) = buildDictionariesJson()
            val dictBytes = gson.toJson(dictRoot).toByteArray(Charsets.UTF_8)
            dictionaryCount = dictSummary.languageCount

            val clipboardDb = ClipboardDatabase.getInstance(context)
            // #156 option B: include private entries only when the full backup is genuinely encrypted.
            val clipboardJson = clipboardDb.exportToJSON(textOnly = false, includePrivate = enc.willEncrypt)
            val clipboardBytes: ByteArray
            if (clipboardJson != null) {
                clipboardEntryCount = clipboardJson.optInt("total_active", 0) +
                    clipboardJson.optInt("total_pinned", 0) +
                    clipboardJson.optInt("total_todo", 0)
                // #156 F2: how many private entries the plaintext export dropped (0 when encrypted).
                privateSkipped = clipboardJson.optInt("private_skipped", 0)
                clipboardBytes = clipboardJson.toString(2).toByteArray(Charsets.UTF_8)
            } else {
                Log.w(TAG, "Clipboard export returned null; ZIP will omit clipboard section")
                clipboardBytes = ByteArray(0)
            }

            // Manifest entry — written FIRST so importers can sanity-check format
            // and refuse forward-incompatible files without scanning the whole ZIP.
            val manifest = JsonObject().apply {
                addProperty("format", "cleverkeys_full_backup")
                addProperty("format_version", FULL_BACKUP_FORMAT_VERSION)
                addProperty("app_version", versionName)
                addProperty("app_version_code", versionCode)
                addProperty("export_date", exportDateIso)
                addProperty("config_preference_count", configCount)
                addProperty("dictionary_language_count", dictionaryCount)
                addProperty("dictionary_custom_word_count", dictSummary.customWordsCount)
                addProperty("dictionary_disabled_word_count", dictSummary.disabledWordsCount)
                addProperty("clipboard_entry_count", clipboardEntryCount)
                val entriesArray = JsonArray().apply {
                    add(ENTRY_MANIFEST)
                    if (configCount > 0) add(ENTRY_CONFIG)
                    if (clipboardBytes.isNotEmpty()) add(ENTRY_CLIPBOARD_JSON)
                    add(ENTRY_DICTIONARIES)
                }
                add("entries", entriesArray)
            }
            val manifestBytes = gson.toJson(manifest).toByteArray(Charsets.UTF_8)

            val mediaManager = ClipboardMediaManager(context)
            writeZipEncryptingIfRequired(uri, EncryptedBackupFormat.FULL_BACKUP_ZIP, enc) { outputStream ->
                java.util.zip.ZipOutputStream(outputStream).use { zipOut ->
                    // 1. manifest.json (first — readable by tools that only inspect headers)
                    totalBytes += writeZipEntry(zipOut, ENTRY_MANIFEST, manifestBytes)

                    // 2. config.json
                    if (configCount > 0) {
                        totalBytes += writeZipEntry(zipOut, ENTRY_CONFIG, configBytes)
                    }

                    // 3. dictionaries.json (always written, even when empty — symmetric importer)
                    totalBytes += writeZipEntry(zipOut, ENTRY_DICTIONARIES, dictBytes)

                    // 4. clipboard_history.json
                    if (clipboardBytes.isNotEmpty()) {
                        totalBytes += writeZipEntry(zipOut, ENTRY_CLIPBOARD_JSON, clipboardBytes)
                    }

                    // 5. clipboard_media/* — stream each file directly (OOM-safe).
                    // mediaPath already contains the `clipboard_media/` prefix from
                    // the DB so the importer can re-extract straight back to the
                    // same on-disk location without translation.
                    // #156 F6: in a PLAINTEXT full backup exclude files referenced ONLY by private
                    // rows, mirroring the clipboard-manifest row filter — else private media bytes
                    // leak into the unencrypted ZIP while the manifest pretends they were excluded.
                    val mediaPaths = clipboardDb.getReferencedMediaPaths(includePrivate = enc.willEncrypt)
                    for (mediaPath in mediaPaths) {
                        val file = mediaManager.getMediaFile(mediaPath)
                        if (!file.exists()) {
                            Log.w(TAG, "Media file not found during full backup, skipping: $mediaPath")
                            continue
                        }
                        zipOut.putNextEntry(java.util.zip.ZipEntry(mediaPath))
                        val streamed = file.inputStream().use { it.copyTo(zipOut) }
                        totalBytes += streamed
                        zipOut.closeEntry()
                        mediaFileCount++
                    }
                }
            }

            Log.i(TAG, "Full backup exported: cfg=$configCount, dict langs=$dictionaryCount, " +
                "clip entries=$clipboardEntryCount, media=$mediaFileCount, bytes=$totalBytes, " +
                "private excluded=$privateSkipped")
            return FullBackupResult(
                success = true,
                configIncluded = configIncluded,
                dictionaryCount = dictionaryCount,
                clipboardEntryCount = clipboardEntryCount,
                mediaFileCount = mediaFileCount,
                totalBytes = totalBytes,
                privateSkipped = privateSkipped,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Full backup export failed", e)
            return FullBackupResult(
                success = false,
                configIncluded = configIncluded,
                dictionaryCount = dictionaryCount,
                clipboardEntryCount = clipboardEntryCount,
                mediaFileCount = mediaFileCount,
                errorMessage = e.message ?: "Unknown error",
                totalBytes = totalBytes,
                privateSkipped = privateSkipped,
            )
        } finally {
            enc.zero()
        }
    }

    /**
     * Helper: write a single in-memory ZIP entry and return the byte count
     * streamed (useful for [FullBackupResult.totalBytes]).
     */
    private fun writeZipEntry(
        zipOut: java.util.zip.ZipOutputStream,
        name: String,
        payload: ByteArray,
    ): Long {
        zipOut.putNextEntry(java.util.zip.ZipEntry(name))
        zipOut.write(payload)
        zipOut.closeEntry()
        return payload.size.toLong()
    }

    /**
     * Symmetric inverse of [exportFullBackup]. Streams the ZIP entries and
     * dispatches each to the corresponding per-section importer, then performs
     * thumbnail regeneration + orphan-media cleanup matching
     * [importClipboardHistoryZip].
     *
     * Forward-compat guard: refuses to import if the manifest's
     * `format_version` is strictly greater than the version this build knows
     * about. The user must update the app first.
     */
    fun importFullBackup(uri: Uri, prefs: SharedPreferences): FullBackupImportResult {
        var configKeysApplied = 0
        var configImported = false
        var customWordsImported = 0
        var disabledWordsImported = 0
        var clipboardEntriesImported = 0
        var clipboardEntriesSkipped = 0
        var mediaFilesRestored = 0
        var sourceAppVersion: String? = null

        // Transparently decrypt a CKENC1 container to a verified temp file first
        // (authenticate-then-parse) — decryptTemp is deleted in the finally.
        var decryptTemp: File? = null
        var stagingDir: File? = null
        var mediaCommit: MediaCommit? = null
        return try {
            val clipboardDb = ClipboardDatabase.getInstance(context)
            val mediaManager = ClipboardMediaManager(context)
            val stage = createImportStagingDir().also { stagingDir = it }
            val stagedMedia = ArrayList<StagedMedia>()
            val seenEntries = HashSet<String>()
            val budget = ImportBudget(importLimits.importTotalBytes)

            // Buffer JSON payloads to memory (small) while streaming media to disk.
            // We must process manifest before applying anything else.
            var manifestJson: JsonObject? = null
            var configJsonBytes: ByteArray? = null
            var dictionariesJsonBytes: ByteArray? = null
            var clipboardJsonData: org.json.JSONObject? = null

            val (zipStream, temp) = openZipForImport(uri, setOf(EncryptedBackupFormat.FULL_BACKUP_ZIP))
            decryptTemp = temp
            zipStream.use { inputStream ->
                java.util.zip.ZipInputStream(inputStream).use { zipIn ->
                    var entryCount = 0
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        // Entry-count cap: reject archives with an implausible number of
                        // entries (per-entry work × count DoS) before processing this one.
                        if (++entryCount > MAX_IMPORT_ENTRIES) {
                            throw java.io.IOException(
                                "Backup ZIP has more than $MAX_IMPORT_ENTRIES entries — refusing to import."
                            )
                        }
                        // CK-150-021: directory members are subject to the duplicate guard too.
                        if (!seenEntries.add(entry.name)) {
                            throw java.io.IOException(
                                "Backup ZIP contains duplicate entry '${entry.name}'"
                            )
                        }
                        when {
                            entry.name == ENTRY_MANIFEST -> {
                                val bytes = readBoundedBytes(zipIn, budget = budget)
                                manifestJson = JsonParser.parseString(
                                    String(bytes, Charsets.UTF_8)
                                ).asJsonObject
                                // Format guard — refuse unrelated ZIPs cleanly instead of
                                // silently treating them as v1 full-backup files.
                                val format = manifestJson?.get("format")?.asString
                                if (format != "cleverkeys_full_backup") {
                                    throw Exception("Not a CleverKeys full backup ZIP " +
                                        "(manifest.json `format` was \"${format ?: "<missing>"}\", " +
                                        "expected \"cleverkeys_full_backup\").")
                                }
                                // Forward-compat check — refuse newer formats early.
                                val formatVersion = manifestJson?.get("format_version")?.asInt ?: 1
                                if (formatVersion > FULL_BACKUP_FORMAT_VERSION) {
                                    throw Exception("Full backup format_version $formatVersion is newer " +
                                        "than supported ($FULL_BACKUP_FORMAT_VERSION). Update the app and retry.")
                                }
                                sourceAppVersion = manifestJson?.get("app_version")?.asString
                            }
                            entry.name == ENTRY_CONFIG -> {
                                configJsonBytes = readBoundedBytes(zipIn, budget = budget)
                            }
                            entry.name == ENTRY_DICTIONARIES -> {
                                dictionariesJsonBytes = readBoundedBytes(zipIn, budget = budget)
                            }
                            entry.name == ENTRY_CLIPBOARD_JSON -> {
                                val bytes = readBoundedBytes(zipIn, budget = budget)
                                clipboardJsonData = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                            }
                            entry.name.startsWith("clipboard_media/") -> {
                                // CK-150-021: skip payload-less directory members (see the
                                // clipboard-ZIP importer for the failure they caused).
                                if (entry.isDirectory) {
                                    Log.d(TAG, "Skipping media directory entry: ${entry.name}")
                                } else {
                                    stagedMedia.add(
                                        stageMediaEntry(
                                            zipIn, entry.name, mediaManager, stage,
                                            stagedMedia.size, budget,
                                        )
                                    )
                                }
                            }
                            else -> {
                                Log.w(TAG, "Unknown entry in full backup, skipping: ${entry.name}")
                                drainBoundedEntry(zipIn, entry.name, budget)
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }

            if (manifestJson == null) {
                throw Exception("Full backup is missing manifest.json")
            }

            // The entire archive is now parsed, bounded, duplicate-free, and has a valid
            // manifest. Only now may media enter the live tree; the commit remains reversible
            // until all logical sections finish applying.
            mediaCommit = commitStagedMedia(stagedMedia, mediaManager, stage)
            mediaFilesRestored = stagedMedia.size

            // CK-150-020: the section appliers below write SharedPreferences, which — unlike the
            // media commit (reversible via `mediaCommit`) and the clipboard import (one SQLite
            // transaction) — has no rollback of its own. Snapshot prefs BEFORE the first applier
            // runs; if any section fails, restore the snapshot so a half-applied settings /
            // dictionary state cannot survive a failed import. Nothing writes prefs between here
            // and the config apply, so this snapshot is also the plan builder's `currentSnapshot`.
            val prefsSnapshot: Map<String, Any?> = prefs.all.toMap()
            try {
                // Apply config.json — funnel through SettingsImportPlanBuilder + Applier
                // so screen-mismatch + drift handling stay consistent with single-file import.
                configJsonBytes?.let { bytes ->
                    val configString = String(bytes, Charsets.UTF_8)
                    val dm = context.resources.displayMetrics
                    val screen = ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.density)
                    val plan = SettingsImportPlanBuilder.fromJson(
                        configString,
                        currentSnapshot = prefsSnapshot,
                        screen = screen,
                        defaultSnapshot = SETTINGS_DEFAULTS,
                        currentShortSwipeRawJson = null,
                    )
                    val result = runBlocking {
                        SettingsImportApplier.apply(
                            plan, emptySet(), ShortSwipeImportMode.REPLACE, prefs, shortSwipeImporter
                        )
                    }
                    configKeysApplied = result.importedCount
                    configImported = true
                    // #156 F5: full-backup restore also writes clipboard_private_copy_toolbar_enabled;
                    // reconcile the component from the imported value (covers the headless path — the
                    // UI path's loadCurrentSettings() re-run is a harmless idempotent no-op).
                    reconcilePrivateCopyToolbarFromPrefs(prefs)
                }

                // Apply dictionaries.json — funnel through DictImportPlanBuilder + Applier.
                dictionariesJsonBytes?.let { bytes ->
                    val dictString = String(bytes, Charsets.UTF_8)
                    val currentCustom = readCurrentCustomWordsByLang(prefs)
                    val currentDisabled = readCurrentDisabledWordsByLang(prefs)
                    val plan = DictImportPlanBuilder.fromJson(dictString, currentCustom, currentDisabled)
                    val (custom, disabled) = DictImportApplier.apply(
                        plan, emptySet(), emptySet(), prefs
                    )
                    customWordsImported = custom
                    disabledWordsImported = disabled

                    // Learned data (context-LM bigrams + user vocabulary) rides the
                    // dictionaries payload since 2026-08-06 (backup blind-spot fix).
                    // TODO(CK-150-020): learned bigrams/vocabulary land outside `prefs`, so the
                    // snapshot restore below cannot undo them; they need their own reversal.
                    importLearnedDataFromJson(dictString)
                }

                // Apply clipboard_history.json. CK-150-019: importFromJSON now throws on a DB
                // failure, which lands in the catch below (prefs restored) and then in the outer
                // catch (media rolled back, success = false).
                clipboardJsonData?.let { json ->
                    val importResult = clipboardDb.importFromJSON(json)
                    clipboardEntriesImported = importResult[0] + importResult[1] + importResult[2]
                    clipboardEntriesSkipped = importResult[3]
                }
            } catch (e: Exception) {
                // Undo the prefs half of a partially-applied import. The rethrow reaches the
                // outer catch, which rolls the media commit back and reports success = false.
                // A restore failure must never replace the original cause — log it and continue.
                runCatching { restorePrefsSnapshot(prefs, prefsSnapshot) }
                    .onFailure { Log.e(TAG, "Settings rollback failed after a failed import", it) }
                configImported = false
                configKeysApplied = 0
                customWordsImported = 0
                disabledWordsImported = 0
                clipboardEntriesImported = 0
                clipboardEntriesSkipped = 0
                throw e
            }

            // Regenerate thumbnails for any media we just extracted to disk. Mirrors
            // importClipboardHistoryZip's post-import housekeeping.
            if (mediaFilesRestored > 0) {
                val referencedPaths = clipboardDb.getAllReferencedMediaPaths()
                try {
                    for (path in referencedPaths) {
                        val file = mediaManager.getMediaFile(path)
                        if (!file.exists()) continue
                        val ext = file.extension.lowercase()
                        val mimeType = when (ext) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            "gif" -> "image/gif"
                            "mp4" -> "video/mp4"
                            "pdf" -> "application/pdf"
                            else -> "application/octet-stream"
                        }
                        val thumbnail = mediaManager.generateThumbnail(file.absolutePath, mimeType)
                        if (thumbnail != null) {
                            updateThumbnailForMediaPath(clipboardDb, path, thumbnail)
                        }
                    }
                    mediaManager.cleanupOrphans(referencedPaths)
                } catch (e: Exception) {
                    Log.w(TAG, "Post-import media housekeeping failed: ${e.message}")
                }
            }

            mediaCommit?.finish()
            mediaCommit = null
            stagingDir = null

            Log.i(TAG, "Full backup imported: cfg=$configKeysApplied, custom=$customWordsImported, " +
                "disabled=$disabledWordsImported, clip imported=$clipboardEntriesImported, " +
                "clip skipped=$clipboardEntriesSkipped, media restored=$mediaFilesRestored")

            FullBackupImportResult(
                success = true,
                configImported = configImported,
                configKeysApplied = configKeysApplied,
                customWordsImported = customWordsImported,
                disabledWordsImported = disabledWordsImported,
                clipboardEntriesImported = clipboardEntriesImported,
                clipboardEntriesSkipped = clipboardEntriesSkipped,
                mediaFilesRestored = mediaFilesRestored,
                sourceAppVersion = sourceAppVersion,
            )
        } catch (e: BackupDecryptException) {
            // Surface decrypt failures distinctly so the UI can prompt for a passphrase
            // (rather than burying the message in a generic FullBackupImportResult).
            Log.e(TAG, "Full backup import decrypt failed", e)
            mediaCommit?.rollback()
            mediaCommit = null
            decryptTemp?.delete()
            throw e
        } catch (e: Exception) {
            mediaCommit?.rollback()
            mediaCommit = null
            Log.e(TAG, "Full backup import failed", e)
            FullBackupImportResult(
                success = false,
                configImported = configImported,
                configKeysApplied = configKeysApplied,
                customWordsImported = customWordsImported,
                disabledWordsImported = disabledWordsImported,
                clipboardEntriesImported = clipboardEntriesImported,
                clipboardEntriesSkipped = clipboardEntriesSkipped,
                mediaFilesRestored = mediaFilesRestored,
                sourceAppVersion = sourceAppVersion,
                errorMessage = e.message ?: "Unknown error",
            )
        } finally {
            stagingDir?.deleteRecursively()
            decryptTemp?.delete()
        }
    }

    /**
     * Restore [prefs] to exactly [snapshot] — the CK-150-020 rollback for the full-backup
     * section appliers, which write preferences with no undo of their own.
     *
     * `clear()` drops every key the failed sections added, then each snapshot value is
     * rewritten through the typed `put*` that matches its runtime class (SharedPreferences
     * has no untyped put). Android's `getAll()` contract limits values to
     * Boolean/Int/Long/Float/String/Set&lt;String&gt;; anything else is a corrupted prefs file
     * and is logged rather than dropped silently.
     *
     * Single editor, single [SharedPreferences.Editor.commit] — the restore is as atomic as
     * the applier it undoes, and `commit` (not `apply`) so the caller's failure result is not
     * reported before the rollback has actually hit disk. The restore assumes the import owns
     * prefs for its duration: a concurrent writer's change made mid-import would be reverted.
     */
    private fun restorePrefsSnapshot(prefs: SharedPreferences, snapshot: Map<String, Any?>) {
        val editor = prefs.edit()
        editor.clear()
        for ((key, value) in snapshot) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    // getAll() only ever yields Set<String> for set-valued preferences.
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                null -> Unit // Absent key — `clear()` already leaves it unset.
                else -> Log.e(
                    TAG,
                    "Cannot restore preference '$key': unsupported type ${value.javaClass.name}"
                )
            }
        }
        if (!editor.commit()) {
            Log.e(TAG, "Settings rollback commit() returned false — prefs may be partially restored")
        }
    }

    data class ImportLimits(
        val jsonEntryBytes: Int = MAX_JSON_ENTRY_BYTES,
        val mediaEntryBytes: Long = MAX_MEDIA_ENTRY_BYTES,
        val importTotalBytes: Long = MAX_IMPORT_TOTAL_BYTES,
        val archiveContainerBytes: Long = MAX_ARCHIVE_CONTAINER_BYTES,
    ) {
        init {
            require(jsonEntryBytes > 0)
            require(mediaEntryBytes > 0)
            require(importTotalBytes > 0)
            require(archiveContainerBytes >= EncryptedBackupFormat.HEADER_LEN)
        }
    }

    companion object {
        private const val TAG = "BackupRestoreManager"

        /**
         * Single user-facing message for the cryptographically-indistinguishable
         * wrong-passphrase and tampered-ciphertext cases (design §6).
         */
        const val WRONG_PASSWORD_OR_CORRUPT =
            "Wrong backup password, or the file is corrupted/tampered."

        /**
         * Bumped when the full-backup ZIP layout changes in a non-back-compatible
         * way. The importer refuses files whose `format_version` is strictly
         * greater than this constant — older files are accepted (the importer
         * tolerates missing entries).
         */
        const val FULL_BACKUP_FORMAT_VERSION = 1

        // Canonical ZIP entry names — referenced by both export + import so the
        // names stay in lockstep.
        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_CONFIG = "config.json"
        const val ENTRY_DICTIONARIES = "dictionaries.json"
        const val ENTRY_CLIPBOARD_JSON = "clipboard_history.json"

        /**
         * Per-entry decompressed size cap for JSON payloads read fully into memory.
         * A malicious/zip-bomb backup could declare a tiny compressed entry that
         * inflates to gigabytes; `ZipInputStream.readBytes()` is unbounded and would
         * OOM the process. 32 MB comfortably exceeds any legitimate config/dictionary/
         * clipboard JSON while capping the blast radius.
         */
        const val MAX_JSON_ENTRY_BYTES = 32 * 1024 * 1024

        /** Maximum decompressed size of one media member in an imported archive. */
        const val MAX_MEDIA_ENTRY_BYTES = 64L * 1024 * 1024

        /** Maximum aggregate decompressed JSON + media bytes accepted from one archive. */
        const val MAX_IMPORT_TOTAL_BYTES = 512L * 1024 * 1024

        /** Maximum encrypted container and authenticated plaintext ZIP size. */
        const val MAX_ARCHIVE_CONTAINER_BYTES = 512L * 1024 * 1024

        /**
         * Upper bound on the number of ZIP entries an import will iterate. Guards
         * against archives with millions of tiny entries (per-entry work × count).
         */
        const val MAX_IMPORT_ENTRIES = 10_000
    }

    /**
     * Read up to [cap] bytes from [input] into memory, throwing [java.io.IOException]
     * if the stream exceeds the cap. Replaces the unbounded [InputStream.readBytes]
     * for JSON entries decompressed from untrusted backup ZIPs (zip-bomb defense).
     *
     * Reads via a 64 KB buffer so we never allocate the full payload up front and
     * bail as soon as the running total crosses the cap.
     */
    private class ImportBudget(private val cap: Long) {
        var consumed: Long = 0L
            private set

        fun add(bytes: Int) {
            consumed += bytes
            if (consumed > cap) {
                throw java.io.IOException(
                    "Backup expands beyond ${cap / (1024 * 1024)} MB aggregate limit"
                )
            }
        }
    }

    private data class StagedMedia(val entryName: String, val file: File)

    private data class CommittedMedia(
        val target: File,
        val backup: File?,
        val existed: Boolean,
    )

    /**
     * A media commit remains reversible until [finish] is called. Existing files are copied
     * into the isolated staging directory before replacement; newly-created targets are deleted
     * by [rollback]. This is intentionally filesystem-only — the clipboard DB importer already
     * wraps its own writes in a SQLite transaction.
     */
    private class MediaCommit(
        private val stagingDir: File,
        private val committed: List<CommittedMedia>,
    ) {
        /**
         * Undo every committed replacement, newest first (reverse commit order), so a target
         * written more than once ends on its original content.
         *
         * CK-150-022: each entry is isolated. A single unrestorable file (permissions, a
         * vanished backup copy, an unlink failure) must not skip the remaining entries, and
         * must not throw out of a `catch` block — every caller invokes `rollback()` while
         * already handling another failure, and an escaping exception there would bypass the
         * structured `success = false` result.
         */
        fun rollback() {
            for (entry in committed.asReversed()) {
                runCatching {
                    if (entry.existed && entry.backup != null && entry.backup.isFile) {
                        entry.backup.copyTo(entry.target, overwrite = true)
                    } else {
                        entry.target.delete()
                    }
                }.onFailure {
                    Log.e(TAG, "Media rollback failed for '${entry.target.absolutePath}'", it)
                }
            }
            runCatching { stagingDir.deleteRecursively() }.onFailure {
                Log.e(TAG, "Failed to remove import staging dir '${stagingDir.absolutePath}'", it)
            }
        }

        fun finish() {
            stagingDir.deleteRecursively()
        }
    }

    private fun createImportStagingDir(): File {
        val dir = File(
            context.cacheDir,
            "ck_import_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        )
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw java.io.IOException("Cannot create backup import staging directory")
        }
        return dir
    }

    private fun stageMediaEntry(
        input: InputStream,
        entryName: String,
        mediaManager: ClipboardMediaManager,
        stagingDir: File,
        ordinal: Int,
        budget: ImportBudget,
    ): StagedMedia {
        // Resolve now solely to validate traversal/canonical-path rules. Nothing is written to
        // the live media tree until the complete archive has been parsed and validated.
        mediaManager.getMediaFile(entryName)
        val staged = File(stagingDir, "media_$ordinal")
        staged.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var entryBytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                entryBytes += read
                if (entryBytes > importLimits.mediaEntryBytes) {
                    throw java.io.IOException(
                        "Media entry '$entryName' exceeds " +
                            "${importLimits.mediaEntryBytes} byte limit"
                    )
                }
                budget.add(read)
                out.write(buffer, 0, read)
            }
        }
        return StagedMedia(entryName, staged)
    }

    /**
     * Consume an unrecognized ZIP member without letting it bypass the per-entry or aggregate
     * decompression ceilings. Compatibility metadata may be ignored, but it is still untrusted
     * compressed input and therefore participates in the same bounded budget as media.
     */
    private fun drainBoundedEntry(
        input: InputStream,
        entryName: String,
        budget: ImportBudget,
    ) {
        val buffer = ByteArray(64 * 1024)
        var entryBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            entryBytes += read
            if (entryBytes > importLimits.mediaEntryBytes) {
                throw java.io.IOException(
                    "Unknown ZIP entry '$entryName' exceeds " +
                        "${importLimits.mediaEntryBytes} byte limit"
                )
            }
            budget.add(read)
        }
    }

    private fun commitStagedMedia(
        staged: List<StagedMedia>,
        mediaManager: ClipboardMediaManager,
        stagingDir: File,
    ): MediaCommit {
        val backupsDir = File(stagingDir, "backups")
        val committed = ArrayList<CommittedMedia>(staged.size)
        try {
            for ((index, media) in staged.withIndex()) {
                val target = mediaManager.getMediaFile(media.entryName)
                target.parentFile?.let {
                    if (!it.mkdirs() && !it.isDirectory) {
                        throw java.io.IOException("Cannot create media directory for '${media.entryName}'")
                    }
                }
                // CK-150-021: `exists()`, not `isFile()` — anything already occupying the target
                // path (including a directory) is pre-existing state, and journaling it as new
                // would make rollback DELETE it instead of restoring it.
                val existed = target.exists()
                val backup = if (target.isFile) {
                    if (!backupsDir.mkdirs() && !backupsDir.isDirectory) {
                        throw java.io.IOException("Cannot create media rollback directory")
                    }
                    File(backupsDir, "original_$index").also {
                        target.copyTo(it, overwrite = true)
                    }
                } else null
                // Record the rollback instruction before replacing the target so a partial copy
                // is still recoverable.
                committed.add(CommittedMedia(target, backup, existed))
                media.file.copyTo(target, overwrite = true)
            }
            return MediaCommit(stagingDir, committed)
        } catch (e: Exception) {
            MediaCommit(stagingDir, committed).rollback()
            throw e
        }
    }

    private fun readBoundedBytes(
        input: InputStream,
        cap: Int = importLimits.jsonEntryBytes,
        budget: ImportBudget? = null,
    ): ByteArray {
        val buffer = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > cap) {
                throw java.io.IOException(
                    "ZIP entry exceeds ${cap / (1024 * 1024)} MB limit — refusing to buffer " +
                        "(possible zip bomb)."
                )
            }
            budget?.add(read)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
