package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.FAQSection
import tribixbite.cleverkeys.ui.settings.openWikiInBrowser

@Composable
internal fun SettingsActivity.HelpSection() {
            // Help Section (Collapsible) - FAQ and Wiki
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_help),
                expanded = helpSectionExpanded,
                onExpandChange = { helpSectionExpanded = it }
            ) {
                // FAQ Items
                FAQSection()

                Spacer(modifier = Modifier.height(16.dp))

                // Online Wiki Button
                Button(
                    onClick = { openWikiInBrowser() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.help_open_wiki))
                }
            }
}
