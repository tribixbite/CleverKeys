package tribixbite.cleverkeys.stt

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 * Outcome, written to the shared preferences the keyboard reads:
 *  - granted → [VoicePacks.PREF_PERMISSION_BLOCKED] cleared; with [EXTRA_ENABLE] also
 *    [VoicePacks.PREF_ENABLED] = true;
 *  - denied and Android will not ask again → PREF_PERMISSION_BLOCKED = true (the keyboard then
 *    points at the system settings page instead of re-launching this);
 *  - denied → a short explanation; voice input stays as it was.
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
        record(granted)
        if (!granted) Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun record(granted: Boolean) {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        val editor = prefs.edit()
        if (granted) {
            editor.putBoolean(VoicePacks.PREF_PERMISSION_BLOCKED, false)
            if (intent.getBooleanExtra(EXTRA_ENABLE, false)) editor.putBoolean(VoicePacks.PREF_ENABLED, true)
        } else {
            // No rationale after a denial = "don't ask again" (or a policy block).
            editor.putBoolean(VoicePacks.PREF_PERMISSION_BLOCKED, !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
        }
        editor.apply()
        Config.globalConfigOrNull()?.let { it.voice_input_enabled = VoicePacks.isEnabled(prefs) }
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
