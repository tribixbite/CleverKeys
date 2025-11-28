package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * Utility class for checking IME status and prompting users to set as default.
 *
 * This class centralizes logic for:
 * - Checking if the keyboard is the default IME
 * - Showing non-intrusive prompts to enable as default
 * - Tracking prompt display to avoid annoyance
 *
 * Responsibilities:
 * - Query system settings for default IME
 * - Compare current IME with default
 * - Show toast notifications when not default
 * - Track session-based prompt display
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
    private const val PREF_KEY_PROMPT_SHOWN = "ime_prompt_shown_this_session"
    private const val TOAST_DELAY_MS = 2000L
    private const val TOAST_MESSAGE =
        "Set Unexpected Keyboard as default in Settings → System → Languages & input → On-screen keyboard"

    /**
     * Check if the keyboard is the default IME and show a prompt if not.
     *
     * This method:
     * 1. Checks if we've already prompted this session (to avoid annoyance)
     * 2. Queries system settings for the default IME
     * 3. Compares our IME with the default
     * 4. Shows a delayed toast if we're not the default
     * 5. Marks prompt as shown for this session
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
            // Check if we've already shown the prompt this session
            val hasPromptedThisSession = prefs.getBoolean(PREF_KEY_PROMPT_SHOWN, false)
            if (hasPromptedThisSession) {
                return // Already prompted, don't annoy the user
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
                // We're not the default - show helpful toast after delay
                handler.postDelayed({
                    Toast.makeText(
                        context,
                        TOAST_MESSAGE,
                        Toast.LENGTH_LONG
                    ).show()
                }, TOAST_DELAY_MS)

                // Mark that we've shown the prompt this session
                prefs.edit().putBoolean(PREF_KEY_PROMPT_SHOWN, true).apply()
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

    /**
     * Reset the session prompt flag, allowing the prompt to be shown again.
     * Useful for testing or when the app is restarted.
     *
     * @param prefs SharedPreferences to reset
     */
    @JvmStatic
    fun resetSessionPrompt(prefs: SharedPreferences) {
        prefs.edit().putBoolean(PREF_KEY_PROMPT_SHOWN, false).apply()
    }
}
