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
}
