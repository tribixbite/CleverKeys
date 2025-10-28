package tribixbite.keyboard2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.SuggestionSpan
import android.util.Log
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.view.inputmethod.InputConnection
import java.util.Locale

/**
 * Manages spell checking integration for the keyboard using Android's TextServicesManager.
 *
 * Features:
 * - Real-time spell checking with red underlines
 * - Debounced requests to prevent performance issues
 * - Multi-language support (follows keyboard locale)
 * - Suggestion span management
 * - Batch spell checking for efficiency
 *
 * Fix for Bug #311: SpellChecker integration missing (CATASTROPHIC)
 */
class SpellCheckerManager(private val context: Context) {

    companion object {
        private const val TAG = "SpellCheckerManager"

        // Debounce delay in milliseconds (check after user stops typing)
        private const val SPELL_CHECK_DELAY_MS = 300L

        // Maximum suggestions to request from spell checker
        private const val MAX_SUGGESTIONS = 5

        // Maximum characters to check in one request
        private const val MAX_TEXT_LENGTH_FOR_CHECK = 500

        // Minimum word length to spell check
        private const val MIN_WORD_LENGTH = 2
    }

    private var spellCheckerSession: SpellCheckerSession? = null
    private var currentLocale: Locale = Locale.getDefault()
    private var isEnabled = true
    private val handler = Handler(Looper.getMainLooper())

    // Debouncing runnable
    private var pendingCheckRunnable: Runnable? = null

    // Callback for when suggestions are ready
    private var suggestionCallback: ((List<SpellingSuggestion>) -> Unit)? = null

    /**
     * Initialize the spell checker session for the given locale
     */
    fun initialize(locale: Locale = Locale.getDefault()) {
        currentLocale = locale

        try {
            // Close existing session if any
            spellCheckerSession?.close()

            // Create new spell checker session
            val textServicesManager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
            if (textServicesManager == null) {
                Log.w(TAG, "TextServicesManager not available")
                return
            }

            // Create session with locale and listener
            // The 'true' argument enables "look ahead" spell checking
            spellCheckerSession = textServicesManager.newSpellCheckerSession(
                null,  // Bundle (can pass custom parameters)
                currentLocale,
                spellCheckerListener,
                true  // referToSpellCheckerLanguageSettings
            )

            Log.d(TAG, "Initialized spell checker session for locale: $currentLocale")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize spell checker: ${e.message}", e)
        }
    }

