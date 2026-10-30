package tribixbite.cleverkeys.ui.settings.sections

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.stt.VoicePackManager
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.io.VoiceImportStatus
import tribixbite.cleverkeys.ui.settings.io.onVoiceSwitchChanged
import tribixbite.cleverkeys.ui.settings.io.openMicAppSettings
import tribixbite.cleverkeys.ui.settings.io.performVoicePackImport
import tribixbite.cleverkeys.ui.settings.io.readVoicePackNotice
import tribixbite.cleverkeys.ui.settings.io.refreshVoiceInputState
import tribixbite.cleverkeys.ui.settings.io.removeVoicePack

/**
 * Settings → Voice input (docs/specs/voice-input.md FR-6/FR-7). Off by default. The switch and a
 * successful pack import are the only two places the microphone permission is requested.
 */
@Composable
internal fun SettingsActivity.VoiceInputSection() {
    var noticePackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { refreshVoiceInputState(this@VoiceInputSection) }

    CollapsibleSettingsSection(
        title = stringResource(R.string.settings_section_voice_input),
        expanded = voiceSectionExpanded,
        onExpandChange = { voiceSectionExpanded = it },
        // The keyboard's "set up voice input" redirect lands on the section header.
        sectionId = "voice_input",
    ) {
        Text(
            text = stringResource(R.string.voice_section_intro),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        SettingsSwitch(
            title = stringResource(R.string.voice_enable_title),
            description = stringResource(R.string.voice_enable_desc),
            checked = voiceInputEnabled,
            highlightId = "voice_input_enabled",
            onCheckedChange = { onVoiceSwitchChanged(this@VoiceInputSection, it) },
        )

        if (voicePermissionBlocked) {
            Text(
                text = stringResource(R.string.voice_permission_blocked_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            OutlinedButton(
                // Granting there while the switch is off turns voice input on when the user returns.
                onClick = { openMicAppSettings(this@VoiceInputSection, toEnable = !voiceInputEnabled) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.voice_open_app_settings)) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Voice packs live on the same GitHub `langpacks` release as the language packs; the
        // app has no INTERNET permission, so this opens the browser.
        Text(
            text = stringResource(R.string.voice_get_packs),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsActivity.LANGUAGE_PACKS_URL)))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this@VoiceInputSection, getString(R.string.gif_toast_no_browser), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 6.dp),
        )

        Button(
            onClick = {
                try {
                    voicePackImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@VoiceInputSection, getString(R.string.gif_toast_no_file_picker), android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !voiceImportInProgress,
        ) {
            if (voiceImportInProgress) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.voice_importing))
            } else {
                Text(stringResource(R.string.voice_import_pack))
            }
        }

        voiceImportStatus?.let { status ->
            Text(
                text = status.message,
                fontSize = 12.sp,
                color = when (status) {
                    is VoiceImportStatus.Failed -> MaterialTheme.colorScheme.error
                    is VoiceImportStatus.Ok -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (installedVoicePacks.isEmpty() && unsupportedVoicePacks.isEmpty()) {
            Text(
                text = stringResource(R.string.voice_no_pack),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (installedVoicePacks.isNotEmpty()) {
            Text(
                text = stringResource(R.string.voice_installed_pack),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            installedVoicePacks.forEach { pack ->
                VoicePackRow(pack, supported = true, onNotice = { noticePackId = pack.manifest.id })
            }
        }
        unsupportedVoicePacks.forEach { pack ->
            VoicePackRow(pack, supported = false, onNotice = { noticePackId = pack.manifest.id })
        }
    }

    // The switch (or an import) asked and Android refused without showing a prompt.
    if (voiceBlockedDialogShown) {
        AlertDialog(
            onDismissRequest = { voiceBlockedDialogShown = false },
            title = { Text(stringResource(R.string.voice_permission_blocked_title)) },
            text = { Text(stringResource(R.string.voice_permission_blocked_steps)) },
            confirmButton = {
                TextButton(onClick = { openMicAppSettings(this@VoiceInputSection, toEnable = true) }) {
                    Text(stringResource(R.string.voice_open_app_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { voiceBlockedDialogShown = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    noticePackId?.let { id ->
        var text by remember(id) { mutableStateOf("") }
        LaunchedEffect(id) { text = readVoicePackNotice(this@VoiceInputSection, id) }
        AlertDialog(
            onDismissRequest = { noticePackId = null },
            title = { Text(stringResource(R.string.voice_pack_notice)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { noticePackId = null }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
}

/** One installed pack: name, size, engine, languages, licence; notice and remove actions. */
@Composable
private fun SettingsActivity.VoicePackRow(pack: VoicePackManager.InstalledPack, supported: Boolean, onNotice: () -> Unit) {
    val m = pack.manifest
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(m.name, fontSize = 14.sp)
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        if (!supported) {
            Text(stringResource(R.string.voice_pack_unsupported, m.name), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.voice_pack_size, Formatter.formatShortFileSize(this@VoicePackRow, pack.bytes)), fontSize = 11.sp, color = secondary)
        Text(stringResource(R.string.voice_pack_engine, m.engineId, m.engineVersion), fontSize = 11.sp, color = secondary)
        Text(stringResource(R.string.voice_pack_languages, m.languages.joinToString(", ")), fontSize = 11.sp, color = secondary)
        m.license?.let { Text(stringResource(R.string.voice_pack_license, it), fontSize = 11.sp, color = secondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onNotice) { Text(stringResource(R.string.voice_pack_notice)) }
            TextButton(onClick = { removeVoicePack(this@VoicePackRow, m.id) }) {
                Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
