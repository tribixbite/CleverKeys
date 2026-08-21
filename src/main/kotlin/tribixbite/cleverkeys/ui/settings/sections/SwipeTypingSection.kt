package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport
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
                    // trie-beam on Latin layouts for the seven served languages — en/fr/de/es
                    // plus the provisional it/pt/sv, swipe.ctc.CtcLanguageSupport is the
                    // table — geometric for every other language and
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

                    // MEDIUM-7: tell the user when the engine they picked is not the one that
                    // will run. CTC serves only the languages in CtcLanguageSupport.SUPPORTED;
                    // everything else falls through to geometric silently. Without this card the
                    // dropdown says "CTC" while geometric does the work, and the only way to
                    // find out is to notice the accuracy difference.
                    //
                    // This existed once and was lost with NeuralPredictionSection in the engine
                    // removal, so it is stated positively — name the engine that WILL run rather
                    // than only what will not.
                    if (swipeEngineMode != "geometric" &&
                        !CtcLanguageSupport.isSupported(primaryLanguage)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = stringResource(R.string.swipe_engine_fallback_title),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(
                                        R.string.swipe_engine_fallback_desc,
                                        primaryLanguage.uppercase(),
                                        CtcLanguageSupport.SUPPORTED.keys.joinToString(", ") {
                                            it.uppercase()
                                        }
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
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

                    // Context rescoring (docs/specs/ctc-context-rescoring-and-tunables.md).
                    //
                    // Default OFF and it must stay OFF until the offline replay harness produces
                    // evidence — flipping the default is an evidence-gated release decision, not
                    // a code change. Shown for BOTH engines on purpose: the rescorer runs on the
                    // engine slate at `handleSwipePredictionResults`, which both CTC and geometric
                    // pass through, so gating the toggle on engine mode would hide a setting that
                    // does apply.
                    //
                    // The copy names LEARNED DATA explicitly. That is what makes this a
                    // privacy-relevant choice rather than a neutral accuracy toggle, and it is
                    // also honest about why it may appear to do nothing: with nothing learned,
                    // every boost is 1.0 and the ranking is identical by construction.
                    SettingsSwitch(
                        title = stringResource(R.string.swipe_context_rescoring_title),
                        description = stringResource(R.string.swipe_context_rescoring_desc),
                        checked = swipeContextRescoring,
                        onCheckedChange = {
                            swipeContextRescoring = it
                            saveSetting("swipe_context_rescoring", it)
                        },
                        highlightId = "swipe_context_rescoring"
                    )

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
