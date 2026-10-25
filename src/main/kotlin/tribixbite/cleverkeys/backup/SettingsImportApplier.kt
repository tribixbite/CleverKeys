package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.JsonParser
import tribixbite.cleverkeys.BackupRestoreManager.ImportResult
import tribixbite.cleverkeys.theme.CustomThemePrefPolicy
import tribixbite.cleverkeys.theme.ThemeProvider

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
 *   5. DIY themes ([CustomThemeBackup]) are written through [CustomThemeStore] BEFORE the
 *      settings commit, so a restored `theme = custom_<id>` never reaches the keyboard ahead
 *      of its theme. A failed theme write throws before anything is committed.
 */
object SettingsImportApplier {

    private const val TAG = "SettingsImportApplier"

    suspend fun apply(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
        prefs: SharedPreferences,
        shortSwipeImporter: ShortSwipeImporter,
        customThemeMode: ShortSwipeImportMode = ShortSwipeImportMode.SKIP,
        customThemeStore: CustomThemeStore? = null,
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

        val activeThemeRemoved = applyCustomThemes(
            plan, excludedKeys, customThemeMode, customThemeStore, nowBeforeCommit, result,
        )

        for (change in plan.changes) {
            if (change.key in excludedKeys) {
                result.excludedByUserCount++
                continue
            }
            // G-4 (comprehensive audit 2026-09-06): for an ADDED change the builder's
            // `current` is the EFFECTIVE default (e.g. Bool(false)) while the key is
            // still ABSENT from prefs — comparing that default against `null` counted
            // every fresh-install ADDED row as drifted ("30 keys drifted" on a clean
            // install). For ADDED, a still-absent key means nothing moved since the
            // preview; only a key that got WRITTEN in between is a real race.
            val now = nowBeforeCommit[change.key]
            val drifted = if (change.type == ChangeType.ADDED) {
                now != null && !planCurrentMatchesNow(change.current, now)
            } else {
                !planCurrentMatchesNow(change.current, now)
            }
            if (drifted) {
                driftCount++
            }
            dispatchPut(editor, change.key, change.proposed)
            result.importedCount++
            result.importedKeys.add(change.key)
        }

        for (legacyKey in plan.internalRemoves) {
            editor.remove(legacyKey)
        }

        if (activeThemeRemoved) {
            // CustomThemePrefPolicy H-2: `theme` never names a DIY theme the store no longer has.
            Log.i(TAG, "Replace removed the selected DIY theme; selecting ${ThemeProvider.FALLBACK_THEME_ID}")
            editor.putString(CustomThemePrefPolicy.THEME_PREF_KEY, ThemeProvider.FALLBACK_THEME_ID)
            editor.putInt(CustomThemePrefPolicy.SWIPE_TRAIL_PREF_KEY, CustomThemePrefPolicy.FALLBACK_SWIPE_TRAIL_COLOR)
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

    /**
     * Write the plan's DIY themes with [mode] (see [CustomThemeBackup.merge]) and count the
     * file themes now stored in [result].
     *
     * @return true when the import removed the theme `theme` will select once the settings are
     *   committed (a Replace without it): the caller then selects the fallback theme.
     */
    private fun applyCustomThemes(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        mode: ShortSwipeImportMode,
        store: CustomThemeStore?,
        nowBeforeCommit: Map<String, Any?>,
        result: ImportResult,
    ): Boolean {
        if (mode == ShortSwipeImportMode.SKIP || store == null) return false
        val file = CustomThemeBackup.parseStored(plan.customThemesImportRawJson) ?: return false
        val current = CustomThemeBackup.parseStored(store.exportThemesJson())
            ?: error("The stored DIY themes are unreadable; refusing to overwrite them")
        val merged = CustomThemeBackup.merge(current, file, mode)
        store.replaceAll(CustomThemeBackup.toArrayJson(merged))

        val storedIds = merged.map(CustomThemeBackup::idOf).toSet()
        result.customThemesImported = file.count { CustomThemeBackup.idOf(it) in storedIds }

        val removedIds = current.map(CustomThemeBackup::idOf).toSet() - storedIds
        val themeKey = CustomThemePrefPolicy.THEME_PREF_KEY
        val importedTheme = plan.changes
            .firstOrNull { it.key == themeKey && it.key !in excludedKeys }
            ?.proposed as? PrefValue.Str
        val selected = importedTheme?.v ?: nowBeforeCommit[themeKey] as? String ?: return false
        return selected.startsWith(CUSTOM_PREFIX) && selected.removePrefix(CUSTOM_PREFIX) in removedIds
    }

    /** `theme` preference prefix of a DIY theme (`CustomThemePrefPolicy.themePrefId`). */
    private const val CUSTOM_PREFIX = "custom_"

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
