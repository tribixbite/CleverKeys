package tribixbite.cleverkeys.ui.settings.sections

import tribixbite.cleverkeys.MarginPrefKeys
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tribixbite.cleverkeys.FoldStateTracker
import tribixbite.cleverkeys.KeyboardHeightPrefs
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.SettingsRanges
import tribixbite.cleverkeys.toSliderRange
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.saveSetting
import kotlin.math.roundToInt

@Composable
internal fun SettingsActivity.AppearanceSection() {
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_appearance),
                expanded = appearanceSectionExpanded,
                onExpandChange = { appearanceSectionExpanded = it }
            ) {
                // Theme Manager moved to Activities section at top

                SettingsSlider(
                    title = stringResource(R.string.appearance_height_portrait_title),
                    description = stringResource(R.string.appearance_height_portrait_desc),
                    value = keyboardHeight.toFloat(),
                    valueRange = SettingsRanges.KEYBOARD_HEIGHT.first.toFloat()..SettingsRanges.KEYBOARD_HEIGHT.last.toFloat(),
                    increment = 1f,
                    onValueChange = {
                        keyboardHeight = it.roundToInt()
                        saveSetting("keyboard_height", keyboardHeight)
                    },
                    displayValue = "$keyboardHeight%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_height_landscape_title),
                    description = stringResource(R.string.appearance_height_landscape_desc),
                    value = keyboardHeightLandscape.toFloat(),
                    valueRange = SettingsRanges.KEYBOARD_HEIGHT.first.toFloat()..SettingsRanges.KEYBOARD_HEIGHT.last.toFloat(),
                    increment = 1f,
                    onValueChange = {
                        keyboardHeightLandscape = it.roundToInt()
                        saveSetting("keyboard_height_landscape", keyboardHeightLandscape)
                    },
                    displayValue = "$keyboardHeightLandscape%"
                )

                // Foldables (2026-10-20 user report): the inner (unfolded) screen has its own
                // heights. Until set they follow the two sliders above (KeyboardHeightPrefs).
                // Visible but inert on phones without a fold, so the option is discoverable.
                val foldable = FoldStateTracker.isFoldableDevice(this@AppearanceSection)
                val innerNote = if (foldable) "" else "\n" + stringResource(R.string.appearance_height_no_fold_detected)
                SettingsSlider(
                    title = stringResource(R.string.appearance_height_unfolded_title),
                    description = stringResource(R.string.appearance_height_unfolded_desc) + innerNote,
                    value = keyboardHeightUnfolded.toFloat(),
                    valueRange = SettingsRanges.KEYBOARD_HEIGHT.first.toFloat()..SettingsRanges.KEYBOARD_HEIGHT.last.toFloat(),
                    increment = 1f,
                    onValueChange = {
                        keyboardHeightUnfolded = it.roundToInt()
                        saveSetting(KeyboardHeightPrefs.PORTRAIT_UNFOLDED, keyboardHeightUnfolded)
                    },
                    displayValue = "$keyboardHeightUnfolded%",
                    enabled = foldable
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_height_landscape_unfolded_title),
                    description = stringResource(R.string.appearance_height_landscape_unfolded_desc) + innerNote,
                    value = keyboardHeightLandscapeUnfolded.toFloat(),
                    valueRange = SettingsRanges.KEYBOARD_HEIGHT.first.toFloat()..SettingsRanges.KEYBOARD_HEIGHT.last.toFloat(),
                    increment = 1f,
                    onValueChange = {
                        keyboardHeightLandscapeUnfolded = it.roundToInt()
                        saveSetting(KeyboardHeightPrefs.LANDSCAPE_UNFOLDED, keyboardHeightLandscapeUnfolded)
                    },
                    displayValue = "$keyboardHeightLandscapeUnfolded%",
                    enabled = foldable
                )

                // F-8 / #58 (maintainer decision 2026-09-08): scale_numpad_height had a
                // live reader (Config.refresh → Theme row height for numeric layouts)
                // but no control — permanently at its default for everyone.
                SettingsSwitch(
                    title = stringResource(R.string.appearance_scale_numpad_title),
                    description = stringResource(R.string.appearance_scale_numpad_desc),
                    checked = scaleNumpadHeight,
                    onCheckedChange = {
                        scaleNumpadHeight = it
                        saveSetting("scale_numpad_height", it)
                    }
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_bottom_margin_portrait_title),
                    description = stringResource(R.string.appearance_bottom_margin_portrait_desc),
                    value = marginBottomPortrait.toFloat(),
                    valueRange = SettingsRanges.MARGIN_BOTTOM_PERCENT.toSliderRange(),
                    increment = 1f,
                    onValueChange = {
                        marginBottomPortrait = it.roundToInt()
                        saveSetting(MarginPrefKeys.BOTTOM_PORTRAIT, marginBottomPortrait)
                    },
                    displayValue = "$marginBottomPortrait%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_bottom_margin_landscape_title),
                    description = stringResource(R.string.appearance_bottom_margin_landscape_desc),
                    value = marginBottomLandscape.toFloat(),
                    valueRange = SettingsRanges.MARGIN_BOTTOM_PERCENT.toSliderRange(),
                    increment = 1f,
                    onValueChange = {
                        marginBottomLandscape = it.roundToInt()
                        saveSetting(MarginPrefKeys.BOTTOM_LANDSCAPE, marginBottomLandscape)
                    },
                    displayValue = "$marginBottomLandscape%"
                )

                // Portrait left/right margins: each side 0-45% (SettingsRanges), 90% total cap
                val maxLeftPortrait = sideMarginCeiling(marginRightPortrait)
                SettingsSlider(
                    title = stringResource(R.string.appearance_left_margin_portrait_title),
                    description = stringResource(R.string.appearance_left_margin_portrait_desc),
                    value = marginLeftPortrait.toFloat(),
                    valueRange = 0f..maxLeftPortrait.toFloat(),
                    increment = 1f,
                    onValueChange = {
                        marginLeftPortrait = it.roundToInt()
                        saveSetting(MarginPrefKeys.LEFT_PORTRAIT, marginLeftPortrait)
                    },
                    displayValue = "$marginLeftPortrait%"
                )

                val maxRightPortrait = sideMarginCeiling(marginLeftPortrait)
                SettingsSlider(
                    title = stringResource(R.string.appearance_right_margin_portrait_title),
                    description = stringResource(R.string.appearance_right_margin_portrait_desc),
                    value = marginRightPortrait.toFloat(),
                    valueRange = 0f..maxRightPortrait.toFloat(),
                    increment = 1f,
                    onValueChange = {
                        marginRightPortrait = it.roundToInt()
                        saveSetting(MarginPrefKeys.RIGHT_PORTRAIT, marginRightPortrait)
                    },
                    displayValue = "$marginRightPortrait%"
                )

                // Landscape left/right margins: each side 0-45% (SettingsRanges), 90% total cap
                val maxLeftLandscape = sideMarginCeiling(marginRightLandscape)
                SettingsSlider(
                    title = stringResource(R.string.appearance_left_margin_landscape_title),
                    description = stringResource(R.string.appearance_left_margin_landscape_desc),
                    value = marginLeftLandscape.toFloat(),
                    valueRange = 0f..maxLeftLandscape.toFloat(),
                    increment = 1f,
                    onValueChange = {
                        marginLeftLandscape = it.roundToInt()
                        saveSetting(MarginPrefKeys.LEFT_LANDSCAPE, marginLeftLandscape)
                    },
                    displayValue = "$marginLeftLandscape%"
                )

                val maxRightLandscape = sideMarginCeiling(marginLeftLandscape)
                SettingsSlider(
                    title = stringResource(R.string.appearance_right_margin_landscape_title),
                    description = stringResource(R.string.appearance_right_margin_landscape_desc),
                    value = marginRightLandscape.toFloat(),
                    valueRange = 0f..maxRightLandscape.toFloat(),
                    increment = 1f,
                    onValueChange = {
                        marginRightLandscape = it.roundToInt()
                        saveSetting(MarginPrefKeys.RIGHT_LANDSCAPE, marginRightLandscape)
                    },
                    displayValue = "$marginRightLandscape%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_label_brightness_title),
                    description = stringResource(R.string.appearance_label_brightness_desc),
                    value = labelBrightness.toFloat(),
                    valueRange = SettingsRanges.OPACITY_PERCENT.toSliderRange(),
                    increment = 1f,
                    onValueChange = {
                        labelBrightness = it.roundToInt()
                        saveSetting("label_brightness", labelBrightness)
                    },
                    displayValue = "$labelBrightness%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_keyboard_opacity_title),
                    description = stringResource(R.string.appearance_keyboard_opacity_desc),
                    value = keyboardOpacity.toFloat(),
                    valueRange = SettingsRanges.OPACITY_PERCENT.toSliderRange(),
                    increment = 1f,
                    onValueChange = {
                        keyboardOpacity = it.roundToInt()
                        saveSetting("keyboard_opacity", keyboardOpacity)
                    },
                    displayValue = "$keyboardOpacity%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_key_opacity_title),
                    description = stringResource(R.string.appearance_key_opacity_desc),
                    value = keyOpacity.toFloat(),
                    valueRange = SettingsRanges.OPACITY_PERCENT.toSliderRange(),
                    increment = 1f,
                    onValueChange = {
                        keyOpacity = it.roundToInt()
                        saveSetting("key_opacity", keyOpacity)
                    },
                    displayValue = "$keyOpacity%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_activated_key_opacity_title),
                    description = stringResource(R.string.appearance_activated_key_opacity_desc),
                    value = keyActivatedOpacity.toFloat(),
                    valueRange = SettingsRanges.OPACITY_PERCENT.toSliderRange(),
                    increment = 1f,
                    onValueChange = {
                        keyActivatedOpacity = it.roundToInt()
                        saveSetting("key_activated_opacity", keyActivatedOpacity)
                    },
                    displayValue = "$keyActivatedOpacity%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_character_size_title),
                    description = stringResource(R.string.appearance_character_size_desc),
                    value = characterSize.toFloat(),
                    // F-4: UI shows percent; the stored float range is SettingsRanges.CHARACTER_SIZE
                    // (shared with the import validator + Config clamp).
                    valueRange = SettingsRanges.CHARACTER_SIZE.start * 100f..
                        SettingsRanges.CHARACTER_SIZE.endInclusive * 100f,
                    increment = 1f,
                    onValueChange = {
                        characterSize = it.roundToInt()
                        saveSetting("character_size", characterSize / 100f)
                    },
                    displayValue = "${characterSize}%"
                )

                // #133: independent sizing for the small secondary (flick) labels
                // so increasing primary Character Size doesn't crowd/overlap them.
                SettingsSlider(
                    title = stringResource(R.string.appearance_secondary_label_size_title),
                    description = stringResource(R.string.appearance_secondary_label_size_desc),
                    value = secondaryLabelSizeScale.toFloat(),
                    valueRange = 50f..200f,
                    increment = 1f,
                    onValueChange = {
                        secondaryLabelSizeScale = it.roundToInt()
                        saveSetting("secondary_label_size_scale", secondaryLabelSizeScale / 100f)
                    },
                    displayValue = "${secondaryLabelSizeScale}%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_key_vertical_margin_title),
                    description = stringResource(R.string.appearance_key_vertical_margin_desc),
                    value = keyVerticalMargin.toFloat(),
                    valueRange = 0f..500f,
                    increment = 5f,
                    onValueChange = {
                        keyVerticalMargin = it.roundToInt()
                        saveSetting("key_vertical_margin", keyVerticalMargin / 100f)
                    },
                    displayValue = "${keyVerticalMargin / 100f}%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_key_horizontal_margin_title),
                    description = stringResource(R.string.appearance_key_horizontal_margin_desc),
                    value = keyHorizontalMargin.toFloat(),
                    valueRange = 0f..500f,
                    increment = 5f,
                    onValueChange = {
                        keyHorizontalMargin = it.roundToInt()
                        saveSetting("key_horizontal_margin", keyHorizontalMargin / 100f)
                    },
                    displayValue = "${keyHorizontalMargin / 100f}%"
                )

                // Keyboard looks hide corner labels like the keyboards they copy; this restores
                // them (LookLegendPolicy). Drawing only: gestures are unchanged.
                SettingsSwitch(
                    title = stringResource(R.string.appearance_look_show_hints_title),
                    description = stringResource(R.string.appearance_look_show_hints_desc),
                    checked = lookShowHints,
                    onCheckedChange = {
                        lookShowHints = it
                        saveSetting("look_show_hints", it)
                    }
                )

                // Looks show their product's key-press preview (LookPreviewPolicy); this turns
                // it off. Non-look themes never draw one.
                SettingsSwitch(
                    title = stringResource(R.string.appearance_look_key_preview_title),
                    description = stringResource(R.string.appearance_look_key_preview_desc),
                    checked = lookKeyPreview,
                    onCheckedChange = {
                        lookKeyPreview = it
                        saveSetting("look_key_preview", it)
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.appearance_custom_border_title),
                    description = stringResource(R.string.appearance_custom_border_desc),
                    checked = borderConfigEnabled,
                    onCheckedChange = {
                        borderConfigEnabled = it
                        saveSetting("border_config", it)
                    }
                )

                if (borderConfigEnabled) {
                    SettingsSlider(
                        title = stringResource(R.string.appearance_border_radius_title),
                        description = stringResource(R.string.appearance_border_radius_desc),
                        value = customBorderRadius.toFloat(),
                        valueRange = SettingsRanges.CUSTOM_BORDER_RADIUS.toSliderRange(),
                        increment = 1f,
                        onValueChange = {
                            customBorderRadius = it.roundToInt()
                            saveSetting("custom_border_radius", customBorderRadius)
                        },
                        displayValue = "${customBorderRadius}%"
                    )

                    SettingsSlider(
                        title = stringResource(R.string.appearance_border_line_width_title),
                        description = stringResource(R.string.appearance_border_line_width_desc),
                        value = customBorderLineWidth.toFloat(),
                        // F-4: shared with the import validator (SettingsRanges).
                        valueRange = SettingsRanges.CUSTOM_BORDER_LINE_WIDTH.start..
                            SettingsRanges.CUSTOM_BORDER_LINE_WIDTH.endInclusive,
                        increment = 1f,
                        onValueChange = {
                            customBorderLineWidth = it.roundToInt()
                            saveSetting("custom_border_line_width", customBorderLineWidth)
                        },
                        displayValue = "${customBorderLineWidth}dp"
                    )
                }
            }
}

/**
 * One side margin's slider ceiling: the per-side cap ([SettingsRanges.MARGIN_SIDE_PERCENT], which
 * Config clamps at read and backup validation enforces), lowered further when the other side
 * would push the total past [SettingsRanges.MARGIN_SIDES_TOTAL_PERCENT]. Before audit M8 the
 * slider offered `90 - other` alone — up to 90% for one side, which Config silently clamped to
 * 45% and a backup import rejected as out of range.
 */
internal fun sideMarginCeiling(otherSide: Int): Int =
    minOf(SettingsRanges.MARGIN_SIDE_PERCENT.last, SettingsRanges.MARGIN_SIDES_TOTAL_PERCENT - otherSide)
        .coerceAtLeast(0)
