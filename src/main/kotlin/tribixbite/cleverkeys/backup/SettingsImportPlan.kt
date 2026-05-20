package tribixbite.cleverkeys.backup

enum class ChangeType { ADDED, MODIFIED }

data class SettingsChange(
    val key: String,
    val current: PrefValue,
    val proposed: PrefValue,
    val type: ChangeType,
)

data class SkippedKey(
    val key: String,
    val reason: String,
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
)
