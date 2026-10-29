package tribixbite.cleverkeys.ui.settings.io

import android.Manifest
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResourcesResultText
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.stt.MicPermissionGate
import tribixbite.cleverkeys.stt.VoicePackImportFailure
import tribixbite.cleverkeys.stt.VoicePackManager
import tribixbite.cleverkeys.stt.VoicePacks
import tribixbite.cleverkeys.ui.settings.saveSetting

/**
 * The Voice input section's import/permission plumbing (docs/specs/voice-input.md FR-6/FR-7).
 * Typed outcome so the section paints failures in the error colour in any language (the
 * ARC-075 rule the GIF import follows).
 */
sealed interface VoiceImportStatus {
    val message: String

    data class Ok(override val message: String) : VoiceImportStatus
    data class Failed(override val message: String) : VoiceImportStatus
}

private const val TAG = "SettingsVoice"

/** What the microphone request was for, so its result can finish the job. */
private enum class PendingMicPurpose { ENABLE, AFTER_IMPORT }

private var pendingMicPurpose: PendingMicPurpose? = null

/** Re-read the voice state (packs, switch, permission) — on resume and after every change. */
internal fun refreshVoiceInputState(activity: SettingsActivity) {
    with(activity) {
        val prefs = tribixbite.cleverkeys.DirectBootAwarePreferences.get_shared_preferences(this)
        val enabled = VoicePacks.isEnabled(prefs)
        // An enabled switch whose permission was revoked in system settings stays on: the keyboard
        // asks again on the next key press (MicPermissionGate KEY_PRESS), which is an opt-in moment.
        voiceInputEnabled = enabled
        voicePermissionBlocked = !VoicePacks.hasMicPermission(this) && VoicePacks.isPermissionBlocked(prefs)
        lifecycleScope.launch {
            val (ok, unsupported) = withContext(Dispatchers.IO) {
                val m = VoicePacks.manager(this@with)
                m.installedPacks() to m.unsupportedPacks()
            }
            installedVoicePacks = ok
            unsupportedVoicePacks = unsupported
        }
    }
}

/** The switch was flipped. ON goes through the gate: it may ask for the microphone first. */
internal fun onVoiceSwitchChanged(activity: SettingsActivity, on: Boolean) {
    with(activity) {
        if (!on) {
            setVoiceEnabled(this, false)
            return
        }
        val prefs = tribixbite.cleverkeys.DirectBootAwarePreferences.get_shared_preferences(this)
        when (MicPermissionGate.decide(MicPermissionGate.Trigger.SETTINGS_TOGGLE_ON, VoicePacks.facts(this, prefs))) {
            MicPermissionGate.Decision.PROCEED -> setVoiceEnabled(this, true)
            MicPermissionGate.Decision.EXPLAIN_BLOCKED -> {
                voicePermissionBlocked = true
                setVoiceEnabled(this, false)
            }
            else -> {
                pendingMicPurpose = PendingMicPurpose.ENABLE
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/** The system prompt answered (Settings' own request). */
internal fun onMicPermissionResult(activity: SettingsActivity, granted: Boolean) {
    with(activity) {
        val purpose = pendingMicPurpose
        pendingMicPurpose = null
        val prefs = tribixbite.cleverkeys.DirectBootAwarePreferences.get_shared_preferences(this)
        val blocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        prefs.edit().putBoolean(VoicePacks.PREF_PERMISSION_BLOCKED, blocked).apply()
        voicePermissionBlocked = blocked
        if (granted) {
            // Both opt-in moments end with voice input on.
            if (purpose != null) setVoiceEnabled(this, true)
        } else {
            setVoiceEnabled(this, false)
            Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
}

private fun setVoiceEnabled(activity: SettingsActivity, on: Boolean) {
    with(activity) {
        voiceInputEnabled = on
        saveSetting(VoicePacks.PREF_ENABLED, on)
        Config.globalConfigOrNull()?.voice_input_enabled = on
    }
}

/** SAF result: import the pack off the main thread, then (opt-in moment) ask for the mic. */
internal fun performVoicePackImport(activity: SettingsActivity, uri: Uri) {
    with(activity) {
        voiceImportInProgress = true
        voiceImportStatus = VoiceImportStatus.Ok(getString(R.string.voice_importing))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val input = contentResolver.openInputStream(uri)
                        ?: return@withContext VoicePackManager.ImportResult.Error(
                            VoicePackImportFailure.Io(tribixbite.cleverkeys.IoFailureReason.FILE_NOT_FOUND, "Cannot open $uri")
                        )
                    VoicePacks.manager(this@with).import(input)
                } catch (e: Exception) {
                    Log.e(TAG, "Voice pack import failed", e)
                    VoicePackManager.ImportResult.Error(VoicePackImportFailure.fromException(e))
                }
            }
            voiceImportInProgress = false
            when (result) {
                is VoicePackManager.ImportResult.Success -> {
                    voiceImportStatus = VoiceImportStatus.Ok(getString(R.string.voice_import_success, result.manifest.name))
                    refreshVoiceInputState(this@with)
                    val prefs = tribixbite.cleverkeys.DirectBootAwarePreferences.get_shared_preferences(this@with)
                    when (MicPermissionGate.decide(MicPermissionGate.Trigger.PACK_IMPORTED, VoicePacks.facts(this@with, prefs))) {
                        MicPermissionGate.Decision.PROCEED -> setVoiceEnabled(this@with, true)
                        MicPermissionGate.Decision.EXPLAIN_BLOCKED -> voicePermissionBlocked = true
                        else -> {
                            pendingMicPurpose = PendingMicPurpose.AFTER_IMPORT
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
                is VoicePackManager.ImportResult.Error -> {
                    Log.w(TAG, "Voice pack import refused: ${result.failure.logMessage}")
                    val reason = result.failure.render(ResourcesResultText(resources))
                    voiceImportStatus = VoiceImportStatus.Failed(getString(R.string.voice_import_failed, reason))
                }
            }
        }
    }
}

/** Remove an installed (or unsupported) pack. */
internal fun removeVoicePack(activity: SettingsActivity, id: String) {
    with(activity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { VoicePacks.manager(this@with).remove(id) }
            voiceImportStatus = null
            refreshVoiceInputState(this@with)
        }
    }
}

/** The pack's NOTICE.txt + LICENSE for the notice dialog (IO). */
internal suspend fun readVoicePackNotice(activity: SettingsActivity, id: String): String = withContext(Dispatchers.IO) {
    val m = VoicePacks.manager(activity)
    listOfNotNull(m.readNotice(id), m.readLicense(id)).joinToString("\n\n")
}
