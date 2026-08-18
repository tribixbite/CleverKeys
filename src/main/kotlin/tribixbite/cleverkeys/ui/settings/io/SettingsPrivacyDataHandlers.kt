package tribixbite.cleverkeys.ui.settings.io

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.SwipePerformanceStats
import tribixbite.cleverkeys.SettingsActivity

/**
 * View collected swipe data in a dialog with pagination
 */
internal fun SettingsActivity.viewCollectedData() {
    collectedDataSearchQuery = ""
    collectedDataCurrentPage = 0
    loadCollectedDataPage()
    showCollectedDataViewer = true
}

/**
 * Load a page of collected data based on current search/pagination state
 */
internal fun SettingsActivity.loadCollectedDataPage() {
    val _self = this
    lifecycleScope.launch {
        try {
            val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(_self)
            val offset = collectedDataCurrentPage * collectedDataPageSize

            // Get total count for pagination
            collectedDataTotalCount = dataStore.countSearchResults(collectedDataSearchQuery)

            // Load page data
            collectedDataList = if (collectedDataSearchQuery.isEmpty()) {
                dataStore.loadPaginatedData(collectedDataPageSize, offset)
            } else {
                dataStore.searchByWord(collectedDataSearchQuery, collectedDataPageSize, offset)
            }

            // Update stats (only on first load)
            if (collectedDataStats == null) {
                collectedDataStats = dataStore.getStatistics()
            }
        } catch (e: Exception) {
            Toast.makeText(_self, "Error loading data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * View performance statistics in a dialog
 */
internal fun SettingsActivity.viewPerfStats() {
    try {
        val stats = SwipePerformanceStats.getInstance(this)
        perfStatsSummary = stats.formatSummary()
        showPerfStatsViewer = true
    } catch (e: Exception) {
        Toast.makeText(this, "Error loading stats: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Export performance statistics to JSON file
 */
internal fun SettingsActivity.exportPerfStats() {
    try {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "perf_stats_${sdf.format(java.util.Date())}.json"
        perfStatsExportLauncher.launch(filename)
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Delete all collected swipe data with confirmation
 */
internal fun SettingsActivity.deleteCollectedData() {
    android.app.AlertDialog.Builder(this)
        .setTitle("Delete Collected Data")
        .setMessage("This will permanently delete all collected swipe data.\n\n" +
            "This action cannot be undone.")
        .setPositiveButton("Delete") { _, _ ->
            val _self = this
            lifecycleScope.launch {
                try {
                    val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(_self)
                    dataStore.clearAllData()
                    Toast.makeText(_self, "All swipe data deleted", Toast.LENGTH_SHORT).show()
                    // Force UI refresh by recreating activity
                    _self.recreate()
                } catch (e: Exception) {
                    Toast.makeText(_self, "Error deleting data: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.performPerfStatsExport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val stats = SwipePerformanceStats.getInstance(_self)
                val json = org.json.JSONObject().apply {
                    put("export_timestamp", System.currentTimeMillis())
                    put("total_predictions", stats.getTotalPredictions())
                    put("total_inference_time_ms", stats.getTotalInferenceTime())
                    put("average_inference_time_ms", stats.getAverageInferenceTime())
                    put("total_selections", stats.getTotalSelections())
                    put("top1_selections", stats.getTop1Selections())
                    put("top3_selections", stats.getTop3Selections())
                    put("top1_accuracy_pct", stats.getTop1Accuracy())
                    put("top3_accuracy_pct", stats.getTop3Accuracy())
                    put("model_load_time_ms", stats.getModelLoadTime())
                    put("days_tracked", stats.getDaysSinceStart())
                    put("first_stat_timestamp", stats.getFirstStatTimestamp())
                }
                java.io.OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(json.toString(2))
                }
                Toast.makeText(
                    _self,
                    "Performance stats exported",
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
