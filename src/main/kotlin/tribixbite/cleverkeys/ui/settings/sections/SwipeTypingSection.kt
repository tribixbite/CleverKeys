package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.swipe.CtcInstalledPacks
import tribixbite.cleverkeys.swipe.SwipeEngineFallback
import tribixbite.cleverkeys.swipe.SwipeEngineRouter
import tribixbite.cleverkeys.swipe.ctc.CtcImportedPackSupport
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.openCtcSettings
import tribixbite.cleverkeys.ui.settings.openGeometricSettings
import tribixbite.cleverkeys.ui.settings.saveSetting

/**
 * What the swipe-engine fallback card needs from disk, read once per primary-language change on
 * [Dispatchers.IO] rather than during composition: the imported pack's eligibility measurement
 * (null when this language has no pack, or is served from a bundled asset) and the full list of
 * languages CTC serves on THIS device.
 */
private class CtcFallbackSurface(
    val report: CtcImportedPackSupport.Report?,
    val servedCodes: List<String>,
)

@Composable
internal fun SettingsActivity.SwipeTypingSection() {
            // Captured for the IO block below: inside `produceState` the receiver is
            // ProduceStateScope, not the activity.
            val activity = this
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
                    // will run. CTC serves the languages in CtcLanguageSupport.SUPPORTED plus any
                    // imported language pack this device measured as a-z-typeable; everything
                    // else falls through to geometric silently. Without this card the dropdown
                    // says "CTC" while geometric does the work, and the only way to find out is
                    // to notice the accuracy difference.
                    //
                    // This existed once and was lost with NeuralPredictionSection in the engine
                    // removal, so it is stated positively — name the engine that WILL run rather
                    // than only what will not.
                    //
                    // The measurement runs HERE, at selection time, on Dispatchers.IO: this is
                    // where the user chose the language and where a refusal can be explained. It
                    // writes through to the same cached verdict the swipe path reads, so the
                    // answer this card gives is the answer the keyboard will act on. Keyed on the
                    // primary language, so re-selecting re-measures a pack that changed.
                    // `remember(primaryLanguage)` resets to null on a language switch, so the
                    // card can never show the PREVIOUS language's refusal reason while the new
                    // one is still being measured.
                    var ctcSurface by remember(primaryLanguage) {
                        mutableStateOf<CtcFallbackSurface?>(null)
                    }
                    LaunchedEffect(primaryLanguage) {
                        ctcSurface = withContext(Dispatchers.IO) {
                            CtcFallbackSurface(
                                report = CtcInstalledPacks.evaluateNow(activity, primaryLanguage),
                                servedCodes = CtcLanguageSupport.SUPPORTED.keys.toList() +
                                    CtcInstalledPacks.servedCodes(activity),
                            )
                        }
                    }
                    val fallback = SwipeEngineFallback.diagnose(
                        mode = SwipeEngineRouter.Mode.fromPref(swipeEngineMode),
                        language = primaryLanguage,
                        layouts = config.layouts
                            .filterNotNull()
                            .map { SwipeEngineFallback.factsFor(it, primaryLanguage) },
                    )
                    if (fallback.hasAny) {
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
                                if (fallback.languageFallback) {
                                    Text(
                                        text = stringResource(
                                            R.string.swipe_engine_fallback_desc,
                                            primaryLanguage.uppercase(),
                                            // SUPPORTED.keys alone would UNDERSTATE coverage on a
                                            // device with an eligible imported pack.
                                            (ctcSurface?.servedCodes
                                                ?: CtcLanguageSupport.SUPPORTED.keys.toList())
                                                .distinct()
                                                .joinToString(", ") { it.uppercase() }
                                        ),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // When an installed pack was measured but refused, explain the
                                    // measured reason rather than claiming only that it is absent.
                                    ctcSurface?.report?.let { report ->
                                        val reason = when (report.verdict) {
                                            CtcImportedPackSupport.Verdict.NOT_AZ_PROJECTABLE ->
                                                stringResource(
                                                    R.string.swipe_engine_pack_not_typeable,
                                                    primaryLanguage.uppercase(),
                                                    report.projectablePercent
                                                )
                                            CtcImportedPackSupport.Verdict.HEAD_NOT_AZ_PROJECTABLE ->
                                                stringResource(
                                                    R.string.swipe_engine_pack_head_not_typeable,
                                                    primaryLanguage.uppercase()
                                                )
                                            CtcImportedPackSupport.Verdict.TOO_FEW_WORDS ->
                                                stringResource(
                                                    R.string.swipe_engine_pack_unusable,
                                                    primaryLanguage.uppercase()
                                                )
                                            else -> null
                                        }
                                        if (reason != null) {
                                            Text(
                                                text = reason,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                                fallback.layoutFindings.forEach { finding ->
                                    val reason = when (finding.reason) {
                                        SwipeEngineFallback.LayoutReason.SCRIPT_NOT_ROUTED ->
                                            stringResource(
                                                R.string.swipe_engine_layout_script_fallback,
                                                finding.layout.displayName,
                                            )
                                        SwipeEngineFallback.LayoutReason.LETTERS_CORNER_ONLY ->
                                            stringResource(
                                                R.string.swipe_engine_layout_corner_fallback,
                                                finding.layout.displayName,
                                                finding.lettersForDisplay,
                                            )
                                        SwipeEngineFallback.LayoutReason.ALPHABET_INCOMPLETE ->
                                            stringResource(
                                                R.string.swipe_engine_layout_incomplete_fallback,
                                                finding.layout.displayName,
                                                finding.lettersForDisplay,
                                            )
                                    }
                                    Text(
                                        text = reason,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
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
                        Text(stringResource(R.string.swipe_full_geometric_settings))
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
                            Text(stringResource(R.string.swipe_full_ctc_settings))
                        }
                    }
                }
            }
}