    /**
     * Spell checker session listener that receives results asynchronously
     */
    private val spellCheckerListener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>) {
            if (!isEnabled) return

            val suggestions = mutableListOf<SpellingSuggestion>()

            for (result in results) {
                // Check if the word is flagged as a typo
                val isMisspelled = (result.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0

                if (isMisspelled) {
                    // Extract the text that was checked
                    // Note: We need to track the original text separately since SuggestionsInfo doesn't provide it
                    val suggestionsList = mutableListOf<String>()
                    for (i in 0 until result.suggestionsCount) {
                        suggestionsList.add(result.getSuggestionAt(i))
                    }

                    if (suggestionsList.isNotEmpty()) {
                        suggestions.add(SpellingSuggestion(
                            word = "",  // Will be filled by the caller who tracks the original text
                            suggestions = suggestionsList,
                            isMisspelled = true
                        ))
                    }
                }
            }

            // Notify callback if set
            suggestionCallback?.invoke(suggestions)
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>) {
            if (!isEnabled) return

            val suggestions = mutableListOf<SpellingSuggestion>()

            for (sentenceResult in results) {
                for (i in 0 until sentenceResult.suggestionsCount) {
                    val result = sentenceResult.getSuggestionsInfoAt(i)
                    val isMisspelled = (result.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0

                    if (isMisspelled) {
                        val suggestionsList = mutableListOf<String>()
                        for (j in 0 until result.suggestionsCount) {
                            suggestionsList.add(result.getSuggestionAt(j))
                        }

                        if (suggestionsList.isNotEmpty()) {
                            suggestions.add(SpellingSuggestion(
                                word = "",  // Caller will fill this
                                suggestions = suggestionsList,
                                isMisspelled = true,
                                offset = sentenceResult.getOffsetAt(i),
                                length = sentenceResult.getLengthAt(i)
                            ))
                        }
                    }
                }
            }

            suggestionCallback?.invoke(suggestions)
        }
    }

    /**
     * Check spelling of a single word (debounced)
     * This is the primary method for checking words as the user types
     */
    fun checkWord(word: String, callback: ((List<SpellingSuggestion>) -> Unit)? = null) {
        if (!isEnabled || word.length < MIN_WORD_LENGTH) {
            callback?.invoke(emptyList())
            return
        }

        // Cancel any pending check
        pendingCheckRunnable?.let { handler.removeCallbacks(it) }

        // Create new debounced check
        pendingCheckRunnable = Runnable {
            checkWordImmediate(word, callback)
        }

        // Schedule check after delay
        handler.postDelayed(pendingCheckRunnable!!, SPELL_CHECK_DELAY_MS)
    }

    /**
     * Check spelling of a word immediately (no debouncing)
     */
    fun checkWordImmediate(word: String, callback: ((List<SpellingSuggestion>) -> Unit)? = null) {
        if (!isEnabled || word.length < MIN_WORD_LENGTH) {
            callback?.invoke(emptyList())
            return
        }

        val session = spellCheckerSession
        if (session == null) {
            Log.w(TAG, "Spell checker session not initialized")
            callback?.invoke(emptyList())
            return
        }

        suggestionCallback = callback

        try {
            // Request suggestions for the word
            val textInfo = TextInfo(word)
            session.getSuggestions(textInfo, MAX_SUGGESTIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check word '$word': ${e.message}", e)
            callback?.invoke(emptyList())
        }
    }

    /**
     * Check spelling of a sentence or paragraph (batch check)
     * More efficient for checking multiple words at once
     */
    fun checkSentence(text: String, callback: ((List<SpellingSuggestion>) -> Unit)? = null) {
        if (!isEnabled || text.length > MAX_TEXT_LENGTH_FOR_CHECK) {
            callback?.invoke(emptyList())
            return
        }

        val session = spellCheckerSession
        if (session == null) {
            Log.w(TAG, "Spell checker session not initialized")
            callback?.invoke(emptyList())
            return
        }

        suggestionCallback = callback

        try {
            // Request sentence suggestions
            val textInfo = TextInfo(text)
            session.getSentenceSuggestions(arrayOf(textInfo), MAX_SUGGESTIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check sentence: ${e.message}", e)
            callback?.invoke(emptyList())
        }
    }

    /**
     * Create a SuggestionSpan for a misspelled word
     * This span will cause the TextView to draw a red underline
     */
    fun createSuggestionSpan(suggestions: List<String>): SuggestionSpan {
        return SuggestionSpan(
            context,
            suggestions.toTypedArray(),
            SuggestionSpan.FLAG_EASY_CORRECT or SuggestionSpan.FLAG_MISSPELLED
        )
    }

    /**
     * Apply spell check suggestions to text and return a spannable with underlines
     */
    fun applySuggestionsToText(text: String, suggestions: List<SpellingSuggestion>): SpannableString {
        val spannable = SpannableString(text)

        for (suggestion in suggestions) {
            if (suggestion.isMisspelled && suggestion.suggestions.isNotEmpty()) {
                val start = suggestion.offset
                val end = start + suggestion.length

                // Ensure indices are valid
                if (start >= 0 && end <= text.length && start < end) {
                    val suggestionSpan = createSuggestionSpan(suggestion.suggestions)
                    spannable.setSpan(
                        suggestionSpan,
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        return spannable
    }

    /**
     * Cancel any pending spell check requests
     */
    fun cancelPendingChecks() {
        pendingCheckRunnable?.let { handler.removeCallbacks(it) }
        pendingCheckRunnable = null
    }

    /**
     * Enable or disable spell checking
     */
    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return

        isEnabled = enabled

        if (!enabled) {
            cancelPendingChecks()
        }

        Log.d(TAG, "Spell checking ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if spell checking is enabled
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * Change the locale for spell checking
     */
    fun setLocale(locale: Locale) {
        if (currentLocale == locale) return

        currentLocale = locale
        initialize(locale)
    }

    /**
     * Get the current locale
     */
    fun getLocale(): Locale = currentLocale

    /**
     * Cleanup and close the spell checker session
     */
    fun cleanup() {
        cancelPendingChecks()
        spellCheckerSession?.close()
        spellCheckerSession = null
        suggestionCallback = null
        Log.d(TAG, "Spell checker session closed")
    }
}

/**
 * Data class representing spell checking suggestions for a word
 */
data class SpellingSuggestion(
    val word: String,
    val suggestions: List<String>,
    val isMisspelled: Boolean,
    val offset: Int = 0,
    val length: Int = word.length
)
