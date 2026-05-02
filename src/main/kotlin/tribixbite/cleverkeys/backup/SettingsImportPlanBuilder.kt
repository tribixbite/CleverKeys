package tribixbite.cleverkeys.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure JSON → `SettingsImportPlan` builder. No Android deps — all
 * snapshot data is injected by the caller. Mirror in `BackupRestoreManager`
 * is the IO wrapper that supplies the snapshots from real `SharedPreferences`.
 *
 * Build-time responsibilities:
 *  1. Parse + validate (today's `importPreference` logic, lifted out of IO).
 *  2. Run legacy-margin migration BEFORE diffing.
 *  3. Materialize each accepted entry as a typed `PrefValue` so apply is
 *     a pure dispatch loop.
 *  4. Categorize: changes / parseSkippedKeys / internalRemoves.
 */
object SettingsImportPlanBuilder {

    fun fromJson(
        jsonString: String,
        currentSnapshot: Map<String, Any?>,
        screen: ScreenMetrics,
    ): SettingsImportPlan {
        val root = JsonParser.parseString(jsonString).asJsonObject

        val sourceVersion = root.getAsJsonObject("metadata")
            ?.get("app_version")?.asString ?: "unknown"
        val sourceWidth = root.getAsJsonObject("metadata")
            ?.get("screen_width")?.asInt ?: screen.width
        val sourceHeight = root.getAsJsonObject("metadata")
            ?.get("screen_height")?.asInt ?: screen.height

        val sourceScreen = ScreenMetrics(sourceWidth, sourceHeight, screen.density)

        // TODO Task 1.4: implement preference iteration + diff
        return SettingsImportPlan(
            sourceVersion = sourceVersion,
            sourceScreen = sourceScreen,
            currentScreen = screen,
            changes = emptyList(),
            parseSkippedKeys = emptyList(),
            internalRemoves = emptyList(),
            shortSwipeImportSize = 0,
            shortSwipeImportRawJson = null,
        )
    }
}
