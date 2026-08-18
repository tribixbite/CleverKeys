package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.openCtcSettings
import tribixbite.cleverkeys.ui.settings.openGeometricSettings
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.SwipeTypingSection() {
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_swipe_typing),
                expanded = swipeTypingSectionExpanded,
                onExpandChange = { swipeTypingSectionExpanded = it }
            ) {
                // Master switch for swipe typing
                SettingsSwitch(
                    title = stringResource(R.string.swipe_enable_title),
                    description = stringResource(R.string.swipe_enable_desc),
                    checked = swipeTypingEnabled,
                    onCheckedChange = {
                        swipeTypingEnabled = it
                        saveSetting("swipe_typing_enabled", it)
                    },
                    highlightId = "swipe_typing"
                )

                if (swipeTypingEnabled) {
                    // WP9 R-1 step 7 (v1.2): engine mode selector. CTC (default) = CTC
                    // trie-beam on Latin layouts for the validated languages (en/fr/de/es —
                    // swipe.ctc.CtcLanguageSupport), geometric for every other language and
                    // for non-Latin layouts; Geometric = SHARK2 on all layouts. The
                    // Neural/Hybrid modes were removed with the neural engine (2026-08-18);
                    // a stored "neural"/"hybrid" resolves to CTC in the router and this
                    // selector shows CTC for it.
                    SettingsDropdown(
                        title = stringResource(R.string.swipe_engine_mode_title),
                        description = stringResource(R.string.swipe_engine_mode_desc),
                        options = listOf("CTC", "Geometric"),
                        selectedIndex = when (swipeEngineMode) {
                            "geometric" -> 1
                            else -> 0 // "ctc" (default) + any legacy value
                        },
                        onSelectionChange = { index ->
                            swipeEngineMode = when (index) {
                                1 -> "geometric"
                                else -> "ctc"
                            }
                            saveSetting("swipe_engine_mode", swipeEngineMode)
                        }
                    )
                }

                if (swipeTypingEnabled) {
                    // #39: Option to enable swipe typing on password fields
                    SettingsSwitch(
                        title = stringResource(R.string.swipe_password_title),
                        description = stringResource(R.string.swipe_password_desc),
                        checked = swipeOnPasswordFields,
                        onCheckedChange = {
                            swipeOnPasswordFields = it
                            saveSetting("swipe_on_password_fields", it)
                        }
                    )

                    // Geometric engine tuning — always reachable: geometric mode uses it
                    // everywhere, and ctc mode uses it for every language/layout CTC does
                    // not serve.
                    Button(
                        onClick = { openGeometricSettings() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text("Full Geometric Settings")
                    }

                    // CTC engine tuning (G5) — only under the ctc mode.
                    if (swipeEngineMode != "geometric") {
                        Button(
                            onClick = { openCtcSettings() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Text("Full CTC Settings")
                        }
                    }
                }
            }
}
