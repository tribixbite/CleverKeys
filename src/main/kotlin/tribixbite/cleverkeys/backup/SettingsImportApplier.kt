package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.JsonParser
import tribixbite.cleverkeys.BackupRestoreManager.ImportResult

/**
 * Stateless apply step for `SettingsImportPlan`.
 *
 * Owns the rules:
 *   1. ONE editor + ONE editor.commit() — atomic write, honest "applied: N" count.
 *   2. Drift detection: snapshot prefs.all again right before commit; for every
 *      un-excluded key, compare to plan.current. Log drift count if > 0; the
 *      user's selection is honored regardless.
 *   3. Internal removes always execute (not user-toggleable).
 *   4. Short-swipe is delegated through `ShortSwipeImporter` so tests inject
 *      a stub and the real path uses `RealShortSwipeImporter`.
 */
object SettingsImportApplier {

    private const val TAG = "SettingsImportApplier"

    suspend fun apply(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
        prefs: SharedPreferences,
        shortSwipeImporter: ShortSwipeImporter,
    ): ImportResult {
        val result = ImportResult().apply {
            sourceVersion = plan.sourceVersion
            sourceScreenWidth = plan.sourceScreen.width
            sourceScreenHeight = plan.sourceScreen.height
            currentScreenWidth = plan.currentScreen.width
            currentScreenHeight = plan.currentScreen.height
        }

        val editor = prefs.edit()
        val nowBeforeCommit: Map<String, Any?> = prefs.all
        var driftCount = 0

        for (change in plan.changes) {
            if (change.key in excludedKeys) {
                result.excludedByUserCount++
                continue
            }
            if (!planCurrentMatchesNow(change.current, nowBeforeCommit[change.key])) {
                driftCount++
            }
            dispatchPut(editor, change.key, change.proposed)
            result.importedCount++
            result.importedKeys.add(change.key)
        }

        for (legacyKey in plan.internalRemoves) {
            editor.remove(legacyKey)
        }

        if (shortSwipeMode != ShortSwipeImportMode.SKIP && plan.shortSwipeImportRawJson != null) {
            val merge = (shortSwipeMode == ShortSwipeImportMode.MERGE)
            val ssCount = shortSwipeImporter.importFromJson(plan.shortSwipeImportRawJson, merge = merge)
            result.shortSwipeCustomizationsImported = ssCount
        }

        result.skippedCount = plan.parseSkippedKeys.size
        plan.parseSkippedKeys.forEach { result.skippedKeys.add(it.key) }
        result.driftCount = driftCount

        if (!editor.commit()) {
            Log.w(TAG, "editor.commit() returned false — disk full or IPC failure")
        }
        if (driftCount > 0) {
            Log.i(TAG, "BackupRestore: $driftCount keys drifted between preview and apply")
        }
        return result
    }

    private fun dispatchPut(editor: SharedPreferences.Editor, key: String, value: PrefValue) {
        when (value) {
            is PrefValue.Bool -> editor.putBoolean(key, value.v)
            is PrefValue.IntV -> editor.putInt(key, value.v)
            is PrefValue.FloatV -> editor.putFloat(key, value.v)
            is PrefValue.Str -> editor.putString(key, value.v)
            is PrefValue.JsonBlob -> editor.putString(key, value.raw)
            PrefValue.Unset -> error(
                "PrefValue.Unset is illegal inside dispatchPut — use plan.internalRemoves or excludedKeys instead"
            )
        }
    }

    private fun planCurrentMatchesNow(planCurrent: PrefValue, now: Any?): Boolean {
        if (planCurrent == PrefValue.Unset) return now == null
        return when (planCurrent) {
            is PrefValue.Bool -> now == planCurrent.v
            is PrefValue.IntV -> now == planCurrent.v
            is PrefValue.FloatV -> now == planCurrent.v
            is PrefValue.Str -> now == planCurrent.v
            is PrefValue.JsonBlob -> now is String &&
                JsonParser.parseString(now).toString() == planCurrent.raw
            PrefValue.Unset -> false   // unreachable; guard above
        }
    }
}
