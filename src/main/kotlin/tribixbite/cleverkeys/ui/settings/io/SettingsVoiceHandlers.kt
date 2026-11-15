package tribixbite.cleverkeys.ui.settings.io

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

/** Saved-state keys for the two request flags below (they must survive process death). */
private const val STATE_MIC_PURPOSE = "voice_pending_mic_purpose"
private const val STATE_WAITING_FOR_APP_SETTINGS = "voice_waiting_after_app_settings"

/**
 * The user was turning voice input on when the blocked explanation sent them to App info: back in
 * Settings, a granted permission finishes the job (MicPermissionGate.turnOnAfterAppSettings).
 */
private var waitingToEnableAfterAppSettings = false

/**
 * Keep what the open microphone request (or the App info round trip) is for across process death
 * (review 2026-11-14, low). Both live in process globals, which a rotation keeps but a process
 * death does not: Android can kill Settings while its permission prompt or App info is in front,
 * and the recreated activity then got the result with no purpose and left voice input off
 * although the user had just granted the microphone.
 */
internal fun saveVoicePermissionState(outState: Bundle) {
    outState.putString(STATE_MIC_PURPOSE, pendingMicPurpose?.name)
    outState.putBoolean(STATE_WAITING_FOR_APP_SETTINGS, waitingToEnableAfterAppSettings)
}

/** Called from onCreate with a non-null saved state, before any result is delivered (onStart). */
internal fun restoreVoicePermissionState(savedState: Bundle) {
    pendingMicPurpose = savedState.getString(STATE_MIC_PURPOSE)
        ?.let { name -> PendingMicPurpose.entries.firstOrNull { it.name == name } }
    waitingToEnableAfterAppSettings = savedState.getBoolean(STATE_WAITING_FOR_APP_SETTINGS, false)
}

/**
 * Mirror the process-wide voice-pack import ([VoicePackImportTracker]) into this activity while
 * it is started, and make the opt-in microphone request a successful import calls for — once,
 * from whichever Settings activity is in front when the import ends.
 */
internal fun observeVoicePackImport(activity: SettingsActivity) {
    VoicePackImportTracker.shared.attach()
    with(activity) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoicePackImportTracker.shared.state.collect { state ->
                    voiceImportInProgress = state.inProgress
                    voiceImportStatus = state.status
                    if (state.micRequestPending && VoicePackImportTracker.shared.consumeMicRequest()) {
                        refreshVoiceInputState(this@with)
                        askForMicAfterImport(this@with)
                    }
                }
            }
        }
    }
}

