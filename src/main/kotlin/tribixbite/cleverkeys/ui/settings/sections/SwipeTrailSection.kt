package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.SwipeTrailSection() {
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_swipe_trail),
                expanded = swipeTrailSectionExpanded,
                onExpandChange = { swipeTrailSectionExpanded = it }
            ) {
                SettingsSwitch(
                    title = stringResource(R.string.swipe_trail_enable_title),
                    description = stringResource(R.string.swipe_trail_enable_desc),
                    checked = swipeTrailEnabled,
                    onCheckedChange = {
                        swipeTrailEnabled = it
                        saveSetting("swipe_trail_enabled", it)
                    }
                )

                if (swipeTrailEnabled) {
                    // Trail effect dropdown
                    SettingsDropdown(
                        title = stringResource(R.string.swipe_trail_effect_title),
                        description = stringResource(R.string.swipe_trail_effect_desc),
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
                        title = stringResource(R.string.swipe_trail_width_title),
                        description = stringResource(R.string.swipe_trail_width_desc),
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
                            title = stringResource(R.string.swipe_trail_glow_radius_title),
                            description = stringResource(R.string.swipe_trail_glow_radius_desc),
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
                        title = stringResource(R.string.swipe_trail_color_title),
                        description = stringResource(R.string.swipe_trail_color_desc),
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
}
