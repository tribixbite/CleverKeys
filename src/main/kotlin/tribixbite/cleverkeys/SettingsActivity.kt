package tribixbite.cleverkeys

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Properties
import tribixbite.cleverkeys.theme.KeyboardTheme
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.langpack.ImportResult
import tribixbite.cleverkeys.langpack.LanguagePackManifest
import androidx.localbroadcastmanager.content.LocalBroadcastManager

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
        private const val TAG = "SettingsActivity"
    }

    // Configuration state
    private lateinit var config: Config
    private lateinit var prefs: SharedPreferences

    // SAF file pickers for backup/restore
    private val configExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performConfigExport(it) }
    }

    private val configImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performConfigImport(it) }
    }

    private val dictionaryExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performDictionaryExport(it) }
    }

    private val dictionaryImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performDictionaryImport(it) }
    }

    private val clipboardExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performClipboardExport(it) }
    }

    private val clipboardImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performClipboardImport(it) }
    }

    // SAF file pickers for swipe ML data export
    private val swipeDataJsonExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performSwipeDataJsonExport(it) }
    }

    private val swipeDataNdjsonExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri: Uri? ->
        uri?.let { performSwipeDataNdjsonExport(it) }
    }

    // Performance metrics export launcher
    private val perfStatsExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { performPerfStatsExport(it) }
    }

    // Language pack import launcher
    private val languagePackImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { performLanguagePackImport(it) }
    }

    // Settings state for reactive UI
    private var beamWidth by mutableStateOf(6)
    private var maxLength by mutableStateOf(20)
    private var confidenceThreshold by mutableStateOf(0.01f)
    private var currentThemeName by mutableStateOf("cleverkeysdark")
    private var keyboardHeight by mutableStateOf(28)
    private var keyboardHeightLandscape by mutableStateOf(50)
    private var vibrationEnabled by mutableStateOf(false)
    private var debugEnabled by mutableStateOf(false)
    private var clipboardHistoryEnabled by mutableStateOf(true)
    private var clipboardHistoryLimit by mutableStateOf(6)
    private var clipboardPaneHeightPercent by mutableStateOf(30)
    private var clipboardMaxItemSizeKb by mutableStateOf(500)
    private var clipboardLimitType by mutableStateOf("count") // "count" or "size"
    private var clipboardSizeLimitMb by mutableStateOf(10)
    private var clipboardExcludePasswordManagers by mutableStateOf(true)  // Privacy: skip password managers
    private var autoCapitalizationEnabled by mutableStateOf(true)

    // Phase 1: Expose existing Config.kt settings
    private var swipeTypingEnabled by mutableStateOf(true)  // Master switch for swipe typing (default ON for CleverKeys)
    private var swipeOnPasswordFields by mutableStateOf(false)  // #39: Allow swipe on password fields
    private var wordPredictionEnabled by mutableStateOf(true)  // Match Config.kt default
    private var autoSpaceAfterSuggestion by mutableStateOf(true)  // #82: Add trailing space after selecting suggestion
    private var suggestionBarOpacity by mutableStateOf(90)
    private var autoCorrectEnabled by mutableStateOf(true)
    private var termuxModeEnabled by mutableStateOf(false)
    private var vibrationDuration by mutableStateOf(20)
    // Per-event haptic feedback toggles
    private var hapticKeyPress by mutableStateOf(Defaults.HAPTIC_KEY_PRESS)
    private var hapticPredictionTap by mutableStateOf(Defaults.HAPTIC_PREDICTION_TAP)
    private var hapticTrackpointActivate by mutableStateOf(Defaults.HAPTIC_TRACKPOINT_ACTIVATE)
    private var hapticLongPress by mutableStateOf(Defaults.HAPTIC_LONG_PRESS)
    private var hapticSwipeComplete by mutableStateOf(Defaults.HAPTIC_SWIPE_COMPLETE)
    private var swipeDebugEnabled by mutableStateOf(false)

    // Adaptive layout settings (percentages of screen dimensions)
    private var marginBottomPortrait by mutableStateOf(Defaults.MARGIN_BOTTOM_PORTRAIT)
    private var marginBottomLandscape by mutableStateOf(Defaults.MARGIN_BOTTOM_LANDSCAPE)
    private var marginLeftPortrait by mutableStateOf(Defaults.MARGIN_LEFT_PORTRAIT)
    private var marginLeftLandscape by mutableStateOf(Defaults.MARGIN_LEFT_LANDSCAPE)
    private var marginRightPortrait by mutableStateOf(Defaults.MARGIN_RIGHT_PORTRAIT)
    private var marginRightLandscape by mutableStateOf(Defaults.MARGIN_RIGHT_LANDSCAPE)

    // Gesture sensitivity settings
    private var swipeDistance by mutableStateOf(23)
    private var circleSensitivity by mutableStateOf(2)
    private var sliderSensitivity by mutableStateOf(30) // Phase 5: Space bar slider (0-100%)

    // Long press settings
    private var longPressTimeout by mutableStateOf(600)
    private var longPressInterval by mutableStateOf(65)
    private var keyRepeatEnabled by mutableStateOf(true)

    // Visual customization settings
    private var labelBrightness by mutableStateOf(100)
    private var keyboardOpacity by mutableStateOf(100)
    private var keyOpacity by mutableStateOf(100)
    private var keyActivatedOpacity by mutableStateOf(100)

    // Spacing and sizing settings
    private var characterSize by mutableStateOf(115)
    private var keyVerticalMargin by mutableStateOf(150)
    private var keyHorizontalMargin by mutableStateOf(200)

    // Border customization settings
    private var borderConfigEnabled by mutableStateOf(false)
    private var customBorderRadius by mutableStateOf(0)
    private var customBorderLineWidth by mutableStateOf(0)

    // Behavior settings
    private var doubleTapLockShift by mutableStateOf(false)
    private var switchInputImmediate by mutableStateOf(false)
    private var smartPunctuationEnabled by mutableStateOf(true) // Attach punctuation to end of last word
    private var vibrateCustomEnabled by mutableStateOf(false) // Custom vibration duration
    private var numberEntryLayout by mutableStateOf("pin") // "pin", "phone", "calculator"

    // Gesture tuning settings
    private var tapDurationThreshold by mutableStateOf(150) // ms
    private var doubleSpaceToPeriod by mutableStateOf(true) // Enable double-space-to-period
    private var doubleSpaceThreshold by mutableStateOf(500) // ms
    private var swipeMinDistance by mutableStateOf(72f) // pixels
    private var swipeMinKeyDistance by mutableStateOf(38f) // pixels
    private var swipeMinDwellTime by mutableStateOf(10) // ms
    private var swipeNoiseThreshold by mutableStateOf(2.0f) // pixels
    private var swipeHighVelocityThreshold by mutableStateOf(1000f) // px/sec
    private var fingerOcclusionOffset by mutableStateOf(12.5f) // % of row height
    private var sliderSpeedSmoothing by mutableStateOf(0.7f) // 0.0-1.0
    private var sliderSpeedMax by mutableStateOf(4.0f) // multiplier

    // Number row and numpad settings
    private var numberRowMode by mutableStateOf("no_number_row") // "no_number_row", "no_symbols", "symbols"
    private var showNumpadMode by mutableStateOf("never") // "never", "landscape", "always"
    private var numpadLayout by mutableStateOf("default") // "default", "low_first"
    private var pinEntryEnabled by mutableStateOf(false)

    // Accessibility settings (Bug #373, #368, #377)
    private var stickyKeysEnabled by mutableStateOf(false)
    private var stickyKeysTimeout by mutableStateOf(5000) // milliseconds
    private var voiceGuidanceEnabled by mutableStateOf(false)

    // Swipe Corrections settings (migrated from XML)
    private var swipeBeamAutocorrectEnabled by mutableStateOf(true)
    private var swipeFinalAutocorrectEnabled by mutableStateOf(true)
    private var swipeCorrectionPreset by mutableStateOf("balanced")
    private var swipeFuzzyMatchMode by mutableStateOf("edit_distance")
    private var autocorrectMaxLengthDiff by mutableStateOf(2)
    private var autocorrectPrefixLength by mutableStateOf(1)
    private var autocorrectMaxBeamCandidates by mutableStateOf(3)
    private var swipePredictionSource by mutableStateOf(80)
    private var swipeCommonWordsBoost by mutableStateOf(1.0f)
    private var swipeTop5000Boost by mutableStateOf(1.0f)
    private var swipeRareWordsPenalty by mutableStateOf(1.0f)

    // Swipe trail appearance settings
    private var swipeTrailEnabled by mutableStateOf(true)
    private var swipeTrailEffect by mutableStateOf("glow")
    private var swipeTrailColor by mutableStateOf(0xFF9B59B6.toInt()) // Jewel purple
    private var swipeTrailWidth by mutableStateOf(8.0f)
    private var swipeTrailGlowRadius by mutableStateOf(12.0f)

    // Word Prediction Advanced settings
    private var contextAwarePredictionsEnabled by mutableStateOf(true)
    private var personalizedLearningEnabled by mutableStateOf(true)
    private var learningAggression by mutableStateOf("BALANCED")
    private var predictionContextBoost by mutableStateOf(2.0f)
    private var predictionFrequencyScale by mutableStateOf(1000f)

    // Auto-correction advanced settings
    private var autocorrectMinWordLength by mutableStateOf(3)
    private var autocorrectCharMatchThreshold by mutableStateOf(0.67f)
    private var autocorrectMinFrequency by mutableStateOf(500)

    // Neural beam search advanced settings
    private var neuralBatchBeams by mutableStateOf(false)
    private var neuralGreedySearch by mutableStateOf(false)
    private var neuralBeamAlpha by mutableStateOf(1.55f)
    private var neuralBeamPruneConfidence by mutableStateOf(0.33f)
    private var neuralBeamScoreGap by mutableStateOf(50.0f)

    // Neural model config settings
    private var neuralResamplingMode by mutableStateOf("discard")
    private var neuralUserMaxSeqLength by mutableStateOf(0)

    // Multi-language settings
    private var multiLangEnabled by mutableStateOf(false)
    private var primaryLanguage by mutableStateOf("en")
    private var secondaryLanguage by mutableStateOf("none") // "none", "es", "fr", etc.
    private var autoDetectLanguage by mutableStateOf(true)
    private var languageDetectionSensitivity by mutableStateOf(0.6f)
    private var secondaryPredictionWeight by mutableStateOf(0.9f) // v1.1.94: Secondary dictionary weight
    private var prefixBoostMultiplier by mutableStateOf(Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER)
    private var prefixBoostMax by mutableStateOf(Defaults.NEURAL_PREFIX_BOOST_MAX)
    private var maxCumulativeBoost by mutableStateOf(Defaults.NEURAL_MAX_CUMULATIVE_BOOST)
    private var strictStartChar by mutableStateOf(Defaults.NEURAL_STRICT_START_CHAR)
    private var primaryLanguageAlt by mutableStateOf("es") // v1.2.0: Alternate primary for quick toggle
    private var secondaryLanguageAlt by mutableStateOf("none") // v1.2.0: Alternate secondary for quick toggle
    private var availableSecondaryLanguages by mutableStateOf(listOf<String>()) // V2 dictionaries
    private var installedLanguagePacks by mutableStateOf(listOf<LanguagePackManifest>())
    private var showLanguagePackDialog by mutableStateOf(false)
    private var languagePackImportStatus by mutableStateOf<String?>(null)

    // Privacy settings - all OFF by default (CleverKeys is fully offline)
    private var privacyCollectSwipe by mutableStateOf(false)
    private var privacyCollectPerformance by mutableStateOf(false)
    private var privacyCollectErrors by mutableStateOf(false)

    // Short gesture settings
    private var shortGesturesEnabled by mutableStateOf(true)
    private var shortGestureMinDistance by mutableStateOf(37)
    private var shortGestureMaxDistance by mutableStateOf(141)

    // Selection-delete mode settings (backspace swipe+hold)
    private var selectionDeleteVerticalThreshold by mutableStateOf(40)
    private var selectionDeleteVerticalSpeed by mutableStateOf(0.4f)

    // Swipe debug advanced settings
    private var swipeDebugDetailedLogging by mutableStateOf(false)
    private var swipeDebugShowRawOutput by mutableStateOf(true)
    private var swipeShowRawBeamPredictions by mutableStateOf(false)

    // Section expanded states
    private var wordPredictionAdvancedExpanded by mutableStateOf(false)
    private var neuralAdvancedExpanded by mutableStateOf(false)
    private var activitiesSectionExpanded by mutableStateOf(true)  // Activities at top, default expanded
    private var multiLangSectionExpanded by mutableStateOf(false)
    private var privacySectionExpanded by mutableStateOf(false)
    private var neuralSectionExpanded by mutableStateOf(false)  // Collapsed by default, Activities is primary
    private var appearanceSectionExpanded by mutableStateOf(false)  // No longer default expanded since Theme is in Activities
    private var swipeTrailSectionExpanded by mutableStateOf(false)
    private var inputSectionExpanded by mutableStateOf(false)
    private var swipeCorrectionsSectionExpanded by mutableStateOf(false)
    private var gestureTuningSectionExpanded by mutableStateOf(false)
    private var accessibilitySectionExpanded by mutableStateOf(false)
    // v1.2.6: dictionarySectionExpanded removed - Dictionary Manager moved to Activities
    private var clipboardSectionExpanded by mutableStateOf(false)
    private var backupRestoreSectionExpanded by mutableStateOf(false)
    private var advancedSectionExpanded by mutableStateOf(false)
    private var infoSectionExpanded by mutableStateOf(false)

    // Test keyboard field (#1134: test input without leaving settings)
    private var testKeyboardExpanded by mutableStateOf(false)
    private var testKeyboardText by mutableStateOf("")

    // Settings search
    private var settingsSearchQuery by mutableStateOf("")
    private var showSearchResults by mutableStateOf(false)
    private var highlightedSettingId by mutableStateOf<String?>(null)  // For pulse animation

    // Position tracking for scroll-to-top functionality
    private val settingPositions = mutableMapOf<String, Int>()  // settingId -> Y position in scroll content
    private var mainScrollState: androidx.compose.foundation.ScrollState? = null

    /** Record the Y position of a setting for scroll targeting */
    fun recordSettingPosition(settingId: String, yPosition: Int) {
        settingPositions[settingId] = yPosition
    }

    /** Scroll to a setting by ID, positioning it at the top of the screen */
    private fun scrollToSetting(settingId: String) {
        val position = settingPositions[settingId] ?: return
        val scrollState = mainScrollState ?: return
        lifecycleScope.launch {
            // Scroll to position with some padding from top
            scrollState.animateScrollTo(maxOf(0, position - 16))
        }
    }

    /** Nested scroll connection to prevent search results from scrolling parent */
    private val searchResultsNestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset = available  // Consume all remaining scroll

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            available  // Consume all remaining velocity
    }

    /** Collapse all sections */
    private fun collapseAllSections() {
        activitiesSectionExpanded = false
        multiLangSectionExpanded = false
        privacySectionExpanded = false
        neuralSectionExpanded = false
        appearanceSectionExpanded = false
        swipeTrailSectionExpanded = false
        inputSectionExpanded = false
        swipeCorrectionsSectionExpanded = false
        gestureTuningSectionExpanded = false
        accessibilitySectionExpanded = false
        // v1.2.6: dictionarySectionExpanded removed
        clipboardSectionExpanded = false
        backupRestoreSectionExpanded = false
        advancedSectionExpanded = false
        infoSectionExpanded = false
    }

    /**
     * Searchable settings index. Each entry maps a setting name to its action.
     * activityClass: if not null, clicking navigates to that activity
     * expandSection: if activityClass is null, clicking expands this section
     * gatedBy: if set, this setting requires another toggle to be enabled first
     * settingId: unique ID for highlighting
     */
    private data class SearchableSetting(
        val title: String,
        val keywords: List<String>,
        val sectionName: String,
        val activityClass: Class<*>? = null,
        val expandSection: () -> Unit = {},
        val gatedBy: String? = null,  // e.g., "swipe_typing" means needs swipe typing enabled
        val settingId: String = ""    // For highlighting
    )

    private val searchableSettings: List<SearchableSetting> by lazy {
        listOf(
            // ==================== ACTIVITIES ====================
            SearchableSetting("Theme Manager", listOf("color", "dark mode", "light", "appearance", "theme"), "Activities", ThemeSettingsActivity::class.java),
            SearchableSetting("Dictionary Manager", listOf("words", "custom", "disabled", "vocabulary"), "Activities", DictionaryManagerActivity::class.java),
            SearchableSetting("Layout Manager", listOf("keyboard layout", "qwerty", "azerty"), "Activities", LayoutManagerActivity::class.java),
            SearchableSetting("Keyboard Calibration", listOf("height", "size", "foldable"), "Activities", SwipeCalibrationActivity::class.java),
            SearchableSetting("Per-Key Customization", listOf("short swipe", "gesture", "actions", "commands"), "Activities", ShortSwipeCustomizationActivity::class.java, gatedBy = "short_gestures", settingId = "per_key_customization"),
            SearchableSetting("Short Swipe Calibration", listOf("calibrate", "practice", "tutorial", "test"), "Gesture Tuning", ShortSwipeCalibrationActivity::class.java, gatedBy = "short_gestures", settingId = "short_swipe_calibration"),
            SearchableSetting("Extra Keys", listOf("toolbar", "arrows", "numbers"), "Activities", ExtraKeysConfigActivity::class.java),
            SearchableSetting("Backup & Restore", listOf("backup", "export", "import", "restore"), "Activities", BackupRestoreActivity::class.java),
            SearchableSetting("What's New", listOf("changelog", "release", "update", "features", "version"), "Activities", settingId = "whats_new"),

            // ==================== NEURAL PREDICTION ====================
            SearchableSetting("Neural Settings", listOf("neural", "ai", "prediction", "model", "onnx"), "Neural Prediction", NeuralSettingsActivity::class.java),
            SearchableSetting("Swipe Typing", listOf("gesture", "neural", "glide", "swipe"), "Neural Prediction", expandSection = { neuralSectionExpanded = true }, settingId = "swipe_typing"),
            SearchableSetting("Swipe on Password Fields", listOf("password", "swipe", "security"), "Neural Prediction", expandSection = { neuralSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "swipe_password"),
            SearchableSetting("Beam Width", listOf("accuracy", "prediction", "candidates", "beam"), "Neural Prediction", expandSection = { neuralSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "beam_width"),
            SearchableSetting("Confidence Threshold", listOf("accuracy", "filter", "confidence"), "Neural Prediction", expandSection = { neuralSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "confidence_threshold"),
            SearchableSetting("Max Sequence Length", listOf("sequence", "length", "maximum", "resampling"), "Neural Prediction", expandSection = { neuralSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "max_seq_length"),

            // ==================== WORD PREDICTION & AUTOCORRECT ====================
            SearchableSetting("Word Predictions", listOf("prediction", "suggestions", "completion", "autocomplete"), "Word Prediction", expandSection = { inputSectionExpanded = true }, settingId = "word_prediction"),
            SearchableSetting("Auto-Space After Suggestion", listOf("space", "auto", "trailing", "automatic"), "Word Prediction", expandSection = { inputSectionExpanded = true }, settingId = "auto_space"),
            SearchableSetting("Suggestion Bar Opacity", listOf("opacity", "transparency", "prediction bar"), "Word Prediction", expandSection = { inputSectionExpanded = true }, settingId = "suggestion_opacity"),
            SearchableSetting("Show Exact Typed Word", listOf("exact", "typed", "add to dictionary"), "Word Prediction", expandSection = { inputSectionExpanded = true }, settingId = "show_exact_typed"),
            SearchableSetting("Context-Aware Predictions", listOf("context", "aware", "intelligent"), "Word Prediction", expandSection = { inputSectionExpanded = true }, settingId = "context_aware"),
            SearchableSetting("Personalized Learning", listOf("learning", "personalized", "adapt"), "Word Prediction", expandSection = { inputSectionExpanded = true }, settingId = "personalized_learning"),
            SearchableSetting("Autocorrect", listOf("autocorrect", "fix", "error", "typo"), "Word Prediction", expandSection = { swipeCorrectionsSectionExpanded = true }, settingId = "autocorrect"),
            SearchableSetting("Autocorrect Min Word Length", listOf("minimum", "length", "autocorrect"), "Word Prediction", expandSection = { swipeCorrectionsSectionExpanded = true }, settingId = "autocorrect_min_length"),
            SearchableSetting("Beam Autocorrect", listOf("beam", "autocorrect", "swipe"), "Word Prediction", expandSection = { swipeCorrectionsSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "beam_autocorrect"),
            SearchableSetting("Final Autocorrect", listOf("final", "autocorrect", "completion"), "Word Prediction", expandSection = { swipeCorrectionsSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "final_autocorrect"),
            SearchableSetting("Capitalize I Words", listOf("capitalize", "i'm", "i'll", "uppercase"), "Word Prediction", expandSection = { inputSectionExpanded = true }, settingId = "capitalize_i"),

            // ==================== APPEARANCE ====================
            SearchableSetting("Key Height", listOf("size", "keyboard", "tall", "short", "height"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "key_height"),
            SearchableSetting("Keyboard Height Portrait", listOf("height", "portrait", "vertical"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "height_portrait"),
            SearchableSetting("Keyboard Height Landscape", listOf("height", "landscape", "horizontal"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "height_landscape"),
            SearchableSetting("Key Borders", listOf("outline", "visible", "border"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "key_borders"),
            SearchableSetting("Border Radius", listOf("corner", "radius", "rounded"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "border_radius"),
            SearchableSetting("Border Width", listOf("border", "width", "line"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "border_width"),
            SearchableSetting("Horizontal Margin", listOf("padding", "edge", "margin", "left", "right"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "horizontal_margin"),
            SearchableSetting("Bottom Margin", listOf("margin", "bottom", "padding"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "bottom_margin"),
            SearchableSetting("Keyboard Opacity", listOf("opacity", "transparent", "background"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "keyboard_opacity"),
            SearchableSetting("Key Opacity", listOf("opacity", "key", "transparent"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "key_opacity"),
            SearchableSetting("Label Brightness", listOf("brightness", "text", "label", "visibility"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "label_brightness"),
            SearchableSetting("Character Size", listOf("size", "font", "text", "label"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "character_size"),
            SearchableSetting("Vertical Key Spacing", listOf("vertical", "spacing", "margin", "row"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "vertical_spacing"),
            SearchableSetting("Number Row", listOf("123", "digits", "top row", "numbers"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "number_row"),
            SearchableSetting("Show Numpad", listOf("numpad", "numbers", "digits", "calculator"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "show_numpad"),
            SearchableSetting("Numpad Layout", listOf("numpad", "order", "123", "789"), "Appearance", expandSection = { appearanceSectionExpanded = true }, settingId = "numpad_layout"),

            // ==================== SWIPE TRAIL ====================
            SearchableSetting("Swipe Trail", listOf("gesture", "path", "visual", "effect", "trail"), "Swipe Trail", expandSection = { swipeTrailSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "swipe_trail"),
            SearchableSetting("Trail Effect", listOf("effect", "sparkle", "glow", "rainbow", "fade"), "Swipe Trail", expandSection = { swipeTrailSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "trail_effect"),
            SearchableSetting("Trail Color", listOf("purple", "rainbow", "glow", "color"), "Swipe Trail", expandSection = { swipeTrailSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "trail_color"),
            SearchableSetting("Trail Width", listOf("width", "thickness", "stroke"), "Swipe Trail", expandSection = { swipeTrailSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "trail_width"),
            SearchableSetting("Trail Glow Radius", listOf("glow", "radius", "effect"), "Swipe Trail", expandSection = { swipeTrailSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "trail_glow"),

            // ==================== INPUT BEHAVIOR ====================
            SearchableSetting("Autocapitalization", listOf("uppercase", "sentence", "shift", "capital"), "Input", expandSection = { inputSectionExpanded = true }, settingId = "autocap"),
            SearchableSetting("Long Press Timeout", listOf("hold", "delay", "duration", "timeout"), "Input", expandSection = { inputSectionExpanded = true }, settingId = "long_press"),
            SearchableSetting("Long Press Interval", listOf("interval", "repeat", "speed"), "Input", expandSection = { inputSectionExpanded = true }, settingId = "long_press_interval"),
            SearchableSetting("Key Repeat", listOf("hold", "backspace", "delete", "repeat"), "Input", expandSection = { inputSectionExpanded = true }, settingId = "key_repeat"),
            SearchableSetting("Double Tap Shift Lock", listOf("caps lock", "shift", "double tap"), "Input", expandSection = { inputSectionExpanded = true }, settingId = "double_tap_shift"),
            SearchableSetting("Smart Punctuation", listOf("punctuation", "smart", "space"), "Input", expandSection = { inputSectionExpanded = true }, settingId = "smart_punctuation"),
            SearchableSetting("Immediate Keyboard Switch", listOf("switch", "keyboard", "immediate"), "Input", expandSection = { inputSectionExpanded = true }, settingId = "immediate_switch"),

            // ==================== GESTURE TUNING ====================
            SearchableSetting("Short Gestures", listOf("short swipe", "quick", "action", "gesture"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "short_gestures"),
            SearchableSetting("Short Gesture Min Distance", listOf("minimum", "short", "distance"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, gatedBy = "short_gestures", settingId = "short_gesture_min"),
            SearchableSetting("Short Gesture Max Distance", listOf("maximum", "short", "distance"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, gatedBy = "short_gestures", settingId = "short_gesture_max"),
            SearchableSetting("Double-Space to Period", listOf("punctuation", "auto", "shortcut", "period"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "double_space"),
            SearchableSetting("Double-Space Timing", listOf("timing", "double space", "threshold"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "double_space_timing"),
            SearchableSetting("Finger Occlusion", listOf("offset", "touch", "compensation", "finger"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "finger_occlusion"),
            SearchableSetting("Tap Duration", listOf("timing", "sensitivity", "tap"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "tap_duration"),
            SearchableSetting("Sensitivity Preset", listOf("swipe", "sensitivity", "low", "medium", "high", "preset", "quick"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "sensitivity_preset"),
            SearchableSetting("Swipe Distance", listOf("sensitivity", "minimum", "recognition", "swipe"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "swipe_distance"),
            SearchableSetting("Min Swipe Distance", listOf("minimum", "swipe", "distance", "pixels"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "min_swipe_distance"),
            SearchableSetting("Min Key Distance", listOf("minimum", "key", "distance"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "min_key_distance"),
            SearchableSetting("Min Dwell Time", listOf("dwell", "time", "minimum"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "min_dwell_time"),
            SearchableSetting("Noise Threshold", listOf("noise", "filter", "threshold"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "noise_threshold"),
            SearchableSetting("High Velocity Threshold", listOf("velocity", "fast", "speed"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "velocity_threshold"),
            SearchableSetting("Circle Sensitivity", listOf("circle", "gesture", "sensitivity"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "circle_sensitivity"),
            SearchableSetting("Slider Sensitivity", listOf("slider", "cursor", "sensitivity", "spacebar"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "slider_sensitivity"),
            SearchableSetting("Selection-Delete Threshold", listOf("selection", "delete", "vertical", "threshold"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "sel_delete_threshold"),
            SearchableSetting("Selection-Delete Speed", listOf("selection", "delete", "speed"), "Gesture Tuning", expandSection = { gestureTuningSectionExpanded = true }, settingId = "sel_delete_speed"),

            // ==================== ACCESSIBILITY & HAPTICS ====================
            SearchableSetting("Vibration", listOf("haptic", "feedback", "tactile", "vibrate"), "Accessibility", expandSection = { accessibilitySectionExpanded = true }, settingId = "vibration"),
            SearchableSetting("Vibration Duration", listOf("duration", "vibration", "intensity"), "Accessibility", expandSection = { accessibilitySectionExpanded = true }, settingId = "vibration_duration"),
            SearchableSetting("Haptic Key Press", listOf("haptic", "keypress", "vibrate"), "Accessibility", expandSection = { accessibilitySectionExpanded = true }, settingId = "haptic_keypress"),
            SearchableSetting("Haptic Prediction Tap", listOf("haptic", "prediction", "tap"), "Accessibility", expandSection = { accessibilitySectionExpanded = true }, settingId = "haptic_prediction"),
            SearchableSetting("Haptic Trackpoint", listOf("haptic", "trackpoint", "navigation"), "Accessibility", expandSection = { accessibilitySectionExpanded = true }, settingId = "haptic_trackpoint"),
            SearchableSetting("Haptic Long Press", listOf("haptic", "long press", "vibrate"), "Accessibility", expandSection = { accessibilitySectionExpanded = true }, settingId = "haptic_long_press"),
            SearchableSetting("Haptic Swipe Complete", listOf("haptic", "swipe", "complete"), "Accessibility", expandSection = { accessibilitySectionExpanded = true }, gatedBy = "swipe_typing", settingId = "haptic_swipe"),
            SearchableSetting("Sound on Keypress", listOf("audio", "click", "noise", "sound"), "Accessibility", expandSection = { accessibilitySectionExpanded = true }, settingId = "sound"),

            // ==================== CLIPBOARD ====================
            SearchableSetting("Clipboard History", listOf("copy", "paste", "buffer", "clipboard"), "Clipboard", expandSection = { clipboardSectionExpanded = true }, settingId = "clipboard"),
            SearchableSetting("Clipboard History Limit", listOf("history", "limit", "items", "count"), "Clipboard", expandSection = { clipboardSectionExpanded = true }, settingId = "clipboard_limit"),
            SearchableSetting("Clipboard Size Limit", listOf("size", "limit", "megabytes"), "Clipboard", expandSection = { clipboardSectionExpanded = true }, settingId = "clipboard_size"),
            SearchableSetting("Clipboard Max Item Size", listOf("item", "size", "maximum"), "Clipboard", expandSection = { clipboardSectionExpanded = true }, settingId = "clipboard_item_size"),
            SearchableSetting("Clipboard Pane Height", listOf("pane", "height", "size"), "Clipboard", expandSection = { clipboardSectionExpanded = true }, settingId = "clipboard_height"),
            SearchableSetting("Exclude Password Managers", listOf("password", "exclude", "security"), "Clipboard", expandSection = { clipboardSectionExpanded = true }, settingId = "clipboard_exclude_passwords"),

            // ==================== MULTI-LANGUAGE ====================
            SearchableSetting("Enable Multi-Language", listOf("multilingual", "bilingual", "language"), "Multi-Language", expandSection = { multiLangSectionExpanded = true }, settingId = "multilang"),
            SearchableSetting("Primary Language", listOf("multilingual", "dictionary", "locale", "primary"), "Multi-Language", expandSection = { multiLangSectionExpanded = true }, gatedBy = "multilang", settingId = "primary_lang"),
            SearchableSetting("Secondary Language", listOf("bilingual", "dual", "alternate", "secondary"), "Multi-Language", expandSection = { multiLangSectionExpanded = true }, gatedBy = "multilang", settingId = "secondary_lang"),
            SearchableSetting("Secondary Language Weight", listOf("weight", "secondary", "prediction"), "Multi-Language", expandSection = { multiLangSectionExpanded = true }, gatedBy = "multilang", settingId = "secondary_weight"),
            SearchableSetting("Language Detection", listOf("auto", "detect", "switch", "automatic"), "Multi-Language", expandSection = { multiLangSectionExpanded = true }, gatedBy = "multilang", settingId = "lang_detection"),
            SearchableSetting("Detection Sensitivity", listOf("sensitivity", "detection", "threshold"), "Multi-Language", expandSection = { multiLangSectionExpanded = true }, gatedBy = "multilang", settingId = "detection_sensitivity"),
            SearchableSetting("Prefix Boost", listOf("prefix", "boost", "language"), "Multi-Language", expandSection = { multiLangSectionExpanded = true }, gatedBy = "multilang", settingId = "prefix_boost"),

            // ==================== PRIVACY ====================
            SearchableSetting("Incognito Mode", listOf("private", "secret", "hide", "incognito"), "Privacy", expandSection = { privacySectionExpanded = true }, settingId = "incognito"),
            SearchableSetting("Swipe Data Collection", listOf("data", "collection", "privacy", "swipe"), "Privacy", expandSection = { privacySectionExpanded = true }, settingId = "swipe_data"),
            SearchableSetting("Performance Metrics", listOf("performance", "metrics", "analytics"), "Privacy", expandSection = { privacySectionExpanded = true }, settingId = "performance_metrics"),

            // ==================== ADVANCED ====================
            SearchableSetting("Debug Logging", listOf("log", "developer", "verbose", "debug"), "Advanced", expandSection = { advancedSectionExpanded = true }, settingId = "debug_logging"),
            SearchableSetting("Terminal Mode", listOf("terminal", "termux", "mode"), "Advanced", expandSection = { advancedSectionExpanded = true }, settingId = "terminal_mode"),
            SearchableSetting("Detailed Swipe Logging", listOf("detailed", "swipe", "logging"), "Advanced", expandSection = { advancedSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "detailed_logging"),
            SearchableSetting("Show Debug Scores", listOf("debug", "scores", "prediction"), "Advanced", expandSection = { advancedSectionExpanded = true }, gatedBy = "swipe_typing", settingId = "debug_scores")
        )
    }

    /** Check if a gating toggle is enabled */
    private fun isGateEnabled(gateId: String): Boolean {
        return when (gateId) {
            "swipe_typing" -> swipeTypingEnabled
            "short_gestures" -> shortGesturesEnabled
            "multilang" -> multiLangEnabled
            else -> true
        }
    }

    /** Execute search result action - collapse others, expand target, handle gating */
    private fun executeSearchAction(setting: SearchableSetting) {
        // Check if gated by a disabled toggle
        if (setting.gatedBy != null && !isGateEnabled(setting.gatedBy)) {
            // Find the gating setting and highlight it
            collapseAllSections()
            val targetId = setting.gatedBy
            when (targetId) {
                "swipe_typing" -> neuralSectionExpanded = true
                "short_gestures" -> gestureTuningSectionExpanded = true
                "multilang" -> multiLangSectionExpanded = true
            }
            // Delay to let section expand, then scroll and highlight
            lifecycleScope.launch {
                kotlinx.coroutines.delay(200)  // Wait for layout
                scrollToSetting(targetId)
                highlightedSettingId = targetId
                kotlinx.coroutines.delay(2000)
                highlightedSettingId = null
            }
            return
        }

        // Navigate to activity or expand section
        if (setting.activityClass != null) {
            startActivity(Intent(this, setting.activityClass))
        } else if (setting.settingId == "whats_new") {
            // Special handling for What's New - opens external URL
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tribixbite/CleverKeys/releases/latest")))
        } else {
            collapseAllSections()
            setting.expandSection()
            // Delay to let section expand, then scroll to top and highlight
            if (setting.settingId.isNotEmpty()) {
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(200)  // Wait for layout
                    scrollToSetting(setting.settingId)
                    highlightedSettingId = setting.settingId
                    kotlinx.coroutines.delay(2000)
                    highlightedSettingId = null
                }
            }
        }
    }

    private fun getFilteredSettings(query: String): List<SearchableSetting> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase().trim()
        return searchableSettings.filter { setting ->
            setting.title.lowercase().contains(lowerQuery) ||
            setting.keywords.any { it.lowercase().contains(lowerQuery) }
        }
    }

    // Collected data viewer dialog state
    private var showCollectedDataViewer by mutableStateOf(false)
    private var collectedDataList by mutableStateOf<List<tribixbite.cleverkeys.ml.SwipeMLData>>(emptyList())
    private var collectedDataStats by mutableStateOf<tribixbite.cleverkeys.ml.SwipeMLDataStore.DataStatistics?>(null)
    private var collectedDataSearchQuery by mutableStateOf("")
    private var collectedDataCurrentPage by mutableStateOf(0)
    private var collectedDataTotalCount by mutableStateOf(0)
    private val collectedDataPageSize = 20

    // Performance stats viewer dialog state
    private var showPerfStatsViewer by mutableStateOf(false)
    private var perfStatsSummary by mutableStateOf("")

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

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing settings", e)
            fallbackEncrypted()
            return
        }

        // Load current settings
        loadCurrentSettings()

        try {
            setContent {
                // #35: Follow system dark/light mode instead of forcing dark
                KeyboardTheme {
                    SettingsScreen()
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

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // Handle preference changes for reactive updates
        when (key) {
            "swipe_typing_enabled" -> {
                swipeTypingEnabled = prefs.getBoolean(key, Defaults.SWIPE_TYPING_ENABLED)
            }
            "neural_beam_width" -> {
                beamWidth = prefs.getInt(key, Defaults.NEURAL_BEAM_WIDTH)
            }
            "neural_max_length" -> {
                maxLength = prefs.getInt(key, Defaults.NEURAL_MAX_LENGTH)
            }
            "neural_confidence_threshold" -> {
                confidenceThreshold = prefs.getFloat(key, Defaults.NEURAL_CONFIDENCE_THRESHOLD)
            }
            "theme" -> {
                currentThemeName = prefs.getSafeString(key, Defaults.THEME)
            }
            "keyboard_height" -> {
                keyboardHeight = prefs.getInt(key, Defaults.KEYBOARD_HEIGHT_PORTRAIT)
            }
            "vibration_enabled" -> {
                vibrationEnabled = prefs.getBoolean(key, Defaults.HAPTIC_ENABLED)
            }
            "debug_enabled" -> {
                debugEnabled = prefs.getBoolean(key, Defaults.DEBUG_ENABLED)
                Logs.setDebugEnabled(debugEnabled)
            }
            "clipboard_history_enabled" -> {
                clipboardHistoryEnabled = prefs.getBoolean(key, Defaults.CLIPBOARD_HISTORY_ENABLED)
            }
            "autocapitalisation" -> {
                autoCapitalizationEnabled = prefs.getBoolean(key, Defaults.AUTOCAPITALISATION)
            }
            "sticky_keys_enabled" -> {
                stickyKeysEnabled = prefs.getBoolean(key, Defaults.STICKY_KEYS_ENABLED)
            }
            "sticky_keys_timeout_ms" -> {
                stickyKeysTimeout = prefs.getInt(key, Defaults.STICKY_KEYS_TIMEOUT)
            }
            "voice_guidance_enabled" -> {
                voiceGuidanceEnabled = prefs.getBoolean(key, Defaults.VOICE_GUIDANCE_ENABLED)
            }
            // Adaptive layout settings
            "keyboard_height_landscape" -> {
                keyboardHeightLandscape = prefs.getInt(key, Defaults.KEYBOARD_HEIGHT_LANDSCAPE)
            }
            "margin_bottom_portrait" -> {
                marginBottomPortrait = prefs.getInt(key, Defaults.MARGIN_BOTTOM_PORTRAIT)
            }
            "margin_bottom_landscape" -> {
                marginBottomLandscape = prefs.getInt(key, Defaults.MARGIN_BOTTOM_LANDSCAPE)
            }
            "margin_left_portrait" -> {
                marginLeftPortrait = prefs.getInt(key, Defaults.MARGIN_LEFT_PORTRAIT)
            }
            "margin_left_landscape" -> {
                marginLeftLandscape = prefs.getInt(key, Defaults.MARGIN_LEFT_LANDSCAPE)
            }
            "margin_right_portrait" -> {
                marginRightPortrait = prefs.getInt(key, Defaults.MARGIN_RIGHT_PORTRAIT)
            }
            "margin_right_landscape" -> {
                marginRightLandscape = prefs.getInt(key, Defaults.MARGIN_RIGHT_LANDSCAPE)
            }
            // Gesture sensitivity settings
            "swipe_dist" -> {
                swipeDistance = prefs.getSafeString(key, Defaults.SWIPE_DIST).toIntOrNull() ?: Defaults.SWIPE_DIST_FALLBACK.toInt()
            }
            "circle_sensitivity" -> {
                circleSensitivity = prefs.getSafeString(key, Defaults.CIRCLE_SENSITIVITY).toIntOrNull() ?: Defaults.CIRCLE_SENSITIVITY_FALLBACK
            }
            // Long press settings
            "longpress_timeout" -> {
                longPressTimeout = prefs.getInt(key, Defaults.LONGPRESS_TIMEOUT)
            }
            "longpress_interval" -> {
                longPressInterval = prefs.getInt(key, Defaults.LONGPRESS_INTERVAL)
            }
            "keyrepeat_enabled" -> {
                keyRepeatEnabled = prefs.getBoolean(key, Defaults.KEYREPEAT_ENABLED)
            }
            // Visual customization settings
            "label_brightness" -> {
                labelBrightness = prefs.getInt(key, Defaults.LABEL_BRIGHTNESS)
            }
            "keyboard_opacity" -> {
                keyboardOpacity = prefs.getInt(key, Defaults.KEYBOARD_OPACITY)
            }
            "key_opacity" -> {
                keyOpacity = prefs.getInt(key, Defaults.KEY_OPACITY)
            }
            "key_activated_opacity" -> {
                keyActivatedOpacity = prefs.getInt(key, Defaults.KEY_ACTIVATED_OPACITY)
            }
            // Spacing and sizing settings
            "character_size" -> {
                characterSize = (prefs.getFloat(key, Defaults.CHARACTER_SIZE) * 100).toInt()
            }
            "key_vertical_margin" -> {
                keyVerticalMargin = (prefs.getFloat(key, Defaults.KEY_VERTICAL_MARGIN) * 100).toInt()
            }
            "key_horizontal_margin" -> {
                keyHorizontalMargin = (prefs.getFloat(key, Defaults.KEY_HORIZONTAL_MARGIN) * 100).toInt()
            }
            // Border customization settings
            "border_config" -> {
                borderConfigEnabled = prefs.getBoolean(key, Defaults.BORDER_CONFIG)
            }
            "custom_border_radius" -> {
                customBorderRadius = prefs.getInt(key, Defaults.CUSTOM_BORDER_RADIUS)
            }
            "custom_border_line_width" -> {
                customBorderLineWidth = prefs.getInt(key, Defaults.CUSTOM_BORDER_LINE_WIDTH)
            }
            // Behavior settings
            "lock_double_tap" -> {
                doubleTapLockShift = prefs.getBoolean(key, Defaults.DOUBLE_TAP_LOCK_SHIFT)
            }
            "switch_input_immediate" -> {
                switchInputImmediate = prefs.getBoolean(key, Defaults.SWITCH_INPUT_IMMEDIATE)
            }
            // Number row and numpad settings
            "number_row" -> {
                numberRowMode = prefs.getSafeString(key, Defaults.NUMBER_ROW)
            }
            "show_numpad" -> {
                showNumpadMode = prefs.getSafeString(key, Defaults.SHOW_NUMPAD)
            }
            "numpad_layout" -> {
                numpadLayout = prefs.getSafeString(key, Defaults.NUMPAD_LAYOUT)
            }
            "pin_entry_enabled" -> {
                pinEntryEnabled = prefs.getBoolean(key, false)
            }
            // Phase 1: Exposed Config.kt settings listeners
            "word_prediction_enabled" -> {
                wordPredictionEnabled = prefs.getBoolean(key, Defaults.WORD_PREDICTION_ENABLED)
            }
            "suggestion_bar_opacity" -> {
                suggestionBarOpacity = Config.safeGetInt(prefs, key, Defaults.SUGGESTION_BAR_OPACITY)
            }
            "autocorrect_enabled" -> {
                autoCorrectEnabled = prefs.getBoolean(key, Defaults.AUTOCORRECT_ENABLED)
            }
            "termux_mode_enabled" -> {
                termuxModeEnabled = prefs.getBoolean(key, Defaults.TERMUX_MODE_ENABLED)
            }
            "vibrate_duration" -> {
                vibrationDuration = prefs.getInt(key, Defaults.VIBRATE_DURATION)
            }
            "swipe_show_debug_scores" -> {
                swipeDebugEnabled = prefs.getBoolean(key, Defaults.SWIPE_SHOW_DEBUG_SCORES)
            }
            // Phase 5: Gesture settings listeners
            "slider_sensitivity" -> {
                sliderSensitivity = prefs.getSafeString(key, Defaults.SLIDER_SENSITIVITY).toIntOrNull() ?: 30
            }
            // Swipe Corrections settings
            "swipe_beam_autocorrect_enabled" -> {
                swipeBeamAutocorrectEnabled = prefs.getBoolean(key, Defaults.SWIPE_BEAM_AUTOCORRECT_ENABLED)
            }
            "swipe_final_autocorrect_enabled" -> {
                swipeFinalAutocorrectEnabled = prefs.getBoolean(key, Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)
            }
            "swipe_correction_preset" -> {
                swipeCorrectionPreset = prefs.getSafeString(key, "balanced")
            }
            "swipe_fuzzy_match_mode" -> {
                swipeFuzzyMatchMode = prefs.getSafeString(key, Defaults.SWIPE_FUZZY_MATCH_MODE)
            }
            "autocorrect_max_length_diff" -> {
                autocorrectMaxLengthDiff = Config.safeGetInt(prefs, key, Defaults.AUTOCORRECT_MAX_LENGTH_DIFF)
            }
            "autocorrect_prefix_length" -> {
                autocorrectPrefixLength = Config.safeGetInt(prefs, key, Defaults.AUTOCORRECT_PREFIX_LENGTH)
            }
            "autocorrect_max_beam_candidates" -> {
                autocorrectMaxBeamCandidates = Config.safeGetInt(prefs, key, Defaults.AUTOCORRECT_MAX_BEAM_CANDIDATES)
            }
            "swipe_prediction_source" -> {
                swipePredictionSource = Config.safeGetInt(prefs, key, Defaults.SWIPE_PREDICTION_SOURCE)
            }
            "swipe_common_words_boost" -> {
                swipeCommonWordsBoost = Config.safeGetFloat(prefs, key, Defaults.SWIPE_COMMON_WORDS_BOOST)
            }
            "swipe_top5000_boost" -> {
                swipeTop5000Boost = Config.safeGetFloat(prefs, key, Defaults.SWIPE_TOP5000_BOOST)
            }
            "swipe_rare_words_penalty" -> {
                swipeRareWordsPenalty = Config.safeGetFloat(prefs, key, Defaults.SWIPE_RARE_WORDS_PENALTY)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun SettingsScreen() {
        val scrollState = rememberScrollState()
        // Store reference for scroll-to-setting functionality
        mainScrollState = scrollState

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

                // Short Swipe Customization Card (Per-Key Actions)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { openShortSwipeCustomization() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "⌨️", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Customize Per-Key Actions",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Short swipes, custom commands per key direction",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
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

                    // Neural Advanced settings (expandable)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { neuralAdvancedExpanded = !neuralAdvancedExpanded }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Advanced Neural Settings", fontWeight = FontWeight.SemiBold)
                        Icon(
                            imageVector = if (neuralAdvancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    AnimatedVisibility(visible = neuralAdvancedExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Beam Search Config
                            Text(
                                text = "Beam Search",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )

                            SettingsSwitch(
                                title = "Batch Processing",
                                description = "Process all beams in single inference (faster but experimental)",
                                checked = neuralBatchBeams,
                                onCheckedChange = {
                                    neuralBatchBeams = it
                                    saveSetting("neural_batch_beams", it)
                                }
                            )

                            SettingsSwitch(
                                title = "Greedy Search",
                                description = "Single-pass decoding (fastest, single result only)",
                                checked = neuralGreedySearch,
                                onCheckedChange = {
                                    neuralGreedySearch = it
                                    saveSetting("neural_greedy_search", it)
                                }
                            )

                            // NOTE: Pruning Confidence and Early Stop Gap moved to Full Neural Settings
                            // to avoid overwhelming users with duplicate controls

                            Button(
                                onClick = { openNeuralSettings() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Full Neural Settings Activity")
                            }
                        }
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
                        }
                    )

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

                SettingsSwitch(
                    title = stringResource(R.string.settings_vibration_title),
                    description = stringResource(R.string.settings_vibration_desc),
                    checked = vibrationEnabled,
                    onCheckedChange = {
                        vibrationEnabled = it
                        saveSetting("vibration_enabled", it)
                    }
                )

                // Phase 1: Vibration Duration Slider (conditional)
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
                        title = "Prediction Tap",
                        description = "Vibrate when selecting a prediction",
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

                SettingsSlider(
                    title = "Swipe Distance Threshold",
                    description = "Minimum distance for swipe gestures (units)",
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
                        description = "Maximum swipe distance (% of key diagonal). 200% = disabled",
                        value = shortGestureMaxDistance.toFloat(),
                        valueRange = 50f..200f,
                        steps = 30,
                        onValueChange = {
                            shortGestureMaxDistance = it.toInt()
                            saveSetting("short_gesture_max_distance", shortGestureMaxDistance)
                        },
                        displayValue = if (shortGestureMaxDistance >= 200) "OFF" else "${shortGestureMaxDistance}%"
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
                    description = "Total distance to recognize a swipe (px). Lower allows shorter words like 'it', 'is'.",
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
                    description = "Maximum size per clipboard entry",
                    value = clipboardMaxItemSizeKb.toFloat(),
                    valueRange = 100f..5000f,
                    steps = 49,
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
            }

            // Backup & Restore Section (Collapsible)
            CollapsibleSettingsSection(
                title = "💾 Backup & Restore",
                expanded = backupRestoreSectionExpanded,
                onExpandChange = { backupRestoreSectionExpanded = it }
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

                // Clipboard history backup/restore
                Text(
                    text = "Clipboard History",
                    fontWeight = FontWeight.Bold,
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

                Text(
                    text = "Tap Export to choose save location, Import to browse for files.",
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
                        modifier = Modifier.padding(bottom = 8.dp)
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
                                           "Download language pack ZIP files and use 'Import Pack' to add them.",
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
                        description = "Display raw neural model outputs",
                        checked = swipeDebugShowRawOutput,
                        onCheckedChange = {
                            swipeDebugShowRawOutput = it
                            saveSetting("swipe_debug_show_raw_output", it)
                        }
                    )

                    SettingsSwitch(
                        title = "Show Beam Predictions",
                        description = "Display all beam search candidates",
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

                // Max Sequence Length Override (advanced neural setting)
                SettingsSlider(
                    title = "Max Sequence Length Override",
                    description = "Override model's max trajectory length (0 = use default 250)",
                    value = neuralUserMaxSeqLength.toFloat(),
                    valueRange = 0f..400f,
                    steps = 40,
                    onValueChange = {
                        neuralUserMaxSeqLength = it.toInt()
                        saveSetting("neural_user_max_seq_length", neuralUserMaxSeqLength)
                    },
                    displayValue = if (neuralUserMaxSeqLength == 0) "Default" else "$neuralUserMaxSeqLength"
                )

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
        }
    }

    // Composable helper components
    @Composable
    private fun CollapsibleSettingsSection(
        title: String,
        expanded: Boolean,
        onExpandChange: (Boolean) -> Unit,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onExpandChange(!expanded) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }

    // Non-collapsible version for simple sections
    @Composable
    private fun SettingsSection(
        title: String,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                content()
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SettingsSwitch(
        title: String,
        description: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        highlightId: String? = null
    ) {
        // Pulse animation for highlighting gated settings
        val isHighlighted = highlightId != null && highlightedSettingId == highlightId
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = if (isHighlighted) 1f else 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(400),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isHighlighted) pulseAlpha * 0.3f else 0f)
        val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isHighlighted) 0.5f + pulseAlpha * 0.5f else 0f)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    // Record position for scroll targeting
                    if (highlightId != null) {
                        val positionInParent = coordinates.positionInRoot()
                        val scrollOffset = mainScrollState?.value ?: 0
                        // Store the position relative to scroll content (add current scroll offset)
                        recordSettingPosition(highlightId, (positionInParent.y + scrollOffset).toInt())
                    }
                }
                .then(
                    if (isHighlighted) {
                        Modifier
                            .background(highlightColor, RoundedCornerShape(8.dp))
                            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                    } else Modifier
                )
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 8.dp, horizontal = if (isHighlighted) 8.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp
                )
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }

    @Composable
    private fun SettingsSlider(
        title: String,
        description: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        onValueChange: (Float) -> Unit,
        displayValue: String
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp
                )
                Text(
                    text = displayValue,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }

    @Composable
    private fun SettingsDropdown(
        title: String,
        description: String,
        options: List<String>,
        selectedIndex: Int,
        onSelectionChange: (Int) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }

        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp
            )
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = options[selectedIndex],
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onSelectionChange(index)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun VersionInfoCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_version_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                val versionInfo = loadVersionInfo()
                Text(
                    text = stringResource(R.string.settings_version_build, versionInfo.getProperty("version", "unknown")),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }

    @Composable
    private fun GitHubInfoCard() {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openGitHubReleases() },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "GitHub Repository",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "tribixbite/cleverkeys",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
                Text(
                    text = "Tap to view releases and download updates",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    /**
     * Dialog to view collected swipe data with search and pagination
     */
    @Composable
    private fun CollectedDataViewerDialog(
        dataList: List<tribixbite.cleverkeys.ml.SwipeMLData>,
        stats: tribixbite.cleverkeys.ml.SwipeMLDataStore.DataStatistics?,
        onDismiss: () -> Unit
    ) {
        val clipboardManager = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val totalPages = if (collectedDataTotalCount > 0) {
            (collectedDataTotalCount + collectedDataPageSize - 1) / collectedDataPageSize
        } else 0

        AlertDialog(
            onDismissRequest = {
                // Reset search state on dismiss
                collectedDataSearchQuery = ""
                collectedDataCurrentPage = 0
                onDismiss()
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Swipe Data")
                    IconButton(
                        onClick = {
                            collectedDataSearchQuery = ""
                            collectedDataCurrentPage = 0
                            loadCollectedDataPage()
                        }
                    ) {
                        Text("↺", fontSize = 18.sp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp)
                ) {
                    // Search field
                    OutlinedTextField(
                        value = collectedDataSearchQuery,
                        onValueChange = { query ->
                            collectedDataSearchQuery = query
                            collectedDataCurrentPage = 0
                            loadCollectedDataPage()
                        },
                        placeholder = { Text("Search words...", fontSize = 13.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )

                    // Stats summary
                    if (stats != null) {
                        Text(
                            text = "Showing ${dataList.size} of $collectedDataTotalCount • ${stats.uniqueWords} unique words",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    // Pagination controls
                    if (totalPages > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (collectedDataCurrentPage > 0) {
                                        collectedDataCurrentPage--
                                        loadCollectedDataPage()
                                    }
                                },
                                enabled = collectedDataCurrentPage > 0
                            ) {
                                Text("◀", fontSize = 16.sp)
                            }
                            Text(
                                text = "${collectedDataCurrentPage + 1} / $totalPages",
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            IconButton(
                                onClick = {
                                    if (collectedDataCurrentPage < totalPages - 1) {
                                        collectedDataCurrentPage++
                                        loadCollectedDataPage()
                                    }
                                },
                                enabled = collectedDataCurrentPage < totalPages - 1
                            ) {
                                Text("▶", fontSize = 16.sp)
                            }
                        }
                    }

                    if (dataList.isEmpty()) {
                        Text(
                            text = if (collectedDataSearchQuery.isNotEmpty()) "No results for \"$collectedDataSearchQuery\"" else "No data collected yet.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Scrollable list of entries
                        val listScrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .verticalScroll(listScrollState)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            dataList.forEach { data ->
                                val dateFormat = java.text.SimpleDateFormat(
                                    "MM/dd HH:mm",
                                    java.util.Locale.getDefault()
                                )
                                val dateStr = dateFormat.format(java.util.Date(data.timestampUtc))
                                val keys = data.getRegisteredKeys().joinToString("")
                                val points = data.getTracePoints().size

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // Copy full trace data to clipboard
                                            val traceJson = data.toJSON().toString(2)
                                            val clip = android.content.ClipData.newPlainText("Swipe Trace", traceJson)
                                            clipboardManager.setPrimaryClip(clip)
                                            Toast.makeText(this@SettingsActivity, "Trace copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "\"${data.targetWord}\"",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = dateStr,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Keys: $keys",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "$points pts",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    collectedDataSearchQuery = ""
                    collectedDataCurrentPage = 0
                    onDismiss()
                }) {
                    Text("Close")
                }
            }
        )
    }

    /**
     * Dialog to view performance statistics
     */
    @Composable
    private fun PerfStatsViewerDialog(
        summary: String,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text("Performance Statistics")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    val scrollState = rememberScrollState()
                    Text(
                        text = summary,
                        fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        )
    }

    // Helper functions

    /** Safely get an Int preference, handling cases where the value is stored as a different type */
    private fun SharedPreferences.getSafeInt(key: String, default: Int): Int {
        return try {
            getInt(key, default)
        } catch (e: ClassCastException) {
            // Value might be stored as String, try to parse it
            try {
                getString(key, null)?.toIntOrNull() ?: default
            } catch (e2: Exception) {
                default
            }
        }
    }

    /** Safely get a Float preference, handling cases where the value is stored as a different type */
    private fun SharedPreferences.getSafeFloat(key: String, default: Float): Float {
        return try {
            getFloat(key, default)
        } catch (e: ClassCastException) {
            try {
                getString(key, null)?.toFloatOrNull() ?: default
            } catch (e2: Exception) {
                default
            }
        }
    }

    /** Safely get a String preference, handling cases where the value is stored as Int or other types */
    private fun SharedPreferences.getSafeString(key: String, default: String): String {
        return try {
            getString(key, default) ?: default
        } catch (e: ClassCastException) {
            // Value might be stored as Int (e.g., from config import)
            try {
                getInt(key, -999999).let {
                    if (it == -999999) default else it.toString()
                }
            } catch (e2: ClassCastException) {
                // Try Float
                try {
                    getFloat(key, Float.MIN_VALUE).let {
                        if (it == Float.MIN_VALUE) default else it.toString()
                    }
                } catch (e3: Exception) {
                    default
                }
            } catch (e2: Exception) {
                default
            }
        }
    }

    /** Safely get a Boolean preference, handling cases where the value is stored as String or Int */
    private fun SharedPreferences.getSafeBoolean(key: String, default: Boolean): Boolean {
        return try {
            getBoolean(key, default)
        } catch (e: ClassCastException) {
            // Value might be stored as String or Int
            try {
                val stringVal = getString(key, null)
                when (stringVal?.lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> default
                }
            } catch (e2: ClassCastException) {
                try {
                    getInt(key, -1).let {
                        when (it) {
                            1 -> true
                            0 -> false
                            else -> default
                        }
                    }
                } catch (e3: Exception) {
                    default
                }
            } catch (e2: Exception) {
                default
            }
        }
    }

    private fun loadCurrentSettings() {
        // Swipe typing master switch
        swipeTypingEnabled = prefs.getSafeBoolean("swipe_typing_enabled", Defaults.SWIPE_TYPING_ENABLED)
        swipeOnPasswordFields = prefs.getSafeBoolean("swipe_on_password_fields", Defaults.SWIPE_ON_PASSWORD_FIELDS)

        // Neural prediction settings
        beamWidth = prefs.getSafeInt("neural_beam_width", Defaults.NEURAL_BEAM_WIDTH)
        maxLength = prefs.getSafeInt("neural_max_length", Defaults.NEURAL_MAX_LENGTH)
        confidenceThreshold = prefs.getSafeFloat("neural_confidence_threshold", Defaults.NEURAL_CONFIDENCE_THRESHOLD)

        // Appearance settings
        currentThemeName = prefs.getSafeString("theme", Defaults.THEME)
        keyboardHeight = prefs.getSafeInt("keyboard_height", Defaults.KEYBOARD_HEIGHT_PORTRAIT)
        keyboardHeightLandscape = prefs.getSafeInt("keyboard_height_landscape", Defaults.KEYBOARD_HEIGHT_LANDSCAPE)

        // Adaptive layout settings (percentages)
        marginBottomPortrait = prefs.getSafeInt("margin_bottom_portrait", Defaults.MARGIN_BOTTOM_PORTRAIT)
        marginBottomLandscape = prefs.getSafeInt("margin_bottom_landscape", Defaults.MARGIN_BOTTOM_LANDSCAPE)
        marginLeftPortrait = prefs.getSafeInt("margin_left_portrait", Defaults.MARGIN_LEFT_PORTRAIT)
        marginLeftLandscape = prefs.getSafeInt("margin_left_landscape", Defaults.MARGIN_LEFT_LANDSCAPE)
        marginRightPortrait = prefs.getSafeInt("margin_right_portrait", Defaults.MARGIN_RIGHT_PORTRAIT)
        marginRightLandscape = prefs.getSafeInt("margin_right_landscape", Defaults.MARGIN_RIGHT_LANDSCAPE)

        // Visual customization settings
        labelBrightness = prefs.getSafeInt("label_brightness", Defaults.LABEL_BRIGHTNESS)
        keyboardOpacity = prefs.getSafeInt("keyboard_opacity", Defaults.KEYBOARD_OPACITY)
        keyOpacity = prefs.getSafeInt("key_opacity", Defaults.KEY_OPACITY)
        keyActivatedOpacity = prefs.getSafeInt("key_activated_opacity", Defaults.KEY_ACTIVATED_OPACITY)

        // Spacing and sizing settings
        characterSize = (prefs.getSafeFloat("character_size", Defaults.CHARACTER_SIZE) * 100).toInt()
        keyVerticalMargin = (prefs.getSafeFloat("key_vertical_margin", Defaults.KEY_VERTICAL_MARGIN) * 100).toInt()
        keyHorizontalMargin = (prefs.getSafeFloat("key_horizontal_margin", Defaults.KEY_HORIZONTAL_MARGIN) * 100).toInt()

        // Border customization settings
        borderConfigEnabled = prefs.getSafeBoolean("border_config", Defaults.BORDER_CONFIG)
        customBorderRadius = prefs.getSafeInt("custom_border_radius", Defaults.CUSTOM_BORDER_RADIUS)
        customBorderLineWidth = prefs.getSafeInt("custom_border_line_width", Defaults.CUSTOM_BORDER_LINE_WIDTH)

        // Input behavior settings
        vibrationEnabled = prefs.getSafeBoolean("vibration_enabled", Defaults.HAPTIC_ENABLED)
        clipboardHistoryEnabled = prefs.getSafeBoolean("clipboard_history_enabled", Defaults.CLIPBOARD_HISTORY_ENABLED)
        clipboardHistoryLimit = prefs.getSafeString("clipboard_history_limit", Defaults.CLIPBOARD_HISTORY_LIMIT).toIntOrNull() ?: Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK
        clipboardPaneHeightPercent = Config.safeGetInt(prefs, "clipboard_pane_height_percent", Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT).coerceIn(10, 50)
        clipboardMaxItemSizeKb = prefs.getSafeString("clipboard_max_item_size_kb", Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB).toIntOrNull() ?: Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK
        clipboardLimitType = prefs.getSafeString("clipboard_limit_type", Defaults.CLIPBOARD_LIMIT_TYPE)
        clipboardSizeLimitMb = prefs.getSafeString("clipboard_size_limit_mb", Defaults.CLIPBOARD_SIZE_LIMIT_MB).toIntOrNull() ?: Defaults.CLIPBOARD_SIZE_LIMIT_MB_FALLBACK
        clipboardExcludePasswordManagers = prefs.getSafeBoolean("clipboard_exclude_password_managers", Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS)
        autoCapitalizationEnabled = prefs.getSafeBoolean("autocapitalisation", Defaults.AUTOCAPITALISATION)

        // Gesture sensitivity settings
        swipeDistance = prefs.getSafeString("swipe_dist", Defaults.SWIPE_DIST).toIntOrNull() ?: Defaults.SWIPE_DIST_FALLBACK.toInt()
        circleSensitivity = prefs.getSafeString("circle_sensitivity", Defaults.CIRCLE_SENSITIVITY).toIntOrNull() ?: Defaults.CIRCLE_SENSITIVITY_FALLBACK
        sliderSensitivity = prefs.getSafeString("slider_sensitivity", Defaults.SLIDER_SENSITIVITY).toIntOrNull() ?: 30

        // Long press settings
        longPressTimeout = prefs.getSafeInt("longpress_timeout", Defaults.LONGPRESS_TIMEOUT)
        longPressInterval = prefs.getSafeInt("longpress_interval", Defaults.LONGPRESS_INTERVAL)
        keyRepeatEnabled = prefs.getSafeBoolean("keyrepeat_enabled", Defaults.KEYREPEAT_ENABLED)

        // Behavior settings
        doubleTapLockShift = prefs.getSafeBoolean("lock_double_tap", Defaults.DOUBLE_TAP_LOCK_SHIFT)
        switchInputImmediate = prefs.getSafeBoolean("switch_input_immediate", Defaults.SWITCH_INPUT_IMMEDIATE)
        smartPunctuationEnabled = prefs.getSafeBoolean("smart_punctuation", Defaults.SMART_PUNCTUATION)
        vibrateCustomEnabled = prefs.getSafeBoolean("vibrate_custom", Defaults.VIBRATE_CUSTOM)
        numberEntryLayout = prefs.getSafeString("number_entry_layout", Defaults.NUMBER_ENTRY_LAYOUT)

        // Gesture tuning settings
        tapDurationThreshold = Config.safeGetInt(prefs, "tap_duration_threshold", Defaults.TAP_DURATION_THRESHOLD)
        doubleSpaceToPeriod = prefs.getSafeBoolean("double_space_to_period", Defaults.DOUBLE_SPACE_TO_PERIOD)
        doubleSpaceThreshold = Config.safeGetInt(prefs, "double_space_threshold", Defaults.DOUBLE_SPACE_THRESHOLD)
        swipeMinDistance = Config.safeGetFloat(prefs, "swipe_min_distance", Defaults.SWIPE_MIN_DISTANCE)
        swipeMinKeyDistance = Config.safeGetFloat(prefs, "swipe_min_key_distance", Defaults.SWIPE_MIN_KEY_DISTANCE)
        swipeMinDwellTime = Config.safeGetInt(prefs, "swipe_min_dwell_time", Defaults.SWIPE_MIN_DWELL_TIME)
        swipeNoiseThreshold = Config.safeGetFloat(prefs, "swipe_noise_threshold", Defaults.SWIPE_NOISE_THRESHOLD)
        swipeHighVelocityThreshold = Config.safeGetFloat(prefs, "swipe_high_velocity_threshold", Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD)
        fingerOcclusionOffset = Config.safeGetFloat(prefs, "finger_occlusion_offset", Defaults.FINGER_OCCLUSION_OFFSET)
        sliderSpeedSmoothing = Config.safeGetFloat(prefs, "slider_speed_smoothing", Defaults.SLIDER_SPEED_SMOOTHING)
        sliderSpeedMax = Config.safeGetFloat(prefs, "slider_speed_max", Defaults.SLIDER_SPEED_MAX)

        // Number row and numpad settings
        numberRowMode = prefs.getSafeString("number_row", Defaults.NUMBER_ROW)
        showNumpadMode = prefs.getSafeString("show_numpad", Defaults.SHOW_NUMPAD)
        numpadLayout = prefs.getSafeString("numpad_layout", Defaults.NUMPAD_LAYOUT)
        pinEntryEnabled = prefs.getSafeBoolean("pin_entry_enabled", false)

        // Advanced settings
        debugEnabled = prefs.getSafeBoolean("debug_enabled", Defaults.DEBUG_ENABLED)

        // Accessibility settings
        stickyKeysEnabled = prefs.getSafeBoolean("sticky_keys_enabled", Defaults.STICKY_KEYS_ENABLED)
        stickyKeysTimeout = prefs.getSafeInt("sticky_keys_timeout_ms", Defaults.STICKY_KEYS_TIMEOUT)
        voiceGuidanceEnabled = prefs.getSafeBoolean("voice_guidance_enabled", Defaults.VOICE_GUIDANCE_ENABLED)

        // Phase 1: Load exposed Config.kt settings
        wordPredictionEnabled = prefs.getSafeBoolean("word_prediction_enabled", Defaults.WORD_PREDICTION_ENABLED)
        autoSpaceAfterSuggestion = prefs.getSafeBoolean("auto_space_after_suggestion", Defaults.AUTO_SPACE_AFTER_SUGGESTION)
        suggestionBarOpacity = Config.safeGetInt(prefs, "suggestion_bar_opacity", Defaults.SUGGESTION_BAR_OPACITY)
        autoCorrectEnabled = prefs.getSafeBoolean("autocorrect_enabled", Defaults.AUTOCORRECT_ENABLED)
        termuxModeEnabled = prefs.getSafeBoolean("termux_mode_enabled", Defaults.TERMUX_MODE_ENABLED)
        vibrationDuration = prefs.getSafeInt("vibrate_duration", Defaults.VIBRATE_DURATION)
        // Per-event haptic feedback
        hapticKeyPress = prefs.getSafeBoolean("haptic_key_press", Defaults.HAPTIC_KEY_PRESS)
        hapticPredictionTap = prefs.getSafeBoolean("haptic_prediction_tap", Defaults.HAPTIC_PREDICTION_TAP)
        hapticTrackpointActivate = prefs.getSafeBoolean("haptic_trackpoint_activate", Defaults.HAPTIC_TRACKPOINT_ACTIVATE)
        hapticLongPress = prefs.getSafeBoolean("haptic_long_press", Defaults.HAPTIC_LONG_PRESS)
        hapticSwipeComplete = prefs.getSafeBoolean("haptic_swipe_complete", Defaults.HAPTIC_SWIPE_COMPLETE)
        swipeDebugEnabled = prefs.getSafeBoolean("swipe_show_debug_scores", Defaults.SWIPE_SHOW_DEBUG_SCORES)

        // Swipe Corrections settings
        swipeBeamAutocorrectEnabled = prefs.getSafeBoolean("swipe_beam_autocorrect_enabled", Defaults.SWIPE_BEAM_AUTOCORRECT_ENABLED)
        swipeFinalAutocorrectEnabled = prefs.getSafeBoolean("swipe_final_autocorrect_enabled", Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)
        swipeCorrectionPreset = prefs.getSafeString("swipe_correction_preset", "balanced")
        swipeFuzzyMatchMode = prefs.getSafeString("swipe_fuzzy_match_mode", Defaults.SWIPE_FUZZY_MATCH_MODE)
        autocorrectMaxLengthDiff = Config.safeGetInt(prefs, "autocorrect_max_length_diff", Defaults.AUTOCORRECT_MAX_LENGTH_DIFF)
        autocorrectPrefixLength = Config.safeGetInt(prefs, "autocorrect_prefix_length", Defaults.AUTOCORRECT_PREFIX_LENGTH)
        autocorrectMaxBeamCandidates = Config.safeGetInt(prefs, "autocorrect_max_beam_candidates", Defaults.AUTOCORRECT_MAX_BEAM_CANDIDATES)
        swipePredictionSource = Config.safeGetInt(prefs, "swipe_prediction_source", Defaults.SWIPE_PREDICTION_SOURCE)
        swipeCommonWordsBoost = Config.safeGetFloat(prefs, "swipe_common_words_boost", Defaults.SWIPE_COMMON_WORDS_BOOST)
        swipeTop5000Boost = Config.safeGetFloat(prefs, "swipe_top5000_boost", Defaults.SWIPE_TOP5000_BOOST)
        swipeRareWordsPenalty = Config.safeGetFloat(prefs, "swipe_rare_words_penalty", Defaults.SWIPE_RARE_WORDS_PENALTY)

        // Swipe trail appearance settings
        swipeTrailEnabled = prefs.getSafeBoolean("swipe_trail_enabled", Defaults.SWIPE_TRAIL_ENABLED)
        swipeTrailEffect = prefs.getSafeString("swipe_trail_effect", Defaults.SWIPE_TRAIL_EFFECT)
        swipeTrailColor = prefs.getSafeInt("swipe_trail_color", Defaults.SWIPE_TRAIL_COLOR)
        swipeTrailWidth = prefs.getSafeFloat("swipe_trail_width", Defaults.SWIPE_TRAIL_WIDTH)
        swipeTrailGlowRadius = prefs.getSafeFloat("swipe_trail_glow_radius", Defaults.SWIPE_TRAIL_GLOW_RADIUS)

        // Word Prediction Advanced settings
        contextAwarePredictionsEnabled = prefs.getSafeBoolean("context_aware_predictions_enabled", Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED)
        personalizedLearningEnabled = prefs.getSafeBoolean("personalized_learning_enabled", Defaults.PERSONALIZED_LEARNING_ENABLED)
        learningAggression = prefs.getSafeString("learning_aggression", Defaults.LEARNING_AGGRESSION)
        predictionContextBoost = Config.safeGetFloat(prefs, "prediction_context_boost", Defaults.PREDICTION_CONTEXT_BOOST)
        predictionFrequencyScale = Config.safeGetFloat(prefs, "prediction_frequency_scale", Defaults.PREDICTION_FREQUENCY_SCALE)

        // Auto-correction advanced settings
        autocorrectMinWordLength = Config.safeGetInt(prefs, "autocorrect_min_word_length", Defaults.AUTOCORRECT_MIN_WORD_LENGTH)
        autocorrectCharMatchThreshold = Config.safeGetFloat(prefs, "autocorrect_char_match_threshold", Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD)
        autocorrectMinFrequency = Config.safeGetInt(prefs, "autocorrect_confidence_min_frequency", Defaults.AUTOCORRECT_MIN_FREQUENCY)

        // Neural beam search advanced settings
        neuralBatchBeams = prefs.getSafeBoolean("neural_batch_beams", Defaults.NEURAL_BATCH_BEAMS)
        neuralGreedySearch = prefs.getSafeBoolean("neural_greedy_search", Defaults.NEURAL_GREEDY_SEARCH)
        neuralBeamAlpha = Config.safeGetFloat(prefs, "neural_beam_alpha", Defaults.NEURAL_BEAM_ALPHA)
        neuralBeamPruneConfidence = Config.safeGetFloat(prefs, "neural_beam_prune_confidence", Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE)
        neuralBeamScoreGap = Config.safeGetFloat(prefs, "neural_beam_score_gap", Defaults.NEURAL_BEAM_SCORE_GAP)

        // Neural model config settings
        neuralResamplingMode = prefs.getSafeString("neural_resampling_mode", Defaults.NEURAL_RESAMPLING_MODE)
        neuralUserMaxSeqLength = Config.safeGetInt(prefs, "neural_user_max_seq_length", Defaults.NEURAL_USER_MAX_SEQ_LENGTH)

        // Multi-language settings
        multiLangEnabled = prefs.getSafeBoolean("pref_enable_multilang", Defaults.ENABLE_MULTILANG)
        primaryLanguage = prefs.getSafeString("pref_primary_language", Defaults.PRIMARY_LANGUAGE)
        secondaryLanguage = prefs.getSafeString("pref_secondary_language", "none")
        autoDetectLanguage = prefs.getSafeBoolean("pref_auto_detect_language", Defaults.AUTO_DETECT_LANGUAGE)
        languageDetectionSensitivity = Config.safeGetFloat(prefs, "pref_language_detection_sensitivity", Defaults.LANGUAGE_DETECTION_SENSITIVITY)
        secondaryPredictionWeight = Config.safeGetFloat(prefs, "pref_secondary_prediction_weight", Defaults.SECONDARY_PREDICTION_WEIGHT)
        // Load per-language prefix boost values (falls back to global default if not set)
        prefixBoostMultiplier = Config.safeGetFloat(prefs, "neural_prefix_boost_multiplier_$primaryLanguage", Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER)
        prefixBoostMax = Config.safeGetFloat(prefs, "neural_prefix_boost_max_$primaryLanguage", Defaults.NEURAL_PREFIX_BOOST_MAX)
        // Prefix boost safety settings (global, not per-language)
        maxCumulativeBoost = Config.safeGetFloat(prefs, "neural_max_cumulative_boost", Defaults.NEURAL_MAX_CUMULATIVE_BOOST)
        strictStartChar = prefs.getSafeBoolean("neural_strict_start_char", Defaults.NEURAL_STRICT_START_CHAR)
        primaryLanguageAlt = prefs.getSafeString("pref_primary_language_alt", "es")
        secondaryLanguageAlt = prefs.getSafeString("pref_secondary_language_alt", "none")

        // Detect available V2 dictionaries for secondary language options
        availableSecondaryLanguages = detectAvailableV2Dictionaries()

        // Load installed language packs
        refreshInstalledLanguagePacks()

        // Privacy settings - all OFF by default (CleverKeys is fully offline)
        privacyCollectSwipe = prefs.getSafeBoolean("privacy_collect_swipe", Defaults.PRIVACY_COLLECT_SWIPE)
        privacyCollectPerformance = prefs.getSafeBoolean("privacy_collect_performance", Defaults.PRIVACY_COLLECT_PERFORMANCE)
        privacyCollectErrors = prefs.getSafeBoolean("privacy_collect_errors", Defaults.PRIVACY_COLLECT_ERRORS)

        // Short gesture settings
        shortGesturesEnabled = prefs.getSafeBoolean("short_gestures_enabled", Defaults.SHORT_GESTURES_ENABLED)
        shortGestureMinDistance = Config.safeGetInt(prefs, "short_gesture_min_distance", Defaults.SHORT_GESTURE_MIN_DISTANCE)
        shortGestureMaxDistance = Config.safeGetInt(prefs, "short_gesture_max_distance", Defaults.SHORT_GESTURE_MAX_DISTANCE)

        // Selection-delete mode settings
        selectionDeleteVerticalThreshold = Config.safeGetInt(prefs, "selection_delete_vertical_threshold", Defaults.SELECTION_DELETE_VERTICAL_THRESHOLD)
        selectionDeleteVerticalSpeed = Config.safeGetFloat(prefs, "selection_delete_vertical_speed", Defaults.SELECTION_DELETE_VERTICAL_SPEED)

        // Swipe debug advanced settings
        swipeDebugDetailedLogging = prefs.getSafeBoolean("swipe_debug_detailed_logging", Defaults.SWIPE_DEBUG_DETAILED_LOGGING)
        swipeDebugShowRawOutput = prefs.getSafeBoolean("swipe_debug_show_raw_output", Defaults.SWIPE_DEBUG_SHOW_RAW_OUTPUT)
        swipeShowRawBeamPredictions = prefs.getSafeBoolean("swipe_show_raw_beam_predictions", Defaults.SWIPE_SHOW_RAW_BEAM_PREDICTIONS)
    }

    private fun saveSetting(key: String, value: Any) {
        lifecycleScope.launch {
            try {
                val editor = prefs.edit()
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                }
                editor.apply()

                // Update configuration object
                updateConfigFromSettings()

                // Broadcast language changes so other activities can refresh
                if (key in listOf("pref_primary_language", "pref_secondary_language", "pref_enable_multilang")) {
                    LocalBroadcastManager.getInstance(this@SettingsActivity)
                        .sendBroadcast(Intent("tribixbite.cleverkeys.LANGUAGE_CHANGED"))
                }

                android.util.Log.d(TAG, "Setting saved: $key = $value")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error saving setting: $key = $value", e)
                Toast.makeText(this@SettingsActivity,
                    getString(R.string.settings_toast_error_saving, e.message ?: ""),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Get current swipe sensitivity preset based on current values
     */
    private fun getSwipeSensitivityPreset(): String {
        // Low: Higher thresholds = less sensitive
        val lowPreset = swipeMinDistance == 80f && swipeMinKeyDistance == 60f && swipeMinDwellTime == 30
        // Medium: Default values
        val mediumPreset = swipeMinDistance == 50f && swipeMinKeyDistance == 40f && swipeMinDwellTime == 15
        // High: Lower thresholds = more sensitive
        val highPreset = swipeMinDistance == 30f && swipeMinKeyDistance == 25f && swipeMinDwellTime == 5

        return when {
            lowPreset -> "Low"
            mediumPreset -> "Medium"
            highPreset -> "High"
            else -> "Custom"
        }
    }

    /**
     * Apply swipe sensitivity preset values
     */
    private fun applySwipeSensitivityPreset(preset: String) {
        when (preset) {
            "Low" -> {
                swipeMinDistance = 80f
                swipeMinKeyDistance = 60f
                swipeMinDwellTime = 30
            }
            "Medium" -> {
                swipeMinDistance = 50f
                swipeMinKeyDistance = 40f
                swipeMinDwellTime = 15
            }
            "High" -> {
                swipeMinDistance = 30f
                swipeMinKeyDistance = 25f
                swipeMinDwellTime = 5
            }
            "Custom" -> return // Don't change values
        }
        // Save the new values
        saveSetting("swipe_min_distance", swipeMinDistance)
        saveSetting("swipe_min_key_distance", swipeMinKeyDistance)
        saveSetting("swipe_min_dwell_time", swipeMinDwellTime)
    }

    private fun updateConfigFromSettings() {
        // Update global config from current settings
        // Note: Config.theme uses R.style.* resource IDs, converted from theme name
        config.apply {
            keyboardHeightPercent = keyboardHeight
            vibrate_custom = vibrationEnabled
            neural_beam_width = beamWidth
            neural_max_length = maxLength
            neural_confidence_threshold = confidenceThreshold
            // Swipe corrections settings (these update the Config object)
            swipe_beam_autocorrect_enabled = swipeBeamAutocorrectEnabled
            swipe_final_autocorrect_enabled = swipeFinalAutocorrectEnabled
            swipe_fuzzy_match_mode = swipeFuzzyMatchMode
            autocorrect_max_length_diff = autocorrectMaxLengthDiff
            autocorrect_prefix_length = autocorrectPrefixLength
            autocorrect_max_beam_candidates = autocorrectMaxBeamCandidates
            swipe_common_words_boost = swipeCommonWordsBoost
            swipe_top5000_boost = swipeTop5000Boost
            swipe_rare_words_penalty = swipeRareWordsPenalty
        }
    }

    /**
     * Detect available V2 binary dictionaries for secondary language selection.
     * Scans assets/dictionaries/ for *_enhanced.bin files.
     *
     * @return List of language codes (e.g., ["es", "fr", "de"])
     */
    private fun detectAvailableV2Dictionaries(): List<String> {
        val languages = mutableSetOf<String>()
        try {
            // Bundled dictionaries in assets
            val files = assets.list("dictionaries") ?: emptyArray()
            for (file in files) {
                if (file.endsWith("_enhanced.bin")) {
                    val langCode = file.removeSuffix("_enhanced.bin")
                    // v1.1.93: Include ALL languages including English
                    // UI already filters out primary language from secondary options
                    if (langCode.length in 2..3) {
                        languages.add(langCode)
                    }
                }
            }

            // Installed language packs
            val packManager = LanguagePackManager.getInstance(this)
            packManager.getInstalledPacks().forEach { pack ->
                languages.add(pack.code)
            }

            android.util.Log.i(TAG, "Available V2 dictionaries: $languages")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to detect V2 dictionaries", e)
        }
        return languages.sorted()
    }

    private fun refreshAvailableSecondaryLanguages() {
        availableSecondaryLanguages = detectAvailableV2Dictionaries()
    }

    /**
     * Get display name for language code.
     */
    private fun getLanguageDisplayName(code: String): String {
        return when (code) {
            "none" -> "None"
            "en" -> "English"
            "es" -> "Spanish (Español)"
            "fr" -> "French (Français)"
            "de" -> "German (Deutsch)"
            "pt" -> "Portuguese (Português)"
            "it" -> "Italian (Italiano)"
            "ru" -> "Russian (Русский)"
            "nl" -> "Dutch (Nederlands)"
            "pl" -> "Polish (Polski)"
            "sv" -> "Swedish (Svenska)"
            "da" -> "Danish (Dansk)"
            "no" -> "Norwegian (Norsk)"
            "fi" -> "Finnish (Suomi)"
            "cs" -> "Czech (Čeština)"
            "hu" -> "Hungarian (Magyar)"
            "tr" -> "Turkish (Türkçe)"
            "el" -> "Greek (Ελληνικά)"
            "ro" -> "Romanian (Română)"
            "uk" -> "Ukrainian (Українська)"
            "hr" -> "Croatian (Hrvatski)"
            "sk" -> "Slovak (Slovenčina)"
            "sl" -> "Slovenian (Slovenščina)"
            "bg" -> "Bulgarian (Български)"
            "ca" -> "Catalan (Català)"
            "eu" -> "Basque (Euskara)"
            "gl" -> "Galician (Galego)"
            // Downloadable language packs
            "id" -> "Indonesian (Bahasa Indonesia)"
            "ms" -> "Malay (Bahasa Melayu)"
            "sw" -> "Swahili (Kiswahili)"
            "tl" -> "Tagalog (Filipino)"
            else -> code.uppercase()
        }
    }

    /**
     * Load per-language prefix boost settings.
     * Called when primary language changes to load that language's specific boost values.
     * Uses same fallback logic as Config.kt: per-language → global → defaults
     */
    private fun loadPrefixBoostForLanguage(langCode: String) {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        // Match Config.kt fallback logic: per-language key -> global key -> defaults
        prefixBoostMultiplier = Config.safeGetFloat(prefs, "neural_prefix_boost_multiplier_$langCode",
            Config.safeGetFloat(prefs, "neural_prefix_boost_multiplier", Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER))
        prefixBoostMax = Config.safeGetFloat(prefs, "neural_prefix_boost_max_$langCode",
            Config.safeGetFloat(prefs, "neural_prefix_boost_max", Defaults.NEURAL_PREFIX_BOOST_MAX))
    }

    private fun loadVersionInfo(): Properties {
        val props = Properties()
        try {
            val reader = BufferedReader(
                InputStreamReader(
                    resources.openRawResource(
                        resources.getIdentifier("version_info", "raw", packageName)
                    )
                )
            )
            props.load(reader)
            reader.close()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load version info", e)
            // Set default version
            props.setProperty("version", "unknown")
        }
        return props
    }

    private fun openNeuralSettings() {
        startActivity(Intent(this, NeuralSettingsActivity::class.java))
    }

    private fun openCalibration() {
        startActivity(Intent(this, SwipeCalibrationActivity::class.java))
    }

    private fun openSwipeDebugActivity() {
        startActivity(Intent(this, SwipeDebugActivity::class.java))
    }

    private fun openDictionaryManager() {
        // Launch our 4-tab Dictionary Manager (Active, Disabled, User, Custom)
        startActivity(Intent(this, DictionaryManagerActivity::class.java))
    }

    private fun openLayoutManager() {
        startActivity(Intent(this, LayoutManagerActivity::class.java))
    }

    private fun openExtraKeysConfig() {
        startActivity(Intent(this, ExtraKeysConfigActivity::class.java))
    }

    private fun openShortSwipeCustomization() {
        startActivity(Intent(this, ShortSwipeCustomizationActivity::class.java))
    }

    private fun openAutoCorrectionSettings() {
        startActivity(Intent(this, AutoCorrectionSettingsActivity::class.java))
    }

    private fun resetAllSettings() {
        lifecycleScope.launch {
            android.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(getString(R.string.settings_reset_dialog_title))
                .setMessage(getString(R.string.settings_reset_dialog_message))
                .setPositiveButton(getString(R.string.settings_reset_dialog_confirm)) { _, _ ->
                    // Reset all settings using Defaults constants
                    val editor = prefs.edit()
                    editor.clear()

                    // Appearance - use Defaults constants
                    editor.putString("theme", Defaults.THEME)
                    editor.putInt("keyboard_height", Defaults.KEYBOARD_HEIGHT_PORTRAIT)
                    editor.putInt("keyboard_height_landscape", Defaults.KEYBOARD_HEIGHT_LANDSCAPE)
                    editor.putInt("label_brightness", Defaults.LABEL_BRIGHTNESS)
                    editor.putInt("keyboard_opacity", Defaults.KEYBOARD_OPACITY)
                    editor.putInt("key_opacity", Defaults.KEY_OPACITY)
                    editor.putFloat("character_size", Defaults.CHARACTER_SIZE)

                    // Margins - use Defaults constants (0% bottom, 1% sides)
                    editor.putInt("margin_bottom_portrait", Defaults.MARGIN_BOTTOM_PORTRAIT)
                    editor.putInt("margin_bottom_landscape", Defaults.MARGIN_BOTTOM_LANDSCAPE)
                    editor.putInt("margin_left_portrait", Defaults.MARGIN_LEFT_PORTRAIT)
                    editor.putInt("margin_left_landscape", Defaults.MARGIN_LEFT_LANDSCAPE)
                    editor.putInt("margin_right_portrait", Defaults.MARGIN_RIGHT_PORTRAIT)
                    editor.putInt("margin_right_landscape", Defaults.MARGIN_RIGHT_LANDSCAPE)

                    // Short gesture settings - use Defaults constants
                    editor.putBoolean("short_gestures_enabled", Defaults.SHORT_GESTURES_ENABLED)
                    editor.putInt("short_gesture_min_distance", Defaults.SHORT_GESTURE_MIN_DISTANCE)
                    editor.putInt("short_gesture_max_distance", Defaults.SHORT_GESTURE_MAX_DISTANCE)

                    // Neural prediction - BALANCED profile (using Defaults constants)
                    editor.putInt("neural_beam_width", Defaults.NEURAL_BEAM_WIDTH)
                    editor.putInt("neural_max_length", Defaults.NEURAL_MAX_LENGTH)
                    editor.putFloat("neural_confidence_threshold", Defaults.NEURAL_CONFIDENCE_THRESHOLD)
                    editor.putFloat("neural_beam_alpha", Defaults.NEURAL_BEAM_ALPHA)
                    editor.putFloat("neural_beam_prune_confidence", Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE)
                    editor.putFloat("neural_beam_score_gap", Defaults.NEURAL_BEAM_SCORE_GAP)
                    editor.putInt("neural_adaptive_width_step", Defaults.NEURAL_ADAPTIVE_WIDTH_STEP)
                    editor.putInt("neural_score_gap_step", Defaults.NEURAL_SCORE_GAP_STEP)
                    editor.putFloat("neural_temperature", Defaults.NEURAL_TEMPERATURE)
                    editor.putFloat("neural_frequency_weight", Defaults.NEURAL_FREQUENCY_WEIGHT)
                    editor.putInt("swipe_smoothing_window", Defaults.SWIPE_SMOOTHING_WINDOW)

                    // Input behavior
                    editor.putBoolean("vibrate_custom", Defaults.VIBRATE_CUSTOM)
                    editor.putInt("vibrate_duration", Defaults.VIBRATE_DURATION)
                    editor.putInt("longpress_timeout", Defaults.LONGPRESS_TIMEOUT)
                    editor.putBoolean("keyrepeat_enabled", Defaults.KEYREPEAT_ENABLED)
                    editor.putBoolean("autocapitalisation", Defaults.AUTOCAPITALISATION)
                    editor.putBoolean("double_space_to_period", Defaults.DOUBLE_SPACE_TO_PERIOD)
                    editor.putBoolean("smart_punctuation", Defaults.SMART_PUNCTUATION)
                    editor.putInt("tap_duration_threshold", Defaults.TAP_DURATION_THRESHOLD)

                    // Haptic feedback
                    editor.putBoolean("haptic_key_press", Defaults.HAPTIC_KEY_PRESS)
                    editor.putBoolean("haptic_prediction_tap", Defaults.HAPTIC_PREDICTION_TAP)
                    editor.putBoolean("haptic_trackpoint_activate", Defaults.HAPTIC_TRACKPOINT_ACTIVATE)
                    editor.putBoolean("haptic_long_press", Defaults.HAPTIC_LONG_PRESS)
                    editor.putBoolean("haptic_swipe_complete", Defaults.HAPTIC_SWIPE_COMPLETE)

                    // Word prediction
                    editor.putBoolean("swipe_typing_enabled", Defaults.SWIPE_TYPING_ENABLED)
                    editor.putBoolean("word_prediction_enabled", Defaults.WORD_PREDICTION_ENABLED)
                    editor.putInt("suggestion_bar_opacity", Defaults.SUGGESTION_BAR_OPACITY)
                    editor.putBoolean("context_aware_predictions_enabled", Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED)
                    editor.putBoolean("personalized_learning_enabled", Defaults.PERSONALIZED_LEARNING_ENABLED)

                    // Autocorrect
                    editor.putBoolean("autocorrect_enabled", Defaults.AUTOCORRECT_ENABLED)
                    editor.putBoolean("swipe_beam_autocorrect_enabled", Defaults.SWIPE_BEAM_AUTOCORRECT_ENABLED)
                    editor.putBoolean("swipe_final_autocorrect_enabled", Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)
                    editor.putString("swipe_fuzzy_match_mode", Defaults.SWIPE_FUZZY_MATCH_MODE)

                    // Swipe trail
                    editor.putBoolean("swipe_trail_enabled", Defaults.SWIPE_TRAIL_ENABLED)
                    editor.putString("swipe_trail_effect", Defaults.SWIPE_TRAIL_EFFECT)

                    // Clipboard
                    editor.putBoolean("clipboard_history_enabled", Defaults.CLIPBOARD_HISTORY_ENABLED)
                    editor.putInt("clipboard_pane_height_percent", Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT)

                    // Accessibility
                    editor.putBoolean("sticky_keys_enabled", Defaults.STICKY_KEYS_ENABLED)
                    editor.putInt("sticky_keys_timeout", Defaults.STICKY_KEYS_TIMEOUT)
                    editor.putBoolean("voice_guidance_enabled", Defaults.VOICE_GUIDANCE_ENABLED)

                    // Debug (off by default)
                    editor.putBoolean("debug_enabled", Defaults.DEBUG_ENABLED)
                    editor.putBoolean("termux_mode_enabled", Defaults.TERMUX_MODE_ENABLED)

                    editor.apply()

                    // Reset UI state
                    loadCurrentSettings()

                    Toast.makeText(this@SettingsActivity,
                        getString(R.string.settings_toast_reset_success),
                        Toast.LENGTH_SHORT).show()

                    // Recreate activity to refresh UI
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // Self-update feature removed for F-Droid compliance
    // F-Droid handles updates automatically - no storage permissions needed

    private fun fallbackEncrypted() {
        // Handle direct boot mode failure
        android.util.Log.w(TAG, "Settings unavailable in direct boot mode")
        finish()
    }

    // Clipboard settings now inline in main settings UI

    private fun openBackupRestore() {
        startActivity(Intent(this, BackupRestoreActivity::class.java))
    }

    // Inline backup/restore functions - launch SAF file pickers
    private fun exportConfiguration() {
        try {
            configExportLauncher.launch("cleverkeys-config.json")
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importConfiguration() {
        try {
            configImportLauncher.launch(arrayOf("application/json", "*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportCustomDictionary() {
        try {
            dictionaryExportLauncher.launch("cleverkeys-dictionary.json")
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importCustomDictionary() {
        try {
            dictionaryImportLauncher.launch(arrayOf("application/json", "*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportClipboardHistory() {
        try {
            clipboardExportLauncher.launch("cleverkeys-clipboard.json")
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importClipboardHistory() {
        try {
            clipboardImportLauncher.launch(arrayOf("application/json", "*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importLanguagePack() {
        languagePackImportStatus = null
        try {
            languagePackImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performLanguagePackImport(uri: Uri) {
        lifecycleScope.launch {
            try {
                val manager = LanguagePackManager.getInstance(this@SettingsActivity)
                when (val result = manager.importLanguagePack(uri)) {
                    is ImportResult.Success -> {
                        languagePackImportStatus = "Imported: ${result.manifest.name} (${result.manifest.wordCount} words)"
                        refreshInstalledLanguagePacks()
                        refreshAvailableSecondaryLanguages()
                        Toast.makeText(
                            this@SettingsActivity,
                            "Language pack imported: ${result.manifest.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is ImportResult.Error -> {
                        languagePackImportStatus = "Error: ${result.message}"
                        Toast.makeText(
                            this@SettingsActivity,
                            "Import failed: ${result.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                languagePackImportStatus = "Error: ${e.message}"
                Toast.makeText(this@SettingsActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteLanguagePack(code: String) {
        lifecycleScope.launch {
            try {
                val manager = LanguagePackManager.getInstance(this@SettingsActivity)
                if (manager.deletePack(code)) {
                    refreshInstalledLanguagePacks()
                    refreshAvailableSecondaryLanguages()
                    Toast.makeText(this@SettingsActivity, "Language pack deleted", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshInstalledLanguagePacks() {
        try {
            val manager = LanguagePackManager.getInstance(this)
            installedLanguagePacks = manager.getInstalledPacks()
        } catch (e: Exception) {
            installedLanguagePacks = emptyList()
        }
    }

    // SAF callback functions that perform actual export/import
    private fun performConfigExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                val backupManager = BackupRestoreManager(this@SettingsActivity)
                backupManager.exportConfig(uri, prefs)
                Toast.makeText(this@SettingsActivity, "Config exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performConfigImport(uri: Uri) {
        lifecycleScope.launch {
            try {
                val backupManager = BackupRestoreManager(this@SettingsActivity)
                val result = backupManager.importConfig(uri, prefs)

                // Reload settings to reflect imported values
                loadCurrentSettings()

                Toast.makeText(
                    this@SettingsActivity,
                    "Config imported: ${result.importedCount} settings imported, ${result.skippedCount} skipped",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performDictionaryExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                val backupManager = BackupRestoreManager(this@SettingsActivity)
                backupManager.exportDictionaries(uri)
                Toast.makeText(this@SettingsActivity, "Dictionary exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performDictionaryImport(uri: Uri) {
        lifecycleScope.launch {
            try {
                val backupManager = BackupRestoreManager(this@SettingsActivity)
                val result = backupManager.importDictionaries(uri)

                Toast.makeText(
                    this@SettingsActivity,
                    "Dictionary imported: ${result.userWordsImported} words, ${result.disabledWordsImported} disabled",
                    Toast.LENGTH_LONG
                ).show()

                // Broadcast the change so Dictionary Manager can refresh
                if (result.userWordsImported > 0 || result.disabledWordsImported > 0) {
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this@SettingsActivity)
                        .sendBroadcast(Intent(BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED))
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performClipboardExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                val backupManager = BackupRestoreManager(this@SettingsActivity)
                val result = backupManager.exportClipboardHistory(uri)
                Toast.makeText(this@SettingsActivity, "Clipboard exported: ${result.exportedCount} entries", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performClipboardImport(uri: Uri) {
        lifecycleScope.launch {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonText = inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(jsonText)

                    val clipboardDb = ClipboardDatabase.getInstance(this@SettingsActivity)
                    // result = [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped]
                    val result = clipboardDb.importFromJSON(json)
                    val imported = result[0] + result[1] + result[2]  // active + pinned + todo
                    val duplicates = result[3]

                    Toast.makeText(
                        this@SettingsActivity,
                        "Clipboard imported: $imported entries ($duplicates duplicates skipped)",
                        Toast.LENGTH_LONG
                    ).show()
                } ?: throw Exception("Could not open file")
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openGitHubReleases() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://github.com/tribixbite/cleverkeys/releases")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAllPrivacyData() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear All Data")
            .setMessage("This will delete all collected data including:\n\n" +
                "• Swipe patterns\n" +
                "• Performance metrics\n" +
                "• Error logs\n" +
                "• Learned word frequencies\n\n" +
                "This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch {
                    try {
                        // Clear privacy-related files
                        val privacyDir = java.io.File(filesDir, "privacy_data")
                        if (privacyDir.exists()) {
                            privacyDir.deleteRecursively()
                        }

                        // Clear learned frequencies from prefs
                        val editor = prefs.edit()
                        prefs.all.keys.filter { it.startsWith("learned_") || it.startsWith("freq_") }.forEach {
                            editor.remove(it)
                        }
                        editor.apply()

                        Toast.makeText(this@SettingsActivity, "All collected data cleared", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "Error clearing data: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * View collected swipe data in a dialog with pagination
     */
    private fun viewCollectedData() {
        collectedDataSearchQuery = ""
        collectedDataCurrentPage = 0
        loadCollectedDataPage()
        showCollectedDataViewer = true
    }

    /**
     * Load a page of collected data based on current search/pagination state
     */
    private fun loadCollectedDataPage() {
        lifecycleScope.launch {
            try {
                val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(this@SettingsActivity)
                val offset = collectedDataCurrentPage * collectedDataPageSize

                // Get total count for pagination
                collectedDataTotalCount = dataStore.countSearchResults(collectedDataSearchQuery)

                // Load page data
                collectedDataList = if (collectedDataSearchQuery.isEmpty()) {
                    dataStore.loadPaginatedData(collectedDataPageSize, offset)
                } else {
                    dataStore.searchByWord(collectedDataSearchQuery, collectedDataPageSize, offset)
                }

                // Update stats (only on first load)
                if (collectedDataStats == null) {
                    collectedDataStats = dataStore.getStatistics()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Error loading data: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * View performance statistics in a dialog
     */
    private fun viewPerfStats() {
        try {
            val stats = NeuralPerformanceStats.getInstance(this)
            perfStatsSummary = stats.formatSummary()
            showPerfStatsViewer = true
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading stats: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Export performance statistics to JSON file
     */
    private fun exportPerfStats() {
        try {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            val filename = "perf_stats_${sdf.format(java.util.Date())}.json"
            perfStatsExportLauncher.launch(filename)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Delete all collected swipe data with confirmation
     */
    private fun deleteCollectedData() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete Collected Data")
            .setMessage("This will permanently delete all collected swipe data.\n\n" +
                "This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(this@SettingsActivity)
                        dataStore.clearAllData()
                        Toast.makeText(this@SettingsActivity, "All swipe data deleted", Toast.LENGTH_SHORT).show()
                        // Force UI refresh by recreating activity
                        recreate()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "Error deleting data: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportSwipeDataJSON() {
        try {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            val filename = "swipe_data_${sdf.format(java.util.Date())}.json"
            swipeDataJsonExportLauncher.launch(filename)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportSwipeDataNDJSON() {
        try {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            val filename = "swipe_data_${sdf.format(java.util.Date())}.ndjson"
            swipeDataNdjsonExportLauncher.launch(filename)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSwipeDataJsonExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(this@SettingsActivity)
                    val count = dataStore.exportToJSON(outputStream)
                    Toast.makeText(
                        this@SettingsActivity,
                        "Exported $count swipe entries to JSON",
                        Toast.LENGTH_SHORT
                    ).show()
                } ?: throw Exception("Could not open file for writing")
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsActivity,
                    "Export failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun performSwipeDataNdjsonExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val dataStore = tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(this@SettingsActivity)
                    val count = dataStore.exportToNDJSON(outputStream)
                    Toast.makeText(
                        this@SettingsActivity,
                        "Exported $count swipe entries to NDJSON",
                        Toast.LENGTH_SHORT
                    ).show()
                } ?: throw Exception("Could not open file for writing")
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsActivity,
                    "Export failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun performPerfStatsExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val stats = NeuralPerformanceStats.getInstance(this@SettingsActivity)
                    val json = org.json.JSONObject().apply {
                        put("export_timestamp", System.currentTimeMillis())
                        put("total_predictions", stats.getTotalPredictions())
                        put("total_inference_time_ms", stats.getTotalInferenceTime())
                        put("average_inference_time_ms", stats.getAverageInferenceTime())
                        put("total_selections", stats.getTotalSelections())
                        put("top1_selections", stats.getTop1Selections())
                        put("top3_selections", stats.getTop3Selections())
                        put("top1_accuracy_pct", stats.getTop1Accuracy())
                        put("top3_accuracy_pct", stats.getTop3Accuracy())
                        put("model_load_time_ms", stats.getModelLoadTime())
                        put("days_tracked", stats.getDaysSinceStart())
                        put("first_stat_timestamp", stats.getFirstStatTimestamp())
                    }
                    java.io.OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(json.toString(2))
                    }
                    Toast.makeText(
                        this@SettingsActivity,
                        "Performance stats exported",
                        Toast.LENGTH_SHORT
                    ).show()
                } ?: throw Exception("Could not open file for writing")
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsActivity,
                    "Export failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
