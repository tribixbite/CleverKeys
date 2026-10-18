package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.WordPredictor.NeighbourTiebreak
import tribixbite.cleverkeys.autocorrect.AutocorrectRanking
import tribixbite.cleverkeys.autocorrect.AutocorrectTuning
import tribixbite.cleverkeys.autocorrect.AutocorrectTuning.Keys
import tribixbite.cleverkeys.autocorrect.ChannelWeights
import tribixbite.cleverkeys.ui.settings.AutocorrectTuningSliders
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.saveSetting
import tribixbite.cleverkeys.ui.settings.updateConfigFromSettings

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
                        text = stringResource(R.string.autocorrect_basic_header),
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
                        displayValue = pluralStringResource(R.plurals.settings_value_letters, autocorrectMinWordLength, autocorrectMinWordLength)
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
                        text = stringResource(R.string.autocorrect_swipe_correction_header),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
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
                        text = stringResource(R.string.autocorrect_advanced_header),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )

                    // ARC-085 (2026-08-29): the "Correction Style" dropdown
                    // (Strict/Balanced/Lenient) lived here. It wrote `swipe_correction_preset`,
                    // which NOTHING read — no Config field, no predictor, no engine adapter —
                    // so the three presets were indistinguishable at runtime. It predates the
                    // neural-engine removal, which is why the ARC-051 sweep 20 lines below
                    // missed it. Deleted rather than wired: no spec defines what strict or
                    // lenient would mean for a lexicon-trie-constrained decoder, so wiring it
                    // would require inventing behaviour. Key tombstoned in
                    // SettingsValidation.DEPRECATED_KEYS; pinned by DeadPlumbingDriftTest.

                    // The fuzzy-algorithm dropdown (edit-distance vs positional) lived here.
                    // Its only consumer was OptimizedVocabulary's fuzzy rescue, deleted with the
                    // neural engine; neither surviving engine has a fuzzy matcher to configure.

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
                        displayValue = pluralStringResource(R.plurals.settings_value_chars, autocorrectMaxLengthDiff, autocorrectMaxLengthDiff)
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
                        displayValue = pluralStringResource(R.plurals.settings_value_letters, autocorrectPrefixLength, autocorrectPrefixLength)
                    )

                    // "Correction Search Depth" (autocorrect_max_beam_candidates) lived here.
                    // It bounded the fuzzy RESCUE of out-of-vocabulary beam strings in
                    // OptimizedVocabulary — a stage that cannot exist for a lexicon-trie-
                    // constrained decoder, since CTC and geometric can only ever emit words the
                    // trie contains. Deleted with the engine; the pref went to DEPRECATED_KEYS
                    // in ed2ead3a, but this control was MISSED in that pass and kept writing a
                    // key already marked dead. Found by the 2026-08-19 settings audit.

                    AutocorrectRankingAndTuning()
                }

                // The "Word Scoring" group lived here: prediction-source balance, common-words
                // boost, top-5000 boost and rare-words penalty. All four were consumed ONLY by
                // OptimizedVocabulary, which was deleted with the neural engine on 2026-08-18.
                // They kept rendering and kept persisting to SharedPreferences while driving
                // nothing at all — a control that visibly responds but changes no behaviour is
                // worse than an absent one, because it costs the user time to discover that.
                // The prefs are deprecated in SettingsValidation so old backups do not
                // resurrect them. CTC exposes ctc_beam_width; geometric has its own knobs.
            }
}

/**
 * The "Ranking & Tuning" group: every weight and switch of `WordPredictor.autoCorrect` beyond the
 * basic settings above (maintainer request 2026-10-18: expose them all). Rendered inside the
 * Auto-Correction section's enabled block, so the settings search index files these controls under
 * that section.
 *
 * Layout rules:
 *  - the ranking dropdown comes first, and only the ACTIVE ranking's own weights are shown (the
 *    dropdown's description says so) — the other ranking's weights have no effect at all;
 *  - the gates and steps both rankings share are always shown;
 *  - a control whose own switch is off stays visible but disabled (project rule, see
 *    [SettingsSwitch]'s `enabled`), never hidden.
 *
 * State is [SettingsActivity.autocorrectTuning], the same snapshot the predictor reads; each
 * control writes one key of [AutocorrectTuning.Keys] and the snapshot is re-read on the preference
 * change (SettingsPersistence).
 */
