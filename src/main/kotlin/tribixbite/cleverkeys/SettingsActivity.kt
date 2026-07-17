package tribixbite.cleverkeys

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.theme.KeyboardTheme
import tribixbite.cleverkeys.langpack.LanguagePackManifest
import tribixbite.cleverkeys.ui.settings.SearchableSetting
import tribixbite.cleverkeys.ui.settings.expanderFor
import tribixbite.cleverkeys.ui.settings.scrollToSetting
import tribixbite.cleverkeys.ui.settings.sectionDisplayName
import tribixbite.cleverkeys.ui.settings.settingSlug
import tribixbite.cleverkeys.ui.settings.io.applyPlannedDictionaries
import tribixbite.cleverkeys.ui.settings.io.applyPlannedSettings
import tribixbite.cleverkeys.ui.settings.io.handleCustomRulesPicked
import tribixbite.cleverkeys.ui.settings.io.handleGifPackShareIntent
import tribixbite.cleverkeys.ui.settings.io.performClipboardExport
import tribixbite.cleverkeys.ui.settings.io.performClipboardImport
import tribixbite.cleverkeys.ui.settings.io.performClipboardZipExport
import tribixbite.cleverkeys.ui.settings.io.performClipboardZipImport
import tribixbite.cleverkeys.ui.settings.io.performConfigExport
import tribixbite.cleverkeys.ui.settings.io.performConfigImport
import tribixbite.cleverkeys.ui.settings.io.performDictionaryExport
import tribixbite.cleverkeys.ui.settings.io.performDictionaryImport
import tribixbite.cleverkeys.ui.settings.io.performFullBackupExport
import tribixbite.cleverkeys.ui.settings.io.performFullBackupImport
import tribixbite.cleverkeys.ui.settings.io.performGifPackImport
import tribixbite.cleverkeys.ui.settings.io.performLanguagePackImport
import tribixbite.cleverkeys.ui.settings.io.performPerfStatsExport
import tribixbite.cleverkeys.ui.settings.io.performSwipeDataJsonExport
import tribixbite.cleverkeys.ui.settings.io.performSwipeDataNdjsonExport
import tribixbite.cleverkeys.ui.settings.fallbackEncrypted
import tribixbite.cleverkeys.ui.settings.handlePreferenceChanged
import tribixbite.cleverkeys.ui.settings.loadCurrentSettings
import tribixbite.cleverkeys.ui.settings.SettingsScreen

