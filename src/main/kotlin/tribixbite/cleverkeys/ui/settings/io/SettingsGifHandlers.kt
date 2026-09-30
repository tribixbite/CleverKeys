package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResourcesResultText
import tribixbite.cleverkeys.ResultText
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.gif.GifPackImportResult

/**
 * ARC-075 — the state of the GIF-pack import as the settings section must consume it: a
 * VARIANT plus a message, never a message alone.
 *
 * The section previously decided "is this a failure?" with `status.startsWith("Error")` against a
 * string produced here. Nothing connected the two ends: rewording a message here, localizing
 * these strings, or simply surfacing a platform `Exception.message` (which the ROM localizes
 * TODAY) silently paints a failed import in the success colour. Carrying the outcome in the type
 * makes that unrepresentable, and lets the message stay pure copy — free to be reworded or
 * translated without touching a render decision.
 *
 * Pure Kotlin on purpose: it is unit-tested in `runPureTests`
 * (`ui.settings.io.GifImportStatusTest`) even though every other declaration in this file needs
 * Android. The success copy is a string resource resolved through [ResultText] (2026-09-29 i18n
 * sweep), so the test drives it with the English `res/values` text and production per locale.
 */
sealed interface GifImportStatus {

    /** The text shown to the user, rendered verbatim — no prefix protocol, no re-parsing. */
    val message: String

    /** An import that is running, finished, or was a no-op. Rendered in the primary colour. */
    data class Ok(override val message: String) : GifImportStatus

    /** An import that failed. Rendered in the error colour, in any language. */
    data class Failed(override val message: String) : GifImportStatus

    companion object {

        /**
         * Classifies [result] by its own sealed variant — the only place the mapping lives.
         *
         * The failure message is the manager's typed reason rendered through [text]: the error
         * COLOUR already says "this failed", so re-stating it in the copy would just reintroduce
         * a marker in a string that is meant to be translatable.
         */
        internal fun forImportResult(result: GifPackImportResult, text: ResultText): GifImportStatus = when (result) {
            is GifPackImportResult.Success ->
                Ok(text.plural(R.plurals.gif_import_status_imported, result.gifCount, result.name, result.gifCount))
            is GifPackImportResult.AlreadyInstalled ->
                Ok(text.string(R.string.gif_import_status_already_installed, result.name))
            is GifPackImportResult.Error -> Failed(result.failure.render(text))
        }
    }
}

// GIF pack share intent handling (for ACTION_SEND / ACTION_VIEW with ZIP)

internal fun SettingsActivity.handleGifPackShareIntent(intent: Intent?) {
    if (intent == null) return
    val uri: Uri? = when (intent.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
    if (uri != null) {
        // Auto-import the shared ZIP file
        performGifPackImport(uri)
    }
}

// GIF pack management methods

internal fun SettingsActivity.performGifPackImport(uri: Uri, replaceExisting: Boolean = false) {
    val _self = this
    gifImportInProgress = true
    gifImportStatus = GifImportStatus.Ok(getString(R.string.gif_importing))
    lifecycleScope.launch {
        try {
            val manager = tribixbite.cleverkeys.gif.GifPackManager.getInstance(_self)
            val result = manager.importPackFromUri(uri, replaceExisting = replaceExisting)
            // ARC-075: ONE classification of the result, by variant, shared with the section.
            gifImportStatus = GifImportStatus.forImportResult(result, ResourcesResultText(resources))
            when (result) {
                is GifPackImportResult.Success -> {
                    refreshInstalledGifPacks()
                    Toast.makeText(
                        _self,
                        getString(R.string.gif_toast_pack_imported, result.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is GifPackImportResult.AlreadyInstalled -> {
                    // Audit E-8: offer replace — previously AlreadyInstalled dead-ended in a
                    // toast and the sole call site hardcoded replaceExisting=false, so the
                    // replace path (the only way a rebuilt pack's rows — e.g. the #149 gid:
                    // markers — can supersede the installed ones) was unreachable.
                    android.app.AlertDialog.Builder(_self)
                        .setTitle(R.string.gif_replace_dialog_title)
                        .setMessage(getString(R.string.gif_replace_dialog_body, result.name))
                        .setPositiveButton(R.string.gif_replace_dialog_confirm) { _, _ ->
                            performGifPackImport(uri, replaceExisting = true)
                        }
                        .setNegativeButton(R.string.common_cancel, null)
                        .show()
                }
                is GifPackImportResult.Error -> {
                    android.util.Log.w(SettingsActivity.TAG, "GIF pack import refused: ${result.message}")
                    Toast.makeText(
                        _self,
                        getString(R.string.common_import_failed_detail, gifImportStatus?.message.orEmpty()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "GIF pack import failed", e)
            gifImportStatus = GifImportStatus.Failed(ioFailureText(e))
            Toast.makeText(
                _self, getString(R.string.common_import_failed_detail, ioFailureText(e)), Toast.LENGTH_SHORT
            ).show()
        } finally {
            gifImportInProgress = false
        }
    }
}

internal fun SettingsActivity.performGifRemovePack(packId: String) {
    val _self = this
    lifecycleScope.launch {
        try {
            val manager = tribixbite.cleverkeys.gif.GifPackManager.getInstance(_self)
            manager.removePack(packId)
            refreshInstalledGifPacks()
            Toast.makeText(_self, R.string.gif_toast_pack_removed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "GIF pack removal failed", e)
            Toast.makeText(
                _self, getString(R.string.common_remove_failed_detail, ioFailureText(e)), Toast.LENGTH_SHORT
            ).show()
        }
    }
}

internal fun SettingsActivity.performGifRemoveAll() {
    val _self = this
    lifecycleScope.launch {
        try {
            val manager = tribixbite.cleverkeys.gif.GifPackManager.getInstance(_self)
            manager.removeAll()
            gifEnabled = false
            prefs.edit().putBoolean("gif_enabled", false).apply()
            refreshInstalledGifPacks()
            gifImportStatus = null
            Toast.makeText(_self, R.string.gif_toast_all_removed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "GIF pack removal failed", e)
            Toast.makeText(
                _self, getString(R.string.common_remove_failed_detail, ioFailureText(e)), Toast.LENGTH_SHORT
            ).show()
        }
    }
}

internal fun SettingsActivity.refreshInstalledGifPacks() {
    try {
        val manager = tribixbite.cleverkeys.gif.GifPackManager.getInstance(this)
        installedGifPacks = manager.getInstalledPacks()
        gifStorageUsed = manager.getTotalStorageUsed()
    } catch (e: Exception) {
        installedGifPacks = emptyList()
        gifStorageUsed = 0L
    }
}
