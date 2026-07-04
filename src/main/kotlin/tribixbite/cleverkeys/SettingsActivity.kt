package tribixbite.cleverkeys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import tribixbite.cleverkeys.theme.KeyboardTheme
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.langpack.ImportResult
import tribixbite.cleverkeys.langpack.LanguagePackManifest
import tribixbite.cleverkeys.clipboard.sanitize.RulesetParser
import tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig
import tribixbite.cleverkeys.backup.DictImportPlan
import tribixbite.cleverkeys.backup.LangWord
import tribixbite.cleverkeys.backup.SettingsImportPlan
import tribixbite.cleverkeys.backup.ShortSwipeImportMode
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.getSafeInt
import tribixbite.cleverkeys.ui.settings.getSafeFloat
import tribixbite.cleverkeys.ui.settings.getSafeString
import tribixbite.cleverkeys.ui.settings.getSafeBoolean
import tribixbite.cleverkeys.ui.settings.VersionInfoCard
import tribixbite.cleverkeys.ui.settings.GitHubInfoCard
import tribixbite.cleverkeys.ui.settings.FAQSection
import tribixbite.cleverkeys.ui.settings.CollectedDataViewerDialog
import tribixbite.cleverkeys.ui.settings.PerfStatsViewerDialog
import tribixbite.cleverkeys.ui.settings.SearchableSetting
import tribixbite.cleverkeys.ui.settings.executeSearchAction
import tribixbite.cleverkeys.ui.settings.expanderFor
import tribixbite.cleverkeys.ui.settings.getFilteredSettings
import tribixbite.cleverkeys.ui.settings.scrollToSetting
import tribixbite.cleverkeys.ui.settings.sectionDisplayName
import tribixbite.cleverkeys.ui.settings.settingSlug
import tribixbite.cleverkeys.ui.settings.io.applyPlannedDictionaries
import tribixbite.cleverkeys.ui.settings.io.applyPlannedSettings
import tribixbite.cleverkeys.ui.settings.io.clearAllPrivacyData
import tribixbite.cleverkeys.ui.settings.io.deleteCollectedData
import tribixbite.cleverkeys.ui.settings.io.deleteLanguagePack
import tribixbite.cleverkeys.ui.settings.io.detectAvailableV2Dictionaries
import tribixbite.cleverkeys.ui.settings.io.exportClipboardHistory
import tribixbite.cleverkeys.ui.settings.io.exportClipboardZip
import tribixbite.cleverkeys.ui.settings.io.exportConfiguration
import tribixbite.cleverkeys.ui.settings.io.exportCustomDictionary
import tribixbite.cleverkeys.ui.settings.io.exportFullBackup
import tribixbite.cleverkeys.ui.settings.io.exportPerfStats
import tribixbite.cleverkeys.ui.settings.io.exportSwipeDataJSON
import tribixbite.cleverkeys.ui.settings.io.exportSwipeDataNDJSON
import tribixbite.cleverkeys.ui.settings.io.getLanguageDisplayName
import tribixbite.cleverkeys.ui.settings.io.handleCustomRulesPicked
import tribixbite.cleverkeys.ui.settings.io.handleGifPackShareIntent
import tribixbite.cleverkeys.ui.settings.io.importClipboardHistory
import tribixbite.cleverkeys.ui.settings.io.importClipboardZip
import tribixbite.cleverkeys.ui.settings.io.importConfiguration
import tribixbite.cleverkeys.ui.settings.io.importCustomDictionary
import tribixbite.cleverkeys.ui.settings.io.importFullBackup
import tribixbite.cleverkeys.ui.settings.io.importLanguagePack
import tribixbite.cleverkeys.ui.settings.io.loadCollectedDataPage
import tribixbite.cleverkeys.ui.settings.io.loadPrefixBoostForLanguage
import tribixbite.cleverkeys.ui.settings.io.notifySanitizationRulesChanged
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
import tribixbite.cleverkeys.ui.settings.io.performGifRemoveAll
import tribixbite.cleverkeys.ui.settings.io.performGifRemovePack
import tribixbite.cleverkeys.ui.settings.io.performLanguagePackImport
import tribixbite.cleverkeys.ui.settings.io.performPerfStatsExport
import tribixbite.cleverkeys.ui.settings.io.performSwipeDataJsonExport
import tribixbite.cleverkeys.ui.settings.io.performSwipeDataNdjsonExport
import tribixbite.cleverkeys.ui.settings.io.recomputeCustomRulesStatus
import tribixbite.cleverkeys.ui.settings.io.refreshAvailableSecondaryLanguages
import tribixbite.cleverkeys.ui.settings.io.refreshInstalledGifPacks
import tribixbite.cleverkeys.ui.settings.io.refreshInstalledLanguagePacks
import tribixbite.cleverkeys.ui.settings.io.viewCollectedData
import tribixbite.cleverkeys.ui.settings.io.viewPerfStats
import tribixbite.cleverkeys.ui.settings.applySwipeSensitivityPreset
import tribixbite.cleverkeys.ui.settings.fallbackEncrypted
import tribixbite.cleverkeys.ui.settings.getSwipeSensitivityPreset
import tribixbite.cleverkeys.ui.settings.handlePreferenceChanged
import tribixbite.cleverkeys.ui.settings.loadCurrentSettings
import tribixbite.cleverkeys.ui.settings.openAutoCorrectionSettings
import tribixbite.cleverkeys.ui.settings.openBackupRestore
import tribixbite.cleverkeys.ui.settings.openCalibration
import tribixbite.cleverkeys.ui.settings.openDictionaryManager
import tribixbite.cleverkeys.ui.settings.openExtraKeysConfig
import tribixbite.cleverkeys.ui.settings.openGitHubReleases
import tribixbite.cleverkeys.ui.settings.openLayoutManager
import tribixbite.cleverkeys.ui.settings.openNeuralSettings
import tribixbite.cleverkeys.ui.settings.openShortSwipeCustomization
import tribixbite.cleverkeys.ui.settings.openSwipeDebugActivity
import tribixbite.cleverkeys.ui.settings.openWikiInBrowser
import tribixbite.cleverkeys.ui.settings.resetAllSettings
import tribixbite.cleverkeys.ui.settings.saveSetting
import tribixbite.cleverkeys.ui.settings.updateConfigFromSettings

