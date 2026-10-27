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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.autocorrect.AutocorrectTuning
import tribixbite.cleverkeys.theme.KeyboardTheme
import tribixbite.cleverkeys.langpack.LanguagePackManifest
import tribixbite.cleverkeys.ui.settings.SearchableSetting
import tribixbite.cleverkeys.ui.settings.expanderFor
import tribixbite.cleverkeys.ui.settings.scrollToSetting
import tribixbite.cleverkeys.ui.settings.sectionDisplayName
import tribixbite.cleverkeys.ui.settings.SettingsSearchMatch
import tribixbite.cleverkeys.ui.settings.bareTitle
import tribixbite.cleverkeys.ui.settings.io.CreateBackupDocument
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
import tribixbite.cleverkeys.ui.settings.io.refreshBoundLayoutLanguages
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
         * The GitHub `langpacks` pre-release carrying every prebuilt language-pack ZIP
         * (bg, de, el, he, mk, nl, ru, sv, tr, uk, ... — 23 assets). Users download a ZIP
         * here and import it via SAF — the app has no INTERNET permission, so this only
         * opens the browser. The same zips are committed under `scripts/dictionaries/`,
         * but the release page is the download surface phones can actually use (raw
         * per-file downloads, no repo browsing).
         */
        const val LANGUAGE_PACKS_URL =
            "https://github.com/tribixbite/CleverKeys/releases/tag/langpacks"

        /**
         * Test-only override for the inline Backup & Restore flow's manager.
         * Instrumented tests set this in @Before, clear it in @After. Mirrors
         * [BackupRestoreActivity.testManagerOverride] — used by the migrated
         * ImportPreview tests that exercise the inline preview dispatch.
         */
        // Not a real leak: this is a test-only hook that instrumented tests set in
        // @Before and null out in @After, so the BackupRestoreManager (and its Context)
        // is never retained beyond a single test. Mirrors
        // BackupRestoreActivity.testManagerOverride, which follows the same pattern.
        @androidx.annotation.VisibleForTesting
        @android.annotation.SuppressLint("StaticFieldLeak")
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

    // SAF file pickers for backup/restore.
    //
    // ARC-035: the five ENCRYPTABLE exports use CreateBackupDocument (per-launch MIME) rather
    // than CreateDocument (MIME fixed at registration), because an encrypted export must ask for
    // application/octet-stream or the DocumentsProvider rewrites the `.ckenc` suffix away. The
    // swipe-ML / perf-stats exports below are never encrypted and keep the plain contract.
    internal val configExportLauncher = registerForActivityResult(
        CreateBackupDocument()
    ) { uri: Uri? ->
        uri?.let { performConfigExport(it, consumePlaintextOptOut()) }
    }

    internal val configImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performConfigImport(it) }
    }

    internal val dictionaryExportLauncher = registerForActivityResult(
        CreateBackupDocument()
    ) { uri: Uri? ->
        uri?.let { performDictionaryExport(it, consumePlaintextOptOut()) }
    }

    internal val dictionaryImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performDictionaryImport(it) }
    }

    internal val clipboardExportLauncher = registerForActivityResult(
        CreateBackupDocument()
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
        CreateBackupDocument()
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
        CreateBackupDocument()
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
    internal var swipeEngineMode by mutableStateOf(Defaults.SWIPE_ENGINE_MODE)
    /**
     * Moving-average window applied to the raw touch path before ANY decoder sees it
     * (`ImprovedSwipeGestureRecognizer`). Its only slider used to live in the deleted
     * NeuralSettingsActivity, so from 018d94f7 until 2026-08-19 the pref stayed live and
     * user-visible control of it vanished — restored into Gesture Tuning, where it belongs:
     * it is an input-path setting, never a decoder one.
     */
    internal var swipeSmoothingWindow by mutableIntStateOf(Defaults.SWIPE_SMOOTHING_WINDOW)
    internal var swipeContextRescoring by mutableStateOf(Defaults.SWIPE_CONTEXT_RESCORING)

    /** Signed percent of one key row; see Defaults.FINGER_OCCLUSION_OFFSET. */
    internal var fingerOcclusionOffset by mutableIntStateOf(Defaults.FINGER_OCCLUSION_OFFSET)
    internal var keyboardHeight by mutableIntStateOf(Defaults.KEYBOARD_HEIGHT_PORTRAIT)
    internal var keyboardHeightLandscape by mutableIntStateOf(Defaults.KEYBOARD_HEIGHT_LANDSCAPE)
    // Inner (unfolded) screen heights as the keyboard will use them: an unset key follows the
    // outer-screen value (KeyboardHeightPrefs), so these show the effective height.
    internal var keyboardHeightUnfolded by mutableIntStateOf(Defaults.KEYBOARD_HEIGHT_PORTRAIT)
    internal var keyboardHeightLandscapeUnfolded by mutableIntStateOf(Defaults.KEYBOARD_HEIGHT_LANDSCAPE)
    internal var scaleNumpadHeight by mutableStateOf(Defaults.SCALE_NUMPAD_HEIGHT)  // F-8 / #58: numpad row scaling
    internal var vibrationEnabled by mutableStateOf(Defaults.HAPTIC_ENABLED)
    internal var debugEnabled by mutableStateOf(Defaults.DEBUG_ENABLED)
    internal var clipboardHistoryEnabled by mutableStateOf(Defaults.CLIPBOARD_HISTORY_ENABLED)
    internal var clipboardHistoryLimit by mutableIntStateOf(Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK)
    internal var clipboardHistoryDuration by mutableIntStateOf(Defaults.CLIPBOARD_HISTORY_DURATION_FALLBACK)  // Minutes; -1 = never expire
    internal var clipboardPaneHeightPercent by mutableIntStateOf(Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT)
    internal var clipboardMaxItemSizeKb by mutableIntStateOf(Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK)
    internal var clipboardLimitType by mutableStateOf(Defaults.CLIPBOARD_LIMIT_TYPE) // "count" or "size"
    internal var clipboardSizeLimitMb by mutableIntStateOf(Defaults.CLIPBOARD_SIZE_LIMIT_MB_FALLBACK)
    internal var clipboardExcludePasswordManagers by mutableStateOf(Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS)  // Privacy: skip password managers
    internal var clipboardRespectSensitiveFlag by mutableStateOf(Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG)  // #86: Respect IS_SENSITIVE flag
    internal var clipboardTextOnly by mutableStateOf(Defaults.CLIPBOARD_TEXT_ONLY)  // v4: Hide media entries
    internal var clipboardMediaEnabled by mutableStateOf(Defaults.CLIPBOARD_MEDIA_ENABLED)  // F-8: media capture toggle
    internal var clipboardMaxMediaSizeMb by mutableIntStateOf(Defaults.CLIPBOARD_MAX_MEDIA_SIZE_MB)  // F-8: media size cap
    internal var clipboardPinnedEnabled by mutableStateOf(Defaults.CLIPBOARD_PINNED_ENABLED)  // v4: Show/hide pinned tab
    internal var clipboardTodoEnabled by mutableStateOf(Defaults.CLIPBOARD_TODO_ENABLED)  // v4: Show/hide todo tab
    internal var clipboardSwipeDeleteEnabled by mutableStateOf(Defaults.CLIPBOARD_SWIPE_DELETE_ENABLED)  // GH #175(2)
    internal var clipboardPrivateCopyToolbarEnabled by mutableStateOf(Defaults.CLIPBOARD_PRIVATE_COPY_TOOLBAR_ENABLED)  // #156: PROCESS_TEXT opt-in
    internal var clipboardPrivateCopyToastEnabled by mutableStateOf(Defaults.CLIPBOARD_PRIVATE_COPY_TOAST_ENABLED)  // ARC-037: suppressible confirmation

    // URL sanitization (Chunk 4): three independent toggles + custom-rules import
    internal var clipboardSanitizeLinksEnabled by mutableStateOf(Defaults.CLIPBOARD_SANITIZE_LINKS_ENABLED)
    internal var clipboardEmbedEnrichEnabled by mutableStateOf(Defaults.CLIPBOARD_EMBED_ENRICH_ENABLED)
    internal var clipboardCustomRulesEnabled by mutableStateOf(Defaults.CLIPBOARD_CUSTOM_RULES_ENABLED)
    internal var clipboardSanitizeSystemClipboard by mutableStateOf(Defaults.CLIPBOARD_SANITIZE_SYSTEM_CLIPBOARD)
    internal var clipboardCustomRulesUri by mutableStateOf<String?>(null)
    // Status text for the custom-rules row — examples:
    //  "" / "12 providers loaded." / "Saved file is malformed: ..." / "URI persisted but no copy on disk yet."
    internal var clipboardCustomRulesStatus by mutableStateOf("")

    // GIF Panel (opt-in, off by default)
    internal var gifEnabled by mutableStateOf(Defaults.GIF_ENABLED)
    internal var gifThumbnailColumns by mutableIntStateOf(Defaults.GIF_THUMBNAIL_COLUMNS)
    internal var installedGifPacks by mutableStateOf(listOf<tribixbite.cleverkeys.gif.InstalledPackInfo>())
    // gifImportInProgress/gifImportStatus delegated to settingsViewModel (survive rotation)
    internal var gifImportInProgress: Boolean
        get() = settingsViewModel.gifImportInProgress
        set(value) { settingsViewModel.gifImportInProgress = value }
    internal var gifImportStatus: tribixbite.cleverkeys.ui.settings.io.GifImportStatus?
        get() = settingsViewModel.gifImportStatus
        set(value) { settingsViewModel.gifImportStatus = value }
    // showGifRemoveAllDialog/showGifRemovePackDialog delegated to settingsViewModel (survive rotation)
    internal var showGifRemoveAllDialog: Boolean
        get() = settingsViewModel.showGifRemoveAllDialog
        set(value) { settingsViewModel.showGifRemoveAllDialog = value }
    internal var showGifRemovePackDialog: String?
        get() = settingsViewModel.showGifRemovePackDialog
        set(value) { settingsViewModel.showGifRemovePackDialog = value }
    internal var gifStorageUsed by mutableLongStateOf(0L)

    internal var autoCapitalizationEnabled by mutableStateOf(Defaults.AUTOCAPITALISATION)
    internal var capitalizeIWords by mutableStateOf(Defaults.AUTOCAPITALIZE_I_WORDS)  // #72: Auto-capitalize I, I'm, I'll, etc.

    // Phase 1: Expose existing Config.kt settings
    internal var continuousSwipeEnabled by mutableStateOf(Defaults.CONTINUOUS_SWIPE_ENABLED)
    internal var swipeTypingEnabled by mutableStateOf(Defaults.SWIPE_TYPING_ENABLED)
    internal var swipeOnPasswordFields by mutableStateOf(Defaults.SWIPE_ON_PASSWORD_FIELDS)  // #39: Allow swipe on password fields
    internal var wordPredictionEnabled by mutableStateOf(Defaults.WORD_PREDICTION_ENABLED)
    internal var autoSpaceAfterSuggestion by mutableStateOf(Defaults.AUTO_SPACE_AFTER_SUGGESTION)  // #82: Add trailing space after selecting suggestion
    internal var autoSpaceBeforeSuggestion by mutableStateOf(Defaults.AUTO_SPACE_BEFORE_SUGGESTION)  // Add leading space before tapped suggestion
    internal var showExactTypedWord by mutableStateOf(Defaults.SHOW_EXACT_TYPED_WORD)  // F-8 / #42: exact-typed-word suggestion
    internal var backspaceUndoSwipe by mutableStateOf(Defaults.BACKSPACE_UNDO_SWIPE)  // #110: Backspace after swipe deletes entire swiped word
    internal var backspaceUndoAutocorrect by mutableStateOf(Defaults.BACKSPACE_UNDO_AUTOCORRECT)  // #110: Backspace after autocorrect reverts to original word
    internal var suggestionBarOpacity by mutableIntStateOf(Defaults.SUGGESTION_BAR_OPACITY)
    internal var autoCorrectEnabled by mutableStateOf(Defaults.AUTOCORRECT_ENABLED)
    internal var customTerminalPackages by mutableStateOf(Defaults.CUSTOM_TERMINAL_PACKAGES)
    internal var imeDefaultPromptEnabled by mutableStateOf(Defaults.IME_DEFAULT_PROMPT_ENABLED)  // I-7: default-IME reminder
    internal var vibrationDuration by mutableIntStateOf(Defaults.VIBRATE_DURATION)
    // #154: false = system haptic pattern (performHapticFeedback), true = custom duration.
    internal var vibrateCustom by mutableStateOf(Defaults.VIBRATE_CUSTOM)
    // Per-event haptic feedback toggles
    internal var hapticKeyPress by mutableStateOf(Defaults.HAPTIC_KEY_PRESS)
    internal var hapticPredictionTap by mutableStateOf(Defaults.HAPTIC_PREDICTION_TAP)
    internal var hapticTrackpointActivate by mutableStateOf(Defaults.HAPTIC_TRACKPOINT_ACTIVATE)
    internal var hapticLongPress by mutableStateOf(Defaults.HAPTIC_LONG_PRESS)
    internal var hapticSwipeComplete by mutableStateOf(Defaults.HAPTIC_SWIPE_COMPLETE)
    internal var swipeDebugEnabled by mutableStateOf(Defaults.SWIPE_SHOW_DEBUG_SCORES)

    // Adaptive layout settings (percentages of screen dimensions)
    internal var marginBottomPortrait by mutableIntStateOf(Defaults.MARGIN_BOTTOM_PORTRAIT)
    internal var marginBottomLandscape by mutableIntStateOf(Defaults.MARGIN_BOTTOM_LANDSCAPE)
    internal var marginLeftPortrait by mutableIntStateOf(Defaults.MARGIN_LEFT_PORTRAIT)
    internal var marginLeftLandscape by mutableIntStateOf(Defaults.MARGIN_LEFT_LANDSCAPE)
    internal var marginRightPortrait by mutableIntStateOf(Defaults.MARGIN_RIGHT_PORTRAIT)
    internal var marginRightLandscape by mutableIntStateOf(Defaults.MARGIN_RIGHT_LANDSCAPE)

    // Gesture sensitivity settings
    internal var swipeDistance by mutableIntStateOf(Defaults.SWIPE_DIST_FALLBACK.toInt())
    internal var circleSensitivity by mutableIntStateOf(Defaults.CIRCLE_SENSITIVITY_FALLBACK)
    internal var sliderSensitivity by mutableIntStateOf(Defaults.SLIDER_SENSITIVITY_FALLBACK.toInt())

    // Long press settings
    internal var longPressTimeout by mutableIntStateOf(Defaults.LONGPRESS_TIMEOUT)
    internal var longPressInterval by mutableIntStateOf(Defaults.LONGPRESS_INTERVAL)
    internal var keyRepeatEnabled by mutableStateOf(Defaults.KEYREPEAT_ENABLED)
    internal var keyRepeatBackspaceOnly by mutableStateOf(Defaults.KEYREPEAT_BACKSPACE_ONLY)  // #81: Only repeat backspace/nav

    // Visual customization settings
    internal var labelBrightness by mutableIntStateOf(Defaults.LABEL_BRIGHTNESS)
    internal var keyboardOpacity by mutableIntStateOf(Defaults.KEYBOARD_OPACITY)
    internal var keyOpacity by mutableIntStateOf(Defaults.KEY_OPACITY)
    internal var keyActivatedOpacity by mutableIntStateOf(Defaults.KEY_ACTIVATED_OPACITY)

    // Spacing and sizing settings
    internal var characterSize by mutableIntStateOf((Defaults.CHARACTER_SIZE * 100).toInt())
    internal var secondaryLabelSizeScale by mutableIntStateOf((Defaults.SECONDARY_LABEL_SIZE_SCALE * 100).toInt()) // #133: percent; 100 = unchanged
    internal var keyVerticalMargin by mutableIntStateOf((Defaults.KEY_VERTICAL_MARGIN * 100).toInt())
    internal var keyHorizontalMargin by mutableIntStateOf((Defaults.KEY_HORIZONTAL_MARGIN * 100).toInt())

    // Border customization settings
    internal var borderConfigEnabled by mutableStateOf(Defaults.BORDER_CONFIG)
    internal var lookShowHints by mutableStateOf(Defaults.LOOK_SHOW_HINTS)
    internal var lookKeyPreview by mutableStateOf(Defaults.LOOK_KEY_PREVIEW)
    internal var customBorderRadius by mutableIntStateOf(Defaults.CUSTOM_BORDER_RADIUS)
    internal var customBorderLineWidth by mutableIntStateOf(Defaults.CUSTOM_BORDER_LINE_WIDTH)

    // Behavior settings
    internal var doubleTapLockShift by mutableStateOf(Defaults.DOUBLE_TAP_LOCK_SHIFT)
    internal var switchInputImmediate by mutableStateOf(Defaults.SWITCH_INPUT_IMMEDIATE)
    internal var smartPunctuationEnabled by mutableStateOf(Defaults.SMART_PUNCTUATION) // Attach punctuation to end of last word
    // #139: Enter inserts a newline in multi-line fields instead of the editor action.
    internal var enterAlwaysNewline by mutableStateOf(Defaults.ENTER_ALWAYS_NEWLINE)

    // Gesture tuning settings
    internal var tapDurationThreshold by mutableIntStateOf(Defaults.TAP_DURATION_THRESHOLD) // ms
    internal var doubleSpaceToPeriod by mutableStateOf(Defaults.DOUBLE_SPACE_TO_PERIOD) // Enable double-space-to-period
    internal var doubleSpaceThreshold by mutableIntStateOf(Defaults.DOUBLE_SPACE_THRESHOLD) // ms
    internal var swipeMinDistance by mutableFloatStateOf(Defaults.SWIPE_MIN_DISTANCE) // pixels
    internal var swipeMinKeyDistance by mutableFloatStateOf(Defaults.SWIPE_MIN_KEY_DISTANCE) // pixels
    internal var swipeMinDwellTime by mutableIntStateOf(Defaults.SWIPE_MIN_DWELL_TIME) // ms
    internal var swipeNoiseThreshold by mutableFloatStateOf(Defaults.SWIPE_NOISE_THRESHOLD) // pixels
    internal var swipeHighVelocityThreshold by mutableFloatStateOf(Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD) // px/sec
    internal var sliderSpeedSmoothing by mutableFloatStateOf(Defaults.SLIDER_SPEED_SMOOTHING) // 0.0-1.0
    internal var sliderSpeedMax by mutableFloatStateOf(Defaults.SLIDER_SPEED_MAX) // multiplier

    // Number row and numpad settings
    internal var numberRowMode by mutableStateOf(Defaults.NUMBER_ROW) // "no_number_row", "no_symbols", "symbols"
    internal var showNumpadMode by mutableStateOf(Defaults.SHOW_NUMPAD) // "never", "landscape", "always"
    internal var numpadLayout by mutableStateOf(Defaults.NUMPAD_LAYOUT) // "default", "low_first"
    internal var pinEntryEnabled by mutableStateOf(Defaults.NUMBER_ENTRY_LAYOUT == "pin")

    // Swipe Corrections settings (migrated from XML)
    internal var swipeFinalAutocorrectEnabled by mutableStateOf(Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)
    internal var autocorrectMaxLengthDiff by mutableIntStateOf(Defaults.AUTOCORRECT_MAX_LENGTH_DIFF)
    internal var autocorrectPrefixLength by mutableIntStateOf(Defaults.AUTOCORRECT_PREFIX_LENGTH)
    // Auto-Correction "Ranking & Tuning" group: the same immutable snapshot the predictor reads
    // (AutocorrectTuning.fromPrefs clamps every key into its AutocorrectTuning.Keys range), so the
    // screen can never show a value the keyboard does not use.
    internal var autocorrectTuning by mutableStateOf(AutocorrectTuning.DEFAULT)
    // swipeFuzzyMatchMode / autocorrectMaxBeamCandidates / swipePredictionSource /
    // swipeCommonWordsBoost / swipeTop5000Boost / swipeRareWordsPenalty are GONE (ARC-051),
    // and swipeCorrectionPreset ("Correction Style") with them since ARC-085 (2026-08-29).
    // Their keys are in SettingsValidation.DEPRECATED_KEYS: the only consumers died with
    // OptimizedVocabulary in the neural-engine removal — or, for the correction preset, never
    // existed at all — so the fields were loaded on every settings open and rewritten on every
    // preference change while being read by nothing.
    // Do not re-add them — a backing field here is what makes a dead key look alive.

    // Swipe trail appearance settings
    internal var swipeTrailEnabled by mutableStateOf(Defaults.SWIPE_TRAIL_ENABLED)
    internal var swipeTrailEffect by mutableStateOf(Defaults.SWIPE_TRAIL_EFFECT)
    internal var swipeTrailColor by mutableIntStateOf(Defaults.SWIPE_TRAIL_COLOR)
    internal var swipeTrailWidth by mutableFloatStateOf(Defaults.SWIPE_TRAIL_WIDTH)
    internal var swipeTrailGlowRadius by mutableFloatStateOf(Defaults.SWIPE_TRAIL_GLOW_RADIUS)

    // Word Prediction Advanced settings
    internal var contextAwarePredictionsEnabled by mutableStateOf(Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED)
    internal var nextWordPredictionEnabled by mutableStateOf(Defaults.NEXT_WORD_PREDICTION_ENABLED) // default ON since 2026-09-26
    internal var contextSource by mutableStateOf(Defaults.CONTEXT_SOURCE) // both | learned_only | static_only
    internal var personalizedLearningEnabled by mutableStateOf(Defaults.PERSONALIZED_LEARNING_ENABLED)
    internal var personalizationWeight by mutableFloatStateOf(Defaults.PERSONALIZATION_WEIGHT) // 0=off … 2=double
    internal var personalizationMaxWords by mutableIntStateOf(Defaults.PERSONALIZATION_MAX_WORDS) // learned-vocab cap
    internal var learningAggression by mutableStateOf(Defaults.LEARNING_AGGRESSION)
    internal var predictionContextBoost by mutableFloatStateOf(Defaults.PREDICTION_CONTEXT_BOOST)
    internal var predictionFrequencyScale by mutableFloatStateOf(Defaults.PREDICTION_FREQUENCY_SCALE)

    // Auto-correction advanced settings
    internal var autocorrectMinWordLength by mutableIntStateOf(Defaults.AUTOCORRECT_MIN_WORD_LENGTH)
    internal var autocorrectCharMatchThreshold by mutableFloatStateOf(Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD)
    internal var autocorrectMinFrequency by mutableIntStateOf(Defaults.AUTOCORRECT_MIN_FREQUENCY)

    // Multi-language settings
    internal var multiLangEnabled by mutableStateOf(Defaults.ENABLE_MULTILANG)
    internal var primaryLanguage by mutableStateOf(Defaults.PRIMARY_LANGUAGE)
    internal var secondaryLanguage by mutableStateOf(Defaults.SECONDARY_LANGUAGE) // "none", "es", "fr", etc.
    internal var autoDetectLanguage by mutableStateOf(Defaults.AUTO_DETECT_LANGUAGE)
    internal var languageDetectionSensitivity by mutableFloatStateOf(Defaults.LANGUAGE_DETECTION_SENSITIVITY)
    internal var secondaryPredictionWeight by mutableFloatStateOf(Defaults.SECONDARY_PREDICTION_WEIGHT) // v1.1.94: Secondary dictionary weight
    internal var primaryLanguageAlt by mutableStateOf(Defaults.PRIMARY_LANGUAGE_ALT) // v1.2.0: Alternate primary for quick toggle
    internal var secondaryLanguageAlt by mutableStateOf(Defaults.SECONDARY_LANGUAGE_ALT) // v1.2.0: Alternate secondary for quick toggle
    internal var availableSecondaryLanguages by mutableStateOf(listOf<String>()) // V2 dictionaries
    /** Distinct languages bound to a layout in Layout Manager (GH #186/#61), refreshed on resume. */
    internal var boundLayoutLanguages by mutableStateOf(listOf<String>())
    internal var installedLanguagePacks by mutableStateOf(listOf<LanguagePackManifest>())
    /** gh #184: installed packs whose dictionary the loader refuses, by language code. */
    internal var languagePackProblems by mutableStateOf(mapOf<String, tribixbite.cleverkeys.PackImportFailure>())
    // showLanguagePackDialog/languagePackImportStatus delegated to settingsViewModel (survive rotation)
    internal var showLanguagePackDialog: Boolean
        get() = settingsViewModel.showLanguagePackDialog
        set(value) { settingsViewModel.showLanguagePackDialog = value }
    internal var languagePackImportStatus: tribixbite.cleverkeys.ui.settings.io.LanguagePackImportStatus?
        get() = settingsViewModel.languagePackImportStatus
        set(value) { settingsViewModel.languagePackImportStatus = value }

    // Cross-language contraction collision warning (2026-08-20). Populated by
    // `rescanContractionCollisions()` after any language selector changes, and shown only when an
    // IMPORTED pack contributed a collision — the bundled ones are already handled by the shipped
    // sidecars, so reporting them would be noise.
    internal var showCollisionWarningDialog by mutableStateOf(false)
    internal var collisionWarningKeyCount by mutableIntStateOf(0)
    internal var collisionWarningLanguages by mutableStateOf("")
    internal var collisionWarningExamples by mutableStateOf(listOf<Pair<String, String>>())

    // Privacy settings - all OFF by default (CleverKeys is fully offline)
    // MASTER on-device learning gate (Task A 2026-08-06): opt-out for ALL
    // typing-behavior learning (context LM, personalization, adaptation, swipe-ML)
    internal var onDeviceLearningEnabled by mutableStateOf(Defaults.ON_DEVICE_LEARNING_ENABLED)
    internal var privacyCollectSwipe by mutableStateOf(Defaults.PRIVACY_COLLECT_SWIPE)
    internal var privacyCollectPerformance by mutableStateOf(Defaults.PRIVACY_COLLECT_PERFORMANCE)

    // Short gesture settings
    internal var shortGesturesEnabled by mutableStateOf(Defaults.SHORT_GESTURES_ENABLED)
    internal var shortGestureMinDistance by mutableIntStateOf(Defaults.SHORT_GESTURE_MIN_DISTANCE)
    internal var shortGestureMaxDistance by mutableIntStateOf(Defaults.SHORT_GESTURE_MAX_DISTANCE)
    /** GH #147/#87 "short swipes only" — the STORED preference (Config folds in short gestures). */
    internal var shortSwipesOnly by mutableStateOf(Defaults.SHORT_SWIPES_ONLY)

    // Subkey popover (hold-then-select) settings
    internal var subkeyPopoverEnabled by mutableStateOf(Defaults.SUBKEY_POPOVER_ENABLED)
    internal var subkeyPopoverNeutralWidth by mutableIntStateOf(Defaults.SUBKEY_POPOVER_NEUTRAL_WIDTH)
    internal var subkeyPopoverNeutralHeight by mutableIntStateOf(Defaults.SUBKEY_POPOVER_NEUTRAL_HEIGHT)

    // Selection-delete mode settings (backspace swipe+hold)
    internal var selectionDeleteVerticalThreshold by mutableIntStateOf(Defaults.SELECTION_DELETE_VERTICAL_THRESHOLD)
    internal var selectionDeleteVerticalSpeed by mutableFloatStateOf(Defaults.SELECTION_DELETE_VERTICAL_SPEED)
    /** GH #190: "speed" (speed by distance) or "follow" (follow finger). */
    internal var selectionDeleteMode by mutableStateOf(Defaults.SELECTION_DELETE_MODE)
    /** GH #190: follow-finger travel per character, dp. */
    internal var selectionDeleteStepDp by mutableIntStateOf(Defaults.SELECTION_DELETE_STEP_DP)

    // Swipe debug advanced settings
    internal var swipeDebugDetailedLogging by mutableStateOf(Defaults.SWIPE_DEBUG_DETAILED_LOGGING)
    // Task B Tier 2: opt-in per-suggestion origin markers (pipeline transparency)
    internal var suggestionProvenanceMarkers by mutableStateOf(Defaults.SUGGESTION_PROVENANCE_MARKERS)

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
    internal var swipeTypingSectionExpanded: Boolean  // Collapsed by default, Activities is primary
        get() = settingsViewModel.swipeTypingSectionExpanded
        set(value) { settingsViewModel.swipeTypingSectionExpanded = value }
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
    /** Root-space Y (px) of the settings scroll viewport's top edge; see [contentYOf]. */
    internal var scrollViewportTop: Float = 0f
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

    /**
     * Setting ids (title resource names, see [GeneratedSearchEntry.settingId]) of the controls
     * living INSIDE the collapsible "Advanced Prediction Settings" sub-panel of the Input section
     * ([InputBehaviorSection]'s `wordPredictionAdvancedExpanded` block). Their search entries must
     * open that panel as well as the section. `SettingsSearchCoverageTest` pins this set to the
     * panel's contents; a miss degrades to "section opens, no scroll", it does not crash.
     */
    private val WORD_PREDICTION_ADVANCED_IDS = setOf(
        "input_context_aware_title",
        "input_next_word_title",
        "input_context_source_title",
        "input_personalized_learning_title",
        "input_personalization_weight_title",
        "input_learning_aggression_title",
        "input_context_boost_title",
        "input_frequency_scale_title",
    )

    /** English resources, for matching English titles under any UI language. */
    private val englishResources: android.content.res.Resources by lazy {
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(java.util.Locale.ENGLISH)
        createConfigurationContext(config).resources
    }

    /**
     * Visible (UI-language) control title -> the locale-independent setting ids of the index
     * entries with that title. Controls register their scroll positions under these ids
     * ([settingIdsFor]); the activity is recreated on a locale change, so this is rebuilt then.
     */
    internal val searchIdsByTitle: Map<String, List<String>> by lazy {
        SettingsSearchMatch.idsByTitle(
            GENERATED_SEARCH_ENTRIES.filter { it.titleRes != 0 }.map { getString(it.titleRes) to it.settingId }
        )
    }

    /** A hand-maintained entry whose title is the string resource [titleRes]. */
    private fun searchEntry(
        @androidx.annotation.StringRes titleRes: Int,
        keywords: List<String>,
        sectionKey: String,
        activityClass: Class<*>? = null,
        expandSection: () -> Unit = {},
        gatedBy: String? = null,
        settingId: String = "",
    ) = SearchableSetting(
        title = bareTitle(getString(titleRes)),
        englishTitle = bareTitle(englishResources.getString(titleRes)),
        keywords = keywords,
        sectionName = sectionDisplayName(sectionKey),
        activityClass = activityClass,
        expandSection = expandSection,
        gatedBy = gatedBy,
        settingId = settingId,
    )

    internal val searchableSettings: List<SearchableSetting> by lazy {
        listOf(
            // Auto-derived control entries — generated from the actual
            // SettingsSwitch/SettingsSlider/SettingsDropdown titles by
            // scripts/generate_settings_search_index.py (never hand-maintained). Shown and
            // matched in the UI language; the English title and keywords match as well.
            *GENERATED_SEARCH_ENTRIES.map { e ->
                SearchableSetting(
                    title = if (e.titleRes != 0) getString(e.titleRes) else e.title,
                    englishTitle = e.title,
                    keywords = e.keywords,
                    sectionName = sectionDisplayName(e.sectionKey),
                    // Controls inside the collapsed "Advanced Prediction Settings" sub-panel
                    // need it OPENED too, or the target is never composed, never registers a
                    // scroll position, and the search result silently lands nowhere (audit
                    // 2026-08-26 — bit all eight advanced-panel entries, found via the
                    // next-word toggle).
                    expandSection = if (e.settingId in WORD_PREDICTION_ADVANCED_IDS) {
                        { expanderFor(e.sectionKey)(); wordPredictionAdvancedExpanded = true }
                    } else {
                        expanderFor(e.sectionKey)
                    },
                    settingId = e.settingId,
                    gateIds = e.gateIds,
                )
            }.toTypedArray(),
            // ===== Hand-maintained NON-control entries (activity navigation, FAQ) =====
            searchEntry(R.string.advanced_custom_terminal_title, listOf("terminal", "packages", "whitelist", "allowlist", "ssh", "remote", "desktop", "rustdesk", "vnc"), "advanced", expandSection = { advancedSectionExpanded = true }, settingId = "custom_terminal_packages"),
            // Titles are the screens' own string resources, so they read exactly like the
            // screen they open, in the UI language.
            searchEntry(R.string.activities_theme_title, listOf("color", "dark mode", "light", "appearance", "theme"), "activities", ThemeSettingsActivity::class.java),
            searchEntry(R.string.activities_dictionary_title, listOf("words", "custom", "disabled", "vocabulary"), "activities", DictionaryManagerActivity::class.java),
            searchEntry(R.string.activities_layout_title, listOf("keyboard layout", "qwerty", "azerty"), "activities", LayoutManagerActivity::class.java),
            // GH #186/#61: per-layout language binding lives on each Layout Manager row.
            searchEntry(R.string.layout_language_title, listOf("language", "layout language", "per layout", "bind", "switch language", "autocorrect"), "activities", LayoutManagerActivity::class.java),
            searchEntry(R.string.calibration_customize_title, listOf("short swipe", "gesture", "actions", "commands", "per-key"), "activities", ShortSwipeCustomizationActivity::class.java, gatedBy = "short_gestures", settingId = "per_key_customization"),
            searchEntry(R.string.activities_calibration_title, listOf("calibrate", "practice", "tutorial", "test"), "gestureTuning", ShortSwipeCalibrationActivity::class.java, gatedBy = "short_gestures", settingId = "short_swipe_calibration"),
            searchEntry(R.string.activities_extra_keys_title, listOf("toolbar", "arrows", "numbers"), "activities", ExtraKeysConfigActivity::class.java),
            searchEntry(R.string.settings_section_backup_restore, listOf("backup", "export", "import", "restore", "zip", "preview", "deselect"), "backupRestore", expandSection = { backupRestoreSectionExpanded = true }, settingId = "backup_restore"),
            searchEntry(R.string.activities_whats_new_title, listOf("changelog", "release", "update", "features", "version"), "activities", settingId = "whats_new"),
            searchEntry(R.string.swipe_full_geometric_settings, listOf("geometric", "shape", "shark", "swipe engine", "tolerance"), "swipeTyping", GeometricSettingsActivity::class.java),
            // L4: ungated, matching the "Geometric Settings" twin — a search-driven visit
            // to a tuning screen is harmless with swipe typing off, and the asymmetric
            // gate made "ctc" unfindable exactly when the user is setting swipe up.
            searchEntry(R.string.swipe_full_ctc_settings, listOf("ctc", "swipe engine", "beam", "trie"), "swipeTyping", CtcSettingsActivity::class.java),
            searchEntry(R.string.ctc_settings_threads_title, listOf("threads", "cpu", "xnnpack", "performance", "onnx"), "swipeTyping", CtcSettingsActivity::class.java, gatedBy = "swipe_typing", settingId = "onnx_threads"),
            searchEntry(R.string.gif_import_pack, listOf("gif", "import", "pack", "zip", "download"), "gif", expandSection = { gifSectionExpanded = true }, gatedBy = "gif_enabled", settingId = "gif_import"),
            searchEntry(R.string.settings_section_help, listOf("help", "faq", "documentation", "wiki", "questions"), "help", expandSection = { helpSectionExpanded = true }, settingId = "help_faq"),
            searchEntry(R.string.settings_faq_numbers_q, listOf("numbers", "symbols", "subkey", "short swipe"), "help", expandSection = { helpSectionExpanded = true }, settingId = "faq_numbers"),
            searchEntry(R.string.settings_faq_cursor_q, listOf("cursor", "navigation", "spacebar", "move"), "help", expandSection = { helpSectionExpanded = true }, settingId = "faq_cursor"),
            searchEntry(R.string.settings_faq_select_delete_q, listOf("selection", "delete", "text", "backspace"), "help", expandSection = { helpSectionExpanded = true }, settingId = "faq_selection"),
            searchEntry(R.string.settings_faq_switch_language_q, listOf("language", "switch", "toggle", "multilingual"), "help", expandSection = { helpSectionExpanded = true }, settingId = "faq_language"),
            searchEntry(R.string.settings_faq_emoji_q, listOf("emoji", "emoticon", "symbols", "fn"), "help", expandSection = { helpSectionExpanded = true }, settingId = "faq_emoji"),
            searchEntry(R.string.settings_faq_clipboard_q, listOf("clipboard", "paste", "history", "pinned", "fn"), "help", expandSection = { helpSectionExpanded = true }, settingId = "faq_clipboard"),
            searchEntry(R.string.settings_faq_swipe_typing_q, listOf("swipe", "typing", "glide", "gesture"), "help", expandSection = { helpSectionExpanded = true }, settingId = "faq_swipe"),
            searchEntry(R.string.settings_faq_other_languages_q, listOf("language", "languages", "pack", "geometric", "ctc"), "help", expandSection = { helpSectionExpanded = true }, settingId = "faq_other_languages"),
            // The privacy information lives in the Privacy & Data section (there is no privacy
            // FAQ item), so this entry opens that section.
            searchEntry(R.string.settings_section_privacy, listOf("privacy", "offline", "data", "secure"), "privacy", expandSection = { privacySectionExpanded = true }, settingId = "faq_privacy")
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

            // Settings can be the first component in the process, and the swipe-engine fallback
            // card asks CtcLanguageSupport whether CTC serves the primary language — which for an
            // imported pack is a per-device answer only this resolver can give. Without it the
            // card would claim CTC does not serve a language the IME is happily serving.
            // Pinned by CoreImeHygieneDriftTest.
            tribixbite.cleverkeys.swipe.CtcInstalledPacks.bind(this)
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
                            onApply = { excluded, ssMode, themeMode ->
                                backupRestoreViewModel.settingsPreviewPlan = null
                                applyPlannedSettings(plan, excluded, ssMode, themeMode)
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
                                    Text(stringResource(R.string.common_ok))
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
            Toast.makeText(
                this,
                getString(
                    R.string.settings_ui_load_failed,
                    e.message ?: getString(R.string.common_unknown_error)
                ),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Register for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this)
        // Layout Manager is a separate activity whose edits land while this one is paused.
        refreshBoundLayoutLanguages()
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
