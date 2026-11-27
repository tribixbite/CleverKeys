package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResourcesResultText
import tribixbite.cleverkeys.ResultText
import tribixbite.cleverkeys.PendingGifReplace
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

    /**
     * An import that is running, finished, or was a no-op. Rendered in the primary colour.
     * [packId] is the pack the message names ("Imported: X"), null for progress text.
     */
    data class Ok(override val message: String, val packId: String? = null) : GifImportStatus

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
                Ok(
                    text.plural(R.plurals.gif_import_status_imported, result.gifCount, result.name, result.gifCount),
                    packId = result.packId,
                )
            is GifPackImportResult.AlreadyInstalled ->
                Ok(text.string(R.string.gif_import_status_already_installed, result.name), packId = result.packId)
            is GifPackImportResult.Error -> Failed(result.failure.render(text))
        }

        /**
         * The status to show once a removal left [installedPackIds] installed: a line that names
         * a pack no longer installed ("Imported: Discord Community 01" after that pack was
         * removed, device pass 2026-11-18) goes away; any other status stays as it was.
         */
        fun afterRemoval(status: GifImportStatus?, installedPackIds: Set<String>): GifImportStatus? {
            val named = (status as? Ok)?.packId ?: return status
            return if (named in installedPackIds) status else null
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

/**
 * Import a GIF pack ZIP. The import runs in [SettingsImports.gifPack], not this activity's scope:
 * a rotation used to cancel it between its writes, leaving rows without thumbnails or, on a
 * replace, the old pack removed and the new one missing, and then reported "Unknown error"
 * (pre-tag review 2026-11-25 S2). The result is shown by whichever Settings activity is in front
 * when it ends ([showGifPackImportResult]).
 */
internal fun SettingsActivity.performGifPackImport(uri: Uri, replaceExisting: Boolean = false) {
    val appContext = applicationContext
    val started = SettingsImports.gifPack.start(null) {
        val followUp = catchingFailures({
            val manager = tribixbite.cleverkeys.gif.GifPackManager.getInstance(appContext)
            val result = manager.importPackFromUri(uri, replaceExisting = replaceExisting)
            followUp { showGifPackImportResult(uri, result) }
        }) { e ->
            android.util.Log.e(SettingsActivity.TAG, "GIF pack import failed", e)
            followUp {
                gifImportStatus = GifImportStatus.Failed(ioFailureText(e))
                Toast.makeText(
                    this, getString(R.string.common_import_failed_detail, ioFailureText(e)), Toast.LENGTH_SHORT
                ).show()
            }
        }
        ImportTracker.Outcome(null, followUp)
    }
    if (started) {
        gifImportStatus = GifImportStatus.Ok(getString(R.string.gif_importing))
    } else {
        // A second pack (a share intent, most likely) while a pack import or removal runs: the
        // section shows the running one, whose result follows; this file was not imported.
        android.util.Log.w(SettingsActivity.TAG, "GIF pack operation already running; $uri not imported")
        showGifPackBusy()
    }
}

/** Another GIF pack import or removal is still running; this one was not started. */
private fun SettingsActivity.showGifPackBusy() {
    Toast.makeText(this, R.string.gif_pack_busy, Toast.LENGTH_LONG).show()
}

/** The finished import's [result] on this (the current) Settings activity. */
private fun SettingsActivity.showGifPackImportResult(uri: Uri, result: GifPackImportResult) {
    val _self = this
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
            // The prompt is DATA in the SettingsViewModel, rendered by the Settings content:
            // a platform dialog built here belonged to this activity and vanished on a rotation.
            pendingGifReplace = PendingGifReplace(uri, result.name)
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
}

/**
 * Remove the installed pack [packId]. Like the import, it runs in [SettingsImports.gifPack], one
 * GIF pack operation at a time, and not in this activity's scope (Fable audit D3, 2026-11-26): a
 * rotation cut the removal there, and it could run beside an import of the same pack.
 */
internal fun SettingsActivity.performGifRemovePack(packId: String) {
    val appContext = applicationContext
    val started = SettingsImports.gifPack.start(null) {
        val followUp = catchingFailures({
            tribixbite.cleverkeys.gif.GifPackManager.getInstance(appContext).removePack(packId)
            followUp {
                refreshInstalledGifPacks()
                gifImportStatus = GifImportStatus.afterRemoval(
                    gifImportStatus, installedGifPacks.mapTo(HashSet()) { it.packId }
                )
                Toast.makeText(this, R.string.gif_toast_pack_removed, Toast.LENGTH_SHORT).show()
            }
        }) { e -> gifRemovalFailure(e) }
        ImportTracker.Outcome(null, followUp)
    }
    if (!started) {
        android.util.Log.w(SettingsActivity.TAG, "GIF pack operation already running; $packId not removed")
        showGifPackBusy()
    }
}

/**
 * Remove every pack and turn the GIF panel off, in [SettingsImports.gifPack] like
 * [performGifRemovePack]. The `gif_enabled` write is part of the work, so the panel is off even
 * when the follow-up is dropped (Settings left meanwhile).
 */
internal fun SettingsActivity.performGifRemoveAll() {
    val appContext = applicationContext
    // The starting activity's preferences: the work turns the panel off there itself.
    val gifPrefs = prefs
    val started = SettingsImports.gifPack.start(null) {
        val followUp = catchingFailures({
            tribixbite.cleverkeys.gif.GifPackManager.getInstance(appContext).removeAll()
            gifPrefs.edit().putBoolean("gif_enabled", false).apply()
            followUp {
                gifEnabled = false
                refreshInstalledGifPacks()
                gifImportStatus = null
                Toast.makeText(this, R.string.gif_toast_all_removed, Toast.LENGTH_SHORT).show()
            }
        }) { e -> gifRemovalFailure(e) }
        ImportTracker.Outcome(null, followUp)
    }
    if (!started) {
        android.util.Log.w(SettingsActivity.TAG, "GIF pack operation already running; packs not removed")
        showGifPackBusy()
    }
}

/** The follow-up of a GIF pack removal that threw [e]. */
private fun gifRemovalFailure(e: Exception): SettingsFollowUp {
    android.util.Log.e(SettingsActivity.TAG, "GIF pack removal failed", e)
    return followUp {
        Toast.makeText(
            this, getString(R.string.common_remove_failed_detail, ioFailureText(e)), Toast.LENGTH_SHORT
        ).show()
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