/**
 * Modern settings activity for CleverKeys.
 *
 * Migrated from SettingsActivity.java with enhanced functionality:
 * - Modern Compose UI with Material Design 3
 * - Reactive settings with live preview
 * - Neural parameter configuration
 * - Enhanced version management
 * - Performance monitoring integration
 * - Accessibility improvements
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

    // ViewModel hosts the import-preview state so plan + dialog state survive
    // configuration changes (rotation). See BackupRestoreViewModel for fields.
    internal val backupRestoreViewModel: BackupRestoreViewModel by viewModels()

    // SAF file pickers for backup/restore
    internal val configExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performConfigExport(it) }
    }

    internal val configImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performConfigImport(it) }
    }

    internal val dictionaryExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performDictionaryExport(it) }
    }

    internal val dictionaryImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performDictionaryImport(it) }
    }

    internal val clipboardExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performClipboardExport(it) }
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
        uri?.let { performClipboardZipExport(it) }
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
        uri?.let { performFullBackupExport(it) }
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
    internal var currentThemeName by mutableStateOf("cleverkeysdark")
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
    internal var gifImportInProgress by mutableStateOf(false)
    internal var gifImportStatus by mutableStateOf<String?>(null)
    internal var showGifRemoveAllDialog by mutableStateOf(false)
    internal var showGifRemovePackDialog by mutableStateOf<String?>(null)
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
    internal var vibrateCustomEnabled by mutableStateOf(false) // Custom vibration duration
    internal var numberEntryLayout by mutableStateOf("pin") // "pin", "phone", "calculator"

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

    // Neural beam search advanced settings (batch/greedy/onnx threads moved to NeuralSettingsActivity)
    internal var neuralBeamAlpha by mutableStateOf(1.55f)
    internal var neuralBeamPruneConfidence by mutableStateOf(0.33f)
    internal var neuralBeamScoreGap by mutableStateOf(50.0f)

    // Neural model config settings
    internal var neuralResamplingMode by mutableStateOf("discard")

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
    internal var showLanguagePackDialog by mutableStateOf(false)
    internal var languagePackImportStatus by mutableStateOf<String?>(null)

    // Privacy settings - all OFF by default (CleverKeys is fully offline)
    internal var privacyCollectSwipe by mutableStateOf(false)
    internal var privacyCollectPerformance by mutableStateOf(false)
    internal var privacyCollectErrors by mutableStateOf(false)

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

    // Section expanded states
    internal var wordPredictionAdvancedExpanded by mutableStateOf(false)
    internal var activitiesSectionExpanded by mutableStateOf(true)  // Activities at top, default expanded
    internal var multiLangSectionExpanded by mutableStateOf(false)
    internal var privacySectionExpanded by mutableStateOf(false)
    internal var neuralSectionExpanded by mutableStateOf(false)  // Collapsed by default, Activities is primary
    internal var appearanceSectionExpanded by mutableStateOf(false)  // No longer default expanded since Theme is in Activities
    internal var swipeTrailSectionExpanded by mutableStateOf(false)
    internal var inputSectionExpanded by mutableStateOf(false)
    internal var swipeCorrectionsSectionExpanded by mutableStateOf(false)
    internal var gestureTuningSectionExpanded by mutableStateOf(false)
    internal var accessibilitySectionExpanded by mutableStateOf(false)
    // v1.2.6: dictionarySectionExpanded removed - Dictionary Manager moved to Activities
    internal var clipboardSectionExpanded by mutableStateOf(false)
    internal var gifSectionExpanded by mutableStateOf(false)
    internal var backupRestoreSectionExpanded by mutableStateOf(false)
    internal var advancedSectionExpanded by mutableStateOf(false)
    internal var infoSectionExpanded by mutableStateOf(false)
    internal var helpSectionExpanded by mutableStateOf(false)

    // Test keyboard field (#1134: test input without leaving settings)
    internal var testKeyboardExpanded by mutableStateOf(false)
    internal var testKeyboardText by mutableStateOf("")

    // Settings search
    internal var settingsSearchQuery by mutableStateOf("")
    internal var showSearchResults by mutableStateOf(false)
    internal var highlightedSettingId by mutableStateOf<String?>(null)  // For pulse animation

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

    // Collected data viewer dialog state
    internal var showCollectedDataViewer by mutableStateOf(false)
    internal var collectedDataList by mutableStateOf<List<tribixbite.cleverkeys.ml.SwipeMLData>>(emptyList())
    internal var collectedDataStats by mutableStateOf<tribixbite.cleverkeys.ml.SwipeMLDataStore.DataStatistics?>(null)
    internal var collectedDataSearchQuery by mutableStateOf("")
    internal var collectedDataCurrentPage by mutableStateOf(0)
    internal var collectedDataTotalCount by mutableStateOf(0)
    internal val collectedDataPageSize = 20

    // Performance stats viewer dialog state
    internal var showPerfStatsViewer by mutableStateOf(false)
    internal var perfStatsSummary by mutableStateOf("")

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
    
    override fun onDestroy() {
        super.onDestroy()
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

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    internal fun SettingsScreen() {
        val scrollState = rememberScrollState()
        // Store references for scroll-to-setting functionality
        // composeScope has MonotonicFrameClock needed for animateScrollTo
        mainScrollState = scrollState
        composeScope = rememberCoroutineScope()

        // Collected Data Viewer Dialog
        if (showCollectedDataViewer) {
            CollectedDataViewerDialog(
                dataList = collectedDataList,
                stats = collectedDataStats,
                onDismiss = { showCollectedDataViewer = false }
            )
        }

        // Performance Stats Viewer Dialog
        if (showPerfStatsViewer) {
            PerfStatsViewerDialog(
                summary = perfStatsSummary,
                onDismiss = { showPerfStatsViewer = false }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.settings_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = stringResource(R.string.settings_description),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            // Settings Search Bar
            val filteredSettings = getFilteredSettings(settingsSearchQuery)
            val showResults = settingsSearchQuery.isNotBlank() && filteredSettings.isNotEmpty()

            OutlinedTextField(
                value = settingsSearchQuery,
                onValueChange = { query ->
                    settingsSearchQuery = query
                },
                label = { Text("Search settings...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                },
                trailingIcon = {
                    if (settingsSearchQuery.isNotBlank()) {
                        IconButton(onClick = {
                            settingsSearchQuery = ""
                        }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear"
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(12.dp)
            )

            // Search Results - always below search field, scrollable
            // Uses nestedScroll barrier to prevent scroll propagation to parent
            AnimatedVisibility(
                visible = showResults,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .nestedScroll(searchResultsNestedScrollConnection),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(4.dp)
                    ) {
                        filteredSettings.forEach { setting ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        executeSearchAction(setting)
                                        settingsSearchQuery = ""
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = setting.title,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "in ${setting.sectionName}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Test Keyboard Section (#1134: test input without leaving settings)
            CollapsibleSettingsSection(
                title = "⌨️ Test Keyboard",
                expanded = testKeyboardExpanded,
                onExpandChange = { testKeyboardExpanded = it }
            ) {
                OutlinedTextField(
                    value = testKeyboardText,
                    onValueChange = { testKeyboardText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type here to test your keyboard...") },
                    minLines = 3,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { testKeyboardText = "" }
                    ) {
                        Text("Clear")
                    }
                }
            }

            // Activities Section (Special Feature Managers) - at top for quick access
            val activityContext = LocalContext.current
            CollapsibleSettingsSection(
                title = "📱 Activities",
                expanded = activitiesSectionExpanded,
                onExpandChange = { activitiesSectionExpanded = it }
            ) {
                // v1.2.7: Dictionary Manager Card (moved to top per user request)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { openDictionaryManager() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "📖", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dictionary Manager",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Custom words, disabled words & vocabulary",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Theme Manager Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable {
                            val intent = Intent(activityContext, ThemeSettingsActivity::class.java)
                            activityContext.startActivity(intent)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🎨", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Theme Manager",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Neon, Pastel, DIY themes & custom colors",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Short Swipe Customization Card (Per-Key Actions) - shared component
                Box(modifier = Modifier.padding(bottom = 8.dp)) {
                    PerKeyCustomizationButton()
                }

                // Extra Keys Configuration Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable {
                            val intent = Intent(activityContext, ExtraKeysConfigActivity::class.java)
                            activityContext.startActivity(intent)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "➕", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Configure Extra Keys",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Add system keys, symbols & shortcuts",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Layout Manager Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable {
                            val intent = Intent(activityContext, LayoutManagerActivity::class.java)
                            activityContext.startActivity(intent)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🌐", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Layout Manager",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "QWERTY, Dvorak, Colemak & more",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Short Swipe Calibration Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable {
                            val intent = Intent(activityContext, ShortSwipeCalibrationActivity::class.java)
                            activityContext.startActivity(intent)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "📐", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Short Swipe Calibration",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Practice and tune gesture sensitivity",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // What's New Card - opens GitHub releases page
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tribixbite/CleverKeys/releases/latest"))
                            activityContext.startActivity(intent)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "✨", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "What's New",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "See latest features and changelog",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Neural Prediction Section (Collapsible, default expanded)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_neural),
                expanded = neuralSectionExpanded,
                onExpandChange = { neuralSectionExpanded = it }
            ) {
                // Master switch for swipe typing (neural prediction is always used when enabled)
                SettingsSwitch(
                    title = "Enable Swipe Typing",
                    description = "Swipe across keys to type words using neural prediction.",
                    checked = swipeTypingEnabled,
                    onCheckedChange = {
                        swipeTypingEnabled = it
                        saveSetting("swipe_typing_enabled", it)
                    },
                    highlightId = "swipe_typing"
                )

                // #9: Warning when swipe is enabled but layout doesn't support it
                if (swipeTypingEnabled && !currentLayoutSupportsSwipe) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "Swipe typing requires a QWERTY layout. " +
                                "Your current layout ($currentLayoutName) uses different key positions — " +
                                "swipe predictions would be inaccurate.\n\n" +
                                "Swipe typing is temporarily disabled. It will re-enable " +
                                "automatically when you switch to a QWERTY layout.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                if (swipeTypingEnabled) {
                    // #39: Option to enable swipe typing on password fields
                    SettingsSwitch(
                        title = "Swipe on Password Fields",
                        description = "Enable swipe typing even in password fields. Predictions will be shown but individual typed characters remain hidden.",
                        checked = swipeOnPasswordFields,
                        onCheckedChange = {
                            swipeOnPasswordFields = it
                            saveSetting("swipe_on_password_fields", it)
                        }
                    )

                    SettingsSlider(
                        title = stringResource(R.string.settings_neural_beam_width_title),
                        description = stringResource(R.string.settings_neural_beam_width_desc),
                        value = beamWidth.toFloat(),
                        valueRange = 1f..20f,
                        steps = 19,
                        onValueChange = {
                            beamWidth = it.toInt()
                            saveSetting("neural_beam_width", beamWidth)
                        },
                        displayValue = beamWidth.toString()
                    )

                    SettingsSlider(
                        title = stringResource(R.string.settings_neural_max_length_title),
                        description = stringResource(R.string.settings_neural_max_length_desc),
                        value = maxLength.toFloat(),
                        valueRange = 5f..35f,
                        steps = 30,
                        onValueChange = {
                            maxLength = it.toInt()
                            saveSetting("neural_max_length", maxLength)
                        },
                        displayValue = maxLength.toString()
                    )

                    SettingsSlider(
                        title = stringResource(R.string.settings_neural_confidence_title),
                        description = stringResource(R.string.settings_neural_confidence_desc),
                        value = confidenceThreshold,
                        valueRange = 0.0f..0.4f,
                        steps = 40,
                        onValueChange = {
                            confidenceThreshold = it
                            saveSetting("neural_confidence_threshold", confidenceThreshold)
                        },
                        displayValue = "%.3f".format(confidenceThreshold)
                    )

                    // Full Neural Settings Activity button (batch/greedy/onnx threads moved there)
                    Button(
                        onClick = { openNeuralSettings() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text("Full Neural Settings")
                    }
                }
            }

            // Appearance Section (Collapsible) - height/visual settings
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_appearance),
                expanded = appearanceSectionExpanded,
                onExpandChange = { appearanceSectionExpanded = it }
            ) {
                // Theme Manager moved to Activities section at top

                SettingsSlider(
                    title = "Keyboard Height (Portrait)",
                    description = "Adjust keyboard height in portrait mode",
                    value = keyboardHeight.toFloat(),
                    valueRange = 20f..60f,
                    steps = 40,
                    onValueChange = {
                        keyboardHeight = it.toInt()
                        saveSetting("keyboard_height", keyboardHeight)
                    },
                    displayValue = "$keyboardHeight%"
                )

                SettingsSlider(
                    title = "Keyboard Height (Landscape)",
                    description = "Adjust keyboard height in landscape mode",
                    value = keyboardHeightLandscape.toFloat(),
                    valueRange = 20f..60f,
                    steps = 40,
                    onValueChange = {
                        keyboardHeightLandscape = it.toInt()
                        saveSetting("keyboard_height_landscape", keyboardHeightLandscape)
                    },
                    displayValue = "$keyboardHeightLandscape%"
                )

                SettingsSlider(
                    title = "Bottom Margin (Portrait)",
                    description = "Vertical margin as % of screen height",
                    value = marginBottomPortrait.toFloat(),
                    valueRange = 0f..30f,
                    steps = 30,
                    onValueChange = {
                        marginBottomPortrait = it.toInt()
                        saveSetting("margin_bottom_portrait", marginBottomPortrait)
                    },
                    displayValue = "$marginBottomPortrait%"
                )

                SettingsSlider(
                    title = "Bottom Margin (Landscape)",
                    description = "Vertical margin as % of screen height",
                    value = marginBottomLandscape.toFloat(),
                    valueRange = 0f..30f,
                    steps = 30,
                    onValueChange = {
                        marginBottomLandscape = it.toInt()
                        saveSetting("margin_bottom_landscape", marginBottomLandscape)
                    },
                    displayValue = "$marginBottomLandscape%"
                )

                // Portrait left/right margins with 90% total cap
                val maxLeftPortrait = (90 - marginRightPortrait).coerceAtLeast(0)
                SettingsSlider(
                    title = "Left Margin (Portrait)",
                    description = "Left margin as % of screen width",
                    value = marginLeftPortrait.toFloat(),
                    valueRange = 0f..maxLeftPortrait.toFloat(),
                    steps = maxLeftPortrait.coerceAtLeast(1),
                    onValueChange = {
                        marginLeftPortrait = it.toInt()
                        saveSetting("margin_left_portrait", marginLeftPortrait)
                    },
                    displayValue = "$marginLeftPortrait%"
                )

                val maxRightPortrait = (90 - marginLeftPortrait).coerceAtLeast(0)
                SettingsSlider(
                    title = "Right Margin (Portrait)",
                    description = "Right margin as % of screen width",
                    value = marginRightPortrait.toFloat(),
                    valueRange = 0f..maxRightPortrait.toFloat(),
                    steps = maxRightPortrait.coerceAtLeast(1),
                    onValueChange = {
                        marginRightPortrait = it.toInt()
                        saveSetting("margin_right_portrait", marginRightPortrait)
                    },
                    displayValue = "$marginRightPortrait%"
                )

                // Landscape left/right margins with 90% total cap
                val maxLeftLandscape = (90 - marginRightLandscape).coerceAtLeast(0)
                SettingsSlider(
                    title = "Left Margin (Landscape)",
                    description = "Left margin as % of screen width",
                    value = marginLeftLandscape.toFloat(),
                    valueRange = 0f..maxLeftLandscape.toFloat(),
                    steps = maxLeftLandscape.coerceAtLeast(1),
                    onValueChange = {
                        marginLeftLandscape = it.toInt()
                        saveSetting("margin_left_landscape", marginLeftLandscape)
                    },
                    displayValue = "$marginLeftLandscape%"
                )

                val maxRightLandscape = (90 - marginLeftLandscape).coerceAtLeast(0)
                SettingsSlider(
                    title = "Right Margin (Landscape)",
                    description = "Right margin as % of screen width",
                    value = marginRightLandscape.toFloat(),
                    valueRange = 0f..maxRightLandscape.toFloat(),
                    steps = maxRightLandscape.coerceAtLeast(1),
                    onValueChange = {
                        marginRightLandscape = it.toInt()
                        saveSetting("margin_right_landscape", marginRightLandscape)
                    },
                    displayValue = "$marginRightLandscape%"
                )

                SettingsSlider(
                    title = "Label Brightness",
                    description = "Brightness of key labels (0-100%)",
                    value = labelBrightness.toFloat(),
                    valueRange = 0f..100f,
                    steps = 100,
                    onValueChange = {
                        labelBrightness = it.toInt()
                        saveSetting("label_brightness", labelBrightness)
                    },
                    displayValue = "$labelBrightness%"
                )

                SettingsSlider(
                    title = "Keyboard Opacity",
                    description = "Opacity of keyboard background",
                    value = keyboardOpacity.toFloat(),
                    valueRange = 0f..100f,
                    steps = 100,
                    onValueChange = {
                        keyboardOpacity = it.toInt()
                        saveSetting("keyboard_opacity", keyboardOpacity)
                    },
                    displayValue = "$keyboardOpacity%"
                )

                SettingsSlider(
                    title = "Key Opacity",
                    description = "Opacity of individual keys",
                    value = keyOpacity.toFloat(),
                    valueRange = 0f..100f,
                    steps = 100,
                    onValueChange = {
                        keyOpacity = it.toInt()
                        saveSetting("key_opacity", keyOpacity)
                    },
                    displayValue = "$keyOpacity%"
                )

                SettingsSlider(
                    title = "Activated Key Opacity",
                    description = "Opacity when key is pressed",
                    value = keyActivatedOpacity.toFloat(),
                    valueRange = 0f..100f,
                    steps = 100,
                    onValueChange = {
                        keyActivatedOpacity = it.toInt()
                        saveSetting("key_activated_opacity", keyActivatedOpacity)
                    },
                    displayValue = "$keyActivatedOpacity%"
                )

                SettingsSlider(
                    title = "Character Size",
                    description = "Size multiplier for key labels",
                    value = characterSize.toFloat(),
                    valueRange = 50f..200f,
                    steps = 150,
                    onValueChange = {
                        characterSize = it.toInt()
                        saveSetting("character_size", characterSize / 100f)
                    },
                    displayValue = "${characterSize}%"
                )

                // #133: independent sizing for the small secondary (flick) labels
                // so increasing primary Character Size doesn't crowd/overlap them.
                SettingsSlider(
                    title = "Secondary Label Size",
                    description = "Size of the small corner (flick) labels, independent of Character Size",
                    value = secondaryLabelSizeScale.toFloat(),
                    valueRange = 50f..200f,
                    steps = 150,
                    onValueChange = {
                        secondaryLabelSizeScale = it.toInt()
                        saveSetting("secondary_label_size_scale", secondaryLabelSizeScale / 100f)
                    },
                    displayValue = "${secondaryLabelSizeScale}%"
                )

                SettingsSlider(
                    title = "Key Vertical Margin",
                    description = "Vertical spacing between keys",
                    value = keyVerticalMargin.toFloat(),
                    valueRange = 0f..500f,
                    steps = 100,
                    onValueChange = {
                        keyVerticalMargin = it.toInt()
                        saveSetting("key_vertical_margin", keyVerticalMargin / 100f)
                    },
                    displayValue = "${keyVerticalMargin / 100f}%"
                )

                SettingsSlider(
                    title = "Key Horizontal Margin",
                    description = "Horizontal spacing between keys",
                    value = keyHorizontalMargin.toFloat(),
                    valueRange = 0f..500f,
                    steps = 100,
                    onValueChange = {
                        keyHorizontalMargin = it.toInt()
                        saveSetting("key_horizontal_margin", keyHorizontalMargin / 100f)
                    },
                    displayValue = "${keyHorizontalMargin / 100f}%"
                )

                SettingsSwitch(
                    title = "Custom Border Config",
                    description = "Enable custom key border styling",
                    checked = borderConfigEnabled,
                    onCheckedChange = {
                        borderConfigEnabled = it
                        saveSetting("border_config", it)
                    }
                )

                if (borderConfigEnabled) {
                    SettingsSlider(
                        title = "Border Radius",
                        description = "Corner radius for keys (dp)",
                        value = customBorderRadius.toFloat(),
                        valueRange = 0f..20f,
                        steps = 20,
                        onValueChange = {
                            customBorderRadius = it.toInt()
                            saveSetting("custom_border_radius", customBorderRadius)
                        },
                        displayValue = "${customBorderRadius}dp"
                    )

                    SettingsSlider(
                        title = "Border Line Width",
                        description = "Width of key borders (dp)",
                        value = customBorderLineWidth.toFloat(),
                        valueRange = 0f..10f,
                        steps = 10,
                        onValueChange = {
                            customBorderLineWidth = it.toInt()
                            saveSetting("custom_border_line_width", customBorderLineWidth)
                        },
                        displayValue = "${customBorderLineWidth}dp"
                    )
                }
            }

            // Swipe Trail Section (Collapsible)
            CollapsibleSettingsSection(
                title = "✨ Swipe Trail",
                expanded = swipeTrailSectionExpanded,
                onExpandChange = { swipeTrailSectionExpanded = it }
            ) {
                SettingsSwitch(
                    title = "Enable Swipe Trail",
                    description = "Show visual trail while swiping across keys",
                    checked = swipeTrailEnabled,
                    onCheckedChange = {
                        swipeTrailEnabled = it
                        saveSetting("swipe_trail_enabled", it)
                    }
                )

                if (swipeTrailEnabled) {
                    // Trail effect dropdown
                    SettingsDropdown(
                        title = "Trail Effect",
                        description = "Visual style of the swipe trail",
                        options = listOf("Glow", "Solid", "Fade", "Rainbow", "None"),
                        selectedIndex = when (swipeTrailEffect) {
                            "glow" -> 0
                            "solid" -> 1
                            "fade" -> 2
                            "rainbow" -> 3
                            "none" -> 4
                            else -> 0
                        },
                        onSelectionChange = { index ->
                            swipeTrailEffect = when (index) {
                                0 -> "glow"
                                1 -> "solid"
                                2 -> "fade"
                                3 -> "rainbow"
                                4 -> "none"
                                else -> "glow"
                            }
                            saveSetting("swipe_trail_effect", swipeTrailEffect)
                        }
                    )

                    // Trail width
                    SettingsSlider(
                        title = "Trail Width",
                        description = "Thickness of the swipe trail",
                        value = swipeTrailWidth,
                        valueRange = 2f..20f,
                        steps = 18,
                        onValueChange = {
                            swipeTrailWidth = it
                            saveSetting("swipe_trail_width", swipeTrailWidth)
                        },
                        displayValue = "%.0fdp".format(swipeTrailWidth)
                    )

                    // Glow radius (only for glow effect)
                    if (swipeTrailEffect == "glow") {
                        SettingsSlider(
                            title = "Glow Radius",
                            description = "Size of the glow effect around trail",
                            value = swipeTrailGlowRadius,
                            valueRange = 4f..30f,
                            steps = 26,
                            onValueChange = {
                                swipeTrailGlowRadius = it
                                saveSetting("swipe_trail_glow_radius", swipeTrailGlowRadius)
                            },
                            displayValue = "%.0fdp".format(swipeTrailGlowRadius)
                        )
                    }

                    // Color picker (simple preset colors)
                    SettingsDropdown(
                        title = "Trail Color",
                        description = "Color of the swipe trail",
                        options = listOf(
                            "Jewel Purple",
                            "Electric Blue",
                            "Emerald Green",
                            "Sunset Orange",
                            "Ruby Red",
                            "Silver",
                            "Gold"
                        ),
                        selectedIndex = when (swipeTrailColor) {
                            0xFF9B59B6.toInt() -> 0  // Jewel Purple
                            0xFF3498DB.toInt() -> 1  // Electric Blue
                            0xFF2ECC71.toInt() -> 2  // Emerald Green
                            0xFFF39C12.toInt() -> 3  // Sunset Orange
                            0xFFE74C3C.toInt() -> 4  // Ruby Red
                            0xFFC0C0C0.toInt() -> 5  // Silver
                            0xFFFFD700.toInt() -> 6  // Gold
                            else -> 0
                        },
                        onSelectionChange = { index ->
                            swipeTrailColor = when (index) {
                                0 -> 0xFF9B59B6.toInt()  // Jewel Purple
                                1 -> 0xFF3498DB.toInt()  // Electric Blue
                                2 -> 0xFF2ECC71.toInt()  // Emerald Green
                                3 -> 0xFFF39C12.toInt()  // Sunset Orange
                                4 -> 0xFFE74C3C.toInt()  // Ruby Red
                                5 -> 0xFFC0C0C0.toInt()  // Silver
                                6 -> 0xFFFFD700.toInt()  // Gold
                                else -> 0xFF9B59B6.toInt()
                            }
                            saveSetting("swipe_trail_color", swipeTrailColor)
                        }
                    )
                }
            }

            // Input Behavior Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_input),
                expanded = inputSectionExpanded,
                onExpandChange = { inputSectionExpanded = it }
            ) {
                // Keyboard Layouts Manager button
                Button(
                    onClick = { openLayoutManager() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage Keyboard Layouts")
                }

                // Extra Keys Configuration button
                Button(
                    onClick = { openExtraKeysConfig() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Configure Extra Keys")
                }

                // Phase 1: Typing/Prediction Settings
                SettingsSwitch(
                    title = "Enable Word Predictions",
                    description = "Show word suggestions while typing",
                    checked = wordPredictionEnabled,
                    onCheckedChange = {
                        wordPredictionEnabled = it
                        saveSetting("word_prediction_enabled", it)
                    }
                )

                if (wordPredictionEnabled) {
                    SettingsSlider(
                        title = "Suggestion Bar Opacity",
                        description = "Transparency of the suggestion bar",
                        value = suggestionBarOpacity.toFloat(),
                        valueRange = 0f..100f,
                        steps = 100,
                        onValueChange = {
                            suggestionBarOpacity = it.toInt()
                            saveSetting("suggestion_bar_opacity", suggestionBarOpacity)
                        },
                        displayValue = "$suggestionBarOpacity%"
                    )

                    // #82: Auto-space after selecting suggestion
                    SettingsSwitch(
                        title = "Auto-Space After Suggestion",
                        description = "Add trailing space when selecting a suggestion",
                        checked = autoSpaceAfterSuggestion,
                        onCheckedChange = {
                            autoSpaceAfterSuggestion = it
                            saveSetting("auto_space_after_suggestion", it)
                            Config.globalConfig()?.auto_space_after_suggestion = it
                        }
                    )

                    // Auto-space before tapped suggestion (leading space)
                    SettingsSwitch(
                        title = "Auto-Space Before Suggestion",
                        description = "Insert leading space before a tapped suggestion (swipe always adds space)",
                        checked = autoSpaceBeforeSuggestion,
                        onCheckedChange = {
                            autoSpaceBeforeSuggestion = it
                            saveSetting("auto_space_before_suggestion", it)
                            Config.globalConfig()?.auto_space_before_suggestion = it
                        }
                    )

                    // #110: Backspace undo swipe — delete entire swiped word on immediate backspace
                    if (swipeTypingEnabled) {
                        SettingsSwitch(
                            title = "Backspace Undo Swipe",
                            description = "Tapping backspace immediately after a swiped word deletes the entire word",
                            checked = backspaceUndoSwipe,
                            highlightId = "backspace_undo_swipe",
                            onCheckedChange = {
                                backspaceUndoSwipe = it
                                saveSetting("backspace_undo_swipe", it)
                                Config.globalConfig()?.backspace_undo_swipe = it
                            }
                        )
                    }

                    // Word Prediction Advanced section (expandable)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { wordPredictionAdvancedExpanded = !wordPredictionAdvancedExpanded }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Advanced Prediction Settings", fontWeight = FontWeight.SemiBold)
                        Icon(
                            imageVector = if (wordPredictionAdvancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    AnimatedVisibility(visible = wordPredictionAdvancedExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsSwitch(
                                title = "Context-Aware Predictions",
                                description = "Learn from typing patterns (N-gram model)",
                                checked = contextAwarePredictionsEnabled,
                                onCheckedChange = {
                                    contextAwarePredictionsEnabled = it
                                    saveSetting("context_aware_predictions_enabled", it)
                                }
                            )

                            SettingsSwitch(
                                title = "Personalized Learning",
                                description = "Boost predictions for frequently typed words",
                                checked = personalizedLearningEnabled,
                                onCheckedChange = {
                                    personalizedLearningEnabled = it
                                    saveSetting("personalized_learning_enabled", it)
                                }
                            )

                            if (personalizedLearningEnabled) {
                                SettingsDropdown(
                                    title = "Learning Aggression",
                                    description = "How strongly habits affect predictions",
                                    options = listOf("Conservative", "Balanced", "Aggressive"),
                                    selectedIndex = when (learningAggression) {
                                        "CONSERVATIVE" -> 0
                                        "BALANCED" -> 1
                                        "AGGRESSIVE" -> 2
                                        else -> 1
                                    },
                                    onSelectionChange = { index ->
                                        learningAggression = when (index) {
                                            0 -> "CONSERVATIVE"
                                            1 -> "BALANCED"
                                            2 -> "AGGRESSIVE"
                                            else -> "BALANCED"
                                        }
                                        saveSetting("learning_aggression", learningAggression)
                                    }
                                )
                            }

                            SettingsSlider(
                                title = "Context Boost Multiplier",
                                description = "How strongly context influences predictions (0.5-5.0)",
                                value = predictionContextBoost,
                                valueRange = 0.5f..5.0f,
                                steps = 45,
                                onValueChange = {
                                    predictionContextBoost = it
                                    saveSetting("prediction_context_boost", predictionContextBoost)
                                },
                                displayValue = "%.1fx".format(predictionContextBoost)
                            )

                            SettingsSlider(
                                title = "Frequency Scale",
                                description = "Balance common vs uncommon words (100-5000)",
                                value = predictionFrequencyScale,
                                valueRange = 100f..5000f,
                                steps = 49,
                                onValueChange = {
                                    predictionFrequencyScale = it
                                    saveSetting("prediction_frequency_scale", predictionFrequencyScale)
                                },
                                displayValue = "%.0f".format(predictionFrequencyScale)
                            )
                        }
                    }
                }

                SettingsSwitch(
                    title = stringResource(R.string.settings_auto_capitalization_title),
                    description = stringResource(R.string.settings_auto_capitalization_desc),
                    checked = autoCapitalizationEnabled,
                    onCheckedChange = {
                        autoCapitalizationEnabled = it
                        saveSetting("autocapitalisation", it)
                        // Update Config immediately so change takes effect without restart
                        Config.globalConfig()?.autocapitalisation = it
                    }
                )

                // #72: Capitalize I words (I, I'm, I'll, I'd, I've)
                SettingsSwitch(
                    title = "Capitalize I Words",
                    description = "Auto-capitalize \"I\" and contractions (I'm, I'll, I'd, I've)",
                    checked = capitalizeIWords,
                    onCheckedChange = {
                        capitalizeIWords = it
                        saveSetting("autocapitalize_i_words", it)
                        // Update Config immediately
                        Config.globalConfig()?.autocapitalize_i_words = it
                    }
                )

                SettingsSwitch(
                    title = "Smart Punctuation",
                    description = "Attach punctuation to end of words (removes space before . , ! ? etc.)",
                    checked = smartPunctuationEnabled,
                    onCheckedChange = {
                        smartPunctuationEnabled = it
                        saveSetting("smart_punctuation", it)
                        // Update Config immediately
                        Config.globalConfig().smart_punctuation = it
                    }
                )

                // v1.2.8: Vibration settings moved to Accessibility section

                SettingsSlider(
                    title = "Swipe Distance Threshold",
                    description = "Distance (device-scaled units) to activate slider/event subkeys mid-swipe (e.g. spacebar cursor slider). Also caps the short-swipe minimum on wide keys like backspace so their flicks stay easy.",
                    value = swipeDistance.toFloat(),
                    valueRange = 5f..30f,
                    steps = 25,
                    onValueChange = {
                        swipeDistance = it.toInt()
                        saveSetting("swipe_dist", swipeDistance.toString())
                    },
                    displayValue = "$swipeDistance"
                )

                SettingsSlider(
                    title = "Circle Gesture Sensitivity",
                    description = "Sensitivity for loop/circle gestures",
                    value = circleSensitivity.toFloat(),
                    valueRange = 1f..5f,
                    steps = 4,
                    onValueChange = {
                        circleSensitivity = it.toInt()
                        saveSetting("circle_sensitivity", circleSensitivity.toString())
                    },
                    displayValue = "$circleSensitivity"
                )

                SettingsSlider(
                    title = "Space Bar Slider Sensitivity",
                    description = "Sensitivity for cursor movement via space bar horizontal swipe",
                    value = sliderSensitivity.toFloat(),
                    valueRange = 0f..100f,
                    steps = 100,
                    onValueChange = {
                        sliderSensitivity = it.toInt()
                        saveSetting("slider_sensitivity", sliderSensitivity.toString())
                    },
                    displayValue = "$sliderSensitivity%"
                )

                SettingsSlider(
                    title = "Long Press Timeout",
                    description = "Duration to trigger long press (milliseconds)",
                    value = longPressTimeout.toFloat(),
                    valueRange = 200f..1000f,
                    steps = 16,
                    onValueChange = {
                        longPressTimeout = it.toInt()
                        saveSetting("longpress_timeout", longPressTimeout)
                    },
                    displayValue = "${longPressTimeout}ms"
                )

                SettingsSlider(
                    title = "Long Press Interval",
                    description = "Key repeat interval when long-pressed (milliseconds)",
                    value = longPressInterval.toFloat(),
                    valueRange = 25f..200f,
                    steps = 35,
                    onValueChange = {
                        longPressInterval = it.toInt()
                        saveSetting("longpress_interval", longPressInterval)
                    },
                    displayValue = "${longPressInterval}ms"
                )

                SettingsSwitch(
                    title = "Key Repeat Enabled",
                    description = "Allow keys to repeat when long-pressed",
                    checked = keyRepeatEnabled,
                    onCheckedChange = {
                        keyRepeatEnabled = it
                        saveSetting("keyrepeat_enabled", it)
                    }
                )

                // #81: Only show when key repeat is enabled
                if (keyRepeatEnabled) {
                    SettingsSwitch(
                        title = "Backspace Only Repeat",
                        description = "Only repeat backspace/navigation keys, not character keys",
                        checked = keyRepeatBackspaceOnly,
                        onCheckedChange = {
                            keyRepeatBackspaceOnly = it
                            saveSetting("keyrepeat_backspace_only", it)
                            Config.globalConfig()?.keyrepeat_backspace_only = it
                        }
                    )
                }

                SettingsSwitch(
                    title = "Double Tap Shift for Caps Lock",
                    description = "Lock shift key by tapping twice quickly",
                    checked = doubleTapLockShift,
                    onCheckedChange = {
                        doubleTapLockShift = it
                        saveSetting("lock_double_tap", it)
                    }
                )

                SettingsSwitch(
                    title = "Immediate Keyboard Switching",
                    description = "Switch keyboards immediately instead of showing menu",
                    checked = switchInputImmediate,
                    onCheckedChange = {
                        switchInputImmediate = it
                        saveSetting("switch_input_immediate", it)
                    }
                )

                SettingsDropdown(
                    title = "Number Row",
                    description = "Show number row at top of keyboard",
                    options = listOf("Hidden", "Numbers Only", "Numbers + Symbols"),
                    selectedIndex = when (numberRowMode) {
                        "no_number_row" -> 0
                        "no_symbols" -> 1
                        "symbols" -> 2
                        else -> 0
                    },
                    onSelectionChange = { index ->
                        numberRowMode = when (index) {
                            0 -> "no_number_row"
                            1 -> "no_symbols"
                            2 -> "symbols"
                            else -> "no_number_row"
                        }
                        saveSetting("number_row", numberRowMode)
                    }
                )

                SettingsDropdown(
                    title = "Show Numpad",
                    description = "When to display the numeric keypad",
                    options = listOf("Never", "Landscape Only", "Always"),
                    selectedIndex = when (showNumpadMode) {
                        "never" -> 0
                        "landscape" -> 1
                        "always" -> 2
                        else -> 0
                    },
                    onSelectionChange = { index ->
                        showNumpadMode = when (index) {
                            0 -> "never"
                            1 -> "landscape"
                            2 -> "always"
                            else -> "never"
                        }
                        saveSetting("show_numpad", showNumpadMode)
                    }
                )

                SettingsDropdown(
                    title = "Numpad Layout",
                    description = "Digit order on numeric keypad",
                    options = listOf("High First (7-8-9 on top)", "Low First (1-2-3 on top)"),
                    selectedIndex = if (numpadLayout == "low_first") 1 else 0,
                    onSelectionChange = { index ->
                        numpadLayout = if (index == 1) "low_first" else "default"
                        saveSetting("numpad_layout", numpadLayout)
                    }
                )

                SettingsSwitch(
                    title = "Pin Entry Layout",
                    description = "Activate specialized layout for typing numbers/dates/phone numbers",
                    checked = pinEntryEnabled,
                    onCheckedChange = {
                        pinEntryEnabled = it
                        saveSetting("pin_entry_enabled", it)
                    }
                )
            }

            // Auto-Correction Section (consolidated from Input + Swipe Corrections)
            CollapsibleSettingsSection(
                title = "✏️ Auto-Correction",
                expanded = swipeCorrectionsSectionExpanded,
                onExpandChange = { swipeCorrectionsSectionExpanded = it }
            ) {
                // Master toggle
                SettingsSwitch(
                    title = "Enable Auto-Correction",
                    description = "Automatically correct misspelled words",
                    checked = autoCorrectEnabled,
                    onCheckedChange = {
                        autoCorrectEnabled = it
                        saveSetting("autocorrect_enabled", it)
                    }
                )

                if (autoCorrectEnabled) {
                    // #110: Backspace undo autocorrect — revert to original word on immediate backspace
                    SettingsSwitch(
                        title = "Backspace Undo Autocorrect",
                        description = "Pressing backspace immediately after autocorrect reverts to the original word",
                        checked = backspaceUndoAutocorrect,
                        highlightId = "backspace_undo_autocorrect",
                        onCheckedChange = {
                            backspaceUndoAutocorrect = it
                            saveSetting("backspace_undo_autocorrect", it)
                            Config.globalConfig()?.backspace_undo_autocorrect = it
                        }
                    )

                    // Basic Settings
                    Text(
                        text = "Basic Settings",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )

                    SettingsSlider(
                        title = "Minimum Word Length",
                        description = "Don't correct words shorter than this (2-5 letters)",
                        value = autocorrectMinWordLength.toFloat(),
                        valueRange = 2f..5f,
                        steps = 3,
                        onValueChange = {
                            autocorrectMinWordLength = it.toInt()
                            saveSetting("autocorrect_min_word_length", autocorrectMinWordLength)
                        },
                        displayValue = "$autocorrectMinWordLength letters"
                    )

                    SettingsSlider(
                        title = "Character Match Threshold",
                        description = "How many characters must match (0.5-0.9)",
                        value = autocorrectCharMatchThreshold,
                        valueRange = 0.5f..0.9f,
                        steps = 8,
                        onValueChange = {
                            autocorrectCharMatchThreshold = it
                            saveSetting("autocorrect_char_match_threshold", autocorrectCharMatchThreshold)
                        },
                        displayValue = "%.0f%%".format(autocorrectCharMatchThreshold * 100)
                    )

                    SettingsSlider(
                        title = "Minimum Word Frequency",
                        description = "Only correct to words with frequency >= this",
                        value = autocorrectMinFrequency.toFloat(),
                        valueRange = 100f..5000f,
                        steps = 49,
                        onValueChange = {
                            autocorrectMinFrequency = it.toInt()
                            saveSetting("autocorrect_confidence_min_frequency", autocorrectMinFrequency)
                        },
                        displayValue = "$autocorrectMinFrequency"
                    )

                    // Swipe-Specific Settings
                    Text(
                        text = "Swipe Correction",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )

                    SettingsSwitch(
                        title = "Beam Autocorrect",
                        description = "Apply fuzzy corrections during beam search decoding",
                        checked = swipeBeamAutocorrectEnabled,
                        onCheckedChange = {
                            swipeBeamAutocorrectEnabled = it
                            saveSetting("swipe_beam_autocorrect_enabled", it)
                        }
                    )

                    SettingsSwitch(
                        title = "Final Autocorrect",
                        description = "Apply dictionary-based corrections to final output",
                        checked = swipeFinalAutocorrectEnabled,
                        onCheckedChange = {
                            swipeFinalAutocorrectEnabled = it
                            saveSetting("swipe_final_autocorrect_enabled", it)
                        }
                    )

                    // Advanced Correction Settings
                    Text(
                        text = "Advanced",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )

                    SettingsDropdown(
                        title = "Correction Style",
                        description = "Overall correction aggressiveness preset",
                        options = listOf("Strict (High Accuracy)", "Balanced (Default)", "Lenient (Flexible)"),
                        selectedIndex = when (swipeCorrectionPreset) {
                            "strict" -> 0
                            "balanced" -> 1
                            "lenient" -> 2
                            else -> 1
                        },
                        onSelectionChange = { index ->
                            swipeCorrectionPreset = when (index) {
                                0 -> "strict"
                                1 -> "balanced"
                                2 -> "lenient"
                                else -> "balanced"
                            }
                            saveSetting("swipe_correction_preset", swipeCorrectionPreset)
                        }
                    )

                    SettingsDropdown(
                        title = "Fuzzy Match Algorithm",
                        description = "Method for matching swipe patterns to words",
                        options = listOf("Edit Distance (Recommended)", "Positional Matching (Legacy)"),
                        selectedIndex = if (swipeFuzzyMatchMode == "edit_distance") 0 else 1,
                        onSelectionChange = { index ->
                            swipeFuzzyMatchMode = if (index == 0) "edit_distance" else "positional"
                            saveSetting("swipe_fuzzy_match_mode", swipeFuzzyMatchMode)
                        }
                    )

                    SettingsSlider(
                        title = "Typo Forgiveness",
                        description = "Max character difference allowed (0-5)",
                        value = autocorrectMaxLengthDiff.toFloat(),
                        valueRange = 0f..5f,
                        steps = 5,
                        onValueChange = {
                            autocorrectMaxLengthDiff = it.toInt()
                            saveSetting("autocorrect_max_length_diff", autocorrectMaxLengthDiff)
                        },
                        displayValue = "$autocorrectMaxLengthDiff chars"
                    )

                    SettingsSlider(
                        title = "Starting Letter Accuracy",
                        description = "Required matching prefix length (0-4)",
                        value = autocorrectPrefixLength.toFloat(),
                        valueRange = 0f..4f,
                        steps = 4,
                        onValueChange = {
                            autocorrectPrefixLength = it.toInt()
                            saveSetting("autocorrect_prefix_length", autocorrectPrefixLength)
                        },
                        displayValue = "$autocorrectPrefixLength letters"
                    )

                    SettingsSlider(
                        title = "Correction Search Depth",
                        description = "Number of beam candidates to consider (1-10)",
                        value = autocorrectMaxBeamCandidates.toFloat(),
                        valueRange = 1f..10f,
                        steps = 9,
                        onValueChange = {
                            autocorrectMaxBeamCandidates = it.toInt()
                            saveSetting("autocorrect_max_beam_candidates", autocorrectMaxBeamCandidates)
                        },
                        displayValue = "$autocorrectMaxBeamCandidates"
                    )
                }

                // Word Scoring (always visible - affects predictions regardless of autocorrect)
                Text(
                    text = "Word Scoring",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                // Prediction source balance
                SettingsSlider(
                    title = "Prediction Source Balance",
                    description = "Neural confidence vs dictionary frequency (0=dict, 100=neural)",
                    value = swipePredictionSource.toFloat(),
                    valueRange = 0f..100f,
                    steps = 20,
                    onValueChange = {
                        swipePredictionSource = it.toInt()
                        saveSetting("swipe_prediction_source", swipePredictionSource)
                    },
                    displayValue = "$swipePredictionSource%"
                )

                // Common words boost
                SettingsSlider(
                    title = "Common Words Boost",
                    description = "Bonus multiplier for common words (0.5-2.0)",
                    value = swipeCommonWordsBoost,
                    valueRange = 0.5f..2.0f,
                    steps = 15,
                    onValueChange = {
                        swipeCommonWordsBoost = it
                        saveSetting("swipe_common_words_boost", swipeCommonWordsBoost)
                    },
                    displayValue = "%.2fx".format(swipeCommonWordsBoost)
                )

                // Top 5000 boost
                SettingsSlider(
                    title = "Frequent Words Boost",
                    description = "Bonus for top 5000 words (0.5-2.0)",
                    value = swipeTop5000Boost,
                    valueRange = 0.5f..2.0f,
                    steps = 15,
                    onValueChange = {
                        swipeTop5000Boost = it
                        saveSetting("swipe_top5000_boost", swipeTop5000Boost)
                    },
                    displayValue = "%.2fx".format(swipeTop5000Boost)
                )

                // Rare words penalty
                SettingsSlider(
                    title = "Rare Words Penalty",
                    description = "Multiplier for uncommon words (0.25-1.0)",
                    value = swipeRareWordsPenalty,
                    valueRange = 0.25f..1.0f,
                    steps = 15,
                    onValueChange = {
                        swipeRareWordsPenalty = it
                        saveSetting("swipe_rare_words_penalty", swipeRareWordsPenalty)
                    },
                    displayValue = "%.2fx".format(swipeRareWordsPenalty)
                )
            }

            // Gesture Tuning Section (Collapsible)
            CollapsibleSettingsSection(
                title = "👆 Gesture Tuning",
                expanded = gestureTuningSectionExpanded,
                onExpandChange = { gestureTuningSectionExpanded = it }
            ) {
                Text(
                    text = "Fine-tune tap, swipe, and slider behavior for your typing style.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Short Gestures subsection (moved from Input section)
                Text(
                    text = "Short Gestures",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSwitch(
                    title = "Enable Short Gestures",
                    description = "Recognize short swipes for quick words (it, is, at, etc.)",
                    checked = shortGesturesEnabled,
                    onCheckedChange = {
                        shortGesturesEnabled = it
                        saveSetting("short_gestures_enabled", it)
                    },
                    highlightId = "short_gestures"
                )

                if (shortGesturesEnabled) {
                    SettingsSlider(
                        title = "Min Distance",
                        description = "Minimum swipe distance to trigger (% of key diagonal)",
                        value = shortGestureMinDistance.toFloat(),
                        valueRange = 10f..60f,
                        steps = 10,
                        onValueChange = {
                            shortGestureMinDistance = it.toInt()
                            saveSetting("short_gesture_min_distance", shortGestureMinDistance)
                        },
                        displayValue = "${shortGestureMinDistance}%"
                    )

                    SettingsSlider(
                        title = "Max Distance",
                        description = "The short/long boundary (% of key diagonal): at or below = short swipe, beyond = swipe-typed word. Low values turn slight overshoots into words; high values require longer swipes before word typing starts.",
                        value = shortGestureMaxDistance.toFloat(),
                        valueRange = 50f..200f,
                        steps = 30,
                        onValueChange = {
                            shortGestureMaxDistance = it.toInt()
                            saveSetting("short_gesture_max_distance", shortGestureMaxDistance)
                        },
                        displayValue = "${shortGestureMaxDistance}%"
                    )

                    // Calibration Activity Button
                    val calibrationContext = LocalContext.current
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(calibrationContext, ShortSwipeCalibrationActivity::class.java)
                            calibrationContext.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📐 Open Calibration Tool")
                    }
                    // Customize Per-Key Actions button moved to Activities section at top
                }

                // Selection-Delete Mode subsection (backspace swipe+hold)
                Text(
                    text = "Selection-Delete Mode",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Text(
                    text = "Short swipe + hold on backspace to select text, then release to delete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                SettingsSlider(
                    title = "Vertical Threshold",
                    description = "% of key height finger must move to trigger line selection. Higher = harder to accidentally select lines.",
                    value = selectionDeleteVerticalThreshold.toFloat(),
                    valueRange = 20f..80f,
                    steps = 12,
                    onValueChange = {
                        selectionDeleteVerticalThreshold = it.toInt()
                        saveSetting("selection_delete_vertical_threshold", selectionDeleteVerticalThreshold)
                    },
                    displayValue = "${selectionDeleteVerticalThreshold}%"
                )

                SettingsSlider(
                    title = "Vertical Speed",
                    description = "Speed multiplier for line selection (lower = slower). Character selection stays at full speed.",
                    value = selectionDeleteVerticalSpeed,
                    valueRange = 0.1f..1.0f,
                    steps = 18,
                    onValueChange = {
                        selectionDeleteVerticalSpeed = it
                        saveSetting("selection_delete_vertical_speed", selectionDeleteVerticalSpeed)
                    },
                    displayValue = String.format("%.1fx", selectionDeleteVerticalSpeed)
                )

                // Tap and Typing subsection
                Text(
                    text = "Tap and Typing",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSlider(
                    title = "Tap Duration Threshold",
                    description = "Maximum duration for a tap gesture (ms). Higher = easier taps but may interfere with swipes.",
                    value = tapDurationThreshold.toFloat(),
                    valueRange = 50f..500f,
                    steps = 45,
                    onValueChange = {
                        tapDurationThreshold = it.toInt()
                        saveSetting("tap_duration_threshold", tapDurationThreshold)
                    },
                    displayValue = "${tapDurationThreshold}ms"
                )

                SettingsSwitch(
                    title = "Double-Space to Period",
                    description = "Tap space twice quickly to insert period. Only triggers after letters/numbers.",
                    checked = doubleSpaceToPeriod,
                    onCheckedChange = {
                        doubleSpaceToPeriod = it
                        saveSetting("double_space_to_period", doubleSpaceToPeriod)
                    }
                )

                if (doubleSpaceToPeriod) {
                    SettingsSlider(
                        title = "Double-Space Timing",
                        description = "Maximum time between spaces to trigger period (ms)",
                        value = doubleSpaceThreshold.toFloat(),
                        valueRange = 200f..800f,
                        steps = 12,
                        onValueChange = {
                            doubleSpaceThreshold = it.toInt()
                            saveSetting("double_space_threshold", doubleSpaceThreshold)
                        },
                        displayValue = "${doubleSpaceThreshold}ms"
                    )
                }

                // Swipe Recognition subsection
                Text(
                    text = "Swipe Recognition",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                // Swipe Sensitivity Preset
                val sensitivityPresets = listOf("Low", "Medium", "High", "Custom")
                val currentPresetIndex = sensitivityPresets.indexOf(getSwipeSensitivityPreset())
                SettingsDropdown(
                    title = "Sensitivity Preset",
                    description = "Quick presets for swipe recognition. Custom shows when values differ from presets.",
                    options = sensitivityPresets,
                    selectedIndex = if (currentPresetIndex >= 0) currentPresetIndex else 3,
                    onSelectionChange = { index ->
                        applySwipeSensitivityPreset(sensitivityPresets[index])
                    }
                )

                SettingsSlider(
                    title = "Minimum Swipe Distance",
                    description = "Minimum traced path (px) before a gesture can qualify as a swipe-typed word. Lower helps very short words register; raise if stray touches misfire as words. (Short-vs-long is decided by Max Distance above.)",
                    value = swipeMinDistance,
                    valueRange = 20f..100f,
                    steps = 16,
                    onValueChange = {
                        swipeMinDistance = it
                        saveSetting("swipe_min_distance", swipeMinDistance)
                    },
                    displayValue = "%.0f px".format(swipeMinDistance)
                )

                SettingsSlider(
                    title = "Minimum Key Distance",
                    description = "Distance between keys during swipe (px). Lower captures more keys but may add noise.",
                    value = swipeMinKeyDistance,
                    valueRange = 15f..80f,
                    steps = 13,
                    onValueChange = {
                        swipeMinKeyDistance = it
                        saveSetting("swipe_min_key_distance", swipeMinKeyDistance)
                    },
                    displayValue = "%.0f px".format(swipeMinKeyDistance)
                )

                SettingsSlider(
                    title = "Minimum Key Dwell Time",
                    description = "Time to register a key during swipe (ms). Lower allows faster swiping.",
                    value = swipeMinDwellTime.toFloat(),
                    valueRange = 0f..50f,
                    steps = 10,
                    onValueChange = {
                        swipeMinDwellTime = it.toInt()
                        saveSetting("swipe_min_dwell_time", swipeMinDwellTime)
                    },
                    displayValue = "${swipeMinDwellTime}ms"
                )

                SettingsSlider(
                    title = "Movement Noise Filter",
                    description = "Minimum movement to register (px). Higher filters jitter but may lose data.",
                    value = swipeNoiseThreshold,
                    valueRange = 0.5f..10f,
                    steps = 19,
                    onValueChange = {
                        swipeNoiseThreshold = it
                        saveSetting("swipe_noise_threshold", swipeNoiseThreshold)
                    },
                    displayValue = "%.1f px".format(swipeNoiseThreshold)
                )

                SettingsSlider(
                    title = "High Velocity Threshold",
                    description = "Velocity for fast swipe detection (px/sec). Higher allows faster swipes.",
                    value = swipeHighVelocityThreshold,
                    valueRange = 200f..2000f,
                    steps = 18,
                    onValueChange = {
                        swipeHighVelocityThreshold = it
                        saveSetting("swipe_high_velocity_threshold", swipeHighVelocityThreshold)
                    },
                    displayValue = "%.0f px/s".format(swipeHighVelocityThreshold)
                )

                SettingsSlider(
                    title = "Finger Occlusion Compensation",
                    description = "Y-offset as % of row height to compensate for finger obscuring keys. Higher shifts touch point down toward key centers.",
                    value = fingerOcclusionOffset,
                    valueRange = 0f..50f,
                    steps = 10,
                    onValueChange = {
                        fingerOcclusionOffset = it
                        saveSetting("finger_occlusion_offset", fingerOcclusionOffset)
                    },
                    displayValue = "%.1f%%".format(fingerOcclusionOffset)
                )

                // Slider Key Behavior subsection
                Text(
                    text = "Slider Key Behavior",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                SettingsSlider(
                    title = "Speed Smoothing",
                    description = "Smoothing factor for slider movement. Higher is smoother but less responsive.",
                    value = sliderSpeedSmoothing,
                    valueRange = 0.1f..0.95f,
                    steps = 17,
                    onValueChange = {
                        sliderSpeedSmoothing = it
                        saveSetting("slider_speed_smoothing", sliderSpeedSmoothing)
                    },
                    displayValue = "%.2f".format(sliderSpeedSmoothing)
                )

                SettingsSlider(
                    title = "Maximum Speed Multiplier",
                    description = "Maximum slider acceleration. Higher allows faster sliding.",
                    value = sliderSpeedMax,
                    valueRange = 1.0f..10f,
                    steps = 18,
                    onValueChange = {
                        sliderSpeedMax = it
                        saveSetting("slider_speed_max", sliderSpeedMax)
                    },
                    displayValue = "%.1fx".format(sliderSpeedMax)
                )

                Text(
                    text = "If gestures feel laggy, reduce dwell time and noise threshold. If taps register as swipes, increase tap duration.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // Accessibility Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_accessibility),
                expanded = accessibilitySectionExpanded,
                onExpandChange = { accessibilitySectionExpanded = it }
            ) {
                SettingsSwitch(
                    title = stringResource(R.string.settings_sticky_keys_title),
                    description = stringResource(R.string.settings_sticky_keys_desc),
                    checked = stickyKeysEnabled,
                    onCheckedChange = {
                        stickyKeysEnabled = it
                        saveSetting("sticky_keys_enabled", it)
                    }
                )

                if (stickyKeysEnabled) {
                    SettingsSlider(
                        title = stringResource(R.string.settings_sticky_keys_timeout_title),
                        description = stringResource(R.string.settings_sticky_keys_timeout_desc),
                        value = (stickyKeysTimeout / 1000f),
                        valueRange = 1f..10f,
                        steps = 9,
                        onValueChange = {
                            stickyKeysTimeout = (it * 1000).toInt()
                            saveSetting("sticky_keys_timeout_ms", stickyKeysTimeout)
                        },
                        displayValue = stringResource(R.string.settings_sticky_keys_timeout_value, stickyKeysTimeout / 1000)
                    )
                }

                SettingsSwitch(
                    title = stringResource(R.string.settings_voice_guidance_title),
                    description = stringResource(R.string.settings_voice_guidance_desc),
                    checked = voiceGuidanceEnabled,
                    onCheckedChange = {
                        voiceGuidanceEnabled = it
                        saveSetting("voice_guidance_enabled", it)

                        // Show restart prompt
                        if (it) {
                            Toast.makeText(this@SettingsActivity,
                                getString(R.string.settings_voice_guidance_toast),
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                Text(
                    text = stringResource(R.string.settings_screen_reader_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )

                // v1.2.8: Vibration settings moved to Accessibility section
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Haptic Feedback",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                SettingsSwitch(
                    title = stringResource(R.string.settings_vibration_title),
                    description = stringResource(R.string.settings_vibration_desc),
                    checked = vibrationEnabled,
                    onCheckedChange = {
                        vibrationEnabled = it
                        saveSetting("vibration_enabled", it)
                        // v1.2.8: Update Config immediately for haptic feedback
                        Config.globalConfig().haptic_enabled = it
                        // v1.2.8: Enable custom vibration mode so duration slider actually works
                        if (it) {
                            saveSetting("vibrate_custom", true)
                            Config.globalConfig().vibrate_custom = true
                            Config.globalConfig().vibrate_duration = vibrationDuration.toLong()
                        }
                    }
                )

                if (vibrationEnabled) {
                    SettingsSlider(
                        title = "Vibration Duration",
                        description = "Length of haptic feedback in milliseconds",
                        value = vibrationDuration.toFloat(),
                        valueRange = 5f..100f,
                        steps = 19,
                        onValueChange = {
                            vibrationDuration = it.toInt()
                            saveSetting("vibrate_duration", vibrationDuration)
                            // v1.2.8: Also enable custom vibration mode and update Config
                            saveSetting("vibrate_custom", true)
                            Config.globalConfig().vibrate_custom = true
                            Config.globalConfig().vibrate_duration = vibrationDuration.toLong()
                        },
                        displayValue = "${vibrationDuration}ms"
                    )

                    // Per-event haptic feedback controls
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Haptic Events",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )

                    SettingsSwitch(
                        title = "Key Press",
                        description = "Vibrate on key tap",
                        checked = hapticKeyPress,
                        onCheckedChange = {
                            hapticKeyPress = it
                            saveSetting("haptic_key_press", it)
                            Config.globalConfig().haptic_key_press = it
                        }
                    )

                    SettingsSwitch(
                        title = "Suggestion Tap",
                        description = "Vibrate when selecting a suggestion",
                        checked = hapticPredictionTap,
                        onCheckedChange = {
                            hapticPredictionTap = it
                            saveSetting("haptic_prediction_tap", it)
                            Config.globalConfig().haptic_prediction_tap = it
                        }
                    )

                    SettingsSwitch(
                        title = "TrackPoint Mode",
                        description = "Vibrate when entering cursor mode on nav keys",
                        checked = hapticTrackpointActivate,
                        onCheckedChange = {
                            hapticTrackpointActivate = it
                            saveSetting("haptic_trackpoint_activate", it)
                            Config.globalConfig().haptic_trackpoint_activate = it
                        }
                    )

                    SettingsSwitch(
                        title = "Long Press",
                        description = "Vibrate on modifier lock",
                        checked = hapticLongPress,
                        onCheckedChange = {
                            hapticLongPress = it
                            saveSetting("haptic_long_press", it)
                            Config.globalConfig().haptic_long_press = it
                        }
                    )

                    SettingsSwitch(
                        title = "Swipe Complete",
                        description = "Vibrate when swipe gesture finishes",
                        checked = hapticSwipeComplete,
                        onCheckedChange = {
                            hapticSwipeComplete = it
                            saveSetting("haptic_swipe_complete", it)
                            Config.globalConfig().haptic_swipe_complete = it
                        }
                    )
                }
            }

            // v1.2.6: Dictionary section removed - Dictionary Manager is now accessible
            // from the Activities section at the top of settings for better UX.

            // Clipboard Section (Collapsible)
            CollapsibleSettingsSection(
                title = "📋 Clipboard",
                expanded = clipboardSectionExpanded,
                onExpandChange = { clipboardSectionExpanded = it }
            ) {
                // Enable/disable clipboard history
                SettingsSwitch(
                    title = "Clipboard History",
                    description = "Remember copied text for quick pasting",
                    checked = clipboardHistoryEnabled,
                    onCheckedChange = {
                        clipboardHistoryEnabled = it
                        saveSetting("clipboard_history_enabled", it)
                    }
                )

                // Clipboard limit type dropdown
                val limitTypeOptions = listOf("By Count", "By Size")
                val limitTypeIndex = if (clipboardLimitType == "count") 0 else 1
                SettingsDropdown(
                    title = "Limit Type",
                    description = "How to limit clipboard history",
                    options = limitTypeOptions,
                    selectedIndex = limitTypeIndex,
                    onSelectionChange = { idx ->
                        clipboardLimitType = if (idx == 0) "count" else "size"
                        saveSetting("clipboard_limit_type", clipboardLimitType)
                    }
                )

                // History limit (only shown if limit type is "count")
                if (clipboardLimitType == "count") {
                    SettingsSlider(
                        title = "History Limit",
                        description = "Maximum number of clipboard entries (0 = unlimited)",
                        value = clipboardHistoryLimit.toFloat(),
                        valueRange = 0f..500f,
                        steps = 50,  // 50 steps = increments of 10
                        onValueChange = {
                            clipboardHistoryLimit = it.toInt()
                            saveSetting("clipboard_history_limit", clipboardHistoryLimit)
                        },
                        displayValue = if (clipboardHistoryLimit == 0) "Unlimited" else "$clipboardHistoryLimit items"
                    )
                }

                // Entry Duration — discrete presets from 1 hour to Never
                // Auto-links with History Limit: setting "Never expire" also sets count to Unlimited
                run {
                    val durationPresets = listOf(60, 360, 720, 1440, 4320, 10080, 20160, 43200, -1)
                    val currentIndex = durationPresets.indexOf(clipboardHistoryDuration).let {
                        if (it >= 0) it else durationPresets.indexOfLast { p -> p in 1..clipboardHistoryDuration }
                            .coerceAtLeast(0)
                    }
                    // Contextual description based on limit+duration combination
                    val durationDesc = when {
                        clipboardHistoryDuration != -1 && clipboardHistoryLimit == 0 ->
                            "Warning: count is unlimited but entries expire after this duration"
                        clipboardHistoryDuration == -1 && clipboardHistoryLimit > 0 ->
                            "Entries never expire but capped at $clipboardHistoryLimit (count limit still applies)"
                        else -> "How long entries persist before auto-deletion (-1 = never)"
                    }
                    SettingsSlider(
                        title = "Entry Duration",
                        description = durationDesc,
                        value = currentIndex.toFloat(),
                        valueRange = 0f..(durationPresets.size - 1).toFloat(),
                        steps = durationPresets.size - 2,
                        onValueChange = {
                            val idx = it.toInt().coerceIn(0, durationPresets.size - 1)
                            clipboardHistoryDuration = durationPresets[idx]
                            saveSetting("clipboard_history_duration", clipboardHistoryDuration.toString())
                            // Auto-link: "Never expire" + capped count → set count to unlimited too
                            if (clipboardHistoryDuration == -1 && clipboardHistoryLimit > 0) {
                                clipboardHistoryLimit = 0
                                saveSetting("clipboard_history_limit", 0)
                            }
                            // Trigger mid-session rescue for entries with stale expiry timestamps
                            ClipboardHistoryService.onDurationSettingChanged()
                        },
                        displayValue = when (clipboardHistoryDuration) {
                            -1 -> "Never expire"
                            60 -> "1 hour"
                            360 -> "6 hours"
                            720 -> "12 hours"
                            1440 -> "1 day"
                            4320 -> "3 days"
                            10080 -> "7 days"
                            20160 -> "14 days"
                            43200 -> "30 days"
                            else -> "${clipboardHistoryDuration / 60} hours"
                        }
                    )
                }

                // Size limit (only shown if limit type is "size")
                if (clipboardLimitType == "size") {
                    SettingsSlider(
                        title = "Size Limit",
                        description = "Maximum total clipboard storage",
                        value = clipboardSizeLimitMb.toFloat(),
                        valueRange = 1f..100f,
                        steps = 99,
                        onValueChange = {
                            clipboardSizeLimitMb = it.toInt()
                            saveSetting("clipboard_size_limit_mb", clipboardSizeLimitMb)
                        },
                        displayValue = "$clipboardSizeLimitMb MB"
                    )
                }

                // Pane height percentage
                SettingsSlider(
                    title = "Pane Height",
                    description = "Clipboard pane height as percentage of keyboard",
                    value = clipboardPaneHeightPercent.toFloat(),
                    valueRange = 10f..50f,
                    steps = 40,
                    onValueChange = {
                        clipboardPaneHeightPercent = it.toInt()
                        saveSetting("clipboard_pane_height_percent", clipboardPaneHeightPercent)
                    },
                    displayValue = "$clipboardPaneHeightPercent%"
                )

                // Max item size
                SettingsSlider(
                    title = "Max Item Size",
                    description = "Maximum size per clipboard entry (Android Binder limit: ~1MB)",
                    value = clipboardMaxItemSizeKb.toFloat(),
                    valueRange = 64f..1024f,
                    steps = 14,  // 64, 128, 192, 256, ... 1024
                    onValueChange = {
                        clipboardMaxItemSizeKb = it.toInt()
                        saveSetting("clipboard_max_item_size_kb", clipboardMaxItemSizeKb)
                    },
                    displayValue = "${clipboardMaxItemSizeKb}KB"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Privacy: Exclude password managers
                SettingsSwitch(
                    title = "Exclude Password Managers",
                    description = "Don't store clipboard from Bitwarden, 1Password, LastPass, KeePass, etc.",
                    checked = clipboardExcludePasswordManagers,
                    onCheckedChange = {
                        clipboardExcludePasswordManagers = it
                        saveSetting("clipboard_exclude_password_managers", clipboardExcludePasswordManagers)
                    }
                )

                // #86: Privacy: Respect IS_SENSITIVE flag (Android 13+)
                SettingsSwitch(
                    title = "Respect Sensitive Flag",
                    description = "Skip clipboard marked as sensitive by password managers (Android 13+)",
                    checked = clipboardRespectSensitiveFlag,
                    onCheckedChange = {
                        clipboardRespectSensitiveFlag = it
                        saveSetting("clipboard_respect_sensitive_flag", clipboardRespectSensitiveFlag)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // v4: Feature toggles
                SettingsSwitch(
                    title = "Text Only",
                    description = "Hide media entries and disable media capture",
                    checked = clipboardTextOnly,
                    onCheckedChange = {
                        clipboardTextOnly = it
                        saveSetting("clipboard_text_only", it)
                    }
                )

                SettingsSwitch(
                    title = "Pinned Tab",
                    description = "Show pinned tab for saving important clips",
                    checked = clipboardPinnedEnabled,
                    onCheckedChange = {
                        clipboardPinnedEnabled = it
                        saveSetting("clipboard_pinned_enabled", it)
                    }
                )

                SettingsSwitch(
                    title = "Todo Tab",
                    description = "Show todo tab for marking clips as tasks",
                    checked = clipboardTodoEnabled,
                    onCheckedChange = {
                        clipboardTodoEnabled = it
                        saveSetting("clipboard_todo_enabled", it)
                    }
                )

                // ── URL handling subsection (Chunk 4) ───────────────────────
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "URL handling",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                SettingsSwitch(
                    title = "Sanitize tracking parameters",
                    description = "Strip utm_*, fbclid, etc. from URLs you copy. Powered by ClearURLs.",
                    checked = clipboardSanitizeLinksEnabled,
                    onCheckedChange = {
                        clipboardSanitizeLinksEnabled = it
                        saveSetting("clipboard_sanitize_links_enabled", it)
                        notifySanitizationRulesChanged()
                    }
                )

                SettingsSwitch(
                    title = "Also clean system clipboard",
                    description = "When a copied URL is cleaned, overwrite the system clipboard too, so pastes in any app are sanitized — not just CleverKeys' panel.",
                    checked = clipboardSanitizeSystemClipboard,
                    onCheckedChange = {
                        clipboardSanitizeSystemClipboard = it
                        saveSetting("clipboard_sanitize_system_clipboard", it)
                        notifySanitizationRulesChanged()
                    }
                )

                SettingsSwitch(
                    title = "Enrich embeds for sharing",
                    description = "Rewrite x.com → fxtwitter.com, reddit.com → rxddit.com, etc.",
                    checked = clipboardEmbedEnrichEnabled,
                    onCheckedChange = {
                        clipboardEmbedEnrichEnabled = it
                        saveSetting("clipboard_embed_enrich_enabled", it)
                        notifySanitizationRulesChanged()
                    }
                )

                SettingsSwitch(
                    title = "Use custom rules",
                    description = "Apply your own ClearURLs-format JSON.",
                    checked = clipboardCustomRulesEnabled,
                    onCheckedChange = {
                        clipboardCustomRulesEnabled = it
                        saveSetting("clipboard_custom_rules_enabled", it)
                        notifySanitizationRulesChanged()
                    }
                )

                if (clipboardCustomRulesEnabled) {
                    Button(
                        onClick = { customRulesPickerLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (clipboardCustomRulesUri == null)
                                "Browse for custom.substitutions.json"
                            else
                                "Replace custom rules"
                        )
                    }
                    if (clipboardCustomRulesStatus.isNotEmpty()) {
                        Text(
                            text = clipboardCustomRulesStatus,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = "Note: cleaning runs when CleverKeys saves a clip to its history, so pastes from CleverKeys' panel are always sanitized. With \"Also clean system clipboard\" on, the Android system clipboard is overwritten with the cleaned URL too, so pastes in other apps are sanitized as well (best-effort — only while the keyboard has clipboard access). With it off, other apps still see the original URL.",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // GIF Panel Section (Collapsible) — opt-in, off by default
            CollapsibleSettingsSection(
                title = "\uD83C\uDFAC GIF Panel",
                expanded = gifSectionExpanded,
                onExpandChange = { gifSectionExpanded = it }
            ) {
                Text(
                    text = "Offline GIF reactions. Import packs from ZIP files (download from GitHub Releases).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Master toggle
                SettingsSwitch(
                    title = "Enable GIF Panel",
                    description = "Show GIF key on keyboard and enable reaction picker",
                    checked = gifEnabled,
                    onCheckedChange = {
                        gifEnabled = it
                        saveSetting("gif_enabled", it)
                    }
                )

                if (gifEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // "Get Packs" button — opens browser to GitHub Releases
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            try {
                                startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(tribixbite.cleverkeys.gif.GifPackManager.GITHUB_RELEASES_URL)
                                ))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@SettingsActivity, "Could not open browser", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Get GIF Packs (opens browser)")
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // "Import Pack" button — opens file picker
                    androidx.compose.material3.Button(
                        onClick = {
                            try {
                                gifPackImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@SettingsActivity, "Could not open file picker", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !gifImportInProgress
                    ) {
                        if (gifImportInProgress) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Importing...")
                        } else {
                            Text("Import Pack from ZIP")
                        }
                    }

                    // Import status message
                    gifImportStatus?.let { status ->
                        Text(
                            text = status,
                            fontSize = 12.sp,
                            color = if (status.startsWith("Error")) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Installed packs list
                    if (installedGifPacks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Installed Packs",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        installedGifPacks.forEach { pack ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pack.name, fontSize = 14.sp)
                                    Text(
                                        "${pack.gifCount} GIFs | ${tribixbite.cleverkeys.gif.GifPackManager.formatBytes(pack.sizeBytes)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.IconButton(
                                    onClick = { showGifRemovePackDialog = pack.packId }
                                ) {
                                    Text("X", color = MaterialTheme.colorScheme.error, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Total storage
                        Text(
                            text = "Total: ${tribixbite.cleverkeys.gif.GifPackManager.formatBytes(gifStorageUsed)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Grid columns slider
                    SettingsSlider(
                        title = "Grid Columns",
                        description = "Number of columns in GIF picker grid",
                        value = gifThumbnailColumns.toFloat(),
                        valueRange = 2f..5f,
                        steps = 3,
                        onValueChange = {
                            gifThumbnailColumns = it.toInt()
                            saveSetting("gif_thumbnail_columns", gifThumbnailColumns)
                        },
                        displayValue = "$gifThumbnailColumns columns"
                    )

                    // Remove all GIF data (destructive, with confirmation)
                    if (installedGifPacks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = { showGifRemoveAllDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Remove All GIF Data")
                        }
                    }
                }
            }

            // GIF pack removal confirmation dialogs
            if (showGifRemoveAllDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showGifRemoveAllDialog = false },
                    title = { Text("Remove All GIF Data?") },
                    text = { Text("This will delete all imported GIF packs, thumbnails, and database. This cannot be undone.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showGifRemoveAllDialog = false
                            performGifRemoveAll()
                        }) { Text("Remove All", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showGifRemoveAllDialog = false }) { Text("Cancel") }
                    }
                )
            }

            showGifRemovePackDialog?.let { packId ->
                val packName = installedGifPacks.find { it.packId == packId }?.name ?: packId
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showGifRemovePackDialog = null },
                    title = { Text("Remove $packName?") },
                    text = { Text("This will delete all GIFs from this pack and reclaim storage space.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showGifRemovePackDialog = null
                            performGifRemovePack(packId)
                        }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showGifRemovePackDialog = null }) { Text("Cancel") }
                    }
                )
            }

            // Backup & Restore Section (Collapsible)
            CollapsibleSettingsSection(
                title = "💾 Backup & Restore",
                expanded = backupRestoreSectionExpanded,
                onExpandChange = { backupRestoreSectionExpanded = it },
                sectionId = "backup_restore"
            ) {
                Text(
                    text = "Export and import keyboard settings, dictionary, and clipboard history.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Configuration backup/restore
                Text(
                    text = "Configuration",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportConfiguration() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export Config")
                    }
                    Button(
                        onClick = { importConfiguration() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Config")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Custom dictionary backup/restore
                Text(
                    text = "Custom Dictionary",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportCustomDictionary() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export Dict")
                    }
                    Button(
                        onClick = { importCustomDictionary() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Dict")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Clipboard history backup/restore — JSON (text-only) + ZIP (full backup with media)
                Text(
                    text = "Clipboard History",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Text only (JSON)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportClipboardHistory() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export Clip")
                    }
                    Button(
                        onClick = { importClipboardHistory() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Clip")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Full backup (ZIP with media)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportClipboardZip() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export ZIP")
                    }
                    Button(
                        onClick = { importClipboardZip() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import ZIP")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // GitHub #142: one-click full backup as dated ZIP (config + dicts + clipboard + media).
                Text(
                    text = "Full Backup",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Export everything (settings, dictionary, clipboard, media) into one dated ZIP file.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportFullBackup() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export Full Backup")
                    }
                    Button(
                        onClick = { importFullBackup() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Full Backup")
                    }
                }

                Text(
                    text = "Settings + dictionary imports show a preview so you can deselect entries before applying. Clipboard imports merge non-destructively (duplicates are skipped).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // Multi-Language Section (Collapsible)
            CollapsibleSettingsSection(
                title = "🌐 Multi-Language",
                expanded = multiLangSectionExpanded,
                onExpandChange = { multiLangSectionExpanded = it }
            ) {
                SettingsSwitch(
                    title = "Enable Multi-Language",
                    description = "Support typing in multiple languages",
                    checked = multiLangEnabled,
                    onCheckedChange = {
                        multiLangEnabled = it
                        saveSetting("pref_enable_multilang", it)
                    },
                    highlightId = "multilang"
                )

                if (multiLangEnabled) {
                    // Primary Language selector - any QWERTY-compatible language
                    // NN outputs 26 letters, dictionary provides accent recovery
                    // v1.1.94: Filter out "en" from availableSecondaryLanguages to avoid duplicate
                    val primaryOptions = listOf("en") + availableSecondaryLanguages.filter { it != "en" }
                    val primaryDisplayOptions = primaryOptions.map { getLanguageDisplayName(it) }
                    val primarySelectedIndex = primaryOptions.indexOf(primaryLanguage).coerceAtLeast(0)

                    SettingsDropdown(
                        title = "Primary Language",
                        description = "Main language for predictions (NN works with any QWERTY language)",
                        options = primaryDisplayOptions,
                        selectedIndex = primarySelectedIndex,
                        onSelectionChange = { index ->
                            primaryLanguage = primaryOptions.getOrElse(index) { "en" }
                            saveSetting("pref_primary_language", primaryLanguage)
                            // Reload per-language prefix boost settings
                            loadPrefixBoostForLanguage(primaryLanguage)
                        }
                    )

                    // Secondary Language selector - shows available V2 dictionaries
                    val secondaryOptions = listOf("none") + availableSecondaryLanguages.filter { it != primaryLanguage }
                    val secondaryDisplayOptions = secondaryOptions.map { getLanguageDisplayName(it) }
                    val secondarySelectedIndex = secondaryOptions.indexOf(secondaryLanguage).coerceAtLeast(0)

                    SettingsDropdown(
                        title = "Secondary Language",
                        description = if (availableSecondaryLanguages.isEmpty())
                            "No additional dictionaries available"
                        else
                            "Enable bilingual predictions (e.g., English + Spanish)",
                        options = secondaryDisplayOptions,
                        selectedIndex = secondarySelectedIndex,
                        onSelectionChange = { index ->
                            secondaryLanguage = secondaryOptions.getOrElse(index) { "none" }
                            saveSetting("pref_secondary_language", secondaryLanguage)
                            // Dictionary reload triggered via PreferenceUIUpdateHandler.reloadLanguageDictionaryIfNeeded()
                        }
                    )

                    if (secondaryLanguage != "none") {
                        Text(
                            text = "Secondary dictionary will be loaded on next keyboard open. " +
                                   "Words from both languages will appear in predictions.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                        )

                        // v1.1.94: Secondary language prediction weight slider
                        SettingsSlider(
                            title = "Secondary Language Weight",
                            description = "Prediction weight for secondary dictionary (0.5-1.5)",
                            value = secondaryPredictionWeight,
                            valueRange = 0.5f..1.5f,
                            steps = 20,
                            onValueChange = {
                                secondaryPredictionWeight = it
                                saveSetting("pref_secondary_prediction_weight", secondaryPredictionWeight)
                            },
                            displayValue = "%.2f".format(secondaryPredictionWeight)
                        )
                    }

                    SettingsSwitch(
                        title = "Auto-Detect Language",
                        description = "Automatically detect and switch languages while typing",
                        checked = autoDetectLanguage,
                        onCheckedChange = {
                            autoDetectLanguage = it
                            saveSetting("pref_auto_detect_language", it)
                        }
                    )

                    if (autoDetectLanguage) {
                        SettingsSlider(
                            title = "Detection Sensitivity",
                            description = "How quickly to switch languages (0.4-0.9)",
                            value = languageDetectionSensitivity,
                            valueRange = 0.4f..0.9f,
                            steps = 10,
                            onValueChange = {
                                languageDetectionSensitivity = it
                                saveSetting("pref_language_detection_sensitivity", languageDetectionSensitivity)
                            },
                            displayValue = "%.2f".format(languageDetectionSensitivity)
                        )
                    }

                    // Prefix Boost Settings - only shown for non-English primary
                    // Per-language settings: each language has its own boost multiplier and max
                    if (primaryLanguage != "en") {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Prefix Boost (${getLanguageDisplayName(primaryLanguage)})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = "Boost prefixes common in ${getLanguageDisplayName(primaryLanguage)} but rare in English. " +
                                   "Settings are saved per language.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        SettingsSlider(
                            title = "Boost Strength",
                            description = "0 = disabled, 1 = normal, 2+ = aggressive",
                            value = prefixBoostMultiplier,
                            valueRange = 0f..3f,
                            steps = 30,
                            onValueChange = {
                                prefixBoostMultiplier = it
                                // Save per-language: neural_prefix_boost_multiplier_fr, _de, etc.
                                saveSetting("neural_prefix_boost_multiplier_$primaryLanguage", prefixBoostMultiplier)
                            },
                            displayValue = "%.2f".format(prefixBoostMultiplier)
                        )

                        SettingsSlider(
                            title = "Max Boost",
                            description = "Cap on boost values (higher = stronger correction)",
                            value = prefixBoostMax,
                            valueRange = 1f..15f,
                            steps = 28,
                            onValueChange = {
                                prefixBoostMax = it
                                // Save per-language: neural_prefix_boost_max_fr, _de, etc.
                                saveSetting("neural_prefix_boost_max_$primaryLanguage", prefixBoostMax)
                            },
                            displayValue = "%.1f".format(prefixBoostMax)
                        )

                        SettingsSlider(
                            title = "Max Cumulative Boost",
                            description = "Total boost cap across all chars. Lower = more conservative, prevents long words from dominating.",
                            value = maxCumulativeBoost,
                            valueRange = 5f..30f,
                            steps = 25,
                            onValueChange = {
                                maxCumulativeBoost = it
                                saveSetting("neural_max_cumulative_boost", maxCumulativeBoost)
                            },
                            displayValue = "%.1f".format(maxCumulativeBoost)
                        )

                        SettingsSwitch(
                            title = "Strict Start Character",
                            description = "Only keep predictions starting with detected first key. Helps short swipes.",
                            checked = strictStartChar,
                            onCheckedChange = {
                                strictStartChar = it
                                saveSetting("neural_strict_start_char", strictStartChar)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick Language Toggle Section (v1.2.0)
                    Text(
                        text = "Quick Language Toggle",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Configure alternate languages for quick toggle commands. " +
                               "Assign PRIMARY_LANG_TOGGLE or SECONDARY_LANG_TOGGLE to any key's short swipe.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Alternate Primary Language selector
                    val altPrimaryOptions = availableSecondaryLanguages.filter { it != primaryLanguage }
                    if (altPrimaryOptions.isNotEmpty()) {
                        val altPrimaryDisplayOptions = altPrimaryOptions.map { getLanguageDisplayName(it) }
                        val altPrimarySelectedIndex = altPrimaryOptions.indexOf(primaryLanguageAlt).coerceAtLeast(0)

                        SettingsDropdown(
                            title = "Alternate Primary",
                            description = "Toggle between $primaryLanguage ↔ ${primaryLanguageAlt}",
                            options = altPrimaryDisplayOptions,
                            selectedIndex = altPrimarySelectedIndex,
                            onSelectionChange = { index ->
                                primaryLanguageAlt = altPrimaryOptions.getOrElse(index) { "es" }
                                saveSetting("pref_primary_language_alt", primaryLanguageAlt)
                            }
                        )
                    }

                    // Alternate Secondary Language selector
                    val altSecondaryOptions = listOf("none") + availableSecondaryLanguages.filter {
                        it != secondaryLanguage && it != primaryLanguage
                    }
                    val altSecondaryDisplayOptions = altSecondaryOptions.map { getLanguageDisplayName(it) }
                    val altSecondarySelectedIndex = altSecondaryOptions.indexOf(secondaryLanguageAlt).coerceAtLeast(0)

                    SettingsDropdown(
                        title = "Alternate Secondary",
                        description = "Toggle between ${getLanguageDisplayName(secondaryLanguage)} ↔ ${getLanguageDisplayName(secondaryLanguageAlt)}",
                        options = altSecondaryDisplayOptions,
                        selectedIndex = altSecondarySelectedIndex,
                        onSelectionChange = { index ->
                            secondaryLanguageAlt = altSecondaryOptions.getOrElse(index) { "none" }
                            saveSetting("pref_secondary_language_alt", secondaryLanguageAlt)
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Language Packs Section
                    Text(
                        text = "Language Packs",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Import additional language dictionaries from ZIP files. " +
                               "Download packs externally and import here (no internet permission needed).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // Link to the prebuilt packs folder on GitHub (opens the browser; SAF import below).
                    Text(
                        text = "Browse available packs ↗",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LANGUAGE_PACKS_URL)))
                            }
                            .padding(bottom = 8.dp)
                    )

                    // Installed packs count
                    Text(
                        text = "Installed: ${installedLanguagePacks.size} language pack(s)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { importLanguagePack() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import Pack")
                        }
                        OutlinedButton(
                            onClick = { showLanguagePackDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Manage")
                        }
                    }

                    // Import status message
                    languagePackImportStatus?.let { status ->
                        Text(
                            text = status,
                            fontSize = 11.sp,
                            color = if (status.startsWith("Error"))
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // Language Pack Management Dialog
            if (showLanguagePackDialog) {
                AlertDialog(
                    onDismissRequest = { showLanguagePackDialog = false },
                    title = { Text("Installed Language Packs") },
                    text = {
                        Column {
                            if (installedLanguagePacks.isEmpty()) {
                                Text(
                                    text = "No language packs installed.\n\n" +
                                           "Use 'Browse available packs' to download a ZIP, then 'Import Pack' to add it.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                installedLanguagePacks.forEach { pack ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = pack.name,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = "Code: ${pack.code} • ${pack.wordCount} words",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            TextButton(
                                                onClick = { deleteLanguagePack(pack.code) }
                                            ) {
                                                Text("Delete", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLanguagePackDialog = false }) {
                            Text("Close")
                        }
                    }
                )
            }

            // Privacy Section (Collapsible)
            CollapsibleSettingsSection(
                title = "🔒 Privacy & Data",
                expanded = privacySectionExpanded,
                onExpandChange = { privacySectionExpanded = it }
            ) {
                Text(
                    text = "CleverKeys is fully offline — no data ever leaves your device. " +
                           "These optional settings store local data for potential future on-device model fine-tuning.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Local Data Collection (Optional)",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSwitch(
                    title = "Swipe Pattern Data",
                    description = "Store swipe trajectories locally for on-device learning",
                    checked = privacyCollectSwipe,
                    onCheckedChange = {
                        privacyCollectSwipe = it
                        saveSetting("privacy_collect_swipe", it)
                    }
                )

                SettingsSwitch(
                    title = "Performance Metrics",
                    description = "Store timing data locally for optimization",
                    checked = privacyCollectPerformance,
                    onCheckedChange = {
                        privacyCollectPerformance = it
                        saveSetting("privacy_collect_performance", it)
                    }
                )

                // TODO: Error Reports toggle hidden - no actual logging implementation yet
                // When implemented, should use async file logging to avoid latency impact

                // Collected Data Stats and Export
                Text(
                    text = "Collected Data",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                // Show stats
                val stats = remember {
                    try {
                        tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(this@SettingsActivity).getStatistics()
                    } catch (e: Exception) {
                        null
                    }
                }

                if (stats != null && stats.totalCount > 0) {
                    Text(
                        text = "Total swipes: ${stats.totalCount} • Unique words: ${stats.uniqueWords}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Export buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { exportSwipeDataJSON() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export JSON")
                        }
                        OutlinedButton(
                            onClick = { exportSwipeDataNDJSON() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export NDJSON")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // View and Delete buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewCollectedData() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View")
                        }
                        OutlinedButton(
                            onClick = { deleteCollectedData() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Delete")
                        }
                    }
                } else {
                    Text(
                        text = "No swipe data collected yet. Enable collection above to start storing patterns for future on-device learning.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Performance Metrics Section
                Text(
                    text = "Performance Metrics",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                val perfStats = remember {
                    try {
                        NeuralPerformanceStats.getInstance(this@SettingsActivity)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (perfStats != null && perfStats.hasStats()) {
                    Text(
                        text = "Predictions: ${perfStats.getTotalPredictions()} • Avg: ${perfStats.getAverageInferenceTime()}ms • Top-1: ${perfStats.getTop1Accuracy()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewPerfStats() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View")
                        }
                        OutlinedButton(
                            onClick = { exportPerfStats() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export")
                        }
                    }
                } else {
                    Text(
                        text = "No performance data collected yet. Enable collection above and use swipe typing.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // Advanced Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_advanced),
                expanded = advancedSectionExpanded,
                onExpandChange = { advancedSectionExpanded = it }
            ) {
                // Terminal Mode - moved from Neural section (layout setting, not prediction)
                SettingsSwitch(
                    title = "Terminal Mode",
                    description = "Show Ctrl, Meta, PageUp/Down keys for terminal apps like Termux",
                    checked = termuxModeEnabled,
                    onCheckedChange = {
                        termuxModeEnabled = it
                        saveSetting("termux_mode_enabled", it)
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.settings_debug_title),
                    description = stringResource(R.string.settings_debug_desc),
                    checked = debugEnabled,
                    onCheckedChange = {
                        debugEnabled = it
                        saveSetting("debug_enabled", it)
                    }
                )

                // Phase 1: Swipe Debug Log Toggle
                SettingsSwitch(
                    title = "Swipe Debug Log",
                    description = "Real-time pipeline analysis for swipe gestures (requires logcat)",
                    checked = swipeDebugEnabled,
                    onCheckedChange = {
                        swipeDebugEnabled = it
                        saveSetting("swipe_show_debug_scores", it)
                    }
                )

                if (swipeDebugEnabled) {
                    SettingsSwitch(
                        title = "Detailed Logging",
                        description = "Include verbose trace information",
                        checked = swipeDebugDetailedLogging,
                        onCheckedChange = {
                            swipeDebugDetailedLogging = it
                            saveSetting("swipe_debug_detailed_logging", it)
                        }
                    )

                    SettingsSwitch(
                        title = "Show Raw Output",
                        description = "Log raw neural outputs to debug log (doesn't affect suggestions)",
                        checked = swipeDebugShowRawOutput,
                        onCheckedChange = {
                            swipeDebugShowRawOutput = it
                            saveSetting("swipe_debug_show_raw_output", it)
                        }
                    )

                    SettingsSwitch(
                        title = "Show Beam Predictions",
                        description = "Add raw:word items to suggestion bar for debugging",
                        checked = swipeShowRawBeamPredictions,
                        onCheckedChange = {
                            swipeShowRawBeamPredictions = it
                            saveSetting("swipe_show_raw_beam_predictions", it)
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { openSwipeDebugActivity() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Debug Log")
                    }
                }

                // #136: "Max Sequence Length Override" slider removed.
                // The encoder ONNX graph is exported with max_seq_length=250 baked
                // in. A user-set value > 250 caused every swipe to crash with
                // ORT_INVALID_ARGUMENT (got: <user value>, expected: 250). Any
                // legacy stored pref is now clamped at the orchestrator level.
                // Setting key `neural_user_max_seq_length` is preserved in Config
                // so backup/restore round-trips still work.

                Button(
                    onClick = { openCalibration() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_calibration_button))
                }
            }

            // Version and Actions Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_info),
                expanded = infoSectionExpanded,
                onExpandChange = { infoSectionExpanded = it }
            ) {
                VersionInfoCard()

                // GitHub release info
                GitHubInfoCard()

                // Reset settings button
                Button(
                    onClick = { resetAllSettings() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.settings_reset_button))
                }

                // Note: Self-update feature removed for F-Droid compliance
                // F-Droid handles updates automatically

            }

            // Help Section (Collapsible) - FAQ and Wiki
            CollapsibleSettingsSection(
                title = "❓ Help & FAQ",
                expanded = helpSectionExpanded,
                onExpandChange = { helpSectionExpanded = it }
            ) {
                // FAQ Items
                FAQSection()

                Spacer(modifier = Modifier.height(16.dp))

                // Online Wiki Button
                Button(
                    onClick = { openWikiInBrowser() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Open Full Wiki")
                }
            }
        }
    }






    // Self-update feature removed for F-Droid compliance
    // F-Droid handles updates automatically - no storage permissions needed

    // GIF pack share intent handling (for ACTION_SEND / ACTION_VIEW with ZIP)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGifPackShareIntent(intent)
    }

}
