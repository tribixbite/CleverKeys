package tribixbite.cleverkeys.backup

import androidx.annotation.StringRes
import tribixbite.cleverkeys.R

enum class ChangeType { ADDED, MODIFIED }

data class SettingsChange(
    val key: String,
    val current: PrefValue,
    val proposed: PrefValue,
    val type: ChangeType,
)

/**
 * Why a key in an imported settings file is not applied. The preview lists skipped keys with
 * [labelRes] (localized); the English [SkippedKey.reason] detail is for logs and tests.
 */
enum class SkipKind(@StringRes val labelRes: Int) {
    /** Bookkeeping pref that an import must never overwrite. */
    INTERNAL(R.string.import_preview_skip_internal),

    /** No code reads this key any more. */
    DEPRECATED(R.string.import_preview_skip_deprecated),

    /** Dictionary words: restored by the dictionary import, not the settings import. */
    SEPARATE_IMPORT(R.string.import_preview_skip_separate_import),

    /** The JSON value has a shape no preference uses (array, nested object, …). */
    UNREADABLE(R.string.import_preview_skip_unreadable),

    /** Wrong type or outside the accepted range. */
    INVALID_VALUE(R.string.import_preview_skip_invalid_value),

    /** A legacy key converted into its replacement keys. */
    SUPERSEDED(R.string.import_preview_skip_superseded),
}

data class SkippedKey(
    val key: String,
    /** English detail for logs and tests (e.g. "out of range, got 40"); not shown. */
    val reason: String,
    val kind: SkipKind,
)

/**
 * Output of `buildSettingsImportPlan`. Pure data — no Android deps.
 *
 * `internalRemoves` is auto-applied alongside its counterpart `Put` and is
 * NOT user-toggleable. `excludedKeys` passed to `applySettingsImportPlan`
 * filters only `changes` rows.
 */
data class SettingsImportPlan(
    val sourceVersion: String,
    val sourceScreen: ScreenMetrics,
    val currentScreen: ScreenMetrics,
    val changes: List<SettingsChange>,
    val parseSkippedKeys: List<SkippedKey>,
    val internalRemoves: List<String>,
    val shortSwipeImportSize: Int,
    val shortSwipeImportRawJson: String?,
    /**
     * Snapshot of the CURRENT device's short-swipe customizations JSON
     * (same shape as the import-side `shortSwipeImportRawJson`). When
     * present, the preview dialog renders a structured diff between the
     * two JSON blobs above the Skip/Merge/Replace radio so the user sees
     * exactly which key+direction mappings are about to change.
     *
     * `null` when the runtime can't or hasn't provided it — the dialog
     * falls back to just the count + radio buttons.
     */
    val currentShortSwipeRawJson: String? = null,
    /**
     * ARC-036: whether the file this plan was built from was an encrypted `CKENC1` container and,
     * if so, when it was exported. Rendered by the preview dialog — the backup-encryption design
     * accepted the replay risk (§7 residual #2) precisely because a stale export date is visible
     * before the user accepts. Defaults to [BackupSourceInfo.PLAINTEXT] so the pure planner and
     * its tests need no knowledge of the crypto layer; the manager overwrites it after reading.
     */
    val source: BackupSourceInfo = BackupSourceInfo.PLAINTEXT,
)
