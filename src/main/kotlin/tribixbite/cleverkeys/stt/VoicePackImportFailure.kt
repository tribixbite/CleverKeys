package tribixbite.cleverkeys.stt

import tribixbite.cleverkeys.IoFailureClassifier
import tribixbite.cleverkeys.IoFailureReason
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResultText

/**
 * Why a voice-pack import (or load) was refused, typed so Settings can say it in the user's
 * language — the same split as `PackImportFailure`: [logMessage] is English for logcat and
 * tests, [render] resolves the localized text through a [ResultText].
 *
 * Arguments are file names, ids and limits: not translatable, inserted as-is.
 */
sealed class VoicePackImportFailure {

    abstract val logMessage: String

    internal abstract fun render(text: ResultText): String

    /** Could not open/read the file, damaged ZIP, disk full, … */
    data class Io(val reason: IoFailureReason, override val logMessage: String) : VoicePackImportFailure() {
        override fun render(text: ResultText) = text.string(reason.messageRes)
    }

    /** The ZIP has no `manifest.json`, or its `kind` is not `voice` (e.g. a language pack). */
    data object NotAVoicePack : VoicePackImportFailure() {
        override val logMessage get() = "Not a voice pack (manifest kind is not \"voice\")"
        override fun render(text: ResultText) = text.string(R.string.voice_pack_error_not_voice_pack)
    }

    /** The manifest is unparseable or a field is malformed; [detail] goes to the log only. */
    data class InvalidManifest(val detail: String) : VoicePackImportFailure() {
        override val logMessage get() = "Invalid voice pack manifest: $detail"
        override fun render(text: ResultText) = text.string(R.string.voice_pack_error_invalid_manifest)
    }

    /** The manifest id is not a safe directory name. */
    data class InvalidId(val id: String) : VoicePackImportFailure() {
        override val logMessage get() = "Invalid voice pack id: \"$id\""
        override fun render(text: ResultText) = text.string(R.string.voice_pack_error_invalid_id, id)
    }

    /** The pack records at a rate the keyboard does not capture. */
    data class UnsupportedSampleRate(val rate: Int) : VoicePackImportFailure() {
        override val logMessage get() = "Unsupported sample rate $rate Hz"
        override fun render(text: ResultText) = text.string(R.string.voice_pack_error_sample_rate, rate)
    }

    /**
     * The pack is for a newer CleverKeys: a newer manifest format, a higher minimum app
     * version, an engine id this build does not register, or an engine version outside the
     * registered range. [detail] is log-only.
     */
    data class NeedsNewerApp(val detail: String) : VoicePackImportFailure() {
        override val logMessage get() = "Voice pack needs a newer CleverKeys ($detail)"
        override fun render(text: ResultText) = text.string(R.string.voice_pack_error_needs_newer_app)
    }

    /** A file the manifest declares is absent from the ZIP. */
    data class MissingFile(val fileName: String) : VoicePackImportFailure() {
        override val logMessage get() = "Missing $fileName"
        override fun render(text: ResultText) = text.string(R.string.pack_error_missing_member, fileName)
    }

    /** A member name is unsafe (traversal, absolute, duplicate after flattening). */
    data class InvalidMember(val fileName: String) : VoicePackImportFailure() {
        override val logMessage get() = "Invalid member $fileName"
        override fun render(text: ResultText) = text.string(R.string.pack_error_invalid_member, fileName)
    }

    /**
     * A member is larger than its own cap or what the archive cap leaves ([fileName] is `ZIP`
     * then). Rendered in MiB, or KiB below one MiB (the notices' 64 KiB cap).
     */
    data class MemberTooLarge(val fileName: String, val limitBytes: Long) : VoicePackImportFailure() {
        override val logMessage get() = "$fileName exceeds the $limitBytes byte limit"
        override fun render(text: ResultText): String =
            if (limitBytes >= MIB) {
                text.string(R.string.pack_error_model_too_large, fileName, (limitBytes / MIB).toInt())
            } else {
                text.string(R.string.pack_error_member_too_large, fileName, (limitBytes / 1024).toInt())
            }

        private companion object {
            const val MIB = 1024L * 1024
        }
    }

    /** More ZIP entries than any voice pack has. */
    data class TooManyEntries(val limit: Int) : VoicePackImportFailure() {
        override val logMessage get() = "More than $limit ZIP entries"
        override fun render(text: ResultText) = text.string(R.string.voice_pack_error_too_many_entries, limit)
    }

    /** A declared file's size or sha256 does not match the manifest (corrupt download). */
    data class ChecksumMismatch(val fileName: String) : VoicePackImportFailure() {
        override val logMessage get() = "$fileName does not match its manifest size/sha256"
        override fun render(text: ResultText) = text.string(R.string.pack_error_model_checksum, fileName)
    }

    /** The verified pack could not be moved into place; any previous pack is kept. */
    data object InstallFailed : VoicePackImportFailure() {
        override val logMessage get() = "Failed to install voice pack (rename failed)"
        override fun render(text: ResultText) = text.string(R.string.pack_error_install_failed)
    }

    companion object {
        fun fromException(e: Throwable): VoicePackImportFailure =
            Io(IoFailureClassifier.classify(e), "Voice pack import failed: ${e.message}")
    }
}
