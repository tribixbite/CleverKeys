package tribixbite.keyboard2

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

/**
 * Voice guidance engine for accessibility support
 * Provides text-to-speech feedback for blind users and screen reader integration
 * Fix for Bug #368 (CATASTROPHIC): NO voice guidance - violates ADA/WCAG
 */
class VoiceGuidanceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceGuidanceEngine"
        private const val UTTERANCE_ID_PREFIX = "cleverkeys_"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isSpeaking = false
    private var speechRate = 1.0f
    private var speechPitch = 1.0f
    private var utteranceId = 0

    /**
     * Initialize text-to-speech engine
     */
    fun initialize(onInitComplete: ((Boolean) -> Unit)? = null) {
        if (isInitialized) {
            onInitComplete?.invoke(true)
            return
        }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    // Set language (default to system locale)
                    val result = engine.setLanguage(Locale.getDefault())

                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Language not supported, falling back to English")
                        engine.setLanguage(Locale.ENGLISH)
                    }

                    // Set speech parameters
                    engine.setSpeechRate(speechRate)
                    engine.setPitch(speechPitch)

                    // Set utterance progress listener
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isSpeaking = true
                        }

                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                        }

                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            Log.e(TAG, "TTS error for utterance: $utteranceId")
                        }
                    })

                    isInitialized = true
                    Log.d(TAG, "Voice guidance initialized successfully")
                    onInitComplete?.invoke(true)
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                onInitComplete?.invoke(false)
            }
        }
    }

    /**
     * Speak key description for voice feedback
     */
    fun speakKey(keyValue: KeyValue) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak key")
            return
        }

        val description = getKeyDescription(keyValue)
        speak(description, TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Speak text with specified queue mode
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak: $text")
            return
        }

        if (text.isEmpty()) return

        val currentUtteranceId = "${UTTERANCE_ID_PREFIX}${utteranceId++}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, currentUtteranceId)
        }

        tts?.speak(text, queueMode, params, currentUtteranceId)
        Log.d(TAG, "Speaking: $text")
    }

    /**
     * Stop current speech
     */
    fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    /**
     * Set speech rate (0.5x to 2.0x)
     */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
        Log.d(TAG, "Speech rate set to: $speechRate")
    }

    /**
     * Set speech pitch (0.8x to 1.2x)
     */
    fun setPitch(pitch: Float) {
        speechPitch = pitch.coerceIn(0.8f, 1.2f)
        tts?.setPitch(speechPitch)
        Log.d(TAG, "Speech pitch set to: $speechPitch")
    }

    /**
     * Check if currently speaking
     */
    fun isSpeaking(): Boolean = isSpeaking

    /**
     * Get human-readable key description for TTS
     */
    fun getKeyDescription(keyValue: KeyValue): String {
        return when (keyValue) {
            // Character keys
            is KeyValue.CharKey -> {
                val char = keyValue.char
                when {
                    char.isLetter() -> {
                        val letterName = char.lowercaseChar().toString()
                        if (char.isUpperCase()) {
                            "Capital $letterName"
                        } else {
                            letterName
                        }
                    }
                    char.isDigit() -> char.toString()
                    else -> getSpecialCharacterName(char)
                }
            }

            // Special keys
            is KeyValue.EventKey -> {
                when (keyValue.event) {
                    KeyValue.Event.ACTION -> "Enter"
                    KeyValue.Event.SWITCH_TEXT -> "Switch to text layout"
                    KeyValue.Event.SWITCH_NUMERIC -> "Switch to numbers"
                    KeyValue.Event.SWITCH_EMOJI -> "Switch to emoji"
                    KeyValue.Event.CONFIG -> "Settings"
                    KeyValue.Event.CAPS_LOCK -> "Caps lock"
                    else -> "Special key"
                }
            }

            // KeyEvent keys
            is KeyValue.KeyEventKey -> {
                when (keyValue.keyCode) {
                    android.view.KeyEvent.KEYCODE_DEL -> "Backspace"
                    android.view.KeyEvent.KEYCODE_ENTER -> "Enter"
                    android.view.KeyEvent.KEYCODE_SPACE -> "Space"
                    android.view.KeyEvent.KEYCODE_TAB -> "Tab"
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "Cursor left"
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "Cursor right"
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> "Cursor up"
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "Cursor down"
                    else -> "Key code ${keyValue.keyCode}"
                }
            }

            // Modifier keys
            is KeyValue.ModifierKey -> {
                when (keyValue.modifier) {
                    KeyValue.Modifier.SHIFT -> "Shift"
                    KeyValue.Modifier.CTRL -> "Control"
                    KeyValue.Modifier.ALT -> "Alt"
                    KeyValue.Modifier.META -> "Meta"
                    else -> "Modifier ${keyValue.modifier.name}"
                }
            }

            // String keys
            is KeyValue.StringKey -> keyValue.string

            // Compose keys
            is KeyValue.ComposePendingKey -> "Compose key"

            else -> "Unknown key"
        }
    }

    /**
     * Get phonetic name for letters (NATO phonetic alphabet for clarity)
     */
    fun getPhoneticName(char: Char): String {
        return when (char.lowercaseChar()) {
            'a' -> "Alpha"
            'b' -> "Bravo"
            'c' -> "Charlie"
            'd' -> "Delta"
            'e' -> "Echo"
            'f' -> "Foxtrot"
            'g' -> "Golf"
            'h' -> "Hotel"
            'i' -> "India"
            'j' -> "Juliet"
            'k' -> "Kilo"
            'l' -> "Lima"
            'm' -> "Mike"
            'n' -> "November"
            'o' -> "Oscar"
            'p' -> "Papa"
            'q' -> "Quebec"
            'r' -> "Romeo"
            's' -> "Sierra"
            't' -> "Tango"
            'u' -> "Uniform"
            'v' -> "Victor"
            'w' -> "Whiskey"
            'x' -> "X-ray"
            'y' -> "Yankee"
            'z' -> "Zulu"
            else -> char.toString()
        }
    }

    /**
     * Get special character names
     */
    private fun getSpecialCharacterName(char: Char): String {
        return when (char) {
            ' ' -> "Space"
            '\n' -> "New line"
            '\t' -> "Tab"
            '.' -> "Period"
            ',' -> "Comma"
            '!' -> "Exclamation mark"
            '?' -> "Question mark"
            ';' -> "Semicolon"
            ':' -> "Colon"
            '\'' -> "Apostrophe"
            '"' -> "Quote"
            '-' -> "Hyphen"
            '_' -> "Underscore"
            '/' -> "Slash"
            '\\' -> "Backslash"
            '|' -> "Pipe"
            '(' -> "Left parenthesis"
            ')' -> "Right parenthesis"
            '[' -> "Left bracket"
            ']' -> "Right bracket"
            '{' -> "Left brace"
            '}' -> "Right brace"
            '<' -> "Less than"
            '>' -> "Greater than"
            '=' -> "Equals"
            '+' -> "Plus"
            '*' -> "Asterisk"
            '&' -> "Ampersand"
            '%' -> "Percent"
            '$' -> "Dollar sign"
            '#' -> "Hash"
            '@' -> "At sign"
            '~' -> "Tilde"
            '`' -> "Backtick"
            '^' -> "Caret"
            else -> "Symbol"
        }
    }

    /**
     * Announce typing context (for screen readers)
     */
    fun announceContext(message: String) {
        speak(message, TextToSpeech.QUEUE_ADD)
    }

    /**
     * Announce prediction suggestions
     */
    fun announceSuggestions(suggestions: List<String>) {
        if (suggestions.isEmpty()) {
            speak("No suggestions available", TextToSpeech.QUEUE_ADD)
            return
        }

        val message = "Suggestions: " + suggestions.joinToString(", ")
        speak(message, TextToSpeech.QUEUE_ADD)
    }

    /**
     * Check if TTS is available on device
     */
    fun isTextToSpeechAvailable(): Boolean {
        val engines = tts?.engines
        return engines?.isNotEmpty() ?: false
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "Voice guidance engine cleaned up")
    }
}
