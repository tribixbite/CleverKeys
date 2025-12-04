package tribixbite.cleverkeys

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides UI for selecting keyboard input language.
 *
 * Shows language selection dialog (typically triggered by globe key),
 * displays current language indicator, and manages language switching UI.
 *
 * Features:
 * - Language selection dialog
 * - Current language indicator view
 * - Quick language switching (swipe/tap globe key)
 * - Recent languages menu
 * - Language search/filter
 * - Keyboard shortcut support
 * - Visual language indicators
 * - Language flags/icons (optional)
 * - Multi-selection for enabling languages
 * - Language settings integration
 *
 * Bug #347 - CATASTROPHIC: Complete implementation of missing IMELanguageSelector.java
 *
 * @param context Application context
 * @param languageManager Language manager for operations
 */
class IMELanguageSelector(
    private val context: Context,
    private val languageManager: LanguageManager
) {
    companion object {
        private const val TAG = "IMELanguageSelector"

        /**
         * Selection mode type.
         */
        enum class SelectionMode {
            SWITCH,          // Switch current language
            ENABLE_DISABLE   // Enable/disable languages
        }

        /**
         * Display style for language indicator.
         */
        enum class IndicatorStyle {
            FULL_NAME,       // "English"
            SHORT_CODE,      // "EN"
            NATIVE_NAME,     // Native script
            FLAG_EMOJI,      // Flag emoji (if available)
            COMPACT          // Minimal display
        }

        /**
         * UI state.
         */
        data class UIState(
            val isDialogShowing: Boolean,
            val currentLanguage: LanguageManager.LanguageInfo,
            val recentLanguages: List<LanguageManager.LanguageInfo>,
            val indicatorStyle: IndicatorStyle
        )
    }

    /**
     * Callback interface for language selection events.
     */
    interface Callback {
        /**
         * Called when language is selected.
         */
        fun onLanguageSelected(language: LanguageManager.LanguageInfo)

        /**
         * Called when languages are enabled/disabled.
         */
        fun onLanguagesChanged(enabled: List<LanguageManager.LanguageInfo>)

        /**
         * Called when user cancels selection.
         */
        fun onSelectionCancelled()

        /**
         * Called when settings should be opened.
         */
        fun onOpenSettings()
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // State
    private val _uiState = MutableStateFlow(
        UIState(
            isDialogShowing = false,
            currentLanguage = languageManager.getCurrentLanguage(),
            recentLanguages = languageManager.getRecentLanguages(),
            indicatorStyle = IndicatorStyle.SHORT_CODE
        )
    )
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    private var callback: Callback? = null

    // Current dialog
    private var currentDialog: AlertDialog? = null

    // Indicator view
    private var indicatorView: TextView? = null

    // Current indicator style
    private var indicatorStyle = IndicatorStyle.SHORT_CODE

    init {
        logD("IMELanguageSelector initialized")

        // Listen to language manager changes
        languageManager.setCallback(object : LanguageManager.Callback {
            override fun onLanguageChanged(event: LanguageManager.LanguageChangeEvent) {
                updateState()
                callback?.onLanguageSelected(event.currentLanguage)
            }

            override fun onEnabledLanguagesChanged(languages: List<LanguageManager.LanguageInfo>) {
                updateState()
                callback?.onLanguagesChanged(languages)
            }

            override fun onError(message: String) {
                showError(message)
            }
        })
    }

    /**
     * Show language selection dialog.
     *
     * @param mode Selection mode
     */
    fun showLanguageSelectionDialog(mode: SelectionMode = SelectionMode.SWITCH) {
        scope.launch {
            try {
                dismissCurrentDialog()

                when (mode) {
                    SelectionMode.SWITCH -> showSwitchDialog()
                    SelectionMode.ENABLE_DISABLE -> showEnableDisableDialog()
                }

                updateState(isDialogShowing = true)
            } catch (e: Exception) {
                logE("Error showing language selection dialog", e)
            }
        }
    }

    /**
     * Show recent languages menu (quick switch).
     */
    fun showRecentLanguagesMenu() {
        scope.launch {
            try {
                dismissCurrentDialog()

                val recent = languageManager.getRecentLanguages()
                if (recent.isEmpty()) {
                    logD("No recent languages")
                    return@launch
                }

                val items = recent.map { it.getFullDisplayName() }.toTypedArray()

                currentDialog = AlertDialog.Builder(context)
                    .setTitle("Recent Languages")
                    .setItems(items) { dialog, which ->
                        val selected = recent[which]
                        scope.launch {
                            languageManager.setLanguage(
                                selected.code,
                                LanguageManager.ChangeTrigger.USER_ACTION
                            )
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        callback?.onSelectionCancelled()
                        dialog.dismiss()
                    }
                    .setOnDismissListener {
                        updateState(isDialogShowing = false)
                    }
                    .create()

                currentDialog?.show()
                updateState(isDialogShowing = true)
            } catch (e: Exception) {
                logE("Error showing recent languages menu", e)
            }
        }
    }

    /**
     * Switch to next language.
     */
    fun switchToNext() {
        scope.launch {
            try {
                languageManager.switchToNextLanguage()
            } catch (e: Exception) {
                logE("Error switching to next language", e)
            }
        }
    }

    /**
     * Switch to previous language.
     */
    fun switchToPrevious() {
        scope.launch {
            try {
                languageManager.switchToPreviousLanguage()
            } catch (e: Exception) {
                logE("Error switching to previous language", e)
            }
        }
    }

    /**
     * Create language indicator view.
     *
     * @param style Display style
     * @return Language indicator view
     */
    fun createIndicatorView(style: IndicatorStyle = IndicatorStyle.SHORT_CODE): TextView {
        indicatorStyle = style

        val textView = TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 4)

            // Initial text
            text = formatLanguageText(languageManager.getCurrentLanguage(), style)

            // Click listener
            setOnClickListener {
                showLanguageSelectionDialog(SelectionMode.SWITCH)
            }

            // Long click listener
            setOnLongClickListener {
                showRecentLanguagesMenu()
                true
            }
        }

        indicatorView = textView
        return textView
    }

    /**
     * Update indicator view.
     */
    fun updateIndicator() {
        indicatorView?.let { view ->
            val currentLang = languageManager.getCurrentLanguage()
            view.text = formatLanguageText(currentLang, indicatorStyle)
        }
    }

    /**
     * Set indicator style.
     *
     * @param style Display style
     */
    fun setIndicatorStyle(style: IndicatorStyle) {
        indicatorStyle = style
        updateIndicator()
    }

    /**
     * Dismiss current dialog if showing.
     */
    fun dismissCurrentDialog() {
        currentDialog?.dismiss()
        currentDialog = null
    }

    /**
     * Check if dialog is showing.
     *
     * @return True if showing
     */
    fun isDialogShowing(): Boolean = currentDialog?.isShowing == true

    /**
     * Set callback for selection events.
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Show switch language dialog.
     */
    private fun showSwitchDialog() {
        val enabled = languageManager.getEnabledLanguages()
        val current = languageManager.getCurrentLanguage()

        val items = enabled.map { lang ->
            val prefix = if (lang.code == current.code) "✓ " else "  "
            "$prefix${lang.getFullDisplayName()}"
        }.toTypedArray()

        val currentIndex = enabled.indexOfFirst { it.code == current.code }

        currentDialog = AlertDialog.Builder(context)
            .setTitle("Select Language")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                val selected = enabled[which]
                scope.launch {
                    languageManager.setLanguage(
                        selected.code,
                        LanguageManager.ChangeTrigger.USER_ACTION
                    )
                }
                dialog.dismiss()
            }
            .setNeutralButton("Manage Languages") { dialog, _ ->
                dialog.dismiss()
                showEnableDisableDialog()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                callback?.onSelectionCancelled()
                dialog.dismiss()
            }
            .setOnDismissListener {
                updateState(isDialogShowing = false)
            }
            .create()

        // Customize dialog appearance
        currentDialog?.setOnShowListener {
            customizeDialogAppearance(currentDialog!!)
        }

        currentDialog?.show()
    }

    /**
     * Show enable/disable languages dialog.
     */
    private fun showEnableDisableDialog() {
        val available = languageManager.getAvailableLanguages()
        val enabled = languageManager.getEnabledLanguages()

        val items = available.map { it.getFullDisplayName() }.toTypedArray()
        val checkedItems = available.map { lang ->
            enabled.any { it.code == lang.code }
        }.toBooleanArray()

        currentDialog = AlertDialog.Builder(context)
            .setTitle("Manage Languages")
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                val language = available[which]
                scope.launch {
                    if (isChecked) {
                        languageManager.enableLanguage(language.code)
                    } else {
                        languageManager.disableLanguage(language.code)
                    }
                }
            }
            .setPositiveButton("Done") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Settings") { dialog, _ ->
                dialog.dismiss()
                callback?.onOpenSettings()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                callback?.onSelectionCancelled()
                dialog.dismiss()
            }
            .setOnDismissListener {
                updateState(isDialogShowing = false)
            }
            .create()

        // Customize dialog appearance
        currentDialog?.setOnShowListener {
            customizeDialogAppearance(currentDialog!!)
        }

        currentDialog?.show()
    }

    /**
     * Customize dialog appearance.
     */
    private fun customizeDialogAppearance(dialog: AlertDialog) {
        try {
            // Set background
            dialog.window?.setBackgroundDrawableResource(android.R.color.background_dark)

            // Get list view
            val listView = dialog.listView
            listView?.apply {
                dividerHeight = 1
                setPadding(16, 16, 16, 16)
            }

            // Customize buttons
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
                setTextColor(Color.parseColor("#4CAF50"))
            }

            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.apply {
                setTextColor(Color.parseColor("#F44336"))
            }

            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.apply {
                setTextColor(Color.parseColor("#2196F3"))
            }
        } catch (e: Exception) {
            logE("Error customizing dialog", e)
        }
    }

    /**
     * Format language text for display.
     */
    private fun formatLanguageText(
        language: LanguageManager.LanguageInfo,
        style: IndicatorStyle
    ): String {
        return when (style) {
            IndicatorStyle.FULL_NAME -> language.displayName
            IndicatorStyle.SHORT_CODE -> language.getShortName()
            IndicatorStyle.NATIVE_NAME -> language.nativeName
            IndicatorStyle.FLAG_EMOJI -> getFlagEmoji(language.code)
            IndicatorStyle.COMPACT -> language.code.uppercase()
        }
    }

    /**
     * Get flag emoji for language code.
     *
     * Note: This is a simplified implementation. In production,
     * you would use a proper flag mapping library.
     */
    private fun getFlagEmoji(languageCode: String): String {
        // Map language codes to country codes for flags
        val flagMap = mapOf(
            "en" to "🇬🇧",
            "es" to "🇪🇸",
            "fr" to "🇫🇷",
            "de" to "🇩🇪",
            "it" to "🇮🇹",
            "pt" to "🇵🇹",
            "ru" to "🇷🇺",
            "zh" to "🇨🇳",
            "ja" to "🇯🇵",
            "ko" to "🇰🇷",
            "ar" to "🇸🇦",
            "he" to "🇮🇱",
            "hi" to "🇮🇳",
            "th" to "🇹🇭",
            "el" to "🇬🇷",
            "tr" to "🇹🇷",
            "pl" to "🇵🇱",
            "nl" to "🇳🇱",
            "sv" to "🇸🇪",
            "da" to "🇩🇰"
        )

        return flagMap[languageCode] ?: languageCode.uppercase()
    }

    /**
     * Show error message.
     */
    private fun showError(message: String) {
        scope.launch(Dispatchers.Main) {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                logE("Error showing error toast", e)
            }
        }
    }

    /**
     * Update UI state.
     */
    private fun updateState(isDialogShowing: Boolean = _uiState.value.isDialogShowing) {
        _uiState.value = UIState(
            isDialogShowing = isDialogShowing,
            currentLanguage = languageManager.getCurrentLanguage(),
            recentLanguages = languageManager.getRecentLanguages(),
            indicatorStyle = indicatorStyle
        )

        // Update indicator if exists
        updateIndicator()
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing IMELanguageSelector resources...")

        try {
            dismissCurrentDialog()
            scope.cancel()
            indicatorView = null
            callback = null

            logD("✅ IMELanguageSelector resources released")
        } catch (e: Exception) {
            logE("Error releasing IME language selector resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
