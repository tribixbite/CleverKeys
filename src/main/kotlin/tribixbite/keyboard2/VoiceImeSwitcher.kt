package tribixbite.keyboard2

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent

/**
 * Voice input method switching
 * Kotlin implementation with modern intent handling
 */
class VoiceImeSwitcher(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceImeSwitcher"
    }
    
    /**
     * Check if voice input is available
     */
    fun isVoiceInputAvailable(): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val activities = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return activities.isNotEmpty()
    }
    
    /**
     * Create voice input intent
     */
    fun createVoiceInputIntent(): Intent? {
        return if (isVoiceInputAvailable()) {
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
        } else {
            null
        }
    }
    
    /**
     * Switch to voice input
     */
    fun switchToVoiceInput(): Boolean {
        return try {
            val intent = createVoiceInputIntent()
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                logW("Voice input not available")
                false
            }
        } catch (e: Exception) {
            logE("Failed to switch to voice input", e)
            false
        }
    }
    
    /**
     * Process voice input results
     */
    fun processVoiceResults(data: Intent?): List<String> {
        return data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: emptyList()
    }
}