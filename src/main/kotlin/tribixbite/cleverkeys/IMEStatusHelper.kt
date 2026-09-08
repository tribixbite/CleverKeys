package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import kotlin.math.abs

/**
 * Utility class for checking IME status and prompting users to set as default.
 *
 * This class centralizes logic for:
 * - Checking if the keyboard is the default IME
 * - Showing non-intrusive prompts to enable as default
 * - Tracking prompt display to avoid annoyance
 *
 * Prompt cadence (I-7, maintainer decision 2026-09-08): **at most once per device
 * boot, with a permanent opt-out.** The pre-2026-09 code kept a "session" flag in
 * a persistent pref with no reset path, so the prompt actually fired once per
 * INSTALL while three in-repo artifacts documented once-per-session. Now:
 *
 *  - Once per boot survives process death within the boot: what is persisted is
 *    the BOOT INSTANT (`currentTimeMillis - elapsedRealtime`) the last prompt
 *    fired in ([PREF_KEY_LAST_PROMPT_BOOT_MS]). Two instants within
 *    [BOOT_INSTANT_TOLERANCE_MS] are the same boot (the recomputation drifts by
 *    a few ms plus any NTP wall-clock nudge); a reboot yields a new instant.
 *  - The "don't ask again" affordance is the Settings reminder switch writing
 *    [PREF_KEY_PROMPT_ENABLED] (the toast itself cannot host a button); default
 *    is honest — prompting enabled.
 *  - The legacy `ime_prompt_shown_this_session` flag is retired and ignored, so
 *    upgraded installs prompt once on their next boot (intended).
 *
 * NOT included (remains in CleverKeysService):
 * - IME lifecycle management
 * - Context and Handler access
 *
 * This utility is extracted from CleverKeysService.java for better code organization
 * and testability (v1.32.377).
 *
 * @since v1.32.377
 */
object IMEStatusHelper {

    private const val TAG = "IMEStatusHelper"
    /** I-7: the "don't ask again" pref, written by the Settings reminder switch. */
    private const val PREF_KEY_PROMPT_ENABLED = "ime_default_prompt_enabled"
    /** I-7: boot instant of the boot the prompt last fired in. 0 = never prompted. */
    private const val PREF_KEY_LAST_PROMPT_BOOT_MS = "ime_prompt_last_boot_ms"
    /** Same-boot window: recomputed boot instants differ by ms-level drift, not more. */
    private const val BOOT_INSTANT_TOLERANCE_MS = 5_000L
    private const val TOAST_DELAY_MS = 2000L

    /**
     * Check if the keyboard is the default IME and show a prompt if not.
     *
     * This method:
     * 1. Returns silently if the user turned the reminder off ("don't ask again")
     * 2. Returns silently if this BOOT already prompted (persisted boot instant)
     * 3. Queries system settings for the default IME
     * 4. Compares our IME with the default
     * 5. Shows a delayed toast if we're not the default
     * 6. Records this boot's instant so the boot never prompts twice
     *
     * @param context Application context
     * @param handler Handler for posting delayed toast
     * @param prefs SharedPreferences for tracking prompt display
     * @param packageName The package name of the keyboard app
     * @param serviceClassName The full class name of the IME service
     */
    @JvmStatic
    fun checkAndPromptDefaultIME(
        context: Context,
        handler: Handler,
        prefs: SharedPreferences,
        packageName: String,
        serviceClassName: String
    ) {
        try {
            // I-7: permanent opt-out — the Settings reminder switch wrote false.
            if (!prefs.getBoolean(PREF_KEY_PROMPT_ENABLED, Defaults.IME_DEFAULT_PROMPT_ENABLED)) {
                return
            }

            // I-7: once per boot. Both guards run BEFORE the settings read — they
            // are the cheap path taken on every service creation.
            val bootInstant = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val lastPromptBoot = prefs.getLong(PREF_KEY_LAST_PROMPT_BOOT_MS, 0L)
            if (abs(bootInstant - lastPromptBoot) <= BOOT_INSTANT_TOLERANCE_MS) {
                return // Already prompted this boot (possibly in an earlier process)
            }

            // Get InputMethodManager
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm == null) {
                Log.w(TAG, "InputMethodManager not available")
                return
            }

            // Get default IME from system settings
            val defaultIme = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )

            // Construct our IME identifier
            val ourIme = "$packageName/$serviceClassName"

            // Check if we're the default
            if (ourIme != defaultIme) {
                // I-7 (comprehensive audit 2026-09-06): the message names THIS app via
                // its app_name resource — the old hardcoded constant said "Unexpected
                // Keyboard" in an app named CleverKeys. It also points at the permanent
                // opt-out, since a toast cannot host a "don't ask again" button.
                val message = "Set ${context.getString(R.string.app_name)} as default in " +
                    "Settings → System → Languages & input → On-screen keyboard " +
                    "(reminder can be turned off in ${context.getString(R.string.app_name)} settings)"
                // We're not the default - show helpful toast after delay
                handler.postDelayed({
                    Toast.makeText(
                        context,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }, TOAST_DELAY_MS)

                // I-7: burn this boot's slot. Persisted so an IME process restart
                // within the same boot cannot re-prompt.
                prefs.edit().putLong(PREF_KEY_LAST_PROMPT_BOOT_MS, bootInstant).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking default IME", e)
        }
    }

    /**
     * Check if the keyboard is currently the default IME.
     *
     * @param context Application context
     * @param packageName The package name of the keyboard app
     * @param serviceClassName The full class name of the IME service
     * @return true if this keyboard is the default IME, false otherwise or on error
     */
    @JvmStatic
    fun isDefaultIME(
        context: Context,
        packageName: String,
        serviceClassName: String
    ): Boolean {
        return try {
            val defaultIme = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            val ourIme = "$packageName/$serviceClassName"
            ourIme == defaultIme
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if default IME", e)
            false
        }
    }

    // resetSessionPrompt was deleted with the I-7 rework (2026-09-08): it had zero
    // callers, and the boot-instant record needs no reset path — a new boot is one.
}