/**
 * Shell host for the CleverKeys settings UI.
 *
 * Owns: all mutable Compose state fields, the 14-16 SAF ActivityResult launchers,
 * lifecycle callbacks (onCreate → setContent { SettingsScreen() }, onResume/onPause
 * for preference listener registration, onSharedPreferenceChanged → handlePreferenceChanged,
 * onNewIntent → handleGifPackShareIntent), and the BackupRestoreViewModel held across
 * configuration changes.
 *
 * Screen/section composables, IO handlers, search/scroll logic, and persistence helpers
 * live in ui/settings/ — see that package for the decomposed extension files.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
class SettingsActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        internal const val TAG = "SettingsActivity"

        /**
         * Broadcast sent when any URL-sanitization toggle changes or the custom-rules
         * file is replaced. ClipboardHistoryService listens and invalidates its cached
         * SanitizationConfig so the next clipboard insert sees the new ruleset.
         */
        const val ACTION_SANITIZATION_RULES_CHANGED =
            "tribixbite.cleverkeys.action.SANITIZATION_RULES_CHANGED"

        /**
         * Public folder containing the prebuilt language-pack ZIPs (de, el, es, fr, it,
         * nl, pt, ru, sv, tr, ...). Users download a ZIP here and import it via SAF — the
         * app has no INTERNET permission, so this only opens the browser. Matches the
         * canonical location referenced by docs/wiki/layouts/language-packs.md.
         */
        const val LANGUAGE_PACKS_URL =
            "https://github.com/tribixbite/CleverKeys/tree/main/scripts/dictionaries"

        /**
         * Test-only override for the inline Backup & Restore flow's manager.
         * Instrumented tests set this in @Before, clear it in @After. Mirrors
         * [BackupRestoreActivity.testManagerOverride] — used by the migrated
         * ImportPreview tests that exercise the inline preview dispatch.
         */
        @androidx.annotation.VisibleForTesting
        var testBackupRestoreManagerOverride: BackupRestoreManager? = null
    }

    // Configuration state
    internal lateinit var config: Config
    internal lateinit var prefs: SharedPreferences
    internal lateinit var backupRestoreManager: BackupRestoreManager

    /** Stage B (backup encryption): the user's backup-passphrase store. */
    internal val backupPassphraseStore: tribixbite.cleverkeys.backup.crypto.BackupPassphraseStore by lazy {
        tribixbite.cleverkeys.backup.crypto.BackupPassphraseStore(this)
    }

    // ViewModel hosts the import-preview state so plan + dialog state survive
    // configuration changes (rotation). See BackupRestoreViewModel for fields.
    internal val backupRestoreViewModel: BackupRestoreViewModel by viewModels()

    /**
     * Hosts transient UI state (section expansion, search, dialogs, data-viewer paging)
     * so it survives configuration changes. Prefs-backed values intentionally remain on
     * the Activity — [loadCurrentSettings] re-reads them from SharedPreferences every
     * onCreate, so they already survive rotation.  See [SettingsViewModel] for full scope.
     */
    internal val settingsViewModel: SettingsViewModel by viewModels()

    /**
     * Stage B: when `true`, the NEXT export launcher callback writes a PLAINTEXT file
     * (the "Export unencrypted…" opt-out). Consumed-and-reset by [consumePlaintextOptOut]
     * so it never leaks into a later export. Only relevant while a passphrase is set.
     */
    internal var pendingPlaintextExport: Boolean = false

    /** Read-and-clear [pendingPlaintextExport] (one-shot). */
    internal fun consumePlaintextOptOut(): Boolean {
        val v = pendingPlaintextExport
        pendingPlaintextExport = false
        return v
    }

    // SAF file pickers for backup/restore
    internal val configExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performConfigExport(it, consumePlaintextOptOut()) }
    }

    internal val configImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performConfigImport(it) }
    }

    internal val dictionaryExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performDictionaryExport(it, consumePlaintextOptOut()) }
    }

    internal val dictionaryImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performDictionaryImport(it) }
    }

    internal val clipboardExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performClipboardExport(it, consumePlaintextOptOut()) }
    }

    internal val clipboardImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performClipboardImport(it) }
    }

    // ZIP variants — full clipboard backup including media files
    internal val clipboardZipExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { performClipboardZipExport(it, consumePlaintextOptOut()) }
    }

    internal val clipboardZipImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performClipboardZipImport(it) }
    }

    // GitHub #142: one-click full backup ZIP — manifest + config + dicts + clipboard + media
    internal val fullBackupExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { performFullBackupExport(it, consumePlaintextOptOut()) }
    }

    internal val fullBackupImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performFullBackupImport(it) }
    }

    // SAF file pickers for swipe ML data export
    internal val swipeDataJsonExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performSwipeDataJsonExport(it) }
    }

    internal val swipeDataNdjsonExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri: Uri? ->
        uri?.let { performSwipeDataNdjsonExport(it) }
    }

    // Performance metrics export launcher
    internal val perfStatsExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performPerfStatsExport(it) }
    }

    // Language pack import launcher
    internal val languagePackImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performLanguagePackImport(it) }
    }

    internal val gifPackImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performGifPackImport(it) }
    }

    // Custom URL-sanitization rules (Chunk 4). SAF-only, mime-restricted to JSON.
    internal val customRulesPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleCustomRulesPicked(uri)
    }

    // Settings state for reactive UI
    internal var beamWidth by mutableStateOf(6)
    internal var maxLength by mutableStateOf(20)
    internal var confidenceThreshold by mutableStateOf(0.01f)
    internal var keyboardHeight by mutableStateOf(28)
    internal var keyboardHeightLandscape by mutableStateOf(50)
    internal var vibrationEnabled by mutableStateOf(false)
    internal var debugEnabled by mutableStateOf(false)
    internal var clipboardHistoryEnabled by mutableStateOf(true)
    internal var clipboardHistoryLimit by mutableStateOf(Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK)
    internal var clipboardHistoryDuration by mutableStateOf(-1)  // Minutes; -1 = never expire
    internal var clipboardPaneHeightPercent by mutableStateOf(30)
    internal var clipboardMaxItemSizeKb by mutableStateOf(500)
    internal var clipboardLimitType by mutableStateOf("count") // "count" or "size"
    internal var clipboardSizeLimitMb by mutableStateOf(10)
    internal var clipboardExcludePasswordManagers by mutableStateOf(true)  // Privacy: skip password managers
    internal var clipboardRespectSensitiveFlag by mutableStateOf(true)  // #86: Respect IS_SENSITIVE flag
    internal var clipboardTextOnly by mutableStateOf(false)  // v4: Hide media entries
    internal var clipboardPinnedEnabled by mutableStateOf(true)  // v4: Show/hide pinned tab
    internal var clipboardTodoEnabled by mutableStateOf(true)  // v4: Show/hide todo tab

    // URL sanitization (Chunk 4): three independent toggles + custom-rules import
    internal var clipboardSanitizeLinksEnabled by mutableStateOf(false)
    internal var clipboardEmbedEnrichEnabled by mutableStateOf(false)
    internal var clipboardCustomRulesEnabled by mutableStateOf(false)
    internal var clipboardSanitizeSystemClipboard by mutableStateOf(true)
    internal var clipboardCustomRulesUri by mutableStateOf<String?>(null)
    // Status text for the custom-rules row — examples:
    //  "" / "12 providers loaded." / "Saved file is malformed: ..." / "URI persisted but no copy on disk yet."
    internal var clipboardCustomRulesStatus by mutableStateOf("")

    // GIF Panel (opt-in, off by default)
    internal var gifEnabled by mutableStateOf(Defaults.GIF_ENABLED)
    internal var gifThumbnailColumns by mutableStateOf(Defaults.GIF_THUMBNAIL_COLUMNS)
    internal var installedGifPacks by mutableStateOf(listOf<tribixbite.cleverkeys.gif.InstalledPackInfo>())
    // gifImportInProgress/gifImportStatus delegated to settingsViewModel (survive rotation)
    internal var gifImportInProgress: Boolean
        get() = settingsViewModel.gifImportInProgress
        set(value) { settingsViewModel.gifImportInProgress = value }
    internal var gifImportStatus: String?
        get() = settingsViewModel.gifImportStatus
        set(value) { settingsViewModel.gifImportStatus = value }
    // showGifRemoveAllDialog/showGifRemovePackDialog delegated to settingsViewModel (survive rotation)
    internal var showGifRemoveAllDialog: Boolean
        get() = settingsViewModel.showGifRemoveAllDialog
        set(value) { settingsViewModel.showGifRemoveAllDialog = value }
    internal var showGifRemovePackDialog: String?
        get() = settingsViewModel.showGifRemovePackDialog
        set(value) { settingsViewModel.showGifRemovePackDialog = value }
    internal var gifStorageUsed by mutableStateOf(0L)

    internal var autoCapitalizationEnabled by mutableStateOf(true)
    internal var capitalizeIWords by mutableStateOf(true)  // #72: Auto-capitalize I, I'm, I'll, etc.

    // Phase 1: Expose existing Config.kt settings
    internal var swipeTypingEnabled by mutableStateOf(true)  // Master switch for swipe typing (default ON for CleverKeys)
    internal var swipeOnPasswordFields by mutableStateOf(false)  // #39: Allow swipe on password fields
    internal var currentLayoutSupportsSwipe by mutableStateOf(true)  // #9: False for non-QWERTY layouts
    internal var currentLayoutName by mutableStateOf("")  // #9: Display name of active layout
    internal var wordPredictionEnabled by mutableStateOf(true)  // Match Config.kt default
    internal var autoSpaceAfterSuggestion by mutableStateOf(true)  // #82: Add trailing space after selecting suggestion
    internal var autoSpaceBeforeSuggestion by mutableStateOf(true)  // Add leading space before tapped suggestion
    internal var backspaceUndoSwipe by mutableStateOf(true)  // #110: Backspace after swipe deletes entire swiped word
    internal var backspaceUndoAutocorrect by mutableStateOf(true)  // #110: Backspace after autocorrect reverts to original word
    internal var suggestionBarOpacity by mutableStateOf(90)
    internal var autoCorrectEnabled by mutableStateOf(true)
    internal var termuxModeEnabled by mutableStateOf(false)
    internal var vibrationDuration by mutableStateOf(20)
    // Per-event haptic feedback toggles
    internal var hapticKeyPress by mutableStateOf(Defaults.HAPTIC_KEY_PRESS)
    internal var hapticPredictionTap by mutableStateOf(Defaults.HAPTIC_PREDICTION_TAP)
    internal var hapticTrackpointActivate by mutableStateOf(Defaults.HAPTIC_TRACKPOINT_ACTIVATE)
    internal var hapticLongPress by mutableStateOf(Defaults.HAPTIC_LONG_PRESS)
    internal var hapticSwipeComplete by mutableStateOf(Defaults.HAPTIC_SWIPE_COMPLETE)
    internal var swipeDebugEnabled by mutableStateOf(false)

    // Adaptive layout settings (percentages of screen dimensions)
    internal var marginBottomPortrait by mutableStateOf(Defaults.MARGIN_BOTTOM_PORTRAIT)
    internal var marginBottomLandscape by mutableStateOf(Defaults.MARGIN_BOTTOM_LANDSCAPE)
    internal var marginLeftPortrait by mutableStateOf(Defaults.MARGIN_LEFT_PORTRAIT)
    internal var marginLeftLandscape by mutableStateOf(Defaults.MARGIN_LEFT_LANDSCAPE)
    internal var marginRightPortrait by mutableStateOf(Defaults.MARGIN_RIGHT_PORTRAIT)
    internal var marginRightLandscape by mutableStateOf(Defaults.MARGIN_RIGHT_LANDSCAPE)

    // Gesture sensitivity settings
    internal var swipeDistance by mutableStateOf(23)
    internal var circleSensitivity by mutableStateOf(2)
    internal var sliderSensitivity by mutableStateOf(30) // Phase 5: Space bar slider (0-100%)

    // Long press settings
    internal var longPressTimeout by mutableStateOf(600)
    internal var longPressInterval by mutableStateOf(65)
    internal var keyRepeatEnabled by mutableStateOf(true)
    internal var keyRepeatBackspaceOnly by mutableStateOf(false)  // #81: Only repeat backspace/nav

    // Visual customization settings
    internal var labelBrightness by mutableStateOf(100)
    internal var keyboardOpacity by mutableStateOf(100)
    internal var keyOpacity by mutableStateOf(100)
    internal var keyActivatedOpacity by mutableStateOf(100)

    // Spacing and sizing settings
    internal var characterSize by mutableStateOf(115)
    internal var secondaryLabelSizeScale by mutableStateOf(100) // #133: percent; 100 = unchanged
    internal var keyVerticalMargin by mutableStateOf(150)
    internal var keyHorizontalMargin by mutableStateOf(200)

    // Border customization settings
    internal var borderConfigEnabled by mutableStateOf(false)
    internal var customBorderRadius by mutableStateOf(0)
    internal var customBorderLineWidth by mutableStateOf(0)

    // Behavior settings
    internal var doubleTapLockShift by mutableStateOf(false)
    internal var switchInputImmediate by mutableStateOf(false)
    internal var smartPunctuationEnabled by mutableStateOf(true) // Attach punctuation to end of last word

    // Gesture tuning settings
    internal var tapDurationThreshold by mutableStateOf(150) // ms
    internal var doubleSpaceToPeriod by mutableStateOf(true) // Enable double-space-to-period
    internal var doubleSpaceThreshold by mutableStateOf(500) // ms
    internal var swipeMinDistance by mutableStateOf(72f) // pixels
    internal var swipeMinKeyDistance by mutableStateOf(38f) // pixels
    internal var swipeMinDwellTime by mutableStateOf(10) // ms
    internal var swipeNoiseThreshold by mutableStateOf(2.0f) // pixels
    internal var swipeHighVelocityThreshold by mutableStateOf(1000f) // px/sec
    internal var fingerOcclusionOffset by mutableStateOf(12.5f) // % of row height
    internal var sliderSpeedSmoothing by mutableStateOf(0.7f) // 0.0-1.0
    internal var sliderSpeedMax by mutableStateOf(4.0f) // multiplier

    // Number row and numpad settings
    internal var numberRowMode by mutableStateOf("no_number_row") // "no_number_row", "no_symbols", "symbols"
    internal var showNumpadMode by mutableStateOf("never") // "never", "landscape", "always"
    internal var numpadLayout by mutableStateOf("default") // "default", "low_first"
    internal var pinEntryEnabled by mutableStateOf(false)

    // Accessibility settings (Bug #373, #368, #377)
    internal var stickyKeysEnabled by mutableStateOf(false)
    internal var stickyKeysTimeout by mutableStateOf(5000) // milliseconds
    internal var voiceGuidanceEnabled by mutableStateOf(false)

    // Swipe Corrections settings (migrated from XML)
    internal var swipeBeamAutocorrectEnabled by mutableStateOf(true)
    internal var swipeFinalAutocorrectEnabled by mutableStateOf(true)
    internal var swipeCorrectionPreset by mutableStateOf("balanced")
    internal var swipeFuzzyMatchMode by mutableStateOf("edit_distance")
    internal var autocorrectMaxLengthDiff by mutableStateOf(2)
    internal var autocorrectPrefixLength by mutableStateOf(1)
    internal var autocorrectMaxBeamCandidates by mutableStateOf(3)
    internal var swipePredictionSource by mutableStateOf(80)
    internal var swipeCommonWordsBoost by mutableStateOf(1.0f)
    internal var swipeTop5000Boost by mutableStateOf(1.0f)
    internal var swipeRareWordsPenalty by mutableStateOf(1.0f)

    // Swipe trail appearance settings
    internal var swipeTrailEnabled by mutableStateOf(true)
    internal var swipeTrailEffect by mutableStateOf("glow")
    internal var swipeTrailColor by mutableStateOf(0xFF9B59B6.toInt()) // Jewel purple
    internal var swipeTrailWidth by mutableStateOf(8.0f)
    internal var swipeTrailGlowRadius by mutableStateOf(12.0f)

    // Word Prediction Advanced settings
    internal var contextAwarePredictionsEnabled by mutableStateOf(true)
    internal var personalizedLearningEnabled by mutableStateOf(true)
    internal var learningAggression by mutableStateOf("BALANCED")
    internal var predictionContextBoost by mutableStateOf(2.0f)
    internal var predictionFrequencyScale by mutableStateOf(1000f)

    // Auto-correction advanced settings
    internal var autocorrectMinWordLength by mutableStateOf(3)
    internal var autocorrectCharMatchThreshold by mutableStateOf(0.67f)
    internal var autocorrectMinFrequency by mutableStateOf(500)

    // Multi-language settings
    internal var multiLangEnabled by mutableStateOf(false)
    internal var primaryLanguage by mutableStateOf("en")
    internal var secondaryLanguage by mutableStateOf("none") // "none", "es", "fr", etc.
    internal var autoDetectLanguage by mutableStateOf(true)
    internal var languageDetectionSensitivity by mutableStateOf(0.6f)
    internal var secondaryPredictionWeight by mutableStateOf(0.9f) // v1.1.94: Secondary dictionary weight
    internal var prefixBoostMultiplier by mutableStateOf(Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER)
    internal var prefixBoostMax by mutableStateOf(Defaults.NEURAL_PREFIX_BOOST_MAX)
    internal var maxCumulativeBoost by mutableStateOf(Defaults.NEURAL_MAX_CUMULATIVE_BOOST)
    internal var strictStartChar by mutableStateOf(Defaults.NEURAL_STRICT_START_CHAR)
    internal var primaryLanguageAlt by mutableStateOf("es") // v1.2.0: Alternate primary for quick toggle
    internal var secondaryLanguageAlt by mutableStateOf("none") // v1.2.0: Alternate secondary for quick toggle
    internal var availableSecondaryLanguages by mutableStateOf(listOf<String>()) // V2 dictionaries
    internal var installedLanguagePacks by mutableStateOf(listOf<LanguagePackManifest>())
    // showLanguagePackDialog/languagePackImportStatus delegated to settingsViewModel (survive rotation)
    internal var showLanguagePackDialog: Boolean
        get() = settingsViewModel.showLanguagePackDialog
        set(value) { settingsViewModel.showLanguagePackDialog = value }
    internal var languagePackImportStatus: String?
        get() = settingsViewModel.languagePackImportStatus
        set(value) { settingsViewModel.languagePackImportStatus = value }

    // Privacy settings - all OFF by default (CleverKeys is fully offline)
    internal var privacyCollectSwipe by mutableStateOf(false)
    internal var privacyCollectPerformance by mutableStateOf(false)

    // Short gesture settings
    internal var shortGesturesEnabled by mutableStateOf(true)
    internal var shortGestureMinDistance by mutableStateOf(37)
    internal var shortGestureMaxDistance by mutableStateOf(141)

    // Selection-delete mode settings (backspace swipe+hold)
    internal var selectionDeleteVerticalThreshold by mutableStateOf(40)
    internal var selectionDeleteVerticalSpeed by mutableStateOf(0.4f)

    // Swipe debug advanced settings
    internal var swipeDebugDetailedLogging by mutableStateOf(false)
    internal var swipeDebugShowRawOutput by mutableStateOf(true)
    internal var swipeShowRawBeamPredictions by mutableStateOf(false)

    // Section expanded states — delegated to settingsViewModel (survive rotation)
    // v1.2.6: dictionarySectionExpanded removed - Dictionary Manager moved to Activities
    internal var wordPredictionAdvancedExpanded: Boolean
        get() = settingsViewModel.wordPredictionAdvancedExpanded
        set(value) { settingsViewModel.wordPredictionAdvancedExpanded = value }
    internal var activitiesSectionExpanded: Boolean  // Activities at top, default expanded
        get() = settingsViewModel.activitiesSectionExpanded
        set(value) { settingsViewModel.activitiesSectionExpanded = value }
    internal var multiLangSectionExpanded: Boolean
        get() = settingsViewModel.multiLangSectionExpanded
        set(value) { settingsViewModel.multiLangSectionExpanded = value }
    internal var privacySectionExpanded: Boolean
        get() = settingsViewModel.privacySectionExpanded
        set(value) { settingsViewModel.privacySectionExpanded = value }
    internal var neuralSectionExpanded: Boolean  // Collapsed by default, Activities is primary
        get() = settingsViewModel.neuralSectionExpanded
        set(value) { settingsViewModel.neuralSectionExpanded = value }
    internal var appearanceSectionExpanded: Boolean  // No longer default expanded since Theme is in Activities
        get() = settingsViewModel.appearanceSectionExpanded
        set(value) { settingsViewModel.appearanceSectionExpanded = value }
    internal var swipeTrailSectionExpanded: Boolean
        get() = settingsViewModel.swipeTrailSectionExpanded
        set(value) { settingsViewModel.swipeTrailSectionExpanded = value }
    internal var inputSectionExpanded: Boolean
        get() = settingsViewModel.inputSectionExpanded
        set(value) { settingsViewModel.inputSectionExpanded = value }
    internal var swipeCorrectionsSectionExpanded: Boolean
        get() = settingsViewModel.swipeCorrectionsSectionExpanded
        set(value) { settingsViewModel.swipeCorrectionsSectionExpanded = value }
    internal var gestureTuningSectionExpanded: Boolean
        get() = settingsViewModel.gestureTuningSectionExpanded
        set(value) { settingsViewModel.gestureTuningSectionExpanded = value }
    internal var accessibilitySectionExpanded: Boolean
        get() = settingsViewModel.accessibilitySectionExpanded
        set(value) { settingsViewModel.accessibilitySectionExpanded = value }
    internal var clipboardSectionExpanded: Boolean
        get() = settingsViewModel.clipboardSectionExpanded
        set(value) { settingsViewModel.clipboardSectionExpanded = value }
    internal var gifSectionExpanded: Boolean
        get() = settingsViewModel.gifSectionExpanded
        set(value) { settingsViewModel.gifSectionExpanded = value }
    internal var backupRestoreSectionExpanded: Boolean
        get() = settingsViewModel.backupRestoreSectionExpanded
        set(value) { settingsViewModel.backupRestoreSectionExpanded = value }
    internal var advancedSectionExpanded: Boolean
        get() = settingsViewModel.advancedSectionExpanded
        set(value) { settingsViewModel.advancedSectionExpanded = value }
    internal var infoSectionExpanded: Boolean
        get() = settingsViewModel.infoSectionExpanded
        set(value) { settingsViewModel.infoSectionExpanded = value }
    internal var helpSectionExpanded: Boolean
        get() = settingsViewModel.helpSectionExpanded
        set(value) { settingsViewModel.helpSectionExpanded = value }

    // Test keyboard field (#1134: test input without leaving settings) — delegated to settingsViewModel
    internal var testKeyboardExpanded: Boolean
        get() = settingsViewModel.testKeyboardExpanded
        set(value) { settingsViewModel.testKeyboardExpanded = value }
    internal var testKeyboardText: String
        get() = settingsViewModel.testKeyboardText
        set(value) { settingsViewModel.testKeyboardText = value }

    // Settings search — delegated to settingsViewModel (survive rotation)
    internal var settingsSearchQuery: String
        get() = settingsViewModel.settingsSearchQuery
        set(value) { settingsViewModel.settingsSearchQuery = value }
    internal var highlightedSettingId: String?  // For pulse animation
        get() = settingsViewModel.highlightedSettingId
        set(value) { settingsViewModel.highlightedSettingId = value }

    // Position tracking for scroll-to-top functionality
    internal val settingPositions = mutableMapOf<String, Int>()  // settingId -> Y position in scroll content
    internal var mainScrollState: androidx.compose.foundation.ScrollState? = null
    internal var composeScope: kotlinx.coroutines.CoroutineScope? = null  // Compose-aware scope with MonotonicFrameClock

    /** Nested scroll connection to prevent search results from scrolling parent */
    internal val searchResultsNestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset = available  // Consume all remaining scroll

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            available  // Consume all remaining velocity
    }

    internal val searchableSettings: List<SearchableSetting> by lazy {
        listOf(
            // Auto-derived control entries — generated from the actual
            // SettingsSwitch/SettingsSlider/SettingsDropdown titles by
            // scripts/generate_settings_search_index.py (never hand-maintained).
            *GENERATED_SEARCH_ENTRIES.map { e ->
                SearchableSetting(
                    title = e.title,
                    keywords = e.keywords,
                    sectionName = sectionDisplayName(e.sectionKey),
                    expandSection = expanderFor(e.sectionKey),
                    settingId = settingSlug(e.title),
                )
            }.toTypedArray(),
            // ===== Hand-maintained NON-control entries (activity navigation, FAQ) =====
            SearchableSetting("Theme Manager", listOf("color", "dark mode", "light", "appearance", "theme"), "Activities", ThemeSettingsActivity::class.java),
            SearchableSetting("Dictionary Manager", listOf("words", "custom", "disabled", "vocabulary"), "Activities", DictionaryManagerActivity::class.java),
            SearchableSetting("Layout Manager", listOf("keyboard layout", "qwerty", "azerty"), "Activities", LayoutManagerActivity::class.java),
            SearchableSetting("Keyboard Calibration", listOf("height", "size", "foldable"), "Activities", SwipeCalibrationActivity::class.java),
            SearchableSetting("Per-Key Customization", listOf("short swipe", "gesture", "actions", "commands"), "Activities", ShortSwipeCustomizationActivity::class.java, gatedBy = "short_gestures", settingId = "per_key_customization"),
            SearchableSetting("Short Swipe Calibration", listOf("calibrate", "practice", "tutorial", "test"), "Gesture Tuning", ShortSwipeCalibrationActivity::class.java, gatedBy = "short_gestures", settingId = "short_swipe_calibration"),
            SearchableSetting("Extra Keys", listOf("toolbar", "arrows", "numbers"), "Activities", ExtraKeysConfigActivity::class.java),
            SearchableSetting("Backup & Restore", listOf("backup", "export", "import", "restore", "zip", "preview", "deselect"), "Backup & Restore", expandSection = { backupRestoreSectionExpanded = true }, settingId = "backup_restore"),
            SearchableSetting("What's New", listOf("changelog", "release", "update", "features", "version"), "Activities", settingId = "whats_new"),
            SearchableSetting("Neural Settings", listOf("neural", "ai", "prediction", "model", "onnx"), "Neural Prediction", NeuralSettingsActivity::class.java),
            SearchableSetting("ONNX Threads", listOf("threads", "cpu", "xnnpack", "performance", "onnx"), "Neural Prediction", NeuralSettingsActivity::class.java, gatedBy = "swipe_typing", settingId = "onnx_threads"),
            SearchableSetting("GIF Import Pack", listOf("gif", "import", "pack", "zip", "download"), "GIF Panel", expandSection = { gifSectionExpanded = true }, gatedBy = "gif_enabled", settingId = "gif_import"),
            SearchableSetting("Help & FAQ", listOf("help", "faq", "documentation", "wiki", "questions"), "Help & FAQ", expandSection = { helpSectionExpanded = true }, settingId = "help_faq"),
            SearchableSetting("Type Numbers & Symbols", listOf("numbers", "symbols", "subkey", "short swipe"), "Help & FAQ", expandSection = { helpSectionExpanded = true }, settingId = "faq_numbers"),
            SearchableSetting("Cursor Control", listOf("cursor", "navigation", "spacebar", "move"), "Help & FAQ", expandSection = { helpSectionExpanded = true }, settingId = "faq_cursor"),
            SearchableSetting("Select & Delete Text", listOf("selection", "delete", "text", "backspace"), "Help & FAQ", expandSection = { helpSectionExpanded = true }, settingId = "faq_selection"),
            SearchableSetting("Language Switching", listOf("language", "switch", "toggle", "multilingual"), "Help & FAQ", expandSection = { helpSectionExpanded = true }, settingId = "faq_language"),
            SearchableSetting("Emoji Access", listOf("emoji", "emoticon", "symbols", "fn"), "Help & FAQ", expandSection = { helpSectionExpanded = true }, settingId = "faq_emoji"),
            SearchableSetting("Clipboard History", listOf("clipboard", "paste", "history", "pinned", "fn"), "Help & FAQ", expandSection = { helpSectionExpanded = true }, settingId = "faq_clipboard"),
            SearchableSetting("Swipe Typing Help", listOf("swipe", "typing", "glide", "gesture"), "Help & FAQ", expandSection = { helpSectionExpanded = true }, settingId = "faq_swipe"),
            SearchableSetting("Privacy Info", listOf("privacy", "offline", "data", "secure"), "Help & FAQ", expandSection = { helpSectionExpanded = true }, settingId = "faq_privacy")
        )
    }

    // Collected data viewer dialog state — delegated to settingsViewModel (survive rotation)
    internal var showCollectedDataViewer: Boolean
        get() = settingsViewModel.showCollectedDataViewer
        set(value) { settingsViewModel.showCollectedDataViewer = value }
    internal var collectedDataList: List<tribixbite.cleverkeys.ml.SwipeMLData>
        get() = settingsViewModel.collectedDataList
        set(value) { settingsViewModel.collectedDataList = value }
    internal var collectedDataStats: tribixbite.cleverkeys.ml.SwipeMLDataStore.DataStatistics?
        get() = settingsViewModel.collectedDataStats
        set(value) { settingsViewModel.collectedDataStats = value }
    internal var collectedDataSearchQuery: String
        get() = settingsViewModel.collectedDataSearchQuery
        set(value) { settingsViewModel.collectedDataSearchQuery = value }
    internal var collectedDataCurrentPage: Int
        get() = settingsViewModel.collectedDataCurrentPage
        set(value) { settingsViewModel.collectedDataCurrentPage = value }
    internal var collectedDataTotalCount: Int
        get() = settingsViewModel.collectedDataTotalCount
        set(value) { settingsViewModel.collectedDataTotalCount = value }
    internal val collectedDataPageSize = 20

    // Performance stats viewer dialog state — delegated to settingsViewModel (survive rotation)
    internal var showPerfStatsViewer: Boolean
        get() = settingsViewModel.showPerfStatsViewer
        set(value) { settingsViewModel.showPerfStatsViewer = value }
    internal var perfStatsSummary: String
        get() = settingsViewModel.perfStatsSummary
        set(value) { settingsViewModel.perfStatsSummary = value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge setup for consistent dark theme appearance
        window?.let { w ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                w.isStatusBarContrastEnforced = false
                w.isNavigationBarContrastEnforced = false
            }
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)?.apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            // Clear backgrounds on all window views to prevent white bar
            w.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            w.findViewById<android.view.View>(android.R.id.content)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // Initialize configuration
        try {
            prefs = DirectBootAwarePreferences.get_shared_preferences(this)

            // Run config migration
            Config.migrate(prefs)

            // Initialize global config if not already initialized
            try {
                config = Config.globalConfig()
            } catch (e: Exception) {
                // Config not initialized yet (NullPointerException or IllegalStateException), initialize it
                Config.initGlobalConfig(prefs, resources, null, null)
                config = Config.globalConfig()
            }

            backupRestoreManager = testBackupRestoreManagerOverride ?: BackupRestoreManager(this)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing settings", e)
            fallbackEncrypted()
            return
        }

        // Load current settings
        loadCurrentSettings()

        // Handle share intent for GIF pack ZIP import
        handleGifPackShareIntent(intent)

        // `scroll_to` extra: expand a named section + scroll/highlight on launch.
        // Used by [BackupRestoreActivity] when redirected without a known intent
        // action so the inline Backup & Restore section is the landing target.
        intent.getStringExtra("scroll_to")?.let { target ->
            when (target) {
                "backup_restore" -> {
                    backupRestoreSectionExpanded = true
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(300)
                        scrollToSetting("backup_restore")
                        highlightedSettingId = "backup_restore"
                        kotlinx.coroutines.delay(2000)
                        highlightedSettingId = null
                    }
                }
            }
        }

        try {
            setContent {
                // #35: Follow system dark/light mode instead of forcing dark
                KeyboardTheme {
                    SettingsScreen()

                    // Backup & Restore preview/result dialogs + loading overlay.
                    // Lifted out of BackupRestoreActivity (Option 2 unification) so
                    // the inline section in SettingsScreen has full feature parity
                    // with the previous dedicated activity.
                    backupRestoreViewModel.settingsPreviewPlan?.let { plan ->
                        SettingsImportPreviewDialog(
                            plan = plan,
                            onCancel = { backupRestoreViewModel.settingsPreviewPlan = null },
                            onApply = { excluded, ssMode ->
                                backupRestoreViewModel.settingsPreviewPlan = null
                                applyPlannedSettings(plan, excluded, ssMode)
                            }
                        )
                    }
                    backupRestoreViewModel.dictPreviewPlan?.let { plan ->
                        DictionaryImportPreviewDialog(
                            plan = plan,
                            onCancel = { backupRestoreViewModel.dictPreviewPlan = null },
                            onApply = { excludedCustom, excludedDisabled ->
                                backupRestoreViewModel.dictPreviewPlan = null
                                applyPlannedDictionaries(plan, excludedCustom, excludedDisabled)
                            }
                        )
                    }
                    if (backupRestoreViewModel.showResultDialog) {
                        AlertDialog(
                            onDismissRequest = { backupRestoreViewModel.showResultDialog = false },
                            title = { Text(backupRestoreViewModel.resultTitle) },
                            text = { Text(backupRestoreViewModel.resultMessage) },
                            confirmButton = {
                                TextButton(onClick = { backupRestoreViewModel.showResultDialog = false }) {
                                    Text("OK")
                                }
                            }
                        )
                    }
                    // Stage B: passphrase prompt for an encrypted import that couldn't be
                    // decrypted with the stored passphrase (design §9).
                    tribixbite.cleverkeys.ui.settings.sections.BackupPassphrasePromptDialog(
                        backupRestoreViewModel
                    )
                    if (backupRestoreViewModel.isProcessing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting up Compose UI", e)
            Toast.makeText(this, "Settings UI failed to load: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Register for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        // Unregister preference listener (balanced with onResume)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        // Save all settings changes to protected storage
        DirectBootAwarePreferences.copy_preferences_to_protected_storage(this, prefs)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) = handlePreferenceChanged(sharedPreferences, key)

    // SettingsScreen moved to ui/settings/SettingsScreen.kt







    // Self-update feature removed for F-Droid compliance
    // F-Droid handles updates automatically - no storage permissions needed

    // GIF pack share intent handling (for ACTION_SEND / ACTION_VIEW with ZIP)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGifPackShareIntent(intent)
    }

}
