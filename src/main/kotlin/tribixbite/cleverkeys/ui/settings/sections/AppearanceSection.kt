package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.saveSetting

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
                    valueRange = 20f..60f,
                    steps = 40,
                    onValueChange = {
                        keyboardHeight = it.toInt()
                        saveSetting("keyboard_height", keyboardHeight)
                    },
                    displayValue = "$keyboardHeight%"
                )

                SettingsSlider(
                    title = stringResource(R.string.appearance_height_landscape_title),
                    description = stringResource(R.string.appearance_height_landscape_desc),
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
                    title = stringResource(R.string.appearance_bottom_margin_portrait_title),
                    description = stringResource(R.string.appearance_bottom_margin_portrait_desc),
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
                    title = stringResource(R.string.appearance_bottom_margin_landscape_title),
                    description = stringResource(R.string.appearance_bottom_margin_landscape_desc),
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
                    title = stringResource(R.string.appearance_left_margin_portrait_title),
                    description = stringResource(R.string.appearance_left_margin_portrait_desc),
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
                    title = stringResource(R.string.appearance_right_margin_portrait_title),
                    description = stringResource(R.string.appearance_right_margin_portrait_desc),
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
                    title = stringResource(R.string.appearance_left_margin_landscape_title),
                    description = stringResource(R.string.appearance_left_margin_landscape_desc),
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
                    title = stringResource(R.string.appearance_right_margin_landscape_title),
                    description = stringResource(R.string.appearance_right_margin_landscape_desc),
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
                    title = stringResource(R.string.appearance_label_brightness_title),
                    description = stringResource(R.string.appearance_label_brightness_desc),
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
                    title = stringResource(R.string.appearance_keyboard_opacity_title),
                    description = stringResource(R.string.appearance_keyboard_opacity_desc),
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
                    title = stringResource(R.string.appearance_key_opacity_title),
                    description = stringResource(R.string.appearance_key_opacity_desc),
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
                    title = stringResource(R.string.appearance_activated_key_opacity_title),
                    description = stringResource(R.string.appearance_activated_key_opacity_desc),
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
                    title = stringResource(R.string.appearance_character_size_title),
                    description = stringResource(R.string.appearance_character_size_desc),
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
                    title = stringResource(R.string.appearance_secondary_label_size_title),
                    description = stringResource(R.string.appearance_secondary_label_size_desc),
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
                    title = stringResource(R.string.appearance_key_vertical_margin_title),
                    description = stringResource(R.string.appearance_key_vertical_margin_desc),
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
                    title = stringResource(R.string.appearance_key_horizontal_margin_title),
                    description = stringResource(R.string.appearance_key_horizontal_margin_desc),
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
                        valueRange = 0f..20f,
                        steps = 20,
                        onValueChange = {
                            customBorderRadius = it.toInt()
                            saveSetting("custom_border_radius", customBorderRadius)
                        },
                        displayValue = "${customBorderRadius}dp"
                    )

                    SettingsSlider(
                        title = stringResource(R.string.appearance_border_line_width_title),
                        description = stringResource(R.string.appearance_border_line_width_desc),
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
}
