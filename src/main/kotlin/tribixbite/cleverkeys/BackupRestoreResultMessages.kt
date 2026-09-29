package tribixbite.cleverkeys

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Context-free seam for resolving string resources in pure render code.
 *
 * The backup/restore result builders below and the import-preview diff renderers in
 * `BackupRestorePreviewDialogs.kt` are unit-tested in `runPureTests`, which has no
 * `android.content.Context`. They therefore receive their text through this interface:
 * production passes a `Resources`-backed implementation (`ResourcesResultText`, next to the
 * preview dialogs), tests pass `EnglishResourceText`, which reads `res/values`.
 */
internal interface ResultText {
    /** Same contract as `Resources.getString(id, *args)`; no args means the raw text. */
    fun string(@StringRes id: Int, vararg args: Any): String

    /** Same contract as `Resources.getQuantityString(id, count, *args)`. */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

/**
 * Pure rendering of `ImportResult` / `DictionaryImportResult` into the
 * user-visible body of the result dialog.
 *
 * Lifted out of `BackupRestoreActivity` so JVM tests can verify the matrix
 * of conditional lines (excluded-by-user, skipped, short-swipe applied,
 * source version, screen-size mismatch) without spinning up the activity.
 * Every line is a string resource resolved through [ResultText] (2026-09-29
 * i18n sweep); the paragraph structure (blank line after the heading and
 * before the footer) is kept here, the wording lives in `res/values`.
 *
 * Note: `driftCount` is intentionally NOT rendered to the user — it's a
 * logcat-only telemetry signal (spec §Drift detection).
 */

/** Render an `ImportResult` into the result dialog body. */
internal fun buildSettingsResultMessage(
    result: BackupRestoreManager.ImportResult,
    text: ResultText,
): String = buildString {
    appendLine(text.string(R.string.backup_result_import_completed))
    appendLine()
    appendLine(text.string(R.string.backup_result_applied, result.importedCount))
    if (result.excludedByUserCount > 0) {
        appendLine(text.string(R.string.backup_result_excluded_by_you, result.excludedByUserCount))
    }
    if (result.skippedCount > 0) {
        appendLine(text.string(R.string.backup_result_not_importable, result.skippedCount))
    }
    if (result.shortSwipeCustomizationsImported > 0) {
        appendLine(
            text.string(R.string.backup_result_short_swipe_applied, result.shortSwipeCustomizationsImported)
        )
    }
    if (result.sourceVersion != "unknown") {
        appendLine(text.string(R.string.backup_result_source_version, result.sourceVersion))
    }
    if (result.sourceScreenWidth > 0 && result.sourceScreenWidth != result.currentScreenWidth) {
        appendLine()
        appendLine(
            text.string(
                R.string.backup_result_screen_mismatch,
                result.sourceScreenWidth, result.sourceScreenHeight,
                result.currentScreenWidth, result.currentScreenHeight,
            )
        )
    }
    appendLine()
    append(text.string(R.string.backup_result_restart_keyboard))
}

/** Render a `DictionaryImportResult` into the result dialog body. */
internal fun buildDictResultMessage(
    result: BackupRestoreManager.DictionaryImportResult,
    text: ResultText,
): String = buildString {
    appendLine(text.string(R.string.backup_result_dict_import_completed))
    appendLine()
    appendLine(text.string(R.string.backup_result_custom_words_applied, result.userWordsImported))
    appendLine(text.string(R.string.backup_result_disabled_words_applied, result.disabledWordsImported))
    if (result.excludedByUserCount > 0) {
        appendLine(text.string(R.string.backup_result_excluded_by_you, result.excludedByUserCount))
    }
    if (result.sourceVersion != "unknown") {
        appendLine(text.string(R.string.backup_result_source_version, result.sourceVersion))
    }
}
