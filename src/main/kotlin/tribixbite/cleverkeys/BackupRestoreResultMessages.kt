package tribixbite.cleverkeys

/**
 * Pure rendering of `ImportResult` / `DictionaryImportResult` into the
 * user-visible body of the result dialog.
 *
 * Lifted out of `BackupRestoreActivity` so JVM tests can verify the matrix
 * of conditional lines (excluded-by-user, skipped, short-swipe applied,
 * source version, screen-size mismatch) without spinning up the activity.
 *
 * Behavior is preserved verbatim from the previous private members; the
 * activity callers are thin one-liners. NOTHING about user-visible copy
 * has been changed.
 *
 * Note: `driftCount` is intentionally NOT rendered to the user — it's a
 * logcat-only telemetry signal (spec §Drift detection).
 */

/** Render an `ImportResult` into the result dialog body. */
internal fun buildSettingsResultMessage(
    result: BackupRestoreManager.ImportResult,
): String = buildString {
    appendLine("Import completed.\n")
    appendLine("• Applied: ${result.importedCount}")
    if (result.excludedByUserCount > 0) {
        appendLine("• Excluded by you: ${result.excludedByUserCount}")
    }
    if (result.skippedCount > 0) {
        appendLine("• Invalid/skipped: ${result.skippedCount}")
    }
    if (result.shortSwipeCustomizationsImported > 0) {
        appendLine("• Short-swipe applied: ${result.shortSwipeCustomizationsImported}")
    }
    if (result.sourceVersion != "unknown") {
        appendLine("• Source version: ${result.sourceVersion}")
    }
    if (result.sourceScreenWidth > 0 && result.sourceScreenWidth != result.currentScreenWidth) {
        appendLine()
        appendLine(
            "Screen-size mismatch: source ${result.sourceScreenWidth}x${result.sourceScreenHeight}, " +
                "current ${result.currentScreenWidth}x${result.currentScreenHeight}. " +
                "Some visual settings may need adjustment."
        )
    }
    appendLine()
    append("Restart the keyboard for changes to take effect.")
}

/** Render a `DictionaryImportResult` into the result dialog body. */
internal fun buildDictResultMessage(
    result: BackupRestoreManager.DictionaryImportResult,
): String = buildString {
    appendLine("Dictionary import completed.\n")
    appendLine("• Custom words applied: ${result.userWordsImported}")
    appendLine("• Disabled words applied: ${result.disabledWordsImported}")
    if (result.excludedByUserCount > 0) appendLine("• Excluded by you: ${result.excludedByUserCount}")
    if (result.sourceVersion != "unknown") appendLine("• Source version: ${result.sourceVersion}")
}
