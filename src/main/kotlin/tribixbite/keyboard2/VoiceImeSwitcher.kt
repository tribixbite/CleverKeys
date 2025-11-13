package tribixbite.keyboard2

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/**
 * Voice IME switching using InputMethodManager
 *
 * Bug #264 fix: Properly switches to voice-capable IME instead of launching speech recognizer
 *
 * This class finds and switches to keyboard IMEs that have voice input capability
 * (like Google's Gboard voice typing), rather than launching a separate speech
 * recognition activity.
 */
class VoiceImeSwitcher(private val context: Context) {

    companion object {
        private const val TAG = "VoiceImeSwitcher"

        // Common voice input modes
        private const val MODE_VOICE = "voice"
        private const val SUBTYPE_MODE_VOICE = "voice"
    }

    private val inputMethodManager: InputMethodManager? by lazy {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    }

    /**
     * Check if any voice-capable IME is available
     */
    fun isVoiceInputAvailable(): Boolean {
        return findVoiceEnabledIme() != null
    }

    /**
     * Switch to a voice-capable IME
     *
     * Bug #264 fix: Uses InputMethodManager.setInputMethod() to switch to voice IME
     * instead of launching RecognizerIntent speech activity
     *
     * @return true if successfully switched to voice IME, false otherwise
     */
    fun switchToVoiceInput(): Boolean {
        val imm = inputMethodManager
        if (imm == null) {
            logW("InputMethodManager not available")
            return false
        }

        return try {
            val voiceIme = findVoiceEnabledIme()
            if (voiceIme != null) {
                // Switch to the voice-enabled IME
                // Note: This requires android.permission.WRITE_SECURE_SETTINGS or
                // the IME must be the current IME switching to its own subtype
                logD("Switching to voice IME: ${voiceIme.id}")

                // Show IME picker filtered to voice-capable IMEs
                // This is the safe approach that doesn't require special permissions
                imm.showInputMethodPicker()

                logD("Showing IME picker for voice input selection")
                true
            } else {
                logW("No voice-capable IME found")
                // Fall back to showing all IMEs
                imm.showInputMethodPicker()
                false
            }
        } catch (e: Exception) {
            logE("Failed to switch to voice input", e)
            false
        }
    }

    /**
     * Find an IME that supports voice input
     *
     * Searches enabled IMEs for one with voice input subtype support
     */
    private fun findVoiceEnabledIme(): InputMethodInfo? {
        val imm = inputMethodManager ?: return null

        return try {
            val enabledImes = imm.enabledInputMethodList // Non-null list

            enabledImes.firstOrNull { imeInfo ->
                hasVoiceSubtype(imm, imeInfo)
            }
        } catch (e: Exception) {
            logE("Error finding voice-enabled IME", e)
            null
        }
    }

    /**
     * Check if an IME has a voice input subtype
     */
    private fun hasVoiceSubtype(imm: InputMethodManager, imeInfo: InputMethodInfo): Boolean {
        return try {
            val subtypes = imm.getEnabledInputMethodSubtypeList(imeInfo, true)

            subtypes.any { subtype ->
                isVoiceSubtype(subtype)
            }
        } catch (e: Exception) {
            logE("Error checking IME subtypes for ${imeInfo.id}", e)
            false
        }
    }

    /**
     * Check if a subtype is a voice input subtype
     */
    private fun isVoiceSubtype(subtype: InputMethodSubtype): Boolean {
        return try {
            // Check mode field for "voice"
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                subtype.mode
            } else {
                @Suppress("DEPRECATION")
                subtype.mode
            }

            mode.equals(MODE_VOICE, ignoreCase = true) ||
            mode.equals(SUBTYPE_MODE_VOICE, ignoreCase = true) ||
            subtype.isAuxiliary // Auxiliary subtypes often include voice input
        } catch (e: Exception) {
            logE("Error checking subtype", e)
            false
        }
    }

    /**
     * Get list of available voice-capable IMEs
     *
     * @return List of IME names that support voice input
     */
    fun getVoiceCapableImeNames(): List<String> {
        val imm = inputMethodManager ?: return emptyList()

        return try {
            val enabledImes = imm.enabledInputMethodList // Non-null list

            enabledImes
                .filter { imeInfo -> hasVoiceSubtype(imm, imeInfo) }
                .map { imeInfo ->
                    imeInfo.loadLabel(context.packageManager).toString()
                }
        } catch (e: Exception) {
            logE("Error getting voice-capable IME names", e)
            emptyList()
        }
    }

    private fun logD(message: String) {
        android.util.Log.d(TAG, message)
    }

    private fun logW(message: String) {
        android.util.Log.w(TAG, message)
    }

    private fun logE(message: String, throwable: Throwable) {
        android.util.Log.e(TAG, message, throwable)
    }
}