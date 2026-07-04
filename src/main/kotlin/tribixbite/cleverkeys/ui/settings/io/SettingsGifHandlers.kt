package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.SettingsActivity

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
    gifImportStatus = "Importing..."
    lifecycleScope.launch {
        try {
            val manager = tribixbite.cleverkeys.gif.GifPackManager.getInstance(_self)
            when (val result = manager.importPackFromUri(uri, replaceExisting = false)) {
                is tribixbite.cleverkeys.gif.GifPackImportResult.Success -> {
                    gifImportStatus = "Imported: ${result.name} (${result.gifCount} GIFs)"
                    refreshInstalledGifPacks()
                    Toast.makeText(
                        _self,
                        "GIF pack imported: ${result.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is tribixbite.cleverkeys.gif.GifPackImportResult.AlreadyInstalled -> {
                    gifImportStatus = "Pack '${result.name}' already installed"
                    Toast.makeText(
                        _self,
                        "Pack already installed: ${result.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is tribixbite.cleverkeys.gif.GifPackImportResult.Error -> {
                    gifImportStatus = "Error: ${result.message}"
                    Toast.makeText(
                        _self,
                        "Import failed: ${result.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            gifImportStatus = "Error: ${e.message}"
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
