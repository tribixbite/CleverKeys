package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import tribixbite.cleverkeys.ui.settings.openNeuralSettings
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.NeuralPredictionSection() {
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_neural),
                expanded = neuralSectionExpanded,
                onExpandChange = { neuralSectionExpanded = it }
            ) {
                // Master switch for swipe typing
                SettingsSwitch(
                    title = stringResource(R.string.neural_enable_swipe_title),
                    description = stringResource(R.string.neural_enable_swipe_desc),
                    checked = swipeTypingEnabled,
                    onCheckedChange = {
                        swipeTypingEnabled = it
                        saveSetting("swipe_typing_enabled", it)
                    },
                    highlightId = "swipe_typing"
                )

                if (swipeTypingEnabled) {
                    // WP9 R-1 step 7 (v1.1): engine mode selector. Hybrid = neural on QWERTY +
                    // geometric elsewhere; Neural = QWERTY-only swipe (pre-geo behavior);
                    // Geometric = SHARK2 on all layouts; CTC (G5) = CTC trie-beam on Latin
                    // layouts for the validated languages (en/fr/de/es —
                    // swipe.ctc.CtcLanguageSupport), with the audit-M1 fallthrough for any
                    // other language: neural on QWERTY, geometric elsewhere.
                    SettingsDropdown(
                        title = stringResource(R.string.swipe_engine_mode_title),
                        description = stringResource(R.string.swipe_engine_mode_desc),
                        options = listOf("Hybrid", "Neural", "Geometric", "CTC"),
                        selectedIndex = when (swipeEngineMode) {
                            "hybrid" -> 0
                            "geometric" -> 2
                            "ctc" -> 3
                            else -> 1 // "neural" (default)
                        },
                        onSelectionChange = { index ->
                            swipeEngineMode = when (index) {
                                0 -> "hybrid"
                                2 -> "geometric"
                                3 -> "ctc"
                                else -> "neural"
                            }
                            saveSetting("swipe_engine_mode", swipeEngineMode)
                        }
                    )
                }

                // #9: Warning when the layout has no swipe engine — only in Neural mode
                // (Hybrid/Geometric cover non-QWERTY layouts via the geometric engine).
                if (swipeTypingEnabled && !currentLayoutSupportsSwipe && swipeEngineMode == "neural") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "The neural engine requires a QWERTY layout. " +
                                "Your current layout ($currentLayoutName) uses different key positions — " +
                                "swipe typing is temporarily disabled on it.\n\n" +
                                "Switch the Prediction Engine to Hybrid or Geometric to enable " +
                                "swipe typing on this layout, or switch to a QWERTY layout.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                if (swipeTypingEnabled) {
                    // #39: Option to enable swipe typing on password fields
                    SettingsSwitch(
                        title = stringResource(R.string.neural_swipe_password_title),
                        description = stringResource(R.string.neural_swipe_password_desc),
                        checked = swipeOnPasswordFields,
                        onCheckedChange = {
                            swipeOnPasswordFields = it
                            saveSetting("swipe_on_password_fields", it)
                        }
                    )

                    // Beam Width / Maximum Word Length / Confidence Threshold moved into the
                    // Full Neural Settings screen (they were duplicated there already) — the
                    // main section stays engine-agnostic.
                    Button(
                        onClick = { openNeuralSettings() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text("Full Neural Settings")
                    }

                    // Geometric engine tuning — only meaningful when a mode that uses it
                    // is on (hybrid/geometric always; ctc uses it for non-QWERTY layouts).
                    if (swipeEngineMode == "hybrid" || swipeEngineMode == "geometric" ||
                        swipeEngineMode == "ctc"
                    ) {
                        Button(
                            onClick = { openGeometricSettings() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Text("Full Geometric Settings")
                        }
                    }

                    // CTC engine tuning (G5) — only under the ctc mode.
                    if (swipeEngineMode == "ctc") {
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
