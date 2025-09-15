package juloo.keyboard2

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.*

/**
 * Complete input connection management for text input
 * Kotlin implementation with comprehensive EditText integration
 */
class InputConnectionManager(private val service: CleverKeysService) {
    
    companion object {
        private const val TAG = "InputConnectionManager"
        private const val MAX_CONTEXT_LENGTH = 1000
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentInputConnection: InputConnection? = null
    private var currentEditorInfo: EditorInfo? = null

    // App-specific behavior flags
    private var enableSmartComposition = false
    private var enableContextualSuggestions = false
    private var enableEmojiSuggestions = false
    private var enableQuickResponses = false
    private var disableAutoCorrect = false
    private var enableHashtagCompletion = false
    private var enableMentionCompletion = false
    private var characterLimitWarning = 0
    private var enableAdvancedFormatting = false
    private var enableGrammarSuggestions = false
    private var enableStyleSuggestions = false
    private var enableUrlCompletion = false
    private var enableSearchSuggestions = false
    private var enableCodeCompletion = false
    private var enableSymbolSuggestions = false
    
    /**
     * Text input state
     */
    data class InputState(
        val textBefore: String,
        val textAfter: String,
        val selectedText: String,
        val cursorPosition: Int,
        val inputType: Int,
        val actionLabel: String?,
        val actionId: Int
    )
    
    /**
     * Set current input connection
     */
    fun setInputConnection(inputConnection: InputConnection?, editorInfo: EditorInfo?) {
        currentInputConnection = inputConnection
        currentEditorInfo = editorInfo
        
        if (inputConnection != null && editorInfo != null) {
            logD("Input connection established: ${editorInfo.packageName}")
            analyzeInputField(editorInfo)
        } else {
            logD("Input connection cleared")
        }
    }
    
    /**
     * Analyze input field characteristics
     */
    private fun analyzeInputField(editorInfo: EditorInfo) {
        val inputType = editorInfo.inputType
        val packageName = editorInfo.packageName
        val fieldName = editorInfo.fieldName ?: "unknown"
        
        logD("Input field analysis:")
        logD("  Package: $packageName")
        logD("  Field: $fieldName")
        logD("  Input type: ${getInputTypeDescription(inputType)}")
        logD("  Action: ${getActionDescription(editorInfo.imeOptions)}")
        
        // Adjust prediction behavior based on input type
        adjustPredictionBehavior(inputType, packageName)
    }
    
    /**
     * Get input type description
     */
    private fun getInputTypeDescription(inputType: Int): String {
        return when (inputType and android.text.InputType.TYPE_MASK_CLASS) {
            android.text.InputType.TYPE_CLASS_TEXT -> "Text"
            android.text.InputType.TYPE_CLASS_NUMBER -> "Number"
            android.text.InputType.TYPE_CLASS_PHONE -> "Phone"
            android.text.InputType.TYPE_CLASS_DATETIME -> "DateTime"
            else -> "Other ($inputType)"
        }
    }
    
    /**
     * Get action description
     */
    private fun getActionDescription(imeOptions: Int): String {
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_PREVIOUS -> "Previous"
            else -> "None"
        }
    }
    
