package tribixbite.cleverkeys

/**
 * Why a language-pack or GIF-pack import was refused, typed so the settings UI can say it in
 * the user's language.
 *
 * The managers (`langpack.LanguagePackManager`, `gif.GifPackManager`) used to return an English
 * sentence that the UI showed inside a localized "Import failed: %1$s". They now return one of
 * these; [logMessage] keeps the English sentence for logcat and tests, and [render] resolves the
 * localized text through a [ResultText] (pure, so it is unit-tested with the English
 * `res/values` text and every locale's `strings.xml`).
 *
 * Arguments are file names, language codes and limits: not translatable, inserted as-is.
 */
sealed class PackImportFailure {

    /** English description for the log. Never shown to the user. */
    abstract val logMessage: String

    /** The localized, user-visible reason. */
    internal abstract fun render(text: ResultText): String

    /** A generic I/O failure (could not open, damaged ZIP, disk full, …). */
    data class Io(val reason: IoFailureReason, override val logMessage: String) : PackImportFailure() {
        override fun render(text: ResultText) = text.string(reason.messageRes)
    }

    /** A required member ([fileName], e.g. `manifest.json`) is absent from the pack. */
    data class MissingMember(val fileName: String) : PackImportFailure() {
        override val logMessage get() = "Missing $fileName"
        override fun render(text: ResultText) = text.string(R.string.pack_error_missing_member, fileName)
    }

    /** A member ([fileName]) is present but empty, unparseable or of the wrong format. */
    data class InvalidMember(val fileName: String) : PackImportFailure() {
        override val logMessage get() = "Invalid $fileName format"
        override fun render(text: ResultText) = text.string(R.string.pack_error_invalid_member, fileName)
    }

    /** The manifest's language [code] is not a safe, well-formed pack code. */
    data class InvalidLanguageCode(val code: String) : PackImportFailure() {
        override val logMessage get() = "Invalid language code in manifest: \"$code\""
        override fun render(text: ResultText) = text.string(R.string.pack_error_invalid_language_code, code)
    }

    /** The manifest declares a model file other than the one this app loads. */
    data class UnsupportedModel(val fileName: String) : PackImportFailure() {
        override val logMessage get() = "Unsupported model file in manifest: \"$fileName\""
        override fun render(text: ResultText) = text.string(R.string.pack_error_unsupported_model, fileName)
    }

    /** The model member exceeds the extraction cap of [limitMiB] MiB. */
    data class ModelTooLarge(val fileName: String, val limitMiB: Int) : PackImportFailure() {
        override val logMessage get() = "$fileName exceeds the $limitMiB MiB limit"
        override fun render(text: ResultText) =
            text.string(R.string.pack_error_model_too_large, fileName, limitMiB)
    }

    /** The model member does not hash to the manifest's sha256. */
    data class ModelChecksumMismatch(val fileName: String) : PackImportFailure() {
        override val logMessage get() = "$fileName does not match its manifest sha256"
        override fun render(text: ResultText) = text.string(R.string.pack_error_model_checksum, fileName)
    }

    /** The verified pack could not be moved into place. */
    data object InstallFailed : PackImportFailure() {
        override val logMessage get() = "Failed to install pack (rename failed)"
        override fun render(text: ResultText) = text.string(R.string.pack_error_install_failed)
    }

    /** A legacy GIF pack with no thumbnails: its GIFs could never be shown. */
    data class GifNoThumbnails(override val logMessage: String) : PackImportFailure() {
        override fun render(text: ResultText) = text.string(R.string.gif_pack_error_no_thumbnails)
    }

    /** Copying a GIF pack's thumbnails failed partway (usually storage full); rolled back. */
    data class GifPartialThumbnails(override val logMessage: String) : PackImportFailure() {
        override fun render(text: ResultText) = text.string(R.string.gif_pack_error_partial_thumbnails)
    }

    companion object {
        /** A failure from an exception thrown during the import. */
        fun fromException(e: Throwable): PackImportFailure =
            Io(IoFailureClassifier.classify(e), "Import failed: ${e.message}")
    }
}
