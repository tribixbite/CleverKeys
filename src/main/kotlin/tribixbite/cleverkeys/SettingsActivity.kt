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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    // Configuration state
    private lateinit var config: Config
    private lateinit var prefs: SharedPreferences

    // APK file picker launcher
    private val apkPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedApk(it) }
    }

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

    // Settings state for reactive UI
    private var neuralPredictionEnabled by mutableStateOf(true)
    private var beamWidth by mutableStateOf(8)
    private var maxLength by mutableStateOf(35)
    private var confidenceThreshold by mutableStateOf(0.1f)
    private var currentThemeName by mutableStateOf("jewel")  // Store theme name string for full theme support
    private var keyboardHeight by mutableStateOf(35)
    private var keyboardHeightLandscape by mutableStateOf(50)
    private var vibrationEnabled by mutableStateOf(false)
    private var debugEnabled by mutableStateOf(false)
    private var clipboardHistoryEnabled by mutableStateOf(true)
    private var clipboardHistoryLimit by mutableStateOf(6)
    private var clipboardPaneHeightPercent by mutableStateOf(30)
    private var clipboardMaxItemSizeKb by mutableStateOf(500)
    private var clipboardLimitType by mutableStateOf("count") // "count" or "size"
    private var clipboardSizeLimitMb by mutableStateOf(10)
    private var autoCapitalizationEnabled by mutableStateOf(true)

    // Phase 1: Expose existing Config.kt settings
    private var swipeTypingEnabled by mutableStateOf(true)  // Master switch for swipe typing (default ON for CleverKeys)
    private var wordPredictionEnabled by mutableStateOf(false)
    private var suggestionBarOpacity by mutableStateOf(90)
    private var autoCorrectEnabled by mutableStateOf(true)
    private var termuxModeEnabled by mutableStateOf(false)
    private var vibrationDuration by mutableStateOf(20)
    private var swipeDebugEnabled by mutableStateOf(false)

    // Adaptive layout settings (for feature parity)
    private var marginBottomPortrait by mutableStateOf(7)
    private var marginBottomLandscape by mutableStateOf(3)
    private var horizontalMarginPortrait by mutableStateOf(3)
    private var horizontalMarginLandscape by mutableStateOf(28)

    // Gesture sensitivity settings
    private var swipeDistance by mutableStateOf(15)
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
    private var doubleSpaceThreshold by mutableStateOf(500) // ms
    private var swipeMinDistance by mutableStateOf(50f) // pixels
    private var swipeMinKeyDistance by mutableStateOf(40f) // pixels
    private var swipeMinDwellTime by mutableStateOf(10) // ms
    private var swipeNoiseThreshold by mutableStateOf(2.0f) // pixels
    private var swipeHighVelocityThreshold by mutableStateOf(1000f) // px/sec
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
    private var autocorrectPrefixLength by mutableStateOf(2)
    private var autocorrectMaxBeamCandidates by mutableStateOf(3)
    private var swipePredictionSource by mutableStateOf(60)
    private var swipeCommonWordsBoost by mutableStateOf(1.3f)
    private var swipeTop5000Boost by mutableStateOf(1.0f)
    private var swipeRareWordsPenalty by mutableStateOf(0.75f)

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
    private var neuralBeamAlpha by mutableStateOf(1.2f)
    private var neuralBeamPruneConfidence by mutableStateOf(0.8f)
    private var neuralBeamScoreGap by mutableStateOf(5.0f)

    // Neural model config settings
    private var neuralModelVersion by mutableStateOf("v2")
    private var neuralUseQuantized by mutableStateOf(false)
    private var neuralResamplingMode by mutableStateOf("discard")
    private var neuralUserMaxSeqLength by mutableStateOf(0)

    // Multi-language settings
    private var multiLangEnabled by mutableStateOf(false)
    private var primaryLanguage by mutableStateOf("en")
    private var autoDetectLanguage by mutableStateOf(true)
    private var languageDetectionSensitivity by mutableStateOf(0.6f)

    // Privacy settings
    private var privacyCollectSwipe by mutableStateOf(true)
    private var privacyCollectPerformance by mutableStateOf(true)
    private var privacyCollectErrors by mutableStateOf(false)
    private var privacyAnonymize by mutableStateOf(true)
    private var privacyLocalOnly by mutableStateOf(true)
    private var privacyRetentionDays by mutableStateOf(90)
    private var privacyAutoDelete by mutableStateOf(true)

    // Short gesture settings
    private var shortGesturesEnabled by mutableStateOf(true)
    private var shortGestureMinDistance by mutableStateOf(40) // Consistent with Config.kt default
    private var shortGestureMaxDistance by mutableStateOf(150) // 150 = disabled (rely on key boundary)

    // Swipe debug advanced settings
    private var swipeDebugDetailedLogging by mutableStateOf(false)
    private var swipeDebugShowRawOutput by mutableStateOf(true)
    private var swipeShowRawBeamPredictions by mutableStateOf(false)

    // Section expanded states
    private var wordPredictionAdvancedExpanded by mutableStateOf(false)
    private var neuralAdvancedExpanded by mutableStateOf(false)
    private var multiLangSectionExpanded by mutableStateOf(false)
    private var privacySectionExpanded by mutableStateOf(false)
    private var neuralSectionExpanded by mutableStateOf(true)
    private var appearanceSectionExpanded by mutableStateOf(true)
    private var swipeTrailSectionExpanded by mutableStateOf(false)
    private var inputSectionExpanded by mutableStateOf(false)
    private var swipeCorrectionsSectionExpanded by mutableStateOf(false)
    private var gestureTuningSectionExpanded by mutableStateOf(false)
    private var accessibilitySectionExpanded by mutableStateOf(false)
    private var dictionarySectionExpanded by mutableStateOf(false)
    private var clipboardSectionExpanded by mutableStateOf(false)
    private var backupRestoreSectionExpanded by mutableStateOf(false)
    private var advancedSectionExpanded by mutableStateOf(false)
    private var infoSectionExpanded by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                KeyboardTheme(darkTheme = true) {
                    SettingsScreen()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting up Compose UI", e)
            // Fallback to XML-based settings if Compose fails
            useLegacySettingsUI()
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
                swipeTypingEnabled = prefs.getBoolean(key, false)
            }
            "neural_prediction_enabled" -> {
                neuralPredictionEnabled = prefs.getBoolean(key, true)
            }
            "neural_beam_width" -> {
                beamWidth = prefs.getInt(key, 8)
            }
            "neural_max_length" -> {
                maxLength = prefs.getInt(key, 35)
            }
            "neural_confidence_threshold" -> {
                confidenceThreshold = prefs.getFloat(key, 0.1f)
            }
            "theme" -> {
                currentThemeName = prefs.getSafeString(key, "jewel")
            }
            "keyboard_height_percent" -> {
                keyboardHeight = prefs.getInt(key, 35)
            }
            "vibration_enabled" -> {
                vibrationEnabled = prefs.getBoolean(key, false)
            }
            "debug_enabled" -> {
                debugEnabled = prefs.getBoolean(key, false)
                Logs.setDebugEnabled(debugEnabled)
            }
            "clipboard_history_enabled" -> {
                clipboardHistoryEnabled = prefs.getBoolean(key, true)
            }
            "auto_capitalization_enabled" -> {
                autoCapitalizationEnabled = prefs.getBoolean(key, true)
            }
            "sticky_keys_enabled" -> {
                stickyKeysEnabled = prefs.getBoolean(key, false)
            }
            "sticky_keys_timeout_ms" -> {
                stickyKeysTimeout = prefs.getInt(key, 5000)
            }
            "voice_guidance_enabled" -> {
                voiceGuidanceEnabled = prefs.getBoolean(key, false)
            }
            // Adaptive layout settings
            "keyboard_height_landscape" -> {
                keyboardHeightLandscape = prefs.getInt(key, 50)
            }
            "margin_bottom_portrait" -> {
                marginBottomPortrait = prefs.getInt(key, 7)
            }
            "margin_bottom_landscape" -> {
                marginBottomLandscape = prefs.getInt(key, 3)
            }
            "horizontal_margin_portrait" -> {
                horizontalMarginPortrait = prefs.getInt(key, 3)
            }
            "horizontal_margin_landscape" -> {
                horizontalMarginLandscape = prefs.getInt(key, 28)
            }
            // Gesture sensitivity settings
            "swipe_dist" -> {
                swipeDistance = prefs.getSafeString(key, "15").toIntOrNull() ?: 15
            }
            "circle_sensitivity" -> {
                circleSensitivity = prefs.getSafeString(key, "2").toIntOrNull() ?: 2
            }
            // Long press settings
            "longpress_timeout" -> {
                longPressTimeout = prefs.getInt(key, 600)
            }
            "longpress_interval" -> {
                longPressInterval = prefs.getInt(key, 65)
            }
            "keyrepeat_enabled" -> {
                keyRepeatEnabled = prefs.getBoolean(key, true)
            }
            // Visual customization settings
            "label_brightness" -> {
                labelBrightness = prefs.getInt(key, 100)
            }
            "keyboard_opacity" -> {
                keyboardOpacity = prefs.getInt(key, 100)
            }
            "key_opacity" -> {
                keyOpacity = prefs.getInt(key, 100)
            }
            "key_activated_opacity" -> {
                keyActivatedOpacity = prefs.getInt(key, 100)
            }
            // Spacing and sizing settings
            "character_size" -> {
                characterSize = (prefs.getFloat(key, 1.15f) * 100).toInt()
            }
            "key_vertical_margin" -> {
                keyVerticalMargin = (prefs.getFloat(key, 1.5f) * 100).toInt()
            }
            "key_horizontal_margin" -> {
                keyHorizontalMargin = (prefs.getFloat(key, 2.0f) * 100).toInt()
            }
            // Border customization settings
            "border_config" -> {
                borderConfigEnabled = prefs.getBoolean(key, false)
            }
            "custom_border_radius" -> {
                customBorderRadius = prefs.getInt(key, 0)
            }
            "custom_border_line_width" -> {
                customBorderLineWidth = prefs.getInt(key, 0)
            }
            // Behavior settings
            "lock_double_tap" -> {
                doubleTapLockShift = prefs.getBoolean(key, false)
            }
            "switch_input_immediate" -> {
                switchInputImmediate = prefs.getBoolean(key, false)
            }
            // Number row and numpad settings
            "number_row" -> {
                numberRowMode = prefs.getSafeString(key, "no_number_row")
            }
            "show_numpad" -> {
                showNumpadMode = prefs.getSafeString(key, "never")
            }
            "numpad_layout" -> {
                numpadLayout = prefs.getSafeString(key, "default")
            }
            "pin_entry_enabled" -> {
                pinEntryEnabled = prefs.getBoolean(key, false)
            }
            // Phase 1: Exposed Config.kt settings listeners
            "word_prediction_enabled" -> {
                wordPredictionEnabled = prefs.getBoolean(key, false)
            }
            "suggestion_bar_opacity" -> {
                suggestionBarOpacity = Config.safeGetInt(prefs, key, 90)
            }
            "autocorrect_enabled" -> {
                autoCorrectEnabled = prefs.getBoolean(key, true)
            }
            "termux_mode_enabled" -> {
                termuxModeEnabled = prefs.getBoolean(key, false)
            }
            "vibrate_duration" -> {
                vibrationDuration = prefs.getInt(key, 20)
            }
            "swipe_show_debug_scores" -> {
                swipeDebugEnabled = prefs.getBoolean(key, false)
            }
            // Phase 5: Gesture settings listeners
            "slider_sensitivity" -> {
                sliderSensitivity = prefs.getSafeString(key, "30").toIntOrNull() ?: 30
            }
            // Swipe Corrections settings
            "swipe_beam_autocorrect_enabled" -> {
                swipeBeamAutocorrectEnabled = prefs.getBoolean(key, true)
            }
            "swipe_final_autocorrect_enabled" -> {
                swipeFinalAutocorrectEnabled = prefs.getBoolean(key, true)
            }
            "swipe_correction_preset" -> {
                swipeCorrectionPreset = prefs.getSafeString(key, "balanced")
            }
            "swipe_fuzzy_match_mode" -> {
                swipeFuzzyMatchMode = prefs.getSafeString(key, "edit_distance")
            }
            "autocorrect_max_length_diff" -> {
                autocorrectMaxLengthDiff = Config.safeGetInt(prefs, key, 2)
            }
            "autocorrect_prefix_length" -> {
                autocorrectPrefixLength = Config.safeGetInt(prefs, key, 2)
            }
            "autocorrect_max_beam_candidates" -> {
                autocorrectMaxBeamCandidates = Config.safeGetInt(prefs, key, 3)
            }
            "swipe_prediction_source" -> {
                swipePredictionSource = Config.safeGetInt(prefs, key, 60)
            }
            "swipe_common_words_boost" -> {
                swipeCommonWordsBoost = Config.safeGetFloat(prefs, key, 1.3f)
            }
            "swipe_top5000_boost" -> {
                swipeTop5000Boost = Config.safeGetFloat(prefs, key, 1.0f)
            }
            "swipe_rare_words_penalty" -> {
                swipeRareWordsPenalty = Config.safeGetFloat(prefs, key, 0.75f)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen() {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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

            // Neural Prediction Section (Collapsible, default expanded)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_neural),
                expanded = neuralSectionExpanded,
                onExpandChange = { neuralSectionExpanded = it }
            ) {
                // Master switch for swipe typing
                SettingsSwitch(
                    title = "Enable Swipe Typing",
                    description = "Swipe across keys to type words. Required for neural prediction.",
                    checked = swipeTypingEnabled,
                    onCheckedChange = {
                        swipeTypingEnabled = it
                        saveSetting("swipe_typing_enabled", it)
                    }
                )

                if (swipeTypingEnabled) {
                    SettingsSwitch(
                        title = stringResource(R.string.settings_neural_enable_title),
                        description = stringResource(R.string.settings_neural_enable_desc),
                        checked = neuralPredictionEnabled,
                        onCheckedChange = {
                            neuralPredictionEnabled = it
                            saveSetting("neural_prediction_enabled", it)
                        }
                    )
                }

                if (swipeTypingEnabled && neuralPredictionEnabled) {
                    SettingsSlider(
                        title = stringResource(R.string.settings_neural_beam_width_title),
                        description = stringResource(R.string.settings_neural_beam_width_desc),
                        value = beamWidth.toFloat(),
                        valueRange = 1f..32f,
                        steps = 31,
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
                        valueRange = 10f..50f,
                        steps = 40,
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
                        valueRange = 0.0f..1.0f,
                        steps = 100,
                        onValueChange = {
                            confidenceThreshold = it
                            saveSetting("neural_confidence_threshold", confidenceThreshold)
                        },
                        displayValue = "%.3f".format(confidenceThreshold)
                    )

                    // Phase 1: Termux Mode Toggle
                    SettingsSwitch(
                        title = "Terminal Mode",
                        description = "Show Ctrl, Meta, PageUp/Down keys. OFF for standard phone layout",
                        checked = termuxModeEnabled,
                        onCheckedChange = {
                            termuxModeEnabled = it
                            saveSetting("termux_mode_enabled", it)
                        }
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
                                description = "Fastest speed, but lower accuracy",
                                checked = neuralGreedySearch,
                                onCheckedChange = {
                                    neuralGreedySearch = it
                                    saveSetting("neural_greedy_search", it)
                                }
                            )

                            SettingsSlider(
                                title = "Length Normalization",
                                description = "Reward longer words (alpha). Higher = prefer longer words",
                                value = neuralBeamAlpha,
                                valueRange = 0.0f..5.0f,
                                steps = 50,
                                onValueChange = {
                                    neuralBeamAlpha = it
                                    saveSetting("neural_beam_alpha", neuralBeamAlpha)
                                },
                                displayValue = "%.1f".format(neuralBeamAlpha)
                            )

                            SettingsSlider(
                                title = "Pruning Confidence",
                                description = "Min confidence to reduce beam width (0.0-1.0)",
                                value = neuralBeamPruneConfidence,
                                valueRange = 0.0f..1.0f,
                                steps = 20,
                                onValueChange = {
                                    neuralBeamPruneConfidence = it
                                    saveSetting("neural_beam_prune_confidence", neuralBeamPruneConfidence)
                                },
                                displayValue = "%.2f".format(neuralBeamPruneConfidence)
                            )

                            SettingsSlider(
                                title = "Early Stop Gap",
                                description = "Score difference to stop early (higher = search longer)",
                                value = neuralBeamScoreGap,
                                valueRange = 0.0f..20.0f,
                                steps = 40,
                                onValueChange = {
                                    neuralBeamScoreGap = it
                                    saveSetting("neural_beam_score_gap", neuralBeamScoreGap)
                                },
                                displayValue = "%.1f".format(neuralBeamScoreGap)
                            )

                            // Model Config
                            Text(
                                text = "Model Configuration",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                            )

                            SettingsDropdown(
                                title = "Model Version",
                                description = "Neural model to use for predictions",
                                options = listOf("v1 (Legacy)", "v2 (Current)", "v3 (Experimental)"),
                                selectedIndex = when (neuralModelVersion) {
                                    "v1" -> 0
                                    "v2" -> 1
                                    "v3" -> 2
                                    else -> 1
                                },
                                onSelectionChange = { index ->
                                    neuralModelVersion = when (index) {
                                        0 -> "v1"
                                        1 -> "v2"
                                        2 -> "v3"
                                        else -> "v2"
                                    }
                                    saveSetting("neural_model_version", neuralModelVersion)
                                }
                            )

                            SettingsSwitch(
                                title = "Use Quantized Models",
                                description = "INT8 quantized (faster but less accurate: 73% vs 81%)",
                                checked = neuralUseQuantized,
                                onCheckedChange = {
                                    neuralUseQuantized = it
                                    saveSetting("neural_use_quantized", it)
                                }
                            )

                            SettingsDropdown(
                                title = "Trajectory Resampling",
                                description = "How to handle long swipe paths",
                                options = listOf("Discard Excess", "Interpolate", "Average"),
                                selectedIndex = when (neuralResamplingMode) {
                                    "discard" -> 0
                                    "interpolate" -> 1
                                    "average" -> 2
                                    else -> 0
                                },
                                onSelectionChange = { index ->
                                    neuralResamplingMode = when (index) {
                                        0 -> "discard"
                                        1 -> "interpolate"
                                        2 -> "average"
                                        else -> "discard"
                                    }
                                    saveSetting("neural_resampling_mode", neuralResamplingMode)
                                }
                            )

                            SettingsSlider(
                                title = "Max Sequence Length Override",
                                description = "Override model's max length (0 = use default)",
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
                                onClick = { openNeuralSettings() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Full Neural Settings Activity")
                            }
                        }
                    }
                }
            }

            // Appearance Section (Collapsible) - default expanded to show Theme Manager
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_appearance),
                expanded = appearanceSectionExpanded,
                onExpandChange = { appearanceSectionExpanded = it }
            ) {
                // Theme Manager Card - prominent access to full theme UI
                val themeContext = LocalContext.current
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable {
                            val intent = Intent(themeContext, ThemeSettingsActivity::class.java)
                            themeContext.startActivity(intent)
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
                        Text(
                            text = "🎨",
                            fontSize = 28.sp
                        )
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
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Theme dropdown removed - all theme selection now happens in Theme Manager
                // Use the Theme Manager card above to select themes

                SettingsSlider(
                    title = "Keyboard Height (Portrait)",
                    description = "Adjust keyboard height in portrait mode",
                    value = keyboardHeight.toFloat(),
                    valueRange = 20f..60f,
                    steps = 40,
                    onValueChange = {
                        keyboardHeight = it.toInt()
                        saveSetting("keyboard_height_percent", keyboardHeight)
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
                    description = "Vertical margin from bottom edge (portrait)",
                    value = marginBottomPortrait.toFloat(),
                    valueRange = 0f..30f,
                    steps = 30,
                    onValueChange = {
                        marginBottomPortrait = it.toInt()
                        saveSetting("margin_bottom_portrait", marginBottomPortrait)
                    },
                    displayValue = "${marginBottomPortrait}dp"
                )

                SettingsSlider(
                    title = "Bottom Margin (Landscape)",
                    description = "Vertical margin from bottom edge (landscape)",
                    value = marginBottomLandscape.toFloat(),
                    valueRange = 0f..30f,
                    steps = 30,
                    onValueChange = {
                        marginBottomLandscape = it.toInt()
                        saveSetting("margin_bottom_landscape", marginBottomLandscape)
                    },
                    displayValue = "${marginBottomLandscape}dp"
                )

                SettingsSlider(
                    title = "Horizontal Margin (Portrait)",
                    description = "Side margins in portrait mode",
                    value = horizontalMarginPortrait.toFloat(),
                    valueRange = 0f..50f,
                    steps = 50,
                    onValueChange = {
                        horizontalMarginPortrait = it.toInt()
                        saveSetting("horizontal_margin_portrait", horizontalMarginPortrait)
                    },
                    displayValue = "${horizontalMarginPortrait}dp"
                )

                SettingsSlider(
                    title = "Horizontal Margin (Landscape)",
                    description = "Side margins in landscape mode",
                    value = horizontalMarginLandscape.toFloat(),
                    valueRange = 0f..50f,
                    steps = 50,
                    onValueChange = {
                        horizontalMarginLandscape = it.toInt()
                        saveSetting("horizontal_margin_landscape", horizontalMarginLandscape)
                    },
                    displayValue = "${horizontalMarginLandscape}dp"
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
                title = "Swipe Trail",
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
                    title = "Enable Auto-Correction",
                    description = "Automatically correct misspelled words",
                    checked = autoCorrectEnabled,
                    onCheckedChange = {
                        autoCorrectEnabled = it
                        saveSetting("autocorrect_enabled", it)
                    }
                )

                if (autoCorrectEnabled) {
                    // Inline auto-correction settings instead of button
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
                        title = "Minimum Frequency",
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
                    title = stringResource(R.string.settings_clipboard_history_title),
                    description = stringResource(R.string.settings_clipboard_history_desc),
                    checked = clipboardHistoryEnabled,
                    onCheckedChange = {
                        clipboardHistoryEnabled = it
                        saveSetting("clipboard_history_enabled", it)
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

                // Short gesture settings
                SettingsSwitch(
                    title = "Short Gestures",
                    description = "Enable recognition of short swipes for quick words (it, is, at, etc.)",
                    checked = shortGesturesEnabled,
                    onCheckedChange = {
                        shortGesturesEnabled = it
                        saveSetting("short_gestures_enabled", it)
                    }
                )

                if (shortGesturesEnabled) {
                    SettingsSlider(
                        title = "Short Gesture Min Distance",
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
                        title = "Short Gesture Max Distance",
                        description = "Maximum swipe distance (% of key diagonal). 200% = disabled (use key boundary only)",
                        value = shortGestureMaxDistance.toFloat(),
                        valueRange = 50f..200f,
                        steps = 30,
                        onValueChange = {
                            shortGestureMaxDistance = it.toInt()
                            saveSetting("short_gesture_max_distance", shortGestureMaxDistance)
                        },
                        displayValue = if (shortGestureMaxDistance >= 200) "OFF" else "${shortGestureMaxDistance}%"
                    )

                    // Button to customize short swipe actions per key
                    Button(
                        onClick = { openShortSwipeCustomization() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Customize Short Swipes")
                    }
                }
            }

            // Swipe Corrections Section (NEW - migrated from XML Full settings)
            CollapsibleSettingsSection(
                title = "Swipe Corrections",
                expanded = swipeCorrectionsSectionExpanded,
                onExpandChange = { swipeCorrectionsSectionExpanded = it }
            ) {
                // Beam search autocorrect
                SettingsSwitch(
                    title = "Beam Autocorrect",
                    description = "Apply fuzzy corrections during beam search decoding",
                    checked = swipeBeamAutocorrectEnabled,
                    onCheckedChange = {
                        swipeBeamAutocorrectEnabled = it
                        saveSetting("swipe_beam_autocorrect_enabled", it)
                    }
                )

                // Final output autocorrect
                SettingsSwitch(
                    title = "Final Autocorrect",
                    description = "Apply dictionary-based corrections to final output",
                    checked = swipeFinalAutocorrectEnabled,
                    onCheckedChange = {
                        swipeFinalAutocorrectEnabled = it
                        saveSetting("swipe_final_autocorrect_enabled", it)
                    }
                )

                // Correction preset
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

                // Fuzzy match mode
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

                // Typo forgiveness (max length diff)
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

                // Starting letter accuracy
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

                // Correction search depth
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
                title = "Gesture Tuning",
                expanded = gestureTuningSectionExpanded,
                onExpandChange = { gestureTuningSectionExpanded = it }
            ) {
                Text(
                    text = "Fine-tune tap, swipe, and slider behavior for your typing style.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Short Swipe Customization Navigation Card
                val gestureContext = LocalContext.current
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable {
                            val intent = android.content.Intent(gestureContext, ShortSwipeCustomizationActivity::class.java)
                            gestureContext.startActivity(intent)
                        },
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
                        Text(
                            text = "👆",
                            fontSize = 28.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Short Swipe Customization",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Customize per-key short swipe gestures",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

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

                SettingsSlider(
                    title = "Double-Space to Period",
                    description = "Time between spaces to insert period (ms). Set 0 to disable.",
                    value = doubleSpaceThreshold.toFloat(),
                    valueRange = 0f..1000f,
                    steps = 20,
                    onValueChange = {
                        doubleSpaceThreshold = it.toInt()
                        saveSetting("double_space_threshold", doubleSpaceThreshold)
                    },
                    displayValue = "${doubleSpaceThreshold}ms"
                )

                // Swipe Recognition subsection
                Text(
                    text = "Swipe Recognition",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
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

            // Dictionary Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_dictionary),
                expanded = dictionarySectionExpanded,
                onExpandChange = { dictionarySectionExpanded = it }
            ) {
                Button(
                    onClick = { openDictionaryManager() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_dictionary_manage_button))
                }

                Text(
                    text = stringResource(R.string.settings_dictionary_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Clipboard Section (Collapsible)
            CollapsibleSettingsSection(
                title = "Clipboard",
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
                        description = "Maximum number of clipboard entries",
                        value = clipboardHistoryLimit.toFloat(),
                        valueRange = 1f..50f,
                        steps = 49,
                        onValueChange = {
                            clipboardHistoryLimit = it.toInt()
                            saveSetting("clipboard_history_limit", clipboardHistoryLimit)
                        },
                        displayValue = "$clipboardHistoryLimit items"
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
            }

            // Backup & Restore Section (Collapsible)
            CollapsibleSettingsSection(
                title = "Backup & Restore",
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
                title = "Multi-Language",
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
                    }
                )

                if (multiLangEnabled) {
                    SettingsDropdown(
                        title = "Primary Language",
                        description = "Main language for predictions",
                        options = listOf("English", "Spanish", "French", "German", "Portuguese", "Italian", "Russian", "Chinese", "Japanese", "Korean"),
                        selectedIndex = when (primaryLanguage) {
                            "en" -> 0
                            "es" -> 1
                            "fr" -> 2
                            "de" -> 3
                            "pt" -> 4
                            "it" -> 5
                            "ru" -> 6
                            "zh" -> 7
                            "ja" -> 8
                            "ko" -> 9
                            else -> 0
                        },
                        onSelectionChange = { index ->
                            primaryLanguage = when (index) {
                                0 -> "en"
                                1 -> "es"
                                2 -> "fr"
                                3 -> "de"
                                4 -> "pt"
                                5 -> "it"
                                6 -> "ru"
                                7 -> "zh"
                                8 -> "ja"
                                9 -> "ko"
                                else -> "en"
                            }
                            saveSetting("pref_primary_language", primaryLanguage)
                        }
                    )

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
                }
            }

            // Privacy Section (Collapsible)
            CollapsibleSettingsSection(
                title = "Privacy & Data",
                expanded = privacySectionExpanded,
                onExpandChange = { privacySectionExpanded = it }
            ) {
                Text(
                    text = "Control what data CleverKeys collects and how long it's stored. This can be used for on-device personalized model fine-tuning",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Data Collection",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSwitch(
                    title = "Swipe Pattern Data",
                    description = "Collect swipe trajectories to improve predictions",
                    checked = privacyCollectSwipe,
                    onCheckedChange = {
                        privacyCollectSwipe = it
                        saveSetting("privacy_collect_swipe", it)
                    }
                )

                SettingsSwitch(
                    title = "Performance Metrics",
                    description = "Collect timing data to optimize latency",
                    checked = privacyCollectPerformance,
                    onCheckedChange = {
                        privacyCollectPerformance = it
                        saveSetting("privacy_collect_performance", it)
                    }
                )

                SettingsSwitch(
                    title = "Error Reports",
                    description = "Collect crash logs to fix bugs",
                    checked = privacyCollectErrors,
                    onCheckedChange = {
                        privacyCollectErrors = it
                        saveSetting("privacy_collect_errors", it)
                    }
                )

                Text(
                    text = "Data Privacy",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                SettingsSwitch(
                    title = "Anonymize Data",
                    description = "Strip personal identifiers from collected data",
                    checked = privacyAnonymize,
                    onCheckedChange = {
                        privacyAnonymize = it
                        saveSetting("privacy_anonymize", it)
                    }
                )

                SettingsSwitch(
                    title = "Local Only",
                    description = "Keep all data on device (never upload)",
                    checked = privacyLocalOnly,
                    onCheckedChange = {
                        privacyLocalOnly = it
                        saveSetting("privacy_local_only", it)
                    }
                )

                Text(
                    text = "Data Retention",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                SettingsSlider(
                    title = "Retention Period",
                    description = "Days to keep collected data (7-365)",
                    value = privacyRetentionDays.toFloat(),
                    valueRange = 7f..365f,
                    steps = 35,
                    onValueChange = {
                        privacyRetentionDays = it.toInt()
                        saveSetting("privacy_retention_days", privacyRetentionDays)
                    },
                    displayValue = "$privacyRetentionDays days"
                )

                SettingsSwitch(
                    title = "Auto-Delete Old Data",
                    description = "Automatically delete data older than retention period",
                    checked = privacyAutoDelete,
                    onCheckedChange = {
                        privacyAutoDelete = it
                        saveSetting("privacy_auto_delete", it)
                    }
                )

                Button(
                    onClick = { clearAllPrivacyData() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear All Collected Data")
                }
            }

            // Advanced Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_advanced),
                expanded = advancedSectionExpanded,
                onExpandChange = { advancedSectionExpanded = it }
            ) {
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
                }

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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { resetAllSettings() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(stringResource(R.string.settings_reset_button))
                    }

                    Button(
                        onClick = { checkForUpdates() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_updates_button))
                    }
                }

                // Second row: Install Update with file picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { installUpdateFromDefault() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Install Update")
                    }

                    Button(
                        onClick = { showUpdateFilePicker() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("Browse APK...")
                    }
                }

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

    @Composable
    private fun SettingsSwitch(
        title: String,
        description: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 8.dp),
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
                    text = stringResource(R.string.settings_version_build, versionInfo.getProperty("build_number", "unknown")) + "\n" +
                           stringResource(R.string.settings_version_commit, versionInfo.getProperty("commit", "unknown")) + "\n" +
                           stringResource(R.string.settings_version_date, versionInfo.getProperty("build_date", "unknown")),
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
        swipeTypingEnabled = prefs.getSafeBoolean("swipe_typing_enabled", true)

        // Neural prediction settings
        neuralPredictionEnabled = prefs.getSafeBoolean("neural_prediction_enabled", true)
        beamWidth = prefs.getSafeInt("neural_beam_width", 8)
        maxLength = prefs.getSafeInt("neural_max_length", 35)
        confidenceThreshold = prefs.getSafeFloat("neural_confidence_threshold", 0.1f)

        // Appearance settings
        currentThemeName = prefs.getSafeString("theme", "jewel")
        keyboardHeight = prefs.getSafeInt("keyboard_height_percent", 35)
        keyboardHeightLandscape = prefs.getSafeInt("keyboard_height_landscape", 50)

        // Adaptive layout settings
        marginBottomPortrait = prefs.getSafeInt("margin_bottom_portrait", 7)
        marginBottomLandscape = prefs.getSafeInt("margin_bottom_landscape", 3)
        horizontalMarginPortrait = prefs.getSafeInt("horizontal_margin_portrait", 3)
        horizontalMarginLandscape = prefs.getSafeInt("horizontal_margin_landscape", 28)

        // Visual customization settings
        labelBrightness = prefs.getSafeInt("label_brightness", 100)
        keyboardOpacity = prefs.getSafeInt("keyboard_opacity", 100)
        keyOpacity = prefs.getSafeInt("key_opacity", 100)
        keyActivatedOpacity = prefs.getSafeInt("key_activated_opacity", 100)

        // Spacing and sizing settings
        characterSize = (prefs.getSafeFloat("character_size", 1.15f) * 100).toInt()
        keyVerticalMargin = (prefs.getSafeFloat("key_vertical_margin", 1.5f) * 100).toInt()
        keyHorizontalMargin = (prefs.getSafeFloat("key_horizontal_margin", 2.0f) * 100).toInt()

        // Border customization settings
        borderConfigEnabled = prefs.getSafeBoolean("border_config", false)
        customBorderRadius = prefs.getSafeInt("custom_border_radius", 0)
        customBorderLineWidth = prefs.getSafeInt("custom_border_line_width", 0)

        // Input behavior settings
        vibrationEnabled = prefs.getSafeBoolean("vibration_enabled", false)
        clipboardHistoryEnabled = prefs.getSafeBoolean("clipboard_history_enabled", true)
        clipboardHistoryLimit = prefs.getSafeString("clipboard_history_limit", "6").toIntOrNull() ?: 6
        clipboardPaneHeightPercent = Config.safeGetInt(prefs, "clipboard_pane_height_percent", 30).coerceIn(10, 50)
        clipboardMaxItemSizeKb = prefs.getSafeString("clipboard_max_item_size_kb", "500").toIntOrNull() ?: 500
        clipboardLimitType = prefs.getSafeString("clipboard_limit_type", "count")
        clipboardSizeLimitMb = prefs.getSafeString("clipboard_size_limit_mb", "10").toIntOrNull() ?: 10
        autoCapitalizationEnabled = prefs.getSafeBoolean("auto_capitalization_enabled", true)

        // Gesture sensitivity settings
        swipeDistance = prefs.getSafeString("swipe_dist", "15").toIntOrNull() ?: 15
        circleSensitivity = prefs.getSafeString("circle_sensitivity", "2").toIntOrNull() ?: 2
        sliderSensitivity = prefs.getSafeString("slider_sensitivity", "30").toIntOrNull() ?: 30

        // Long press settings
        longPressTimeout = prefs.getSafeInt("longpress_timeout", 600)
        longPressInterval = prefs.getSafeInt("longpress_interval", 65)
        keyRepeatEnabled = prefs.getSafeBoolean("keyrepeat_enabled", true)

        // Behavior settings
        doubleTapLockShift = prefs.getSafeBoolean("lock_double_tap", true)
        switchInputImmediate = prefs.getSafeBoolean("switch_input_immediate", false)
        smartPunctuationEnabled = prefs.getSafeBoolean("smart_punctuation", true)
        vibrateCustomEnabled = prefs.getSafeBoolean("vibrate_custom", false)
        numberEntryLayout = prefs.getSafeString("number_entry_layout", "pin")

        // Gesture tuning settings
        tapDurationThreshold = Config.safeGetInt(prefs, "tap_duration_threshold", 150)
        doubleSpaceThreshold = Config.safeGetInt(prefs, "double_space_threshold", 500)
        swipeMinDistance = Config.safeGetFloat(prefs, "swipe_min_distance", 50f)
        swipeMinKeyDistance = Config.safeGetFloat(prefs, "swipe_min_key_distance", 40f)
        swipeMinDwellTime = Config.safeGetInt(prefs, "swipe_min_dwell_time", 10)
        swipeNoiseThreshold = Config.safeGetFloat(prefs, "swipe_noise_threshold", 2.0f)
        swipeHighVelocityThreshold = Config.safeGetFloat(prefs, "swipe_high_velocity_threshold", 1000f)
        sliderSpeedSmoothing = Config.safeGetFloat(prefs, "slider_speed_smoothing", 0.7f)
        sliderSpeedMax = Config.safeGetFloat(prefs, "slider_speed_max", 4.0f)

        // Number row and numpad settings
        numberRowMode = prefs.getSafeString("number_row", "no_number_row")
        showNumpadMode = prefs.getSafeString("show_numpad", "never")
        numpadLayout = prefs.getSafeString("numpad_layout", "default")
        pinEntryEnabled = prefs.getSafeBoolean("pin_entry_enabled", false)

        // Advanced settings
        debugEnabled = prefs.getSafeBoolean("debug_enabled", false)

        // Accessibility settings
        stickyKeysEnabled = prefs.getSafeBoolean("sticky_keys_enabled", false)
        stickyKeysTimeout = prefs.getSafeInt("sticky_keys_timeout_ms", 5000)
        voiceGuidanceEnabled = prefs.getSafeBoolean("voice_guidance_enabled", false)

        // Phase 1: Load exposed Config.kt settings
        wordPredictionEnabled = prefs.getSafeBoolean("word_prediction_enabled", false)
        suggestionBarOpacity = Config.safeGetInt(prefs, "suggestion_bar_opacity", 90)
        autoCorrectEnabled = prefs.getSafeBoolean("autocorrect_enabled", true)
        termuxModeEnabled = prefs.getSafeBoolean("termux_mode_enabled", false)
        vibrationDuration = prefs.getSafeInt("vibrate_duration", 20)
        swipeDebugEnabled = prefs.getSafeBoolean("swipe_show_debug_scores", false)

        // Swipe Corrections settings
        swipeBeamAutocorrectEnabled = prefs.getSafeBoolean("swipe_beam_autocorrect_enabled", true)
        swipeFinalAutocorrectEnabled = prefs.getSafeBoolean("swipe_final_autocorrect_enabled", true)
        swipeCorrectionPreset = prefs.getSafeString("swipe_correction_preset", "balanced")
        swipeFuzzyMatchMode = prefs.getSafeString("swipe_fuzzy_match_mode", "edit_distance")
        autocorrectMaxLengthDiff = Config.safeGetInt(prefs, "autocorrect_max_length_diff", 2)
        autocorrectPrefixLength = Config.safeGetInt(prefs, "autocorrect_prefix_length", 2)
        autocorrectMaxBeamCandidates = Config.safeGetInt(prefs, "autocorrect_max_beam_candidates", 3)
        swipePredictionSource = Config.safeGetInt(prefs, "swipe_prediction_source", 60)
        swipeCommonWordsBoost = Config.safeGetFloat(prefs, "swipe_common_words_boost", 1.3f)
        swipeTop5000Boost = Config.safeGetFloat(prefs, "swipe_top5000_boost", 1.0f)
        swipeRareWordsPenalty = Config.safeGetFloat(prefs, "swipe_rare_words_penalty", 0.75f)

        // Swipe trail appearance settings
        swipeTrailEnabled = prefs.getSafeBoolean("swipe_trail_enabled", true)
        swipeTrailEffect = prefs.getSafeString("swipe_trail_effect", "glow")
        swipeTrailColor = prefs.getSafeInt("swipe_trail_color", 0xFF9B59B6.toInt())
        swipeTrailWidth = prefs.getSafeFloat("swipe_trail_width", 8.0f)
        swipeTrailGlowRadius = prefs.getSafeFloat("swipe_trail_glow_radius", 12.0f)

        // Word Prediction Advanced settings
        contextAwarePredictionsEnabled = prefs.getSafeBoolean("context_aware_predictions_enabled", true)
        personalizedLearningEnabled = prefs.getSafeBoolean("personalized_learning_enabled", true)
        learningAggression = prefs.getSafeString("learning_aggression", "BALANCED")
        predictionContextBoost = Config.safeGetFloat(prefs, "prediction_context_boost", 2.0f)
        predictionFrequencyScale = Config.safeGetFloat(prefs, "prediction_frequency_scale", 1000f)

        // Auto-correction advanced settings
        autocorrectMinWordLength = Config.safeGetInt(prefs, "autocorrect_min_word_length", 3)
        autocorrectCharMatchThreshold = Config.safeGetFloat(prefs, "autocorrect_char_match_threshold", 0.67f)
        autocorrectMinFrequency = Config.safeGetInt(prefs, "autocorrect_confidence_min_frequency", 500)

        // Neural beam search advanced settings
        neuralBatchBeams = prefs.getSafeBoolean("neural_batch_beams", false)
        neuralGreedySearch = prefs.getSafeBoolean("neural_greedy_search", false)
        neuralBeamAlpha = Config.safeGetFloat(prefs, "neural_beam_alpha", 1.2f)
        neuralBeamPruneConfidence = Config.safeGetFloat(prefs, "neural_beam_prune_confidence", 0.8f)
        neuralBeamScoreGap = Config.safeGetFloat(prefs, "neural_beam_score_gap", 5.0f)

        // Neural model config settings
        neuralModelVersion = prefs.getSafeString("neural_model_version", "v2")
        neuralUseQuantized = prefs.getSafeBoolean("neural_use_quantized", false)
        neuralResamplingMode = prefs.getSafeString("neural_resampling_mode", "discard")
        neuralUserMaxSeqLength = Config.safeGetInt(prefs, "neural_user_max_seq_length", 0)

        // Multi-language settings
        multiLangEnabled = prefs.getSafeBoolean("pref_enable_multilang", false)
        primaryLanguage = prefs.getSafeString("pref_primary_language", "en")
        autoDetectLanguage = prefs.getSafeBoolean("pref_auto_detect_language", true)
        languageDetectionSensitivity = Config.safeGetFloat(prefs, "pref_language_detection_sensitivity", 0.6f)

        // Privacy settings
        privacyCollectSwipe = prefs.getSafeBoolean("privacy_collect_swipe", true)
        privacyCollectPerformance = prefs.getSafeBoolean("privacy_collect_performance", true)
        privacyCollectErrors = prefs.getSafeBoolean("privacy_collect_errors", false)
        privacyAnonymize = prefs.getSafeBoolean("privacy_anonymize", true)
        privacyLocalOnly = prefs.getSafeBoolean("privacy_local_only", true)
        privacyRetentionDays = Config.safeGetInt(prefs, "privacy_retention_days", 90)
        privacyAutoDelete = prefs.getSafeBoolean("privacy_auto_delete", true)

        // Short gesture settings
        shortGesturesEnabled = prefs.getSafeBoolean("short_gestures_enabled", true)
        shortGestureMinDistance = Config.safeGetInt(prefs, "short_gesture_min_distance", 40)
        shortGestureMaxDistance = Config.safeGetInt(prefs, "short_gesture_max_distance", 200) // 200 = disabled

        // Swipe debug advanced settings
        swipeDebugDetailedLogging = prefs.getSafeBoolean("swipe_debug_detailed_logging", false)
        swipeDebugShowRawOutput = prefs.getSafeBoolean("swipe_debug_show_raw_output", true)
        swipeShowRawBeamPredictions = prefs.getSafeBoolean("swipe_show_raw_beam_predictions", false)
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

                android.util.Log.d(TAG, "Setting saved: $key = $value")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error saving setting: $key = $value", e)
                Toast.makeText(this@SettingsActivity,
                    getString(R.string.settings_toast_error_saving, e.message ?: ""),
                    Toast.LENGTH_SHORT).show()
            }
        }
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
            // Set default values
            props.setProperty("build_number", "dev")
            props.setProperty("commit", "unknown")
            props.setProperty("build_date", "unknown")
        }
        return props
    }

    private fun openNeuralSettings() {
        startActivity(Intent(this, NeuralSettingsActivity::class.java))
    }

    private fun openCalibration() {
        startActivity(Intent(this, SwipeCalibrationActivity::class.java))
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
                    // Reset all settings to safe defaults
                    val editor = prefs.edit()
                    editor.clear()

                    // Set essential defaults to prevent crashes
                    editor.putBoolean("neural_prediction_enabled", true)
                    editor.putInt("neural_beam_width", 8)
                    editor.putInt("neural_max_length", 35)
                    editor.putFloat("neural_confidence_threshold", 0.1f)
                    editor.putString("theme", "jewel")  // Default theme
                    editor.putInt("keyboard_height_percent", 35)
                    editor.putBoolean("vibration_enabled", false)
                    editor.putBoolean("debug_enabled", false)
                    editor.putBoolean("clipboard_history_enabled", true)
                    editor.putBoolean("auto_capitalization_enabled", true)
                    editor.putBoolean("sticky_keys_enabled", false)
                    editor.putInt("sticky_keys_timeout_ms", 5000)
                    editor.putBoolean("voice_guidance_enabled", false)
                    // Swipe corrections defaults
                    editor.putBoolean("swipe_beam_autocorrect_enabled", true)
                    editor.putBoolean("swipe_final_autocorrect_enabled", true)
                    editor.putString("swipe_correction_preset", "balanced")
                    editor.putString("swipe_fuzzy_match_mode", "edit_distance")

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

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                // Use proper Android storage APIs instead of hardcoded paths
                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val externalStorage = android.os.Environment.getExternalStorageDirectory()

                // Check for APK updates in common locations using proper APIs
                val possibleLocations = arrayOf(
                    File(downloadDir, "cleverkeys-debug.apk").absolutePath,
                    File(downloadDir, "tribixbite.cleverkeys.debug.apk").absolutePath,
                    File(externalStorage, "unexpected/debug-kb.apk").absolutePath,
                    File(externalStorage, "Download/cleverkeys-debug.apk").absolutePath,
                    "${getExternalFilesDir(null)?.absolutePath}/cleverkeys-debug.apk"
                )

                var updateApk: File? = null
                for (location in possibleLocations) {
                    val file = File(location)
                    if (file.exists() && file.canRead()) {
                        updateApk = file
                        android.util.Log.d(TAG, "Found update APK at: $location")
                        break
                    }
                }

                if (updateApk != null) {
                    showUpdateDialog(updateApk)
                } else {
                    showNoUpdateDialog()
                }

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking for updates", e)
                Toast.makeText(this@SettingsActivity,
                    getString(R.string.settings_toast_error_update, e.message ?: ""),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Install update from default location without prompting.
     * Uses proper Android storage APIs to check common update locations.
     */
    private fun installUpdateFromDefault() {
        val externalStorage = android.os.Environment.getExternalStorageDirectory()
        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )

        // Primary location (Unexpected-Keyboard compatible)
        val defaultPath = File(externalStorage, "unexpected/debug-kb.apk")
        if (defaultPath.exists() && defaultPath.canRead()) {
            installUpdate(defaultPath)
            return
        }

        // Fallback to other locations using proper APIs
        val possibleLocations = arrayOf(
            File(downloadDir, "cleverkeys-debug.apk").absolutePath,
            File(downloadDir, "tribixbite.cleverkeys.debug.apk").absolutePath,
            File(externalStorage, "Download/cleverkeys-debug.apk").absolutePath
        )

        for (location in possibleLocations) {
            val file = File(location)
            if (file.exists() && file.canRead()) {
                installUpdate(file)
                return
            }
        }

        // No update found - use proper path in message
        val expectedPath = File(externalStorage, "unexpected/debug-kb.apk").absolutePath
        Toast.makeText(
            this,
            "No update APK found at $expectedPath",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showUpdateDialog(apkFile: File) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_update_dialog_title))
            .setMessage(getString(R.string.settings_update_dialog_message, apkFile.name, apkFile.length() / 1024))
            .setPositiveButton(getString(R.string.settings_update_dialog_install)) { _, _ ->
                installUpdate(apkFile)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNoUpdateDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_no_update_dialog_title))
            .setMessage(getString(R.string.settings_no_update_dialog_message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun installUpdate(apkFile: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use FileProvider for Android 7.0+
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    apkFile
                )
            } else {
                android.net.Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            startActivity(intent)
            android.util.Log.d(TAG, "Launched installer for: ${apkFile.absolutePath}")

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error installing update", e)

            // Fallback: try to copy to accessible location
            try {
                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val publicApk = File(downloadDir, "cleverkeys-update.apk")
                apkFile.copyTo(publicApk, overwrite = true)

                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        android.net.Uri.fromFile(publicApk),
                        "application/vnd.android.package-archive"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(fallbackIntent)

                Toast.makeText(this,
                    getString(R.string.settings_toast_install_copied),
                    Toast.LENGTH_SHORT).show()

            } catch (fallbackError: Exception) {
                Toast.makeText(this,
                    getString(R.string.settings_toast_install_failed, e.message ?: ""),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fallbackEncrypted() {
        // Handle direct boot mode failure
        android.util.Log.w(TAG, "Settings unavailable in direct boot mode")
        finish()
    }

    /**
     * Fallback to legacy XML-based settings UI if Compose fails
     */
    private fun useLegacySettingsUI() {
        try {
            // Create simple scrollable settings UI with XML views
            val scrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Title
            val title = TextView(this).apply {
                text = getString(R.string.settings_legacy_title)
                textSize = 24f
                setPadding(0, 0, 0, 24)
                setTextColor(android.graphics.Color.WHITE)
            }
            layout.addView(title)

            // Neural prediction toggle
            val neuralSwitch = Switch(this).apply {
                text = getString(R.string.settings_legacy_neural_switch)
                isChecked = neuralPredictionEnabled
                setPadding(0, 16, 0, 16)
                setOnCheckedChangeListener { _, isChecked ->
                    neuralPredictionEnabled = isChecked
                    saveSetting("neural_prediction_enabled", isChecked)
                }
            }
            layout.addView(neuralSwitch)

            // Beam width setting
            val beamWidthLabel = TextView(this).apply {
                text = getString(R.string.settings_legacy_beam_width, beamWidth)
                setPadding(0, 16, 0, 8)
                setTextColor(android.graphics.Color.WHITE)
            }
            layout.addView(beamWidthLabel)

            val beamWidthSeekBar = SeekBar(this).apply {
                max = 31 // 1-32 range
                progress = beamWidth - 1
                setPadding(0, 0, 0, 16)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        beamWidth = progress + 1
                        beamWidthLabel.text = getString(R.string.settings_legacy_beam_width, beamWidth)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        saveSetting("neural_beam_width", beamWidth)
                    }
                })
            }
            layout.addView(beamWidthSeekBar)

            // Open advanced neural settings button
            val advancedButton = Button(this).apply {
                text = getString(R.string.settings_legacy_advanced_button)
                setOnClickListener {
                    openNeuralSettings()
                }
            }
            layout.addView(advancedButton)

            // Calibration button
            val calibrationButton = Button(this).apply {
                text = getString(R.string.settings_legacy_calibration_button)
                setOnClickListener {
                    openCalibration()
                }
            }
            layout.addView(calibrationButton)

            // System info
            val versionInfo = TextView(this).apply {
                text = getString(R.string.settings_legacy_version)
                setPadding(0, 32, 0, 0)
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            }
            layout.addView(versionInfo)

            scrollView.addView(layout)
            setContentView(scrollView)

            android.util.Log.i(TAG, "Legacy settings UI initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create legacy UI", e)
            // If even this fails, show error and close
            Toast.makeText(this, getString(R.string.settings_legacy_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            finish()
        }
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
                backupManager.exportClipboardHistory(uri)
                Toast.makeText(this@SettingsActivity, "Clipboard exported successfully", Toast.LENGTH_SHORT).show()
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
                    val result = clipboardDb.importFromJSON(json)
                    val imported = result[0]
                    val duplicates = result[1]

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
     * Show Android system file picker to select APK files.
     * Uses Storage Access Framework (SAF) for proper file browsing.
     */
    private fun showUpdateFilePicker() {
        try {
            // Launch system file picker for APK files
            apkPickerLauncher.launch("application/vnd.android.package-archive")
        } catch (e: Exception) {
            // Fallback: show message if file picker not available
            android.app.AlertDialog.Builder(this)
                .setTitle("File Picker Unavailable")
                .setMessage("Could not open file picker. You can:\n\n" +
                    "1. Download APK from GitHub releases\n" +
                    "2. Use a file manager to install manually")
                .setPositiveButton("Open GitHub") { _, _ -> openGitHubReleases() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Handle APK selected from file picker.
     */
    private fun handleSelectedApk(uri: Uri) {
        try {
            // Get file info from URI
            val cursor = contentResolver.query(uri, null, null, null, null)
            var fileName = "Unknown"
            var fileSize = 0L

            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = it.getString(nameIndex) ?: "Unknown"
                    if (sizeIndex >= 0) fileSize = it.getLong(sizeIndex)
                }
            }

            val sizeKb = fileSize / 1024

            android.app.AlertDialog.Builder(this)
                .setTitle("Install Update")
                .setMessage(
                    "File: $fileName\n" +
                    "Size: ${sizeKb}KB\n\n" +
                    "Install this APK?"
                )
                .setPositiveButton("Install") { _, _ -> installUpdateFromUri(uri) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Install APK from content URI.
     */
    private fun installUpdateFromUri(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not install: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showInstallConfirmation(apkFile: File) {
        val sizeKb = apkFile.length() / 1024
        android.app.AlertDialog.Builder(this)
            .setTitle("Install Update")
            .setMessage(
                "File: ${apkFile.name}\n" +
                "Size: ${sizeKb}KB\n" +
                "Path: ${apkFile.parent}\n\n" +
                "Install this APK?"
            )
            .setPositiveButton("Install") { _, _ -> installUpdate(apkFile) }
            .setNeutralButton("Copy Path") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("APK Path", apkFile.absolutePath)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