    /**
     * Adjust prediction behavior based on input field
     */
    private fun adjustPredictionBehavior(inputType: Int, packageName: String?) {
        val shouldUseNeuralPrediction = when {
            // Disable neural prediction for password fields
            (inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 -> false
            (inputType and android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0 -> false
            
            // Disable for email addresses (might want autocomplete instead)
            (inputType and android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) != 0 -> false
            
            // Disable for URLs
            (inputType and android.text.InputType.TYPE_TEXT_VARIATION_URI) != 0 -> false
            
            // Enable for normal text input
            (inputType and android.text.InputType.TYPE_CLASS_TEXT) != 0 -> true
            
            // Disable for number fields
            (inputType and android.text.InputType.TYPE_CLASS_NUMBER) != 0 -> false
            
            else -> true
        }
        
        logD("Neural prediction ${if (shouldUseNeuralPrediction) "enabled" else "disabled"} for this field")
        
        // App-specific input behavior adjustments
        when (packageName) {
            "com.google.android.gm" -> { // Gmail
                logD("Gmail detected - enabling smart composition features")
                // Enable advanced autocorrection and smart punctuation
                enableSmartComposition = true
                enableContextualSuggestions = true
            }
            "com.whatsapp", "com.whatsapp.w4b" -> { // WhatsApp
                logD("WhatsApp detected - enabling emoji and quick response features")
                enableEmojiSuggestions = true
                enableQuickResponses = true
                disableAutoCorrect = true // User preference for messaging
            }
            "com.twitter.android" -> { // Twitter
                logD("Twitter detected - enabling hashtag/mention completion")
                enableHashtagCompletion = true
                enableMentionCompletion = true
                characterLimitWarning = 280
            }
            "com.google.android.apps.docs.editors.docs" -> { // Google Docs
                logD("Google Docs detected - enabling advanced formatting")
                enableAdvancedFormatting = true
                enableGrammarSuggestions = true
            }
            "com.microsoft.office.word" -> { // Microsoft Word
                logD("Word detected - enabling document-specific features")
                enableAdvancedFormatting = true
                enableStyleSuggestions = true
            }
            "com.android.chrome", "org.mozilla.firefox" -> { // Browsers
                logD("Browser detected - enabling URL and search optimization")
                enableUrlCompletion = true
                enableSearchSuggestions = true
            }
            "com.termux" -> { // Termux
                logD("Termux detected - enabling programming features")
                enableCodeCompletion = true
                disableAutoCorrect = true
                enableSymbolSuggestions = true
            }
        }
    }
    
    /**
     * Get current input state
     */
    fun getCurrentInputState(): InputState? {
        val inputConnection = currentInputConnection ?: return null
        val editorInfo = currentEditorInfo ?: return null
        
        return try {
            val extractedText = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
            val textBefore = inputConnection.getTextBeforeCursor(MAX_CONTEXT_LENGTH, 0)?.toString() ?: ""
            val textAfter = inputConnection.getTextAfterCursor(MAX_CONTEXT_LENGTH, 0)?.toString() ?: ""
            val selectedText = inputConnection.getSelectedText(0)?.toString() ?: ""
            
            InputState(
                textBefore = textBefore,
                textAfter = textAfter,
                selectedText = selectedText,
                cursorPosition = extractedText?.selectionStart ?: 0,
                inputType = editorInfo.inputType,
                actionLabel = editorInfo.actionLabel?.toString(),
                actionId = editorInfo.actionId
            )
        } catch (e: Exception) {
            logE("Failed to get input state", e)
            null
        }
    }
    
    /**
     * Commit text with intelligent spacing and capitalization
     */
    fun commitTextIntelligently(text: String) {
        val inputConnection = currentInputConnection ?: return
        val inputState = getCurrentInputState() ?: return
        
        try {
            var finalText = text
            
            // Add intelligent spacing
            if (shouldAddSpaceBefore(inputState, text)) {
                finalText = " $finalText"
            }
            
            // Apply capitalization
            if (shouldCapitalize(inputState, text)) {
                finalText = finalText.replaceFirstChar { it.uppercaseChar() }
            }
            
            // Commit text
            inputConnection.commitText(finalText, 1)
            
            // Add space after if needed
            if (shouldAddSpaceAfter(inputState, text)) {
                inputConnection.commitText(" ", 1)
            }
            
            logD("Committed text: '$finalText'")
            
        } catch (e: Exception) {
            logE("Failed to commit text intelligently", e)
            // Fallback to simple commit
            inputConnection.commitText(text, 1)
        }
    }
    
    /**
     * Determine if space should be added before text
     */
    private fun shouldAddSpaceBefore(state: InputState, text: String): Boolean {
        return state.textBefore.isNotEmpty() && 
               !state.textBefore.endsWith(" ") && 
               !state.textBefore.endsWith("\n") &&
               !text.startsWith(" ") &&
               text.first().isLetter()
    }
    
    /**
     * Determine if text should be capitalized
     */
    private fun shouldCapitalize(state: InputState, text: String): Boolean {
        return state.textBefore.isEmpty() || 
               state.textBefore.endsWith(". ") ||
               state.textBefore.endsWith("! ") ||
               state.textBefore.endsWith("? ") ||
               state.textBefore.endsWith("\n")
    }
    
    /**
     * Determine if space should be added after text
     */
    private fun shouldAddSpaceAfter(state: InputState, text: String): Boolean {
        return !text.endsWith(" ") && 
               state.textAfter.isNotEmpty() && 
               !state.textAfter.startsWith(" ") &&
               state.textAfter.first().isLetter()
    }
    
    /**
     * Handle special actions (Send, Go, Search, etc.)
     */
    fun performEditorAction() {
        val inputConnection = currentInputConnection ?: return
        val editorInfo = currentEditorInfo ?: return
        
        val actionId = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        
        try {
            when (actionId) {
                EditorInfo.IME_ACTION_SEND -> {
                    inputConnection.performEditorAction(EditorInfo.IME_ACTION_SEND)
                    logD("Performed SEND action")
                }
                EditorInfo.IME_ACTION_GO -> {
                    inputConnection.performEditorAction(EditorInfo.IME_ACTION_GO)
                    logD("Performed GO action")
                }
                EditorInfo.IME_ACTION_SEARCH -> {
                    inputConnection.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
                    logD("Performed SEARCH action")
                }
                EditorInfo.IME_ACTION_DONE -> {
                    inputConnection.performEditorAction(EditorInfo.IME_ACTION_DONE)
                    logD("Performed DONE action")
                }
                else -> {
                    // Default to newline
                    inputConnection.commitText("\n", 1)
                    logD("Committed newline (default action)")
                }
            }
        } catch (e: Exception) {
            logE("Failed to perform editor action", e)
        }
    }
    
    /**
     * Delete text intelligently (respect word boundaries)
     */
    fun deleteTextIntelligently() {
        val inputConnection = currentInputConnection ?: return
        val inputState = getCurrentInputState() ?: return
        
        try {
            when {
                inputState.selectedText.isNotEmpty() -> {
                    // Delete selected text
                    inputConnection.commitText("", 1)
                    logD("Deleted selected text: '${inputState.selectedText}'")
                }
                
                inputState.textBefore.isNotEmpty() -> {
                    val lastChar = inputState.textBefore.last()
                    
                    if (lastChar.isWhitespace()) {
                        // Delete single whitespace
                        inputConnection.deleteSurroundingText(1, 0)
                    } else if (lastChar.isLetter()) {
                        // Delete whole word if ctrl is pressed, otherwise single character
                        // TODO: Check for ctrl modifier
                        inputConnection.deleteSurroundingText(1, 0)
                    } else {
                        // Delete single character
                        inputConnection.deleteSurroundingText(1, 0)
                    }
                }
            }
        } catch (e: Exception) {
            logE("Failed to delete text intelligently", e)
            // Fallback to simple delete
            inputConnection.deleteSurroundingText(1, 0)
        }
    }
    
    /**
     * Get text context for predictions
     */
    fun getTextContext(maxWords: Int = 5): List<String> {
        val inputState = getCurrentInputState() ?: return emptyList()
        
        return inputState.textBefore
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .takeLast(maxWords)
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
        currentInputConnection = null
        currentEditorInfo = null
    }
}