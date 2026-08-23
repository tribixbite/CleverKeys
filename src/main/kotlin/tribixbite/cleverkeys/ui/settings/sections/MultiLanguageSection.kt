package tribixbite.cleverkeys.ui.settings.sections

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.io.deleteLanguagePack
import tribixbite.cleverkeys.ui.settings.io.getLanguageDisplayName
import tribixbite.cleverkeys.ui.settings.io.importLanguagePack
import tribixbite.cleverkeys.ui.settings.io.rescanContractionCollisions
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.MultiLanguageSection() {
            // Multi-Language Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_multilang),
                expanded = multiLangSectionExpanded,
                onExpandChange = { multiLangSectionExpanded = it }
            ) {
                SettingsSwitch(
                    title = stringResource(R.string.multilang_enable_title),
                    description = stringResource(R.string.multilang_enable_desc),
                    checked = multiLangEnabled,
                    onCheckedChange = {
                        multiLangEnabled = it
                        saveSetting("pref_enable_multilang", it)
                    },
                    highlightId = "multilang"
                )

                if (multiLangEnabled) {
                    // Primary Language selector - any QWERTY-compatible language
                    // NN outputs 26 letters, dictionary provides accent recovery
                    // v1.1.94: Filter out "en" from availableSecondaryLanguages to avoid duplicate
                    val primaryOptions = listOf("en") + availableSecondaryLanguages.filter { it != "en" }
                    val primaryDisplayOptions = primaryOptions.map { getLanguageDisplayName(it) }
                    val primarySelectedIndex = primaryOptions.indexOf(primaryLanguage).coerceAtLeast(0)

                    SettingsDropdown(
                        title = stringResource(R.string.multilang_primary_title),
                        description = stringResource(R.string.multilang_primary_desc),
                        options = primaryDisplayOptions,
                        selectedIndex = primarySelectedIndex,
                        onSelectionChange = { index ->
                            primaryLanguage = primaryOptions.getOrElse(index) { "en" }
                            saveSetting("pref_primary_language", primaryLanguage)
                        rescanContractionCollisions()
                            // Reload per-language prefix boost settings
                        }
                    )

                    // Secondary Language selector - shows available V2 dictionaries
                    val secondaryOptions = listOf("none") + availableSecondaryLanguages.filter { it != primaryLanguage }
                    val secondaryDisplayOptions = secondaryOptions.map { getLanguageDisplayName(it) }
                    val secondarySelectedIndex = secondaryOptions.indexOf(secondaryLanguage).coerceAtLeast(0)

                    SettingsDropdown(
                        title = stringResource(R.string.multilang_secondary_title),
                        description = if (availableSecondaryLanguages.isEmpty())
                            "No additional dictionaries available"
                        else
                            "Enable bilingual predictions (e.g., English + Spanish)",
                        options = secondaryDisplayOptions,
                        selectedIndex = secondarySelectedIndex,
                        onSelectionChange = { index ->
                            secondaryLanguage = secondaryOptions.getOrElse(index) { "none" }
                            saveSetting("pref_secondary_language", secondaryLanguage)
                        rescanContractionCollisions()
                            // Dictionary reload triggered via PreferenceUIUpdateHandler.reloadLanguageDictionaryIfNeeded()
                        }
                    )

                    if (secondaryLanguage != "none") {
                        Text(
                            text = "Secondary dictionary will be loaded on next keyboard open. " +
                                   "Words from both languages will appear in predictions.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                        )

                        // v1.1.94: Secondary language prediction weight slider
                        SettingsSlider(
                            title = stringResource(R.string.multilang_secondary_weight_title),
                            description = stringResource(R.string.multilang_secondary_weight_desc),
                            value = secondaryPredictionWeight,
                            valueRange = 0.5f..1.5f,
                            steps = 20,
                            onValueChange = {
                                secondaryPredictionWeight = it
                                saveSetting("pref_secondary_prediction_weight", secondaryPredictionWeight)
                            },
                            displayValue = "%.2f".format(secondaryPredictionWeight)
                        )
                    }

                    SettingsSwitch(
                        title = stringResource(R.string.multilang_auto_detect_title),
                        description = stringResource(R.string.multilang_auto_detect_desc),
                        checked = autoDetectLanguage,
                        onCheckedChange = {
                            autoDetectLanguage = it
                            saveSetting("pref_auto_detect_language", it)
                        }
                    )

                    if (autoDetectLanguage) {
                        SettingsSlider(
                            title = stringResource(R.string.multilang_detection_sensitivity_title),
                            description = stringResource(R.string.multilang_detection_sensitivity_desc),
                            value = languageDetectionSensitivity,
                            valueRange = 0.4f..0.9f,
                            steps = 10,
                            onValueChange = {
                                languageDetectionSensitivity = it
                                saveSetting("pref_language_detection_sensitivity", languageDetectionSensitivity)
                            },
                            displayValue = "%.2f".format(languageDetectionSensitivity)
                        )
                    }

                    // The per-language "Prefix Boost" block (boost strength / max boost /
                    // max cumulative boost / strict start char) was removed on 2026-08-18.
                    // Every one of those sliders wrote a `neural_prefix_boost_*` pref read
                    // ONLY by the deleted transformer's beam search; the CTC and geometric
                    // engines score candidates from the lexicon and key geometry instead.

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick Language Toggle Section (v1.2.0)
                    Text(
                        text = "Quick Language Toggle",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Configure alternate languages for quick toggle commands. " +
                               "Assign PRIMARY_LANG_TOGGLE or SECONDARY_LANG_TOGGLE to any key's short swipe.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Alternate Primary Language selector
                    val altPrimaryOptions = availableSecondaryLanguages.filter { it != primaryLanguage }
                    if (altPrimaryOptions.isNotEmpty()) {
                        val altPrimaryDisplayOptions = altPrimaryOptions.map { getLanguageDisplayName(it) }
                        val altPrimarySelectedIndex = altPrimaryOptions.indexOf(primaryLanguageAlt).coerceAtLeast(0)

                        SettingsDropdown(
                            title = stringResource(R.string.multilang_alternate_primary_title),
                            description = stringResource(R.string.multilang_toggle_between, primaryLanguage, primaryLanguageAlt),
                            options = altPrimaryDisplayOptions,
                            selectedIndex = altPrimarySelectedIndex,
                            onSelectionChange = { index ->
                                primaryLanguageAlt = altPrimaryOptions.getOrElse(index) { "es" }
                                saveSetting("pref_primary_language_alt", primaryLanguageAlt)
                                rescanContractionCollisions()
                            }
                        )
                    }

                    // Alternate Secondary Language selector
                    val altSecondaryOptions = listOf("none") + availableSecondaryLanguages.filter {
                        it != secondaryLanguage && it != primaryLanguage
                    }
                    val altSecondaryDisplayOptions = altSecondaryOptions.map { getLanguageDisplayName(it) }
                    val altSecondarySelectedIndex = altSecondaryOptions.indexOf(secondaryLanguageAlt).coerceAtLeast(0)

                    SettingsDropdown(
                        title = stringResource(R.string.multilang_alternate_secondary_title),
                        description = stringResource(R.string.multilang_toggle_between, getLanguageDisplayName(secondaryLanguage), getLanguageDisplayName(secondaryLanguageAlt)),
                        options = altSecondaryDisplayOptions,
                        selectedIndex = altSecondarySelectedIndex,
                        onSelectionChange = { index ->
                            secondaryLanguageAlt = altSecondaryOptions.getOrElse(index) { "none" }
                            saveSetting("pref_secondary_language_alt", secondaryLanguageAlt)
                            rescanContractionCollisions()
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Language Packs Section
                    Text(
                        text = "Language Packs",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Import additional language dictionaries from ZIP files. " +
                               "Download packs externally and import here (no internet permission needed).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // Link to the prebuilt packs folder on GitHub (opens the browser; SAF import below).
                    Text(
                        text = "Browse available packs ↗",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsActivity.LANGUAGE_PACKS_URL)))
                            }
                            .padding(bottom = 8.dp)
                    )

                    // Installed packs count
                    Text(
                        text = "Installed: ${installedLanguagePacks.size} language pack(s)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { importLanguagePack() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import Pack")
                        }
                        OutlinedButton(
                            onClick = { showLanguagePackDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Manage")
                        }
                    }

                    // Import status message
                    languagePackImportStatus?.let { status ->
                        Text(
                            text = status,
                            fontSize = 11.sp,
                            color = if (status.startsWith("Error"))
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // Cross-language contraction collision warning (2026-08-20).
            //
            // Shown ONLY when an imported pack contributed a collision. Bundled-language
            // collisions are handled by the shipped sidecars and were handled before the user
            // touched anything, so announcing those too would be noise — and a dialog that
            // usually says nothing actionable is a dialog people learn to dismiss unread.
            //
            // The framing is deliberately "this was prevented", not "this is broken": by the
            // time the dialog appears the demotion is already cached and will apply on the next
            // language load. The user is being told what their pack does, not asked to fix it.
            if (showCollisionWarningDialog) {
                AlertDialog(
                    onDismissRequest = { showCollisionWarningDialog = false },
                    title = { Text(stringResource(R.string.collision_warning_title)) },
                    text = {
                        Column {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.collision_warning_body,
                                    collisionWarningKeyCount,
                                    collisionWarningKeyCount,
                                    collisionWarningLanguages,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (collisionWarningExamples.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.collision_warning_examples),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                collisionWarningExamples.forEach { (key, display) ->
                                    Text(
                                        text = "  $key → $display",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCollisionWarningDialog = false }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                )
            }

            // Language Pack Management Dialog
            if (showLanguagePackDialog) {
                AlertDialog(
                    onDismissRequest = { showLanguagePackDialog = false },
                    title = { Text("Installed Language Packs") },
                    text = {
                        Column {
                            if (installedLanguagePacks.isEmpty()) {
                                Text(
                                    text = "No language packs installed.\n\n" +
                                           "Use 'Browse available packs' to download a ZIP, then 'Import Pack' to add it.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                installedLanguagePacks.forEach { pack ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = pack.name,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = "Code: ${pack.code} • ${pack.wordCount} words",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            TextButton(
                                                onClick = { deleteLanguagePack(pack.code) }
                                            ) {
                                                Text("Delete", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLanguagePackDialog = false }) {
                            Text("Close")
                        }
                    }
                )
            }
}
