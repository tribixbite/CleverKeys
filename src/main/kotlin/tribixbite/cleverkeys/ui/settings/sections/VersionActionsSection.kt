package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.GitHubInfoCard
import tribixbite.cleverkeys.ui.settings.VersionInfoCard
import tribixbite.cleverkeys.ui.settings.resetAllSettings

@Composable
internal fun SettingsActivity.VersionActionsSection() {
            // Version and Actions Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_info),
                expanded = infoSectionExpanded,
                onExpandChange = { infoSectionExpanded = it }
            ) {
                VersionInfoCard()

                // GitHub release info
                GitHubInfoCard()

                // Reset settings button
                Button(
                    onClick = { resetAllSettings() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.settings_reset_button))
                }

                // Note: Self-update feature removed for F-Droid compliance
                // F-Droid handles updates automatically

            }
}
