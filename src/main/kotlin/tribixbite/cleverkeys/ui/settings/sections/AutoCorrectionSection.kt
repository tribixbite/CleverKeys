package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.AutoCorrectionSection() {
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_autocorrection),
                expanded = swipeCorrectionsSectionExpanded,
                onExpandChange = { swipeCorrectionsSectionExpanded = it }
            ) {
                // Master toggle
                SettingsSwitch(
                    title = stringResource(R.string.autocorrect_enable_title),
                    description = stringResource(R.string.autocorrect_enable_desc),
                    checked = autoCorrectEnabled,
                    onCheckedChange = {
                        autoCorrectEnabled = it
                        saveSetting("autocorrect_enabled", it)
                    }
                )

                if (autoCorrectEnabled) {
                    // #110: Backspace undo autocorrect — revert to original word on immediate backspace
                    SettingsSwitch(
                        title = stringResource(R.string.autocorrect_backspace_undo_title),
                        description = stringResource(R.string.autocorrect_backspace_undo_desc),
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
                        title = stringResource(R.string.autocorrect_min_word_length_title),
                        description = stringResource(R.string.autocorrect_min_word_length_desc),
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
                        title = stringResource(R.string.autocorrect_char_match_threshold_title),
                        description = stringResource(R.string.autocorrect_char_match_threshold_desc),
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
                        title = stringResource(R.string.autocorrect_min_word_frequency_title),
                        description = stringResource(R.string.autocorrect_min_word_frequency_desc),
                        value = autocorrectMinFrequency.toFloat(),
                        valueRange = 100f..2000f,
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
                        title = stringResource(R.string.autocorrect_beam_title),
                        description = stringResource(R.string.autocorrect_beam_desc),
                        checked = swipeBeamAutocorrectEnabled,
                        onCheckedChange = {
                            swipeBeamAutocorrectEnabled = it
                            saveSetting("swipe_beam_autocorrect_enabled", it)
                        }
                    )

                    SettingsSwitch(
                        title = stringResource(R.string.autocorrect_final_title),
                        description = stringResource(R.string.autocorrect_final_desc),
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
                        title = stringResource(R.string.autocorrect_style_title),
                        description = stringResource(R.string.autocorrect_style_desc),
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
                        title = stringResource(R.string.autocorrect_fuzzy_algorithm_title),
                        description = stringResource(R.string.autocorrect_fuzzy_algorithm_desc),
                        options = listOf("Edit Distance (Recommended)", "Positional Matching (Legacy)"),
                        selectedIndex = if (swipeFuzzyMatchMode == "edit_distance") 0 else 1,
                        onSelectionChange = { index ->
                            swipeFuzzyMatchMode = if (index == 0) "edit_distance" else "positional"
                            saveSetting("swipe_fuzzy_match_mode", swipeFuzzyMatchMode)
                        }
                    )

                    SettingsSlider(
                        title = stringResource(R.string.autocorrect_typo_forgiveness_title),
                        description = stringResource(R.string.autocorrect_typo_forgiveness_desc),
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
                        title = stringResource(R.string.autocorrect_starting_letter_title),
                        description = stringResource(R.string.autocorrect_starting_letter_desc),
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
                        title = stringResource(R.string.autocorrect_search_depth_title),
                        description = stringResource(R.string.autocorrect_search_depth_desc),
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
                    title = stringResource(R.string.autocorrect_source_balance_title),
                    description = stringResource(R.string.autocorrect_source_balance_desc),
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
                    title = stringResource(R.string.autocorrect_common_boost_title),
                    description = stringResource(R.string.autocorrect_common_boost_desc),
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
                    title = stringResource(R.string.autocorrect_frequent_boost_title),
                    description = stringResource(R.string.autocorrect_frequent_boost_desc),
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
                    title = stringResource(R.string.autocorrect_rare_penalty_title),
                    description = stringResource(R.string.autocorrect_rare_penalty_desc),
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
}
