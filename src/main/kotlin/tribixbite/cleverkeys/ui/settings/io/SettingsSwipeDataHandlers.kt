package tribixbite.cleverkeys.ui.settings.io

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.SettingsActivity

internal fun SettingsActivity.exportSwipeDataJSON() {
    try {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "swipe_data_${sdf.format(java.util.Date())}.json"
        swipeDataJsonExportLauncher.launch(filename)
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.exportSwipeDataNDJSON() {
    try {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "swipe_data_${sdf.format(java.util.Date())}.ndjson"
        swipeDataNdjsonExportLauncher.launch(filename)
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.performSwipeDataJsonExport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(_self)
                val count = dataStore.exportToJSON(outputStream)
                Toast.makeText(
                    _self,
                    "Exported $count swipe entries to JSON",
                    Toast.LENGTH_SHORT
                ).show()
            } ?: throw Exception("Could not open file for writing")
        } catch (e: Exception) {
            Toast.makeText(
                _self,
                "Export failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
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
                Toast.makeText(
                    _self,
                    "Exported $count swipe entries to NDJSON",
                    Toast.LENGTH_SHORT
                ).show()
            } ?: throw Exception("Could not open file for writing")
        } catch (e: Exception) {
            Toast.makeText(
                _self,
                "Export failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
