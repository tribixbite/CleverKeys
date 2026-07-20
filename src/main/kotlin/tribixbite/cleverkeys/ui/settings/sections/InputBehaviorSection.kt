package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.openExtraKeysConfig
import tribixbite.cleverkeys.ui.settings.openLayoutManager
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.InputBehaviorSection() {
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
                    title = stringResource(R.string.input_enable_predictions_title),
                    description = stringResource(R.string.input_enable_predictions_desc),
                    checked = wordPredictionEnabled,
                    onCheckedChange = {
                        wordPredictionEnabled = it
                        saveSetting("word_prediction_enabled", it)
                    }
                )

                if (wordPredictionEnabled) {
                    SettingsSlider(
                        title = stringResource(R.string.input_suggestion_bar_opacity_title),
                        description = stringResource(R.string.input_suggestion_bar_opacity_desc),
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
                        title = stringResource(R.string.input_auto_space_after_title),
                        description = stringResource(R.string.input_auto_space_after_desc),
                        checked = autoSpaceAfterSuggestion,
                        onCheckedChange = {
                            autoSpaceAfterSuggestion = it
                            saveSetting("auto_space_after_suggestion", it)
                            Config.globalConfig()?.auto_space_after_suggestion = it
                        }
                    )

                    // Auto-space before tapped suggestion (leading space)
                    SettingsSwitch(
                        title = stringResource(R.string.input_auto_space_before_title),
                        description = stringResource(R.string.input_auto_space_before_desc),
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
                            title = stringResource(R.string.input_backspace_undo_swipe_title),
                            description = stringResource(R.string.input_backspace_undo_swipe_desc),
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
                                title = stringResource(R.string.input_context_aware_title),
                                description = stringResource(R.string.input_context_aware_desc),
                                checked = contextAwarePredictionsEnabled,
                                onCheckedChange = {
                                    contextAwarePredictionsEnabled = it
                                    saveSetting("context_aware_predictions_enabled", it)
                                }
                            )

                            SettingsSwitch(
                                title = stringResource(R.string.input_personalized_learning_title),
                                description = stringResource(R.string.input_personalized_learning_desc),
                                checked = personalizedLearningEnabled,
                                onCheckedChange = {
                                    personalizedLearningEnabled = it
                                    saveSetting("personalized_learning_enabled", it)
                                }
                            )

                            if (personalizedLearningEnabled) {
                                SettingsDropdown(
                                    title = stringResource(R.string.input_learning_aggression_title),
                                    description = stringResource(R.string.input_learning_aggression_desc),
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
                                title = stringResource(R.string.input_context_boost_title),
                                description = stringResource(R.string.input_context_boost_desc),
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
                                title = stringResource(R.string.input_frequency_scale_title),
                                description = stringResource(R.string.input_frequency_scale_desc),
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
                    title = stringResource(R.string.input_capitalize_i_title),
                    description = stringResource(R.string.input_capitalize_i_desc),
                    checked = capitalizeIWords,
                    onCheckedChange = {
                        capitalizeIWords = it
                        saveSetting("autocapitalize_i_words", it)
                        // Update Config immediately
                        Config.globalConfig()?.autocapitalize_i_words = it
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.input_smart_punctuation_title),
                    description = stringResource(R.string.input_smart_punctuation_desc),
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
                    title = stringResource(R.string.input_swipe_distance_threshold_title),
                    description = stringResource(R.string.input_swipe_distance_threshold_desc),
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
                    title = stringResource(R.string.input_circle_gesture_title),
                    description = stringResource(R.string.input_circle_gesture_desc),
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
                    title = stringResource(R.string.input_space_slider_title),
                    description = stringResource(R.string.input_space_slider_desc),
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
                    title = stringResource(R.string.input_long_press_timeout_title),
                    description = stringResource(R.string.input_long_press_timeout_desc),
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
                    title = stringResource(R.string.input_long_press_interval_title),
                    description = stringResource(R.string.input_long_press_interval_desc),
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
                    title = stringResource(R.string.input_key_repeat_title),
                    description = stringResource(R.string.input_key_repeat_desc),
                    checked = keyRepeatEnabled,
                    onCheckedChange = {
                        keyRepeatEnabled = it
                        saveSetting("keyrepeat_enabled", it)
                    }
                )

                // #81: Only show when key repeat is enabled
                if (keyRepeatEnabled) {
                    SettingsSwitch(
                        title = stringResource(R.string.input_backspace_only_repeat_title),
                        description = stringResource(R.string.input_backspace_only_repeat_desc),
                        checked = keyRepeatBackspaceOnly,
                        onCheckedChange = {
                            keyRepeatBackspaceOnly = it
                            saveSetting("keyrepeat_backspace_only", it)
                            Config.globalConfig()?.keyrepeat_backspace_only = it
                        }
                    )
                }

                SettingsSwitch(
                    title = stringResource(R.string.input_double_tap_shift_title),
                    description = stringResource(R.string.input_double_tap_shift_desc),
                    checked = doubleTapLockShift,
                    onCheckedChange = {
                        doubleTapLockShift = it
                        saveSetting("lock_double_tap", it)
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.input_immediate_switching_title),
                    description = stringResource(R.string.input_immediate_switching_desc),
                    checked = switchInputImmediate,
                    onCheckedChange = {
                        switchInputImmediate = it
                        saveSetting("switch_input_immediate", it)
                    }
                )

                SettingsDropdown(
                    title = stringResource(R.string.input_number_row_title),
                    description = stringResource(R.string.input_number_row_desc),
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
                    title = stringResource(R.string.input_show_numpad_title),
                    description = stringResource(R.string.input_show_numpad_desc),
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
                    title = stringResource(R.string.input_numpad_layout_title),
                    description = stringResource(R.string.input_numpad_layout_desc),
                    options = listOf("High First (7-8-9 on top)", "Low First (1-2-3 on top)"),
                    selectedIndex = if (numpadLayout == "low_first") 1 else 0,
                    onSelectionChange = { index ->
                        numpadLayout = if (index == 1) "low_first" else "default"
                        saveSetting("numpad_layout", numpadLayout)
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.input_pin_entry_layout_title),
                    description = stringResource(R.string.input_pin_entry_layout_desc),
                    checked = pinEntryEnabled,
                    onCheckedChange = {
                        pinEntryEnabled = it
                        saveSetting("pin_entry_enabled", it)
                    }
                )
            }
}
