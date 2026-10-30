package tribixbite.cleverkeys.stt

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.widget.Toast
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.R

/**
 * A transparent activity that shows the system microphone prompt for the keyboard.
 *
 * An `InputMethodService` cannot request a runtime permission (only an Activity can), so — as
 * AOSP LatinIME's `PermissionsActivity` does — the keyboard starts this, it asks, records the
 * answer and finishes; the app the user was typing in comes back. It is only ever started after
 * [MicPermissionGate] decided REQUEST_PERMISSION, i.e. once the user has opted in to voice input
 * (maintainer rule 2026-10-29).
 *
 * The answer is read by [MicPermissionGate.afterRequest] — the same reading as Settings — and
 * written to the shared preferences the keyboard reads:
 *  - GRANTED → [VoicePacks.PREF_PERMISSION_BLOCKED] cleared; with [EXTRA_ENABLE] also
 *    [VoicePacks.PREF_ENABLED] = true;
 *  - BLOCKED (Android will not ask again; it answered without showing a prompt) →
 *    PREF_PERMISSION_BLOCKED = true and the same explanation as Settings, with "Open app settings"
 *    (the keyboard's next press then explains in the bar instead of re-launching this);
 *  - DENIED → a short toast; voice input stays as it was.
 */
class MicPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            if (VoicePacks.hasMicPermission(this)) {
                record(granted = true)
                finish()
                return
            }
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        val outcome = record(granted)
        if (outcome.offersAppSettings) {
            explainBlocked()
            return
        }
        if (!granted) Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show()
        close()
    }

    /** Android will not show the prompt again: the Settings explanation, offering App info. */
    private fun explainBlocked() {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.voice_permission_blocked_title)
            .setMessage(R.string.voice_permission_blocked_steps)
            .setPositiveButton(R.string.voice_open_app_settings) { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    android.util.Log.w("MicPermission", "No App info screen", e)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .setOnDismissListener { close() }
            .show()
    }

    private fun close() {
        finish()
        overridePendingTransition(0, 0)
    }

    private fun record(granted: Boolean): MicPermissionGate.RequestOutcome {
        val outcome = MicPermissionGate.afterRequest(
            granted,
            !granted && shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
        )
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        val editor = prefs.edit().putBoolean(VoicePacks.PREF_PERMISSION_BLOCKED, outcome.blocked)
        if (outcome.turnsVoiceOn && intent.getBooleanExtra(EXTRA_ENABLE, false)) {
            editor.putBoolean(VoicePacks.PREF_ENABLED, true)
        }
        editor.apply()
        Config.globalConfigOrNull()?.let { it.voice_input_enabled = VoicePacks.isEnabled(prefs) }
        return outcome
    }

    companion object {
        private const val REQUEST_CODE = 0x5717

        /** Boolean: turn voice input on when the permission is granted. */
        const val EXTRA_ENABLE = "tribixbite.cleverkeys.stt.ENABLE_ON_GRANT"

        /** Start from a non-activity context (the keyboard service). */
        fun start(context: Context, enableOnGrant: Boolean) {
            context.startActivity(
                Intent(context, MicPermissionActivity::class.java)
                    .putExtra(EXTRA_ENABLE, enableOnGrant)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}
