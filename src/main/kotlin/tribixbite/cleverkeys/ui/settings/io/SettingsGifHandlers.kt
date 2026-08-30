package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
 * Android.
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
         * The failure message is the manager's verbatim reason: the error COLOUR already says
         * "this failed", so re-stating it in the copy would just reintroduce an English marker
         * in a string that is meant to be translatable.
         */
        fun forImportResult(result: GifPackImportResult): GifImportStatus = when (result) {
            is GifPackImportResult.Success ->
                Ok("Imported: ${result.name} (${result.gifCount} GIFs)")
            is GifPackImportResult.AlreadyInstalled ->
                Ok("Pack '${result.name}' already installed")
            is GifPackImportResult.Error -> Failed(result.message)
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

internal fun SettingsActivity.performGifPackImport(uri: Uri) {
    val _self = this
    gifImportInProgress = true
    gifImportStatus = GifImportStatus.Ok("Importing...")
    lifecycleScope.launch {
        try {
            val manager = tribixbite.cleverkeys.gif.GifPackManager.getInstance(_self)
            val result = manager.importPackFromUri(uri, replaceExisting = false)
            // ARC-075: ONE classification of the result, by variant, shared with the section.
            gifImportStatus = GifImportStatus.forImportResult(result)
            when (result) {
                is GifPackImportResult.Success -> {
                    refreshInstalledGifPacks()
                    Toast.makeText(
                        _self,
                        "GIF pack imported: ${result.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is GifPackImportResult.AlreadyInstalled -> {
                    Toast.makeText(
                        _self,
                        "Pack already installed: ${result.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is GifPackImportResult.Error -> {
                    Toast.makeText(
                        _self,
                        "Import failed: ${result.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            gifImportStatus = GifImportStatus.Failed(e.message ?: e.javaClass.simpleName)
            Toast.makeText(_self, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(_self, "GIF pack removed", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(_self, "Remove failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(_self, "All GIF data removed", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(_self, "Remove failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
