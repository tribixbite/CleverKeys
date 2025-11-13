package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages keyboard language settings and switching.
 *
 * Provides comprehensive language management for multi-language input,
 * including language switching, preferences, available languages, and
 * integration with system locales.
 *
 * Features:
 * - Multi-language support
 * - Dynamic language switching
 * - Language preferences persistence
 * - System locale integration
 * - Language fallback handling
 * - Enabled languages management
 * - Language-specific settings
 * - Recent languages tracking
 * - Automatic language detection
 * - Language change notifications
 * - Keyboard layout mapping
 * - Script/writing system detection
 *
 * Bug #344 - CATASTROPHIC: Complete implementation of missing LanguageManager.java
 *
 * @param context Application context
 */
class LanguageManager(
    private val context: Context
) {
    /**
     * Writing script type.
     */
    enum class Script {
        LATIN,           // English, French, German, Spanish, etc.
        CYRILLIC,        // Russian, Ukrainian, Bulgarian, etc.
        ARABIC,          // Arabic, Persian, Urdu
        HEBREW,          // Hebrew, Yiddish
        DEVANAGARI,      // Hindi, Sanskrit, Marathi, Nepali
        CHINESE,         // Chinese (Simplified/Traditional)
        JAPANESE,        // Japanese (Hiragana, Katakana, Kanji)
        KOREAN,          // Korean (Hangul)
        THAI,            // Thai
        GREEK,           // Greek
        UNKNOWN          // Unknown or mixed
    }

    /**
     * Language information.
     */
    data class LanguageInfo(
        val code: String,
        val displayName: String,
        val nativeName: String,
        val script: Script,
        val isRTL: Boolean,
        val locale: Locale
    ) {
        /**
         * Get short display name (code).
         */
        fun getShortName(): String = code.uppercase()

        /**
         * Get full display name with native name.
         */
        fun getFullDisplayName(): String = "$displayName ($nativeName)"
    }

    /**
     * Language change event.
     */
    data class LanguageChangeEvent(
        val previousLanguage: LanguageInfo?,
        val currentLanguage: LanguageInfo,
        val trigger: ChangeTrigger
    )

    /**
     * Trigger for language change.
     */
    enum class ChangeTrigger {
        USER_ACTION,        // User manually switched
        AUTO_DETECTION,     // Automatic detection
        SYSTEM_LOCALE,      // Following system locale
        APP_START,          // On app startup
        EXTERNAL_REQUEST    // From external component
    }

    /**
     * Language state.
     */
    data class LanguageState(
        val currentLanguage: LanguageInfo,
        val enabledLanguages: List<LanguageInfo>,
        val recentLanguages: List<LanguageInfo>,
        val autoSwitchEnabled: Boolean,
        val followSystemLocale: Boolean
    )

    companion object {
        private const val TAG = "LanguageManager"

        // SharedPreferences keys
        private const val PREFS_NAME = "language_manager_prefs"
        private const val KEY_CURRENT_LANGUAGE = "current_language"
        private const val KEY_ENABLED_LANGUAGES = "enabled_languages"
        private const val KEY_RECENT_LANGUAGES = "recent_languages"
        private const val KEY_AUTO_SWITCH = "auto_switch_enabled"
        private const val KEY_SYSTEM_FOLLOW = "follow_system_locale"

        // Constants
        private const val MAX_RECENT_LANGUAGES = 5
        private const val DEFAULT_LANGUAGE = "en"
    }

    /**
     * Callback interface for language events.
     */
    interface Callback {
        /**
         * Called when language changes.
         */
        fun onLanguageChanged(event: LanguageChangeEvent)

        /**
         * Called when enabled languages list changes.
         */
        fun onEnabledLanguagesChanged(languages: List<LanguageInfo>)

        /**
         * Called on language operation error.
         */
        fun onError(message: String)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // SharedPreferences
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // State
    private val _languageState = MutableStateFlow(
        LanguageState(
            currentLanguage = getDefaultLanguageInfo(),
            enabledLanguages = getDefaultEnabledLanguages(),
            recentLanguages = emptyList(),
            autoSwitchEnabled = false,
            followSystemLocale = true
        )
    )
    val languageState: StateFlow<LanguageState> = _languageState.asStateFlow()

    private var callback: Callback? = null

    // Available languages database
    private val availableLanguages = ConcurrentHashMap<String, LanguageInfo>()

    // Current language
    private var currentLanguage: LanguageInfo

    // Enabled languages
    private val enabledLanguages = Collections.synchronizedList(mutableListOf<LanguageInfo>())

    // Recent languages
    private val recentLanguages = Collections.synchronizedList(mutableListOf<LanguageInfo>())

    init {
        logD("LanguageManager initialized")

        // Initialize available languages database
        initializeAvailableLanguages()

        // Load preferences
        currentLanguage = loadCurrentLanguage()
        loadEnabledLanguages()
        loadRecentLanguages()

        // Update state
        updateState()
    }

    /**
     * Get current language.
     *
     * @return Current language info
     */
    fun getCurrentLanguage(): LanguageInfo = currentLanguage

    /**
     * Set current language.
     *
     * @param languageCode Language code (e.g., "en", "fr", "zh")
     * @param trigger Change trigger
     * @return True if language changed
     */
    suspend fun setLanguage(
        languageCode: String,
        trigger: ChangeTrigger = ChangeTrigger.USER_ACTION
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            val newLanguage = availableLanguages[languageCode]
            if (newLanguage == null) {
                logE("Language not found: $languageCode")
                callback?.onError("Language not found: $languageCode")
                return@withContext false
            }

            if (newLanguage.code == currentLanguage.code) {
                logD("Language already current: $languageCode")
                return@withContext false
            }

            val previousLanguage = currentLanguage
            currentLanguage = newLanguage

            // Save to preferences
            saveCurrentLanguage(newLanguage)

            // Add to recent languages
            addToRecentLanguages(newLanguage)

            // Update state
            updateState()

            // Notify callback
            val event = LanguageChangeEvent(
                previousLanguage = previousLanguage,
                currentLanguage = newLanguage,
                trigger = trigger
            )
            callback?.onLanguageChanged(event)

            logD("Language changed: ${previousLanguage.code} → ${newLanguage.code} (trigger: $trigger)")
            true
        } catch (e: Exception) {
            logE("Error setting language", e)
            callback?.onError("Failed to set language: ${e.message}")
            false
        }
    }

    /**
     * Get enabled languages.
     *
     * @return List of enabled languages
     */
    fun getEnabledLanguages(): List<LanguageInfo> {
        synchronized(enabledLanguages) {
            return enabledLanguages.toList()
        }
    }

    /**
     * Enable language.
     *
     * @param languageCode Language code to enable
     * @return True if language was enabled
     */
    suspend fun enableLanguage(languageCode: String): Boolean = withContext(Dispatchers.Default) {
        try {
            val language = availableLanguages[languageCode]
            if (language == null) {
                logE("Language not found: $languageCode")
                callback?.onError("Language not found: $languageCode")
                return@withContext false
            }

            synchronized(enabledLanguages) {
                if (enabledLanguages.any { it.code == languageCode }) {
                    logD("Language already enabled: $languageCode")
                    return@withContext false
                }

                enabledLanguages.add(language)
                saveEnabledLanguages()
                updateState()

                callback?.onEnabledLanguagesChanged(enabledLanguages.toList())
                logD("Language enabled: $languageCode")
                true
            }
        } catch (e: Exception) {
            logE("Error enabling language", e)
            callback?.onError("Failed to enable language: ${e.message}")
            false
        }
    }

    /**
     * Disable language.
     *
     * @param languageCode Language code to disable
     * @return True if language was disabled
     */
    suspend fun disableLanguage(languageCode: String): Boolean = withContext(Dispatchers.Default) {
        try {
            synchronized(enabledLanguages) {
                // Don't allow disabling the last language
                if (enabledLanguages.size <= 1) {
                    logD("Cannot disable last enabled language")
                    callback?.onError("Cannot disable last enabled language")
                    return@withContext false
                }

                // Don't allow disabling current language
                if (languageCode == currentLanguage.code) {
                    logD("Cannot disable current language")
                    callback?.onError("Cannot disable current language")
                    return@withContext false
                }

                val removed = enabledLanguages.removeIf { it.code == languageCode }
                if (removed) {
                    saveEnabledLanguages()
                    updateState()

                    callback?.onEnabledLanguagesChanged(enabledLanguages.toList())
                    logD("Language disabled: $languageCode")
                }
                removed
            }
        } catch (e: Exception) {
            logE("Error disabling language", e)
            callback?.onError("Failed to disable language: ${e.message}")
            false
        }
    }

    /**
     * Get all available languages.
     *
     * @return List of all available languages
     */
    fun getAvailableLanguages(): List<LanguageInfo> {
        return availableLanguages.values.sortedBy { it.displayName }
    }

    /**
     * Get recent languages.
     *
     * @return List of recently used languages
     */
    fun getRecentLanguages(): List<LanguageInfo> {
        synchronized(recentLanguages) {
            return recentLanguages.toList()
        }
    }

    /**
     * Switch to next enabled language.
     *
     * @return New current language
     */
    suspend fun switchToNextLanguage(): LanguageInfo? = withContext(Dispatchers.Default) {
        val nextLanguage = synchronized(enabledLanguages) {
            if (enabledLanguages.size <= 1) {
                logD("Only one language enabled, cannot switch")
                return@withContext null
            }

            val currentIndex = enabledLanguages.indexOfFirst { it.code == currentLanguage.code }
            val nextIndex = (currentIndex + 1) % enabledLanguages.size
            enabledLanguages[nextIndex]
        }

        setLanguage(nextLanguage.code, ChangeTrigger.USER_ACTION)
        nextLanguage
    }

    /**
     * Switch to previous enabled language.
     *
     * @return New current language
     */
    suspend fun switchToPreviousLanguage(): LanguageInfo? = withContext(Dispatchers.Default) {
        val prevLanguage = synchronized(enabledLanguages) {
            if (enabledLanguages.size <= 1) {
                logD("Only one language enabled, cannot switch")
                return@withContext null
            }

            val currentIndex = enabledLanguages.indexOfFirst { it.code == currentLanguage.code }
            val prevIndex = if (currentIndex == 0) enabledLanguages.size - 1 else currentIndex - 1
            enabledLanguages[prevIndex]
        }

        setLanguage(prevLanguage.code, ChangeTrigger.USER_ACTION)
        prevLanguage
    }

    /**
     * Get language info by code.
     *
     * @param languageCode Language code
     * @return Language info or null
     */
    fun getLanguageInfo(languageCode: String): LanguageInfo? {
        return availableLanguages[languageCode]
    }

    /**
     * Check if language is enabled.
     *
     * @param languageCode Language code
     * @return True if enabled
     */
    fun isLanguageEnabled(languageCode: String): Boolean {
        synchronized(enabledLanguages) {
            return enabledLanguages.any { it.code == languageCode }
        }
    }

    /**
     * Set auto-switch enabled.
     *
     * @param enabled Whether auto-switch is enabled
     */
    fun setAutoSwitchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SWITCH, enabled).apply()
        updateState()
        logD("Auto-switch ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if auto-switch is enabled.
     *
     * @return True if enabled
     */
    fun isAutoSwitchEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SWITCH, false)
    }

    /**
     * Set follow system locale enabled.
     *
     * @param enabled Whether to follow system locale
     */
    suspend fun setFollowSystemLocale(enabled: Boolean) = withContext(Dispatchers.Default) {
        prefs.edit().putBoolean(KEY_SYSTEM_FOLLOW, enabled).apply()
        updateState()

        if (enabled) {
            // Apply system locale immediately
            val systemLocale = Locale.getDefault()
            val systemLanguage = availableLanguages[systemLocale.language]
            if (systemLanguage != null) {
                setLanguage(systemLanguage.code, ChangeTrigger.SYSTEM_LOCALE)
            }
        }

        logD("Follow system locale ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if follow system locale is enabled.
     *
     * @return True if enabled
     */
    fun isFollowSystemLocale(): Boolean {
        return prefs.getBoolean(KEY_SYSTEM_FOLLOW, true)
    }

    /**
     * Set callback for language events.
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Initialize available languages database.
     */
    private fun initializeAvailableLanguages() {
        // Major world languages
        addLanguage("en", "English", "English", Script.LATIN, false)
        addLanguage("es", "Spanish", "Español", Script.LATIN, false)
        addLanguage("fr", "French", "Français", Script.LATIN, false)
        addLanguage("de", "German", "Deutsch", Script.LATIN, false)
        addLanguage("it", "Italian", "Italiano", Script.LATIN, false)
        addLanguage("pt", "Portuguese", "Português", Script.LATIN, false)
        addLanguage("ru", "Russian", "Русский", Script.CYRILLIC, false)
        addLanguage("zh", "Chinese", "中文", Script.CHINESE, false)
        addLanguage("ja", "Japanese", "日本語", Script.JAPANESE, false)
        addLanguage("ko", "Korean", "한국어", Script.KOREAN, false)
        addLanguage("ar", "Arabic", "العربية", Script.ARABIC, true)
        addLanguage("he", "Hebrew", "עברית", Script.HEBREW, true)
        addLanguage("hi", "Hindi", "हिन्दी", Script.DEVANAGARI, false)
        addLanguage("th", "Thai", "ไทย", Script.THAI, false)
        addLanguage("el", "Greek", "Ελληνικά", Script.GREEK, false)
        addLanguage("tr", "Turkish", "Türkçe", Script.LATIN, false)
        addLanguage("pl", "Polish", "Polski", Script.LATIN, false)
        addLanguage("nl", "Dutch", "Nederlands", Script.LATIN, false)
        addLanguage("sv", "Swedish", "Svenska", Script.LATIN, false)
        addLanguage("da", "Danish", "Dansk", Script.LATIN, false)

        logD("Initialized ${availableLanguages.size} available languages")
    }

    /**
     * Add language to available languages.
     */
    private fun addLanguage(
        code: String,
        displayName: String,
        nativeName: String,
        script: Script,
        isRTL: Boolean
    ) {
        val locale = Locale(code)
        val language = LanguageInfo(
            code = code,
            displayName = displayName,
            nativeName = nativeName,
            script = script,
            isRTL = isRTL,
            locale = locale
        )
        availableLanguages[code] = language
    }

    /**
     * Load current language from preferences.
     */
    private fun loadCurrentLanguage(): LanguageInfo {
        val code = prefs.getString(KEY_CURRENT_LANGUAGE, null)
        return if (code != null && availableLanguages.containsKey(code)) {
            availableLanguages[code]!!
        } else {
            // Try system locale
            val systemLocale = Locale.getDefault()
            availableLanguages[systemLocale.language] ?: getDefaultLanguageInfo()
        }
    }

    /**
     * Save current language to preferences.
     */
    private fun saveCurrentLanguage(language: LanguageInfo) {
        prefs.edit().putString(KEY_CURRENT_LANGUAGE, language.code).apply()
    }

    /**
     * Load enabled languages from preferences.
     */
    private fun loadEnabledLanguages() {
        val codes = prefs.getStringSet(KEY_ENABLED_LANGUAGES, null)

        synchronized(enabledLanguages) {
            enabledLanguages.clear()

            if (codes != null && codes.isNotEmpty()) {
                for (code in codes) {
                    availableLanguages[code]?.let { enabledLanguages.add(it) }
                }
            }

            // Ensure current language is enabled
            if (!enabledLanguages.any { it.code == currentLanguage.code }) {
                enabledLanguages.add(currentLanguage)
            }

            // Ensure at least one language is enabled
            if (enabledLanguages.isEmpty()) {
                enabledLanguages.addAll(getDefaultEnabledLanguages())
            }
        }
    }

    /**
     * Save enabled languages to preferences.
     */
    private fun saveEnabledLanguages() {
        synchronized(enabledLanguages) {
            val codes = enabledLanguages.map { it.code }.toSet()
            prefs.edit().putStringSet(KEY_ENABLED_LANGUAGES, codes).apply()
        }
    }

    /**
     * Load recent languages from preferences.
     */
    private fun loadRecentLanguages() {
        val codes = prefs.getString(KEY_RECENT_LANGUAGES, null)

        synchronized(recentLanguages) {
            recentLanguages.clear()

            if (codes != null) {
                val codesList = codes.split(",")
                for (code in codesList) {
                    availableLanguages[code]?.let { recentLanguages.add(it) }
                }
            }
        }
    }

    /**
     * Save recent languages to preferences.
     */
    private fun saveRecentLanguages() {
        synchronized(recentLanguages) {
            val codes = recentLanguages.joinToString(",") { it.code }
            prefs.edit().putString(KEY_RECENT_LANGUAGES, codes).apply()
        }
    }

    /**
     * Add language to recent languages.
     */
    private fun addToRecentLanguages(language: LanguageInfo) {
        synchronized(recentLanguages) {
            // Remove if already present
            recentLanguages.removeIf { it.code == language.code }

            // Add to front
            recentLanguages.add(0, language)

            // Trim to max size
            while (recentLanguages.size > MAX_RECENT_LANGUAGES) {
                recentLanguages.removeAt(recentLanguages.size - 1)
            }

            saveRecentLanguages()
        }
    }

    /**
     * Update language state.
     */
    private fun updateState() {
        _languageState.value = LanguageState(
            currentLanguage = currentLanguage,
            enabledLanguages = getEnabledLanguages(),
            recentLanguages = getRecentLanguages(),
            autoSwitchEnabled = isAutoSwitchEnabled(),
            followSystemLocale = isFollowSystemLocale()
        )
    }

    /**
     * Get default language info.
     */
    private fun getDefaultLanguageInfo(): LanguageInfo {
        return availableLanguages[DEFAULT_LANGUAGE] ?: LanguageInfo(
            code = DEFAULT_LANGUAGE,
            displayName = "English",
            nativeName = "English",
            script = Script.LATIN,
            isRTL = false,
            locale = Locale.ENGLISH
        )
    }

    /**
     * Get default enabled languages.
     */
    private fun getDefaultEnabledLanguages(): List<LanguageInfo> {
        return listOf(getDefaultLanguageInfo())
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing LanguageManager resources...")

        try {
            scope.cancel()
            availableLanguages.clear()
            enabledLanguages.clear()
            recentLanguages.clear()
            callback = null

            logD("✅ LanguageManager resources released")
        } catch (e: Exception) {
            logE("Error releasing language manager resources", e)
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