@Composable
private fun SettingsActivity.AutocorrectRankingAndTuning() {
    val tuning = autocorrectTuning
    val sliders = AutocorrectTuningSliders

    Text(
        text = stringResource(R.string.autocorrect_tuning_header),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )

    // Option order = AutocorrectRanking.entries order (BAND, CHANNEL).
    val rankingLabels = AutocorrectRanking.entries.map { ranking ->
        when (ranking) {
            AutocorrectRanking.BAND -> stringResource(R.string.autocorrect_ranking_band)
            AutocorrectRanking.CHANNEL -> stringResource(R.string.autocorrect_ranking_channel)
        }
    }
    SettingsDropdown(
        title = stringResource(R.string.autocorrect_ranking_title),
        description = stringResource(R.string.autocorrect_ranking_desc),
        options = rankingLabels,
        selectedIndex = AutocorrectRanking.entries.indexOf(tuning.ranking),
        onSelectionChange = { index ->
            val ranking = AutocorrectRanking.entries[index]
            autocorrectTuning = tuning.copy(ranking = ranking)
            saveSetting(Keys.RANKING.key, ranking.prefValue)
        }
    )

    when (tuning.ranking) {
        AutocorrectRanking.BAND -> BandRankingControls(tuning)
        AutocorrectRanking.CHANNEL -> ChannelRankingControls(tuning)
    }

    // ── Gates and steps used by both rankings ──
    SettingsSlider(
        title = stringResource(R.string.autocorrect_min_exact_ratio_title),
        description = stringResource(R.string.autocorrect_min_exact_ratio_desc),
        value = tuning.minExactRatio,
        valueRange = Keys.MIN_EXACT_RATIO.range,
        steps = sliders.MIN_EXACT_RATIO.steps,
        onValueChange = {
            val v = sliders.MIN_EXACT_RATIO.snap(it)
            autocorrectTuning = tuning.copy(minExactRatio = v)
            saveSetting(Keys.MIN_EXACT_RATIO.key, v)
        },
        displayValue = "%.0f%%".format(tuning.minExactRatio * 100)
    )

    SettingsSlider(
        title = stringResource(R.string.autocorrect_max_substitutions_title),
        description = stringResource(R.string.autocorrect_max_substitutions_desc),
        value = tuning.maxSubstitutions.toFloat(),
        valueRange = sliders.MAX_SUBSTITUTIONS.valueRange,
        steps = sliders.MAX_SUBSTITUTIONS.steps,
        onValueChange = {
            val v = sliders.MAX_SUBSTITUTIONS.snap(it)
            autocorrectTuning = tuning.copy(maxSubstitutions = v)
            saveSetting(Keys.MAX_SUBSTITUTIONS.key, v)
        },
        displayValue = pluralStringResource(R.plurals.settings_value_letters, tuning.maxSubstitutions, tuning.maxSubstitutions)
    )

    SettingsSlider(
        title = stringResource(R.string.autocorrect_length_edit_budget_title),
        description = stringResource(R.string.autocorrect_length_edit_budget_desc),
        value = tuning.lengthEditBudget,
        valueRange = Keys.LENGTH_EDIT_BUDGET.range,
        steps = sliders.LENGTH_EDIT_BUDGET.steps,
        onValueChange = {
            val v = sliders.LENGTH_EDIT_BUDGET.snap(it)
            autocorrectTuning = tuning.copy(lengthEditBudget = v)
            saveSetting(Keys.LENGTH_EDIT_BUDGET.key, v)
        },
        displayValue = sliders.LENGTH_EDIT_BUDGET.format(tuning.lengthEditBudget)
    )

    SettingsSwitch(
        title = stringResource(R.string.autocorrect_elongation_collapse_title),
        description = stringResource(R.string.autocorrect_elongation_collapse_desc),
        checked = tuning.elongationCollapseEnabled,
        onCheckedChange = {
            autocorrectTuning = tuning.copy(elongationCollapseEnabled = it)
            saveSetting(Keys.ELONGATION_COLLAPSE_ENABLED.key, it)
        }
    )

    SettingsSwitch(
        title = stringResource(R.string.autocorrect_inflection_guard_title),
        description = stringResource(R.string.autocorrect_inflection_guard_desc),
        checked = tuning.inflectionGuardEnabled,
        onCheckedChange = {
            autocorrectTuning = tuning.copy(inflectionGuardEnabled = it)
            saveSetting(Keys.INFLECTION_GUARD_ENABLED.key, it)
        }
    )

    SettingsSlider(
        title = stringResource(R.string.autocorrect_inflection_guard_min_stem_title),
        description = stringResource(R.string.autocorrect_inflection_guard_min_stem_desc),
        value = tuning.inflectionGuardMinStem.toFloat(),
        valueRange = sliders.INFLECTION_GUARD_MIN_STEM.valueRange,
        steps = sliders.INFLECTION_GUARD_MIN_STEM.steps,
        onValueChange = {
            val v = sliders.INFLECTION_GUARD_MIN_STEM.snap(it)
            autocorrectTuning = tuning.copy(inflectionGuardMinStem = v)
            saveSetting(Keys.INFLECTION_GUARD_MIN_STEM.key, v)
        },
        displayValue = pluralStringResource(R.plurals.settings_value_letters, tuning.inflectionGuardMinStem, tuning.inflectionGuardMinStem),
        enabled = tuning.inflectionGuardEnabled
    )

    // The word-form keep cost (γ) belongs to the channel ranking but depends on the guard switch
    // above, so it sits after it rather than in ChannelRankingControls.
    if (tuning.ranking == AutocorrectRanking.CHANNEL) {
        val gammaPosition = sliders.inflectionCostSliderValue(tuning.channel.inflectionCost)
        SettingsSlider(
            title = stringResource(R.string.autocorrect_channel_inflection_cost_title),
            description = stringResource(R.string.autocorrect_channel_inflection_cost_desc),
            value = gammaPosition,
            valueRange = Keys.CHANNEL_INFLECTION_COST.range,
            steps = sliders.CHANNEL_INFLECTION_COST.steps,
            onValueChange = {
                val v = sliders.CHANNEL_INFLECTION_COST.snap(it)
                autocorrectTuning = tuning.copy(
                    channel = tuning.channel.copy(inflectionCost = ChannelWeights.inflectionCostFromPref(v))
                )
                saveSetting(Keys.CHANNEL_INFLECTION_COST.key, v)
            },
            displayValue = if (sliders.isAlwaysKeep(gammaPosition)) {
                stringResource(R.string.autocorrect_value_always_keep)
            } else {
                sliders.CHANNEL_INFLECTION_COST.format(gammaPosition)
            },
            enabled = tuning.inflectionGuardEnabled
        )
    }

    // ── Reset: restores exactly this group's keys; the basic settings above are untouched ──
    var showResetDialog by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showResetDialog = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Text(stringResource(R.string.autocorrect_tuning_reset))
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.autocorrect_tuning_reset)) },
            text = { Text(stringResource(R.string.autocorrect_tuning_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    resetAutocorrectTuning()
                }) { Text(stringResource(R.string.settings_reset_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** The band ranking's own controls (shown only while it is the active ranking). */
@Composable
private fun SettingsActivity.BandRankingControls(tuning: AutocorrectTuning) {
    val sliders = AutocorrectTuningSliders

    SettingsSlider(
        title = stringResource(R.string.autocorrect_band_gap_title),
        description = stringResource(R.string.autocorrect_band_gap_desc),
        value = tuning.bandGap,
        valueRange = Keys.BAND_GAP.range,
        steps = sliders.BAND_GAP.steps,
        onValueChange = {
            val v = sliders.BAND_GAP.snap(it)
            autocorrectTuning = tuning.copy(bandGap = v)
            saveSetting(Keys.BAND_GAP.key, v)
        },
        displayValue = sliders.BAND_GAP.format(tuning.bandGap)
    )

    SettingsSlider(
        title = stringResource(R.string.autocorrect_transposition_penalty_title),
        description = stringResource(R.string.autocorrect_transposition_penalty_desc),
        value = tuning.transpositionPenalty,
        valueRange = Keys.TRANSPOSITION_PENALTY.range,
        steps = sliders.TRANSPOSITION_PENALTY.steps,
        onValueChange = {
            val v = sliders.TRANSPOSITION_PENALTY.snap(it)
            autocorrectTuning = tuning.copy(transpositionPenalty = v)
            saveSetting(Keys.TRANSPOSITION_PENALTY.key, v)
        },
        displayValue = sliders.TRANSPOSITION_PENALTY.format(tuning.transpositionPenalty)
    )

    // Option order = NeighbourTiebreak.entries order (OFF, N1, NA); stored as the lowercase name.
    val tiebreakLabels = NeighbourTiebreak.entries.map { variant ->
        when (variant) {
            NeighbourTiebreak.OFF -> stringResource(R.string.autocorrect_neighbour_tiebreak_off)
            NeighbourTiebreak.N1 -> stringResource(R.string.autocorrect_neighbour_tiebreak_n1)
            NeighbourTiebreak.NA -> stringResource(R.string.autocorrect_neighbour_tiebreak_na)
        }
    }
    SettingsDropdown(
        title = stringResource(R.string.autocorrect_neighbour_tiebreak_title),
        description = stringResource(R.string.autocorrect_neighbour_tiebreak_desc),
        options = tiebreakLabels,
        selectedIndex = NeighbourTiebreak.entries.indexOf(tuning.neighbourTiebreak),
        onSelectionChange = { index ->
            val variant = NeighbourTiebreak.entries[index]
            autocorrectTuning = tuning.copy(neighbourTiebreak = variant)
            saveSetting(Keys.NEIGHBOUR_TIEBREAK.key, variant.name.lowercase(Locale.ROOT))
        }
    )

    SettingsSwitch(
        title = stringResource(R.string.autocorrect_dropped_double_title),
        description = stringResource(R.string.autocorrect_dropped_double_desc),
        checked = tuning.droppedDoubleEnabled,
        onCheckedChange = {
            autocorrectTuning = tuning.copy(droppedDoubleEnabled = it)
            saveSetting(Keys.DROPPED_DOUBLE_ENABLED.key, it)
        }
    )

    SettingsSlider(
        title = stringResource(R.string.autocorrect_dropped_double_margin_title),
        description = stringResource(R.string.autocorrect_dropped_double_margin_desc),
        value = tuning.droppedDoubleMarginSteps.toFloat(),
        valueRange = sliders.DROPPED_DOUBLE_MARGIN.valueRange,
        steps = sliders.DROPPED_DOUBLE_MARGIN.steps,
        onValueChange = {
            val v = sliders.DROPPED_DOUBLE_MARGIN.snap(it)
            autocorrectTuning = tuning.copy(droppedDoubleMarginSteps = v)
            saveSetting(Keys.DROPPED_DOUBLE_MARGIN.key, v)
        },
        displayValue = pluralStringResource(
            R.plurals.autocorrect_value_rank_steps, tuning.droppedDoubleMarginSteps, tuning.droppedDoubleMarginSteps
        ),
        enabled = tuning.droppedDoubleEnabled
    )
}

/** The channel ranking's edit and frequency weights (shown only while it is the active ranking). */
@Composable
private fun SettingsActivity.ChannelRankingControls(tuning: AutocorrectTuning) {
    val sliders = AutocorrectTuningSliders
    val weights = tuning.channel

    SettingsSlider(
        title = stringResource(R.string.autocorrect_channel_distance_cost_title),
        description = stringResource(R.string.autocorrect_channel_distance_cost_desc),
        value = weights.distanceCost,
        valueRange = Keys.CHANNEL_DISTANCE_COST.range,
        steps = sliders.CHANNEL_DISTANCE_COST.steps,
        onValueChange = {
            val v = sliders.CHANNEL_DISTANCE_COST.snap(it)
            autocorrectTuning = tuning.copy(channel = weights.copy(distanceCost = v))
            saveSetting(Keys.CHANNEL_DISTANCE_COST.key, v)
        },
        displayValue = sliders.CHANNEL_DISTANCE_COST.format(weights.distanceCost)
    )

    SettingsSlider(
        title = stringResource(R.string.autocorrect_channel_indel_cost_title),
        description = stringResource(R.string.autocorrect_channel_indel_cost_desc),
        value = weights.indelCost,
        valueRange = Keys.CHANNEL_INDEL_COST.range,
        steps = sliders.CHANNEL_INDEL_COST.steps,
        onValueChange = {
            val v = sliders.CHANNEL_INDEL_COST.snap(it)
            autocorrectTuning = tuning.copy(channel = weights.copy(indelCost = v))
            saveSetting(Keys.CHANNEL_INDEL_COST.key, v)
        },
        displayValue = sliders.CHANNEL_INDEL_COST.format(weights.indelCost)
    )

    SettingsSlider(
        title = stringResource(R.string.autocorrect_channel_double_cost_title),
        description = stringResource(R.string.autocorrect_channel_double_cost_desc),
        value = weights.doubleCost,
        valueRange = Keys.CHANNEL_DOUBLE_COST.range,
        steps = sliders.CHANNEL_DOUBLE_COST.steps,
        onValueChange = {
            val v = sliders.CHANNEL_DOUBLE_COST.snap(it)
            autocorrectTuning = tuning.copy(channel = weights.copy(doubleCost = v))
            saveSetting(Keys.CHANNEL_DOUBLE_COST.key, v)
        },
        displayValue = sliders.CHANNEL_DOUBLE_COST.format(weights.doubleCost)
    )

    SettingsSlider(
        title = stringResource(R.string.autocorrect_channel_transposition_cost_title),
        description = stringResource(R.string.autocorrect_channel_transposition_cost_desc),
        value = weights.transpositionCost,
        valueRange = Keys.CHANNEL_TRANSPOSITION_COST.range,
        steps = sliders.CHANNEL_TRANSPOSITION_COST.steps,
        onValueChange = {
            val v = sliders.CHANNEL_TRANSPOSITION_COST.snap(it)
            autocorrectTuning = tuning.copy(channel = weights.copy(transpositionCost = v))
            saveSetting(Keys.CHANNEL_TRANSPOSITION_COST.key, v)
        },
        displayValue = sliders.CHANNEL_TRANSPOSITION_COST.format(weights.transpositionCost)
    )

    // λ: the description also states the trade in plain units (round(1/λ) rank steps per wrong key).
    val rankSteps = sliders.rankStepsPerWrongKey(weights.frequencyWeight)
    val equivalence = if (rankSteps == null) {
        stringResource(R.string.autocorrect_frequency_ignored)
    } else {
        pluralStringResource(R.plurals.autocorrect_frequency_equivalence, rankSteps, rankSteps)
    }
    SettingsSlider(
        title = stringResource(R.string.autocorrect_channel_frequency_weight_title),
        description = stringResource(R.string.autocorrect_channel_frequency_weight_desc) + "\n" + equivalence,
        value = weights.frequencyWeight,
        valueRange = Keys.CHANNEL_FREQUENCY_WEIGHT.range,
        steps = sliders.CHANNEL_FREQUENCY_WEIGHT.steps,
        onValueChange = {
            val v = sliders.CHANNEL_FREQUENCY_WEIGHT.snap(it)
            autocorrectTuning = tuning.copy(channel = weights.copy(frequencyWeight = v))
            saveSetting(Keys.CHANNEL_FREQUENCY_WEIGHT.key, v)
        },
        displayValue = sliders.CHANNEL_FREQUENCY_WEIGHT.format(weights.frequencyWeight)
    )

    SettingsSlider(
        title = stringResource(R.string.autocorrect_channel_alias_bonus_title),
        description = stringResource(R.string.autocorrect_channel_alias_bonus_desc),
        value = weights.aliasBonus,
        valueRange = Keys.CHANNEL_ALIAS_BONUS.range,
        steps = sliders.CHANNEL_ALIAS_BONUS.steps,
        onValueChange = {
            val v = sliders.CHANNEL_ALIAS_BONUS.snap(it)
            autocorrectTuning = tuning.copy(channel = weights.copy(aliasBonus = v))
            saveSetting(Keys.CHANNEL_ALIAS_BONUS.key, v)
        },
        displayValue = sliders.CHANNEL_ALIAS_BONUS.format(weights.aliasBonus)
    )
}

/**
 * "Reset autocorrect tuning": removes every [AutocorrectTuning.Keys] preference in one edit, so
 * each falls back to its shipped default, and shows the defaults at once. Only this group's keys
 * ([AutocorrectTuningSliders.RESET_KEYS]); the full "Reset Settings" also covers them through
 * SETTINGS_DEFAULTS.
 */
private fun SettingsActivity.resetAutocorrectTuning() {
    autocorrectTuning = AutocorrectTuning.DEFAULT
    val editor = prefs.edit()
    for (key in AutocorrectTuningSliders.RESET_KEYS) editor.remove(key)
    editor.apply()
    updateConfigFromSettings()
}
