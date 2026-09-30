package tribixbite.cleverkeys

import androidx.annotation.StringRes

/**
 * Why an import, export or backup operation failed, in categories the UI can say in the user's
 * language.
 *
 * Until 2026-09-30 the settings screens put the raw exception message into a localized wrapper
 * ("Import failed: %1$s"), so a Hungarian or Persian user read "Import failed: Backup ZIP
 * contains duplicate entry 'x'" or a ROM-localized platform message. The raw text is for
 * developers: callers now log the throwable and show [messageRes] instead (see
 * [IoFailureClassifier] and `ui/settings/io`).
 */
enum class IoFailureReason(@StringRes val messageRes: Int) {
    /** The file (or content URI) could not be found or opened. */
    FILE_NOT_FOUND(R.string.io_error_file_not_found),

    /** Storage or content-provider permission was refused. */
    PERMISSION_DENIED(R.string.io_error_permission_denied),

    /** Not the expected kind of file, or damaged: bad ZIP/JSON, truncated, missing members. */
    INVALID_FORMAT(R.string.io_error_invalid_format),

    /** Written by a newer CleverKeys whose format this build does not read. */
    NEWER_VERSION(R.string.io_error_newer_version),

    /** The device ran out of storage (ENOSPC). */
    OUT_OF_SPACE(R.string.io_error_out_of_space),

    /** Exceeds a safety limit (entry count, entry size, total expansion). */
    TOO_LARGE(R.string.io_error_too_large),

    /** AES-GCM tag mismatch: wrong backup password, or the file was altered. */
    WRONG_PASSWORD_OR_CORRUPT(R.string.io_error_wrong_password_or_corrupt),

    /** An encrypted backup was opened with no backup password available. */
    NO_BACKUP_PASSWORD(R.string.io_error_no_backup_password),

    /** An encrypted backup of a different kind than this import button accepts. */
    WRONG_BACKUP_KIND(R.string.io_error_wrong_backup_kind),

    /** No app on the device can handle the file picker request. */
    NO_FILE_PICKER(R.string.io_error_no_file_picker),

    /** Any other read/write failure. */
    READ_WRITE(R.string.io_error_read_write),

    /** Nothing more specific is known. */
    UNKNOWN(R.string.common_unknown_error),
}

/**
 * An I/O failure whose [reason] is known where it is thrown. The message stays English: it is
 * written to the log, never shown. Extends [java.io.IOException] so throw sites that used a
 * plain `IOException` keep their type for any caller that catches it.
 */
open class ClassifiedIoException(
    val reason: IoFailureReason,
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

/**
 * Maps a throwable to an [IoFailureReason].
 *
 * The cause chain is walked because the managers wrap failures (`Exception("Import failed:
 * ${e.message}", e)`): the first link with a specific reason wins, a generic read/write
 * failure is used only when nothing more specific is found anywhere in the chain.
 *
 * Android-only exception types are matched by class name so this stays pure JVM (tested in
 * `runPureTests`).
 */
object IoFailureClassifier {

    private val invalidFormatClassNames = setOf(
        "org.json.JSONException",
        "android.util.MalformedJsonException",
        "com.google.gson.JsonParseException",
        "com.google.gson.JsonSyntaxException",
        "com.google.gson.JsonIOException",
        "com.google.gson.stream.MalformedJsonException",
        "kotlinx.serialization.SerializationException",
    )

    /** Classify [error]; `null` (no exception, only a missing result) is [IoFailureReason.UNKNOWN]. */
    fun classify(error: Throwable?): IoFailureReason {
        var generic: IoFailureReason? = null
        val seen = HashSet<Throwable>()
        var t = error
        while (t != null && seen.add(t)) {
            when (val r = classifyOne(t)) {
                null -> Unit
                IoFailureReason.READ_WRITE -> if (generic == null) generic = r
                else -> return r
            }
            t = t.cause
        }
        return generic ?: IoFailureReason.UNKNOWN
    }

    /** The reason for one link of the chain, or null when this link says nothing. */
    private fun classifyOne(t: Throwable): IoFailureReason? {
        if (t is ClassifiedIoException) return t.reason
        val message = t.message.orEmpty()
        // errno text survives every wrapper Android puts around it ("… ENOSPC (No space left
        // on device)", "open failed: EACCES (Permission denied)").
        if ("ENOSPC" in message || message.contains("No space left", ignoreCase = true)) {
            return IoFailureReason.OUT_OF_SPACE
        }
        if ("EACCES" in message || "EPERM" in message ||
            message.contains("Permission denied", ignoreCase = true) ||
            message.contains("Permission Denial", ignoreCase = true)
        ) {
            return IoFailureReason.PERMISSION_DENIED
        }
        if (isA(t, "android.content.ActivityNotFoundException")) return IoFailureReason.NO_FILE_PICKER
        // java.nio.file is API 26+ and minSdk is 24, so those two are matched by name too.
        return when {
            t is SecurityException || isA(t, "java.nio.file.AccessDeniedException") ->
                IoFailureReason.PERMISSION_DENIED
            t is java.io.FileNotFoundException || isA(t, "java.nio.file.NoSuchFileException") ->
                IoFailureReason.FILE_NOT_FOUND
            t is javax.crypto.AEADBadTagException -> IoFailureReason.WRONG_PASSWORD_OR_CORRUPT
            t is java.util.zip.ZipException || t is java.io.EOFException ||
                t is java.io.UTFDataFormatException || t is java.nio.charset.CharacterCodingException ||
                t is NumberFormatException || invalidFormatClassNames.any { isA(t, it) } ->
                IoFailureReason.INVALID_FORMAT
            t is java.io.IOException -> IoFailureReason.READ_WRITE
            else -> null
        }
    }

    /** True if [t]'s class or any superclass is named [className]. */
    private fun isA(t: Throwable, className: String): Boolean {
        var c: Class<*>? = t.javaClass
        while (c != null) {
            if (c.name == className) return true
            c = c.superclass
        }
        return false
    }
}
