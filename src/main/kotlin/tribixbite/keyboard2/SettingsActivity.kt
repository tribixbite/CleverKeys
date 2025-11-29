package tribixbite.keyboard2

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import tribixbite.keyboard2.theme.KeyboardTheme

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

    // Settings state for reactive UI
    private var neuralPredictionEnabled by mutableStateOf(true)
    private var beamWidth by mutableStateOf(8)
    private var maxLength by mutableStateOf(35)
    private var confidenceThreshold by mutableStateOf(0.1f)
    private var currentTheme by mutableStateOf(R.style.Dark)
    private var keyboardHeight by mutableStateOf(35)
    private var keyboardHeightLandscape by mutableStateOf(50)
    private var vibrationEnabled by mutableStateOf(false)
    private var debugEnabled by mutableStateOf(false)
    private var clipboardHistoryEnabled by mutableStateOf(true)
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

    // Number row and numpad settings
    private var numberRowMode by mutableStateOf("no_number_row") // "no_number_row", "no_symbols", "symbols"
    private var showNumpadMode by mutableStateOf("never") // "never", "landscape", "always"
    private var numpadLayout by mutableStateOf("default") // "default", "low_first"
    private var pinEntryEnabled by mutableStateOf(false)

    // Accessibility settings (Bug #373, #368, #377)
    private var stickyKeysEnabled by mutableStateOf(false)
    private var stickyKeysTimeout by mutableStateOf(5000) // milliseconds
    private var voiceGuidanceEnabled by mutableStateOf(false)

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
                currentTheme = prefs.getInt(key, R.style.Dark)
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
                swipeDistance = (prefs.getString(key, "15") ?: "15").toIntOrNull() ?: 15
            }
            "circle_sensitivity" -> {
                circleSensitivity = (prefs.getString(key, "2") ?: "2").toIntOrNull() ?: 2
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
                numberRowMode = prefs.getString(key, "no_number_row") ?: "no_number_row"
            }
            "show_numpad" -> {
                showNumpadMode = prefs.getString(key, "never") ?: "never"
            }
            "numpad_layout" -> {
                numpadLayout = prefs.getString(key, "default") ?: "default"
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
                sliderSensitivity = (prefs.getString(key, "30") ?: "30").toIntOrNull() ?: 30
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

            // Neural Prediction Section
            SettingsSection(stringResource(R.string.settings_section_neural)) {
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

                    Button(
                        onClick = { openNeuralSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_neural_advanced_button))
                    }
                }
            }

            // Appearance Section
            SettingsSection(stringResource(R.string.settings_section_appearance)) {
                SettingsDropdown(
                    title = stringResource(R.string.settings_theme_title),
                    description = stringResource(R.string.settings_theme_desc),
                    options = listOf(
                        stringResource(R.string.settings_theme_system),
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_dark),
                        stringResource(R.string.settings_theme_black)
                    ),
                    selectedIndex = getThemeIndex(currentTheme),
                    onSelectionChange = { index ->
                        val newTheme = getThemeFromIndex(index)
                        currentTheme = newTheme
                        saveSetting("theme", newTheme)
                    }
                )

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

            // Input Behavior Section
            SettingsSection(stringResource(R.string.settings_section_input)) {
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
                    Button(
                        onClick = { openAutoCorrectionSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Auto-Correction Settings")
                    }
                }

                SettingsSwitch(
                    title = stringResource(R.string.settings_auto_capitalization_title),
                    description = stringResource(R.string.settings_auto_capitalization_desc),
                    checked = autoCapitalizationEnabled,
                    onCheckedChange = {
                        autoCapitalizationEnabled = it
                        saveSetting("auto_capitalization_enabled", it)
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
            }

            // Accessibility Section (Fix for Bug #373, #368, #377)
            SettingsSection(stringResource(R.string.settings_section_accessibility)) {
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

            // Dictionary Section (Bug #472 fix)
            SettingsSection(stringResource(R.string.settings_section_dictionary)) {
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

            // Clipboard Section
            SettingsSection("Clipboard") {
                Button(
                    onClick = { openClipboardSettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clipboard Settings")
                }

                Text(
                    text = "Configure clipboard history, limits, and duration",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Backup & Restore Section (Phase 7)
            SettingsSection("Backup & Restore") {
                Button(
                    onClick = { openBackupRestore() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Backup & Restore Settings")
                }

                Text(
                    text = "Export and import keyboard configuration",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Advanced Section
            SettingsSection(stringResource(R.string.settings_section_advanced)) {
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

                Button(
                    onClick = { openCalibration() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_calibration_button))
                }
            }

            // Version and Actions Section
            SettingsSection(stringResource(R.string.settings_section_info)) {
                VersionInfoCard()

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
            }
        }
    }

    // Composable helper components
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

    private fun loadCurrentSettings() {
        // Swipe typing master switch
        swipeTypingEnabled = prefs.getBoolean("swipe_typing_enabled", true)

        // Neural prediction settings
        neuralPredictionEnabled = prefs.getBoolean("neural_prediction_enabled", true)
        beamWidth = prefs.getSafeInt("neural_beam_width", 8)
        maxLength = prefs.getSafeInt("neural_max_length", 35)
        confidenceThreshold = prefs.getSafeFloat("neural_confidence_threshold", 0.1f)

        // Appearance settings
        currentTheme = prefs.getSafeInt("theme", R.style.Dark)
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
        borderConfigEnabled = prefs.getBoolean("border_config", false)
        customBorderRadius = prefs.getSafeInt("custom_border_radius", 0)
        customBorderLineWidth = prefs.getSafeInt("custom_border_line_width", 0)

        // Input behavior settings
        vibrationEnabled = prefs.getBoolean("vibration_enabled", false)
        clipboardHistoryEnabled = prefs.getBoolean("clipboard_history_enabled", true)
        autoCapitalizationEnabled = prefs.getBoolean("auto_capitalization_enabled", true)

        // Gesture sensitivity settings
        swipeDistance = (prefs.getString("swipe_dist", "15") ?: "15").toIntOrNull() ?: 15
        circleSensitivity = (prefs.getString("circle_sensitivity", "2") ?: "2").toIntOrNull() ?: 2
        sliderSensitivity = (prefs.getString("slider_sensitivity", "30") ?: "30").toIntOrNull() ?: 30

        // Long press settings
        longPressTimeout = prefs.getSafeInt("longpress_timeout", 600)
        longPressInterval = prefs.getSafeInt("longpress_interval", 65)
        keyRepeatEnabled = prefs.getBoolean("keyrepeat_enabled", true)

        // Behavior settings
        doubleTapLockShift = prefs.getBoolean("lock_double_tap", false)
        switchInputImmediate = prefs.getBoolean("switch_input_immediate", false)

        // Number row and numpad settings
        numberRowMode = prefs.getString("number_row", "no_number_row") ?: "no_number_row"
        showNumpadMode = prefs.getString("show_numpad", "never") ?: "never"
        numpadLayout = prefs.getString("numpad_layout", "default") ?: "default"
        pinEntryEnabled = prefs.getBoolean("pin_entry_enabled", false)

        // Advanced settings
        debugEnabled = prefs.getBoolean("debug_enabled", false)

        // Accessibility settings
        stickyKeysEnabled = prefs.getBoolean("sticky_keys_enabled", false)
        stickyKeysTimeout = prefs.getSafeInt("sticky_keys_timeout_ms", 5000)
        voiceGuidanceEnabled = prefs.getBoolean("voice_guidance_enabled", false)

        // Phase 1: Load exposed Config.kt settings
        wordPredictionEnabled = prefs.getBoolean("word_prediction_enabled", false)
        suggestionBarOpacity = Config.safeGetInt(prefs, "suggestion_bar_opacity", 90)
        autoCorrectEnabled = prefs.getBoolean("autocorrect_enabled", true)
        termuxModeEnabled = prefs.getBoolean("termux_mode_enabled", false)
        vibrationDuration = prefs.getSafeInt("vibrate_duration", 20)
        swipeDebugEnabled = prefs.getBoolean("swipe_show_debug_scores", false)
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
        config.apply {
            theme = currentTheme
            keyboardHeightPercent = keyboardHeight
            vibrate_custom = vibrationEnabled
            neural_beam_width = beamWidth
            neural_max_length = maxLength
            neural_confidence_threshold = confidenceThreshold
        }
    }

    private fun getThemeIndex(theme: Int): Int {
        return when (theme) {
            R.style.Light -> 1
            R.style.Dark -> 2
            R.style.Black -> 3
            else -> 2 // Default to Dark
        }
    }

    private fun getThemeFromIndex(index: Int): Int {
        return when (index) {
            1 -> R.style.Light
            2 -> R.style.Dark
            3 -> R.style.Black
            else -> R.style.Dark
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
        startActivity(Intent(this, DictionaryManagerActivity::class.java))
    }

    private fun openLayoutManager() {
        startActivity(Intent(this, LayoutManagerActivity::class.java))
    }

    private fun openExtraKeysConfig() {
        startActivity(Intent(this, ExtraKeysConfigActivity::class.java))
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
                    editor.putInt("theme", R.style.Dark)
                    editor.putInt("keyboard_height_percent", 35)
                    editor.putBoolean("vibration_enabled", false)
                    editor.putBoolean("debug_enabled", false)
                    editor.putBoolean("clipboard_history_enabled", true)
                    editor.putBoolean("auto_capitalization_enabled", true)
                    editor.putBoolean("sticky_keys_enabled", false)
                    editor.putInt("sticky_keys_timeout_ms", 5000)
                    editor.putBoolean("voice_guidance_enabled", false)

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
                // Check for APK updates in common locations
                val possibleLocations = arrayOf(
                    "/sdcard/Download/cleverkeys-debug.apk",
                    "/storage/emulated/0/Download/cleverkeys-debug.apk",
                    "/sdcard/Download/tribixbite.keyboard2.debug.apk",
                    "/storage/emulated/0/Download/tribixbite.keyboard2.debug.apk",
                    "/sdcard/unexpected/debug-kb.apk",
                    "/storage/emulated/0/unexpected/debug-kb.apk",
                    "${getExternalFilesDir(null)?.parent}/files/home/storage/downloads/cleverkeys-debug.apk"
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
                val publicApk = File("/sdcard/Download/cleverkeys-update.apk")
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

    private fun openClipboardSettings() {
        startActivity(Intent(this, ClipboardSettingsActivity::class.java))
    }

    private fun openBackupRestore() {
        startActivity(Intent(this, BackupRestoreActivity::class.java))
    }
}
