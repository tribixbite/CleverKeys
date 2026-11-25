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
 * before a footer) is kept here, the wording lives in `res/values`.
 *
 * Settings and full-backup results carry no "restart the keyboard" footer (2026-11-24): an
 * import reaches the running keyboard through its preference listener (layout, size and theme
 * changed on screen on the Pixel with the same process) and the shared word and learned-data
 * stores. The one setting that waits for a new encoder session says so in its own description.
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
    if (result.customThemesImported > 0) {
        appendLine(text.string(R.string.backup_result_custom_themes_applied, result.customThemesImported))
    }
    // Words a 1.x settings file carried (2026-11-24).
    if (result.customWordsAdded > 0) {
        appendLine(text.string(R.string.backup_result_custom_words_applied, result.customWordsAdded))
    }
    if (result.disabledWordsAdded > 0) {
        appendLine(text.string(R.string.backup_result_disabled_words_applied, result.disabledWordsAdded))
    }
    if (result.swipePrioritiesAdded > 0) {
        appendLine(text.string(R.string.backup_result_swipe_priorities_applied, result.swipePrioritiesAdded))
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
}.trimEnd()

/**
 * Render a `FullBackupImportResult` into the result dialog body. Lifted out of
 * `SettingsActivity.performFullBackupImport` (2026-11-24) so the lines are tested like the
 * single-file results above.
 */
internal fun buildFullBackupResultMessage(
    result: BackupRestoreManager.FullBackupImportResult,
    text: ResultText,
): String = buildString {
    appendLine(text.string(R.string.backup_result_full_import_completed))
    appendLine()
    appendLine(text.string(R.string.backup_result_full_settings_applied, result.configKeysApplied))
    appendLine(text.string(R.string.backup_result_full_custom_words, result.customWordsImported))
    appendLine(text.string(R.string.backup_result_full_disabled_words, result.disabledWordsImported))
    if (result.customThemesImported > 0) {
        appendLine(text.string(R.string.backup_result_custom_themes_applied, result.customThemesImported))
    }
    if (result.learnedDataIncomplete) {
        appendLine(text.string(R.string.backup_result_learned_data_incomplete))
    }
    appendLine(text.string(R.string.backup_result_full_clipboard_imported, result.clipboardEntriesImported))
    appendLine(text.string(R.string.backup_result_full_clipboard_skipped, result.clipboardEntriesSkipped))
    appendLine(text.string(R.string.backup_result_media_restored, result.mediaFilesRestored))
    result.sourceAppVersion?.let {
        appendLine(text.string(R.string.backup_result_source_version, it))
    }
}.trimEnd()

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
    // Audit M2: a corrupt learned-data section is no longer reported as restored.
    if (result.learnedDataIncomplete) {
        appendLine(text.string(R.string.backup_result_learned_data_incomplete))
    }
    if (result.sourceVersion != "unknown") {
        appendLine(text.string(R.string.backup_result_source_version, result.sourceVersion))
    }
}

/**
 * Render a clipboard restore into the result dialog body: the JSON import, or with [withMedia]
 * the ZIP import. The file's app version shows only when the file names one (clipboard exports
 * since 2026-11-24); its export date has its own line — it used to be shown as the version
 * ("Source version: 2026-09-08 12:18:21", Pixel). The ZIP says media was restored only when some
 * was.
 */
internal fun buildClipboardImportResultMessage(
    result: BackupRestoreManager.ClipboardImportResult,
    withMedia: Boolean,
    text: ResultText,
): String = buildString {
    appendLine(
        text.string(
            if (withMedia) R.string.clipboard_backup_zip_import_completed
            else R.string.clipboard_backup_import_completed
        )
    )
    appendLine()
    appendLine(text.plural(R.plurals.clipboard_backup_imported_entries, result.importedCount, result.importedCount))
    appendLine(text.plural(R.plurals.clipboard_backup_skipped_duplicates, result.skippedCount, result.skippedCount))
    if (withMedia) {
        appendLine(text.string(R.string.backup_result_media_restored, result.mediaFilesRestored))
    }
    if (result.sourceVersion != "unknown") {
        appendLine(text.string(R.string.backup_result_source_version, result.sourceVersion))
    }
    result.exportDate?.let { appendLine(text.string(R.string.backup_result_export_date, it)) }
    appendLine()
    append(
        text.string(
            if (withMedia && result.mediaFilesRestored > 0) R.string.clipboard_backup_zip_media_restored_note
            else R.string.clipboard_backup_import_merge_note
        )
    )
}
