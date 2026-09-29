package tribixbite.cleverkeys.ui.settings.io

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity

/** File-format names shown in the export toast; proper nouns, identical in every locale. */
private const val SWIPE_FORMAT_JSON = "JSON"
private const val SWIPE_FORMAT_NDJSON = "NDJSON"

/** "Exported N swipe entries to <format>" toast shared by both swipe-data exporters. */
private fun SettingsActivity.toastSwipeDataExported(count: Int, format: String) {
    Toast.makeText(
        this,
        resources.getQuantityString(R.plurals.privacy_swipe_data_exported, count, count, format),
        Toast.LENGTH_SHORT,
    ).show()
}

internal fun SettingsActivity.exportSwipeDataJSON() {
    try {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "swipe_data_${sdf.format(java.util.Date())}.json"
        swipeDataJsonExportLauncher.launch(filename)
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.exportSwipeDataNDJSON() {
    try {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "swipe_data_${sdf.format(java.util.Date())}.ndjson"
        swipeDataNdjsonExportLauncher.launch(filename)
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.performSwipeDataJsonExport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(_self)
                val count = dataStore.exportToJSON(outputStream)
                toastSwipeDataExported(count, SWIPE_FORMAT_JSON)
            } ?: throw java.io.IOException(getString(R.string.privacy_export_open_failed))
        } catch (e: Exception) {
            toastExportFailed(e)
        }
    }
}

internal fun SettingsActivity.performSwipeDataNdjsonExport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(_self)
                val count = dataStore.exportToNDJSON(outputStream)
                toastSwipeDataExported(count, SWIPE_FORMAT_NDJSON)
            } ?: throw java.io.IOException(getString(R.string.privacy_export_open_failed))
        } catch (e: Exception) {
            toastExportFailed(e)
        }
    }
}
