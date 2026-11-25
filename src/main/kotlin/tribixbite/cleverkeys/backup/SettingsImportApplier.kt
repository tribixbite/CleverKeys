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
 *   6. A 1.x settings file's dictionary ([SettingsImportPlan.legacyDictionary]) is written
 *      FIRST, less what the preview left out ([LegacyWordExclusions]), with its own single
 *      commit. A failure there throws before any theme or setting is written, so the import
 *      reports it (review 2026-11-25 S9: it was logged while the import reported success).
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
        legacyWords: LegacyWordExclusions = LegacyWordExclusions.NONE,
    ): ImportResult {
        val result = ImportResult().apply {
            sourceVersion = plan.sourceVersion
            sourceScreenWidth = plan.sourceScreen.width
            sourceScreenHeight = plan.sourceScreen.height
            currentScreenWidth = plan.currentScreen.width
            currentScreenHeight = plan.currentScreen.height
        }

        // Rule 6: the file's dictionary words before anything else; a failure throws from here.
        plan.legacyDictionary?.let { applyLegacyDictionary(it, legacyWords, prefs, result) }

        val editor = prefs.edit()
        val nowBeforeCommit: Map<String, Any?> = prefs.all
        var driftCount = 0

        // The DIY themes are written before the settings commit (rule 5); keep what was stored
        // so a failed commit can put it back (review 2026-11-14 M7).
        val themesBefore = if (customThemeMode != ShortSwipeImportMode.SKIP) {
            customThemeStore?.exportThemesJson()
        } else null
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

        result.skippedCount = plan.parseSkippedKeys.size
        plan.parseSkippedKeys.forEach { result.skippedKeys.add(it.key) }
        result.driftCount = driftCount

        if (!editor.commit()) {
            // Nothing was written: report a failure instead of "Applied: N" (review 2026-11-14
            // M7), and put back the DIY themes written above so the store matches the settings.
            Log.w(TAG, "editor.commit() returned false — disk full or IPC failure")
            if (themesBefore != null && result.customThemesImported > 0) {
                runCatching { customThemeStore?.replaceAll(themesBefore) }
                    .onFailure { Log.e(TAG, "DIY theme rollback after a failed commit failed", it) }
            }
            throw tribixbite.cleverkeys.ClassifiedIoException(
                tribixbite.cleverkeys.IoFailureReason.READ_WRITE, "settings commit failed"
            )
        }

        // Short swipes live outside prefs: applied only once the settings are stored.
        if (shortSwipeMode != ShortSwipeImportMode.SKIP && plan.shortSwipeImportRawJson != null) {
            val merge = (shortSwipeMode == ShortSwipeImportMode.MERGE)
            val ssCount = shortSwipeImporter.importFromJson(plan.shortSwipeImportRawJson, merge = merge)
            result.shortSwipeCustomizationsImported = ssCount
        }
        if (driftCount > 0) {
            Log.i(TAG, "BackupRestore: $driftCount keys drifted between preview and apply")
        }
        return result
    }

    /**
     * Add [dict]'s words and swipe priorities less [exclusions] (rule 6) and count them in
     * [result]. Nothing is written when the user left everything out. Throws (through
     * [DictImportApplier.apply]) when the write fails.
     */
    private fun applyLegacyDictionary(
        dict: DictImportPlan,
        exclusions: LegacyWordExclusions,
        prefs: SharedPreferences,
        result: ImportResult,
    ) {
        val selectedWords = dict.perLanguage.entries.sumOf { (lang, changes) ->
            changes.newCustomWords.keys.count { LangWord(lang, it) !in exclusions.custom } +
                changes.newDisabledWords.count { LangWord(lang, it) !in exclusions.disabled }
        }
        val priorities = exclusions.includePriorities && dict.newSwipePriorityCount > 0
        if (selectedWords == 0 && !priorities) return
        val toApply = if (exclusions.includePriorities) dict else dict.copy(mergedSwipePrioritiesByLang = emptyMap())
        val counts = DictImportApplier.apply(toApply, exclusions.custom, exclusions.disabled, prefs)
        result.customWordsAdded = counts.customApplied
        result.disabledWordsAdded = counts.disabledApplied
        result.swipePrioritiesAdded = counts.prioritiesApplied
        Log.i(TAG, "Settings file dictionary: $counts")
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

    /**
     * Numeric settings the plan carries as a String (the backup files' form, and the diff's
     * canonical one) that the app itself stores as Int: the Settings screen, `Config.set_*` and
     * Reset All write Int. An import wrote the String, so the stored type flipped with every
     * restore (Pixel, 2026-11-24); the readers accept both, but the type should not depend on
     * the writer.
     */
    private val STORED_AS_INT = setOf("clipboard_history_limit", "clipboard_max_item_size_kb", "clipboard_size_limit_mb")

    private fun dispatchPut(editor: SharedPreferences.Editor, key: String, value: PrefValue) {
        if (key in STORED_AS_INT && value is PrefValue.Str) {
            value.v.trim().toIntOrNull()?.let {
                editor.putInt(key, it)
                return
            }
        }
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
            // A [STORED_AS_INT] key's plan value is its String form (2026-11-24).
            is PrefValue.Str -> now == planCurrent.v || (now is Int && now.toString() == planCurrent.v)
            is PrefValue.JsonBlob -> now is String &&
                JsonParser.parseString(now).toString() == planCurrent.raw
            PrefValue.Unset -> false   // unreachable; guard above
        }
    }
}
