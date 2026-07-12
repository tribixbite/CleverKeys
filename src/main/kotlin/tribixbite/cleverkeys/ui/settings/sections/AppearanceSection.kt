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

                // #133: independent sizing for the small secondary (flick) labels
                // so increasing primary Character Size doesn't crowd/overlap them.
                SettingsSlider(
                    title = "Secondary Label Size",
                    description = "Size of the small corner (flick) labels, independent of Character Size",
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
}
