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
)
