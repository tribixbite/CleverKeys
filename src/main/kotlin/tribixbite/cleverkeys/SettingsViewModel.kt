package tribixbite.cleverkeys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import tribixbite.cleverkeys.ml.SwipeMLData
import tribixbite.cleverkeys.ml.SwipeMLDataStore

/**
 * Holds transient UI state for [SettingsActivity] across configuration changes (rotation, fold).
 *
 * **Why a ViewModel?**
 * Android destroys and recreates the Activity on every configuration change.  Compose
 * `mutableStateOf` fields declared directly on an Activity are torn down with it, so
 * any in-progress UI state — section expansion, open dialogs, search query, data-viewer
 * paging — is silently reset to its default on rotation.  A ViewModel survives the
 * Activity recreation cycle, giving the user a seamless experience.
 *
 * **Scope: what's here vs what stays on the Activity**
 * Only *transient* state lives here: values produced by user interaction that have no
 * natural re-derivation after recreation.  Specifically:
 *  - Section/panel expansion flags (18 vars) — collapse/expand is a pure UI gesture.
 *  - Settings search query + highlighted-setting pulse (2 vars).
 *  - Dialog and viewer visibility flags (5 vars).
 *  - Collected-data viewer paging/content and perf-stats summary (6 vars).
 *  - Import-in-progress status strings for GIF packs and language packs (3 vars).
 *  - Test-keyboard panel state (2 vars).
 *
 * **What intentionally stays on the Activity:**
 *  - 139 prefs-backed vars — [SettingsPersistence.loadCurrentSettings] re-reads them
 *    from SharedPreferences in every `onCreate`, so they already survive rotation.
 *  - 4 recomputed-on-create vars (`installedGifPacks`, `gifStorageUsed`,
 *    `installedLanguagePacks`, `clipboardCustomRulesStatus`) — refreshed by helper
 *    functions called from `loadCurrentSettings`.
 *  - The [BackupRestoreViewModel] handles import-preview plan state separately (predates
 *    this ViewModel and is kept distinct to avoid merge churn).
 */
class SettingsViewModel : ViewModel() {

    // ── Section / panel expansion flags ──────────────────────────────────────

    var wordPredictionAdvancedExpanded by mutableStateOf(false)
    /** Activities section at top, expanded by default. */
    var activitiesSectionExpanded by mutableStateOf(true)
    var multiLangSectionExpanded by mutableStateOf(false)
    var privacySectionExpanded by mutableStateOf(false)
    /** Collapsed by default — Activities is primary entry point. */
    var swipeTypingSectionExpanded by mutableStateOf(false)
    /** No longer default-expanded since Theme moved to Activities. */
    var appearanceSectionExpanded by mutableStateOf(false)
    var swipeTrailSectionExpanded by mutableStateOf(false)
    var inputSectionExpanded by mutableStateOf(false)
    var swipeCorrectionsSectionExpanded by mutableStateOf(false)
    var gestureTuningSectionExpanded by mutableStateOf(false)
    var accessibilitySectionExpanded by mutableStateOf(false)
    var clipboardSectionExpanded by mutableStateOf(false)
    var gifSectionExpanded by mutableStateOf(false)
    var backupRestoreSectionExpanded by mutableStateOf(false)
    var advancedSectionExpanded by mutableStateOf(false)
    var infoSectionExpanded by mutableStateOf(false)
    var helpSectionExpanded by mutableStateOf(false)

    // ── Test keyboard panel (#1134) ───────────────────────────────────────────

    var testKeyboardExpanded by mutableStateOf(false)
    var testKeyboardText by mutableStateOf("")

    // ── Settings search ───────────────────────────────────────────────────────

    var settingsSearchQuery by mutableStateOf("")
    /** Highlighted setting ID for pulse animation after scroll-to. */
    var highlightedSettingId by mutableStateOf<String?>(null)

    // ── Dialog / viewer visibility ────────────────────────────────────────────

    var showCollectedDataViewer by mutableStateOf(false)
    var showPerfStatsViewer by mutableStateOf(false)
    var showGifRemoveAllDialog by mutableStateOf(false)
    var showGifRemovePackDialog by mutableStateOf<String?>(null)
    var showLanguagePackDialog by mutableStateOf(false)

    // ── Collected-data viewer paging and content ──────────────────────────────

    var collectedDataList by mutableStateOf<List<SwipeMLData>>(emptyList())
    var collectedDataStats by mutableStateOf<SwipeMLDataStore.DataStatistics?>(null)
    var collectedDataSearchQuery by mutableStateOf("")
    var collectedDataCurrentPage by mutableIntStateOf(0)
    var collectedDataTotalCount by mutableIntStateOf(0)

    // ── Performance stats viewer ──────────────────────────────────────────────

    var perfStatsSummary by mutableStateOf("")

    // ── Import-in-progress status (GIF / language pack) ──────────────────────

    var gifImportInProgress by mutableStateOf(false)
    // ARC-075: typed, so the settings section branches on the variant instead of matching
    // English message text (`startsWith("Error")`).
    var gifImportStatus by
        mutableStateOf<tribixbite.cleverkeys.ui.settings.io.GifImportStatus?>(null)
    var languagePackImportStatus by mutableStateOf<String?>(null)
}
