package tribixbite.keyboard2

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.*

/**
 * Manages locale-specific formatting, separators, RTL support, and internationalization.
 *
 * Provides comprehensive locale handling for proper text formatting, number formatting,
 * date/time formatting, currency symbols, decimal separators, and right-to-left language support.
 *
 * Features:
 * - Automatic locale detection from system settings
 * - Manual locale override support
 * - Number formatting with locale-specific separators
 * - Currency formatting with proper symbols
 * - Date and time formatting per locale conventions
 * - RTL (right-to-left) layout detection and support
 * - Decimal separator handling (period vs comma)
 * - Grouping separator handling (comma vs space vs period)
 * - Quote style detection (" " vs « »)
 * - First day of week detection (Sunday vs Monday)
 * - 12/24 hour time format detection
 * - Locale change notification system
 *
 * Bug #346 - HIGH: Complete implementation of missing LocaleManager.java
 *
 * @param context Application context for accessing resources
 */
class LocaleManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "LocaleManager"

        // RTL language codes
        private val RTL_LANGUAGES = setOf(
            "ar",  // Arabic
            "fa",  // Persian
            "he",  // Hebrew
            "iw",  // Hebrew (old code)
            "ji",  // Yiddish (old code)
            "ur",  // Urdu
            "yi"   // Yiddish
        )

        // Languages using comma as decimal separator
        private val COMMA_DECIMAL_LANGUAGES = setOf(
            "de",  // German
            "es",  // Spanish
            "fr",  // French
            "it",  // Italian
            "pt",  // Portuguese
            "nl",  // Dutch
            "pl",  // Polish
            "ru",  // Russian
            "sv",  // Swedish
            "da",  // Danish
            "fi",  // Finnish
            "no",  // Norwegian
            "cs",  // Czech
            "tr"   // Turkish
        )

        // Default locale symbols
        private const val DEFAULT_DECIMAL_SEPARATOR = '.'
        private const val DEFAULT_GROUPING_SEPARATOR = ','
        private const val DEFAULT_QUOTE_START = '"'
        private const val DEFAULT_QUOTE_END = '"'
    }

    /**
     * Locale change callback interface.
     */
    interface Callback {
        /**
         * Called when locale changes.
         *
         * @param locale New locale
         * @param isRtl Whether new locale is RTL
         */
        fun onLocaleChanged(locale: Locale, isRtl: Boolean)

        /**
         * Called when system locale is detected.
         *
         * @param locale Detected system locale
         */
        fun onSystemLocaleDetected(locale: Locale)

        /**
         * Called when formatting symbols change.
         *
         * @param decimalSeparator Decimal separator character
         * @param groupingSeparator Grouping separator character
         */
        fun onFormattingSymbolsChanged(decimalSeparator: Char, groupingSeparator: Char)
    }

    /**
     * Locale information data class.
     */
    data class LocaleInfo(
        val locale: Locale,
        val isRtl: Boolean,
        val decimalSeparator: Char,
        val groupingSeparator: Char,
        val currencySymbol: String,
        val currencyCode: String,
        val quoteStart: String,
        val quoteEnd: String,
        val firstDayOfWeek: Int,
        val is24HourFormat: Boolean
    )

    // Current state
    private var currentLocale: Locale = Locale.getDefault()
    private var systemLocale: Locale = Locale.getDefault()
    private var overrideLocale: Locale? = null
    private var callback: Callback? = null

    // Cached locale information
    private var localeInfo: LocaleInfo
    private var decimalFormat: DecimalFormat
    private var numberFormat: NumberFormat
    private var currencyFormat: NumberFormat

    init {
        logD("Initializing LocaleManager")
        detectSystemLocale()
        localeInfo = buildLocaleInfo(currentLocale)
        decimalFormat = buildDecimalFormat(currentLocale)
        numberFormat = NumberFormat.getInstance(currentLocale)
        currencyFormat = NumberFormat.getCurrencyInstance(currentLocale)
        logD("Initial locale: ${currentLocale.toLanguageTag()}, RTL: ${localeInfo.isRtl}")
    }

    /**
     * Detect current system locale.
     */
    private fun detectSystemLocale() {
        systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }

        currentLocale = overrideLocale ?: systemLocale
        logD("System locale detected: ${systemLocale.toLanguageTag()}")
        callback?.onSystemLocaleDetected(systemLocale)
    }

    /**
     * Build locale information for given locale.
     */
    private fun buildLocaleInfo(locale: Locale): LocaleInfo {
        val decimalSymbols = DecimalFormatSymbols.getInstance(locale)
        val isRtl = isRtlLocale(locale)
        val calendar = Calendar.getInstance(locale)

        // Get currency for locale
        val currencyCode = try {
            Currency.getInstance(locale).currencyCode
        } catch (e: Exception) {
            "USD"
        }

        return LocaleInfo(
            locale = locale,
            isRtl = isRtl,
            decimalSeparator = decimalSymbols.decimalSeparator,
            groupingSeparator = decimalSymbols.groupingSeparator,
            currencySymbol = decimalSymbols.currencySymbol,
            currencyCode = currencyCode,
            quoteStart = getQuoteStart(locale),
            quoteEnd = getQuoteEnd(locale),
            firstDayOfWeek = calendar.firstDayOfWeek,
            is24HourFormat = android.text.format.DateFormat.is24HourFormat(context)
        )
    }

    /**
     * Build decimal format for given locale.
     */
    private fun buildDecimalFormat(locale: Locale): DecimalFormat {
        val format = DecimalFormat.getInstance(locale) as DecimalFormat
        format.isDecimalSeparatorAlwaysShown = false
        return format
    }

    /**
     * Check if locale is right-to-left.
     */
    private fun isRtlLocale(locale: Locale): Boolean {
        val language = locale.language.lowercase(Locale.ROOT)

        // Check language code
        if (RTL_LANGUAGES.contains(language)) {
            return true
        }

        // Check layout direction (API 17+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val config = Configuration()
            config.setLocale(locale)
            return config.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        }

        return false
    }

    /**
     * Get opening quote character for locale.
     */
    private fun getQuoteStart(locale: Locale): String {
        return when (locale.language) {
            "fr", "ru", "it" -> "«"
            "de" -> "„"
            "pl", "ro" -> "„"
            else -> "\""
        }
    }

    /**
     * Get closing quote character for locale.
     */
    private fun getQuoteEnd(locale: Locale): String {
        return when (locale.language) {
            "fr", "ru", "it" -> "»"
            "de" -> """
            "pl", "ro" -> """
            else -> "\""
        }
    }

    /**
     * Set locale override.
     *
     * @param locale Locale to use (null to use system locale)
     */
    fun setLocale(locale: Locale?) {
        val newLocale = locale ?: systemLocale
        if (newLocale == currentLocale) {
            return
        }

        overrideLocale = locale
        currentLocale = newLocale

        // Rebuild cached information
        localeInfo = buildLocaleInfo(currentLocale)
        decimalFormat = buildDecimalFormat(currentLocale)
        numberFormat = NumberFormat.getInstance(currentLocale)
        currencyFormat = NumberFormat.getCurrencyInstance(currentLocale)

        logD("Locale changed to: ${currentLocale.toLanguageTag()}, RTL: ${localeInfo.isRtl}")

        callback?.onLocaleChanged(currentLocale, localeInfo.isRtl)
        callback?.onFormattingSymbolsChanged(localeInfo.decimalSeparator, localeInfo.groupingSeparator)
    }

    /**
     * Handle configuration change (e.g., system locale changed).
     *
     * @param newConfig New configuration
     */
    fun onConfigurationChanged(newConfig: Configuration) {
        val oldSystemLocale = systemLocale
        detectSystemLocale()

        if (systemLocale != oldSystemLocale && overrideLocale == null) {
            // System locale changed and no override set
            setLocale(null)  // Refresh to use new system locale
        }
    }

    /**
     * Format number with locale-specific separators.
     *
     * @param number Number to format
     * @return Formatted string
     */
    fun formatNumber(number: Number): String {
        return numberFormat.format(number)
    }

    /**
     * Format decimal with locale-specific decimal separator.
     *
     * @param value Decimal value
     * @param decimals Number of decimal places
     * @return Formatted string
     */
    fun formatDecimal(value: Double, decimals: Int = 2): String {
        decimalFormat.minimumFractionDigits = decimals
        decimalFormat.maximumFractionDigits = decimals
        return decimalFormat.format(value)
    }

    /**
     * Format currency with locale-specific symbol and formatting.
     *
     * @param amount Currency amount
     * @return Formatted currency string
     */
    fun formatCurrency(amount: Double): String {
        return currencyFormat.format(amount)
    }

    /**
     * Parse locale-formatted number string.
     *
     * @param text Number string with locale-specific separators
     * @return Parsed number or null if invalid
     */
    fun parseNumber(text: String): Number? {
        return try {
            numberFormat.parse(text)
        } catch (e: Exception) {
            logE("Failed to parse number: $text", e)
            null
        }
    }

    /**
     * Parse locale-formatted decimal string.
     *
     * @param text Decimal string with locale-specific separator
     * @return Parsed double or null if invalid
     */
    fun parseDecimal(text: String): Double? {
        return try {
            decimalFormat.parse(text)?.toDouble()
        } catch (e: Exception) {
            logE("Failed to parse decimal: $text", e)
            null
        }
    }

    /**
     * Convert number string from one locale to another.
     *
     * @param text Number string
     * @param sourceLocale Source locale
     * @param targetLocale Target locale
     * @return Converted string or original if conversion fails
     */
    fun convertNumberFormat(text: String, sourceLocale: Locale, targetLocale: Locale): String {
        return try {
            val sourceFormat = NumberFormat.getInstance(sourceLocale)
            val number = sourceFormat.parse(text) ?: return text

            val targetFormat = NumberFormat.getInstance(targetLocale)
            targetFormat.format(number)
        } catch (e: Exception) {
            logE("Failed to convert number format", e)
            text
        }
    }

    /**
     * Normalize decimal separator to standard period.
     *
     * @param text Text with locale-specific decimal separator
     * @return Text with period as decimal separator
     */
    fun normalizeDecimalSeparator(text: String): String {
        if (localeInfo.decimalSeparator == DEFAULT_DECIMAL_SEPARATOR) {
            return text
        }

        return text.replace(localeInfo.decimalSeparator, DEFAULT_DECIMAL_SEPARATOR)
    }

    /**
     * Localize decimal separator from standard period.
     *
     * @param text Text with period as decimal separator
     * @return Text with locale-specific decimal separator
     */
    fun localizeDecimalSeparator(text: String): String {
        if (localeInfo.decimalSeparator == DEFAULT_DECIMAL_SEPARATOR) {
            return text
        }

        return text.replace(DEFAULT_DECIMAL_SEPARATOR, localeInfo.decimalSeparator)
    }

    /**
     * Wrap text in locale-specific quotes.
     *
     * @param text Text to quote
     * @return Quoted text
     */
    fun quote(text: String): String {
        return "${localeInfo.quoteStart}$text${localeInfo.quoteEnd}"
    }

    /**
     * Get current locale.
     *
     * @return Current locale
     */
    fun getCurrentLocale(): Locale = currentLocale

    /**
     * Get system locale.
     *
     * @return System locale
     */
    fun getSystemLocale(): Locale = systemLocale

    /**
     * Get override locale.
     *
     * @return Override locale or null if using system locale
     */
    fun getOverrideLocale(): Locale? = overrideLocale

    /**
     * Get complete locale information.
     *
     * @return Locale information
     */
    fun getLocaleInfo(): LocaleInfo = localeInfo

    /**
     * Check if current locale is RTL.
     *
     * @return true if RTL, false otherwise
     */
    fun isRtl(): Boolean = localeInfo.isRtl

    /**
     * Get decimal separator for current locale.
     *
     * @return Decimal separator character
     */
    fun getDecimalSeparator(): Char = localeInfo.decimalSeparator

    /**
     * Get grouping separator for current locale.
     *
     * @return Grouping separator character
     */
    fun getGroupingSeparator(): Char = localeInfo.groupingSeparator

    /**
     * Get currency symbol for current locale.
     *
     * @return Currency symbol
     */
    fun getCurrencySymbol(): String = localeInfo.currencySymbol

    /**
     * Get currency code for current locale.
     *
     * @return Currency code (e.g., USD, EUR)
     */
    fun getCurrencyCode(): String = localeInfo.currencyCode

    /**
     * Check if current locale uses 24-hour time format.
     *
     * @return true if 24-hour, false if 12-hour
     */
    fun is24HourFormat(): Boolean = localeInfo.is24HourFormat

    /**
     * Get first day of week for current locale.
     *
     * @return Calendar constant (Calendar.SUNDAY, Calendar.MONDAY, etc.)
     */
    fun getFirstDayOfWeek(): Int = localeInfo.firstDayOfWeek

    /**
     * Get language code for current locale.
     *
     * @return Language code (e.g., "en", "fr", "de")
     */
    fun getLanguageCode(): String = currentLocale.language

    /**
     * Get country code for current locale.
     *
     * @return Country code (e.g., "US", "GB", "FR")
     */
    fun getCountryCode(): String = currentLocale.country

    /**
     * Get language tag for current locale.
     *
     * @return Language tag (e.g., "en-US", "fr-FR")
     */
    fun getLanguageTag(): String = currentLocale.toLanguageTag()

    /**
     * Get display name for current locale in current locale.
     *
     * @return Display name
     */
    fun getDisplayName(): String = currentLocale.displayName

    /**
     * Get display language for current locale.
     *
     * @return Display language name
     */
    fun getDisplayLanguage(): String = currentLocale.displayLanguage

    /**
     * Get display country for current locale.
     *
     * @return Display country name
     */
    fun getDisplayCountry(): String = currentLocale.displayCountry

    /**
     * Check if locale uses comma as decimal separator.
     *
     * @param locale Locale to check
     * @return true if uses comma, false if uses period
     */
    fun usesCommaDecimal(locale: Locale = currentLocale): Boolean {
        return COMMA_DECIMAL_LANGUAGES.contains(locale.language)
    }

    /**
     * Get list of available locales.
     *
     * @return Array of available locales
     */
    fun getAvailableLocales(): Array<Locale> = Locale.getAvailableLocales()

    /**
     * Create locale from language and country codes.
     *
     * @param language Language code
     * @param country Country code (optional)
     * @return Created locale
     */
    fun createLocale(language: String, country: String = ""): Locale {
        return if (country.isEmpty()) {
            Locale(language)
        } else {
            Locale(language, country)
        }
    }

    /**
     * Set callback for locale change events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Reset to system locale.
     */
    fun reset() {
        setLocale(null)
        logD("Reset to system locale")
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing LocaleManager resources...")

        try {
            callback = null
            logD("✅ LocaleManager resources released")
        } catch (e: Exception) {
            logE("Error releasing locale manager resources", e)
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