/** The PACK_IMPORTED opt-in moment: enable at once, or ask for the microphone first. */
private fun askForMicAfterImport(activity: SettingsActivity) {
    with(activity) {
        val prefs = tribixbite.cleverkeys.DirectBootAwarePreferences.get_shared_preferences(this)
        when (MicPermissionGate.decide(MicPermissionGate.Trigger.PACK_IMPORTED, VoicePacks.facts(this, prefs))) {
            MicPermissionGate.Decision.PROCEED -> setVoiceEnabled(this, true)
            else -> {
                pendingMicPurpose = PendingMicPurpose.AFTER_IMPORT
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/** App info for CleverKeys (Permissions → Microphone), the only place left once Android stops asking. */
internal fun openMicAppSettings(activity: SettingsActivity, toEnable: Boolean) {
    with(activity) {
        waitingToEnableAfterAppSettings = toEnable
        voiceBlockedDialogShown = false
        try {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "No App info screen", e)
            waitingToEnableAfterAppSettings = false
        }
    }
}

/** Re-read the voice state (packs, switch, permission) — on resume and after every change. */
internal fun refreshVoiceInputState(activity: SettingsActivity) {
    with(activity) {
        val prefs = tribixbite.cleverkeys.DirectBootAwarePreferences.get_shared_preferences(this)
        val granted = VoicePacks.hasMicPermission(this)
        // A granted permission always wins over a remembered "don't ask again" (it goes stale when
        // the user changes the permission in system settings).
        if (granted && VoicePacks.isPermissionBlocked(prefs)) {
            prefs.edit().putBoolean(VoicePacks.PREF_PERMISSION_BLOCKED, false).apply()
        }
        if (MicPermissionGate.turnOnAfterAppSettings(waitingToEnableAfterAppSettings, granted)) {
            setVoiceEnabled(this, true)
        }
        waitingToEnableAfterAppSettings = false
        // An enabled switch whose permission was revoked in system settings stays on: the keyboard
        // asks again on the next key press (MicPermissionGate KEY_PRESS), which is an opt-in moment.
        voiceInputEnabled = VoicePacks.isEnabled(prefs)
        voicePermissionBlocked = !granted && VoicePacks.isPermissionBlocked(prefs)
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
            else -> {
                // Always REQUEST_PERMISSION here: the switch asks Android even when the app
                // remembers a block; a real block is answered at once and explained by the result.
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
        val outcome = MicPermissionGate.afterRequest(granted, shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
        prefs.edit().putBoolean(VoicePacks.PREF_PERMISSION_BLOCKED, outcome.blocked).apply()
        voicePermissionBlocked = outcome.blocked
        when {
            // Both opt-in moments end with voice input on.
            outcome.turnsVoiceOn -> if (purpose != null) setVoiceEnabled(this, true)
            outcome.offersAppSettings -> {
                // Android answered without showing anything: say why and offer App info. The
                // switch stays off until the permission is granted.
                setVoiceEnabled(this, false)
                voiceBlockedDialogShown = true
            }
            else -> {
                setVoiceEnabled(this, false)
                Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show()
            }
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

/**
 * SAF result: import the pack off the main thread, then (opt-in moment) ask for the mic.
 *
 * Runs in [VoicePackImportTracker], not this activity's scope, so a rotation mid-import keeps the
 * status and the follow-up request ([observeVoicePackImport]). The work holds the application
 * context and this activity's Resources (for the message language), never the activity.
 */
internal fun performVoicePackImport(activity: SettingsActivity, uri: Uri) {
    val appContext = activity.applicationContext
    val res = activity.resources
    VoicePackImportTracker.shared.start(res.getString(R.string.voice_importing)) {
        val result = try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: return@start VoiceImportStatus.Failed(
                    res.getString(
                        R.string.voice_import_failed,
                        VoicePackImportFailure.Io(tribixbite.cleverkeys.IoFailureReason.FILE_NOT_FOUND, "Cannot open $uri")
                            .render(ResourcesResultText(res)),
                    )
                ) to false
            VoicePacks.manager(appContext).import(input)
        } catch (e: Exception) {
            Log.e(TAG, "Voice pack import failed", e)
            VoicePackManager.ImportResult.Error(VoicePackImportFailure.fromException(e))
        }
        when (result) {
            is VoicePackManager.ImportResult.Success ->
                VoiceImportStatus.Ok(res.getString(R.string.voice_import_success, result.manifest.name)) to true
            is VoicePackManager.ImportResult.Error -> {
                Log.w(TAG, "Voice pack import refused: ${result.failure.logMessage}")
                val reason = result.failure.render(ResourcesResultText(res))
                VoiceImportStatus.Failed(res.getString(R.string.voice_import_failed, reason)) to false
            }
        }
    }
}

/** Remove an installed (or unsupported) pack. */
internal fun removeVoicePack(activity: SettingsActivity, id: String) {
    with(activity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { VoicePacks.manager(this@with).remove(id) }
            VoicePackImportTracker.shared.clearStatus()
            refreshVoiceInputState(this@with)
        }
    }
}

/** The pack's NOTICE.txt + LICENSE for the notice dialog (IO). */
internal suspend fun readVoicePackNotice(activity: SettingsActivity, id: String): String = withContext(Dispatchers.IO) {
    val m = VoicePacks.manager(activity)
    listOfNotNull(m.readNotice(id), m.readLicense(id)).joinToString("\n\n")
}
