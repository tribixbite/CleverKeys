package tribixbite.cleverkeys

import android.util.Log
import java.nio.charset.Charset
import java.text.Normalizer

/**
 * Manages character set detection, encoding conversion, and transliteration.
 *
 * Provides comprehensive character handling for proper encoding detection,
 * conversion between character sets, transliteration of special characters,
 * and normalization of Unicode text.
 *
 * Features:
 * - Automatic character encoding detection
 * - Encoding conversion between charsets (UTF-8, ISO-8859-1, etc.)
 * - Transliteration (converting accented characters to ASCII)
 * - Character set validation and compatibility checking
 * - Script detection (Latin, Cyrillic, Arabic, Chinese, etc.)
 * - Diacritic removal (café → cafe)
 * - ASCII conversion with fallback mapping
 * - Character classification utilities
 * - Emoji detection and filtering
 * - Control character handling
 *
 * Bug #350 - HIGH: Complete implementation of missing CharacterSetManager.java
 */
class CharacterSetManager {
    companion object {
        private const val TAG = "CharacterSetManager"

        // Common character sets
        val UTF_8: Charset = Charsets.UTF_8
        val ISO_8859_1: Charset = Charsets.ISO_8859_1
        val US_ASCII: Charset = Charsets.US_ASCII
        val UTF_16: Charset = Charsets.UTF_16
        val UTF_16BE: Charset = Charsets.UTF_16BE
        val UTF_16LE: Charset = Charsets.UTF_16LE

        // Unicode ranges for script detection
        private val LATIN_RANGE = '\u0000'..'\u024F'
        private val CYRILLIC_RANGE = '\u0400'..'\u04FF'
        private val ARABIC_RANGE = '\u0600'..'\u06FF'
        private val HEBREW_RANGE = '\u0590'..'\u05FF'
        private val GREEK_RANGE = '\u0370'..'\u03FF'
        private val CJK_RANGE = '\u4E00'..'\u9FFF'          // CJK Unified Ideographs
        private val HANGUL_RANGE = '\uAC00'..'\uD7AF'       // Hangul Syllables
        private val DEVANAGARI_RANGE = '\u0900'..'\u097F'
        private val THAI_RANGE = '\u0E00'..'\u0E7F'

        // Emoji ranges (basic detection)
        private val EMOJI_RANGE_1 = '\u2600'..'\u26FF'      // Miscellaneous Symbols
        private val EMOJI_RANGE_2 = '\u2700'..'\u27BF'      // Dingbats
        private val EMOJI_RANGE_3 = '\uD83C'..'\uD83F'      // Emoticons (high surrogate)
        private val EMOJI_RANGE_4 = '\uDC00'..'\uDFFF'      // Emoticons (low surrogate)

        // Transliteration map (accented → ASCII)
        private val TRANSLITERATION_MAP = mapOf(
            'à' to 'a', 'á' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'å' to 'a', 'æ' to "ae",
            'À' to 'A', 'Á' to 'A', 'Â' to 'A', 'Ã' to 'A', 'Ä' to 'A', 'Å' to 'A', 'Æ' to "AE",
            'ç' to 'c', 'Ç' to 'C',
            'è' to 'e', 'é' to 'e', 'ê' to 'e', 'ë' to 'e',
            'È' to 'E', 'É' to 'E', 'Ê' to 'E', 'Ë' to 'E',
            'ì' to 'i', 'í' to 'i', 'î' to 'i', 'ï' to 'i',
            'Ì' to 'I', 'Í' to 'I', 'Î' to 'I', 'Ï' to 'I',
            'ñ' to 'n', 'Ñ' to 'N',
            'ò' to 'o', 'ó' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o', 'ø' to 'o', 'œ' to "oe",
            'Ò' to 'O', 'Ó' to 'O', 'Ô' to 'O', 'Õ' to 'O', 'Ö' to 'O', 'Ø' to 'O', 'Œ' to "OE",
            'ù' to 'u', 'ú' to 'u', 'û' to 'u', 'ü' to 'u',
            'Ù' to 'U', 'Ú' to 'U', 'Û' to 'U', 'Ü' to 'U',
            'ý' to 'y', 'ÿ' to 'y', 'Ý' to 'Y', 'Ÿ' to 'Y',
            'ß' to "ss",
            // Additional European characters
            'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
            'Ą' to 'A', 'Ć' to 'C', 'Ę' to 'E', 'Ł' to 'L', 'Ń' to 'N', 'Ś' to 'S', 'Ź' to 'Z', 'Ż' to 'Z',
            // Czech/Slovak
            'č' to 'c', 'ď' to 'd', 'ě' to 'e', 'ň' to 'n', 'ř' to 'r', 'š' to 's', 'ť' to 't', 'ů' to 'u', 'ž' to 'z',
            'Č' to 'C', 'Ď' to 'D', 'Ě' to 'E', 'Ň' to 'N', 'Ř' to 'R', 'Š' to 'S', 'Ť' to 'T', 'Ů' to 'U', 'Ž' to 'Z',
            // Romanian
            'ă' to 'a', 'î' to 'i', 'ș' to 's', 'ț' to 't',
            'Ă' to 'A', 'Î' to 'I', 'Ș' to 'S', 'Ț' to 'T',
            // Scandinavian
            'đ' to 'd', 'Đ' to 'D',
            // Turkish
            'ı' to 'i', 'İ' to 'I', 'ğ' to 'g', 'Ğ' to 'G',
            // Icelandic
            'þ' to "th", 'Þ' to "TH", 'ð' to "d", 'Ð' to "D"
        )
    }

    /**
     * Script type classification.
     */
    enum class Script {
        LATIN,
        CYRILLIC,
        ARABIC,
        HEBREW,
        GREEK,
        CJK,           // Chinese, Japanese, Korean (ideographs)
        HANGUL,        // Korean alphabet
        DEVANAGARI,    // Hindi, Sanskrit, etc.
        THAI,
        EMOJI,
        MIXED,         // Multiple scripts
        UNKNOWN
    }

    /**
     * Callback interface for character set events.
     */
    interface Callback {
        /**
         * Called when character encoding is detected.
         *
         * @param charset Detected character set
         * @param confidence Detection confidence (0.0 to 1.0)
         */
        fun onEncodingDetected(charset: Charset, confidence: Float)

        /**
         * Called when script is detected.
         *
         * @param script Detected script
         */
        fun onScriptDetected(script: Script)

        /**
         * Called when transliteration is performed.
         *
         * @param original Original text
         * @param transliterated Transliterated text
         */
        fun onTransliterated(original: String, transliterated: String)
    }

    // Current state
    private var callback: Callback? = null
    private var defaultCharset: Charset = UTF_8

    init {
        logD("CharacterSetManager initialized (default: ${defaultCharset.name()})")
    }

    /**
     * Detect script used in text.
     *
     * @param text Text to analyze
     * @return Detected script
     */
    fun detectScript(text: String): Script {
        if (text.isEmpty()) {
            return Script.UNKNOWN
        }

        val scriptCounts = mutableMapOf<Script, Int>()

        for (char in text) {
            val script = detectCharScript(char)
            if (script != Script.UNKNOWN) {
                scriptCounts[script] = scriptCounts.getOrDefault(script, 0) + 1
            }
        }

        if (scriptCounts.isEmpty()) {
            return Script.UNKNOWN
        }

        // Check if mixed scripts
        if (scriptCounts.size > 1) {
            // Filter out emoji and punctuation to check if truly mixed
            val significantScripts = scriptCounts.filterKeys { it != Script.EMOJI }
            if (significantScripts.size > 1) {
                return Script.MIXED
            }
        }

        // Return dominant script
        val dominantScript = scriptCounts.maxByOrNull { it.value }?.key ?: Script.UNKNOWN

        callback?.onScriptDetected(dominantScript)
        return dominantScript
    }

    /**
     * Detect script for a single character.
     */
    private fun detectCharScript(char: Char): Script {
        return when (char) {
            in LATIN_RANGE -> Script.LATIN
            in CYRILLIC_RANGE -> Script.CYRILLIC
            in ARABIC_RANGE -> Script.ARABIC
            in HEBREW_RANGE -> Script.HEBREW
            in GREEK_RANGE -> Script.GREEK
            in CJK_RANGE -> Script.CJK
            in HANGUL_RANGE -> Script.HANGUL
            in DEVANAGARI_RANGE -> Script.DEVANAGARI
            in THAI_RANGE -> Script.THAI
            in EMOJI_RANGE_1, in EMOJI_RANGE_2, in EMOJI_RANGE_3, in EMOJI_RANGE_4 -> Script.EMOJI
            else -> Script.UNKNOWN
        }
    }

    /**
     * Transliterate text by converting accented characters to ASCII equivalents.
     *
     * @param text Text to transliterate
     * @return Transliterated text
     */
    fun transliterate(text: String): String {
        val result = StringBuilder()

        for (char in text) {
            when {
                TRANSLITERATION_MAP.containsKey(char) -> {
                    val replacement = TRANSLITERATION_MAP[char]
                    if (replacement is Char) {
                        result.append(replacement)
                    } else if (replacement is String) {
                        result.append(replacement)
                    }
                }
                else -> result.append(char)
            }
        }

        val transliterated = result.toString()
        if (transliterated != text) {
            callback?.onTransliterated(text, transliterated)
        }

        return transliterated
    }

    /**
     * Remove diacritics (accents) from text using Unicode normalization.
     *
     * @param text Text with diacritics
     * @return Text without diacritics
     */
    fun removeDiacritics(text: String): String {
        // NFD normalization separates base characters from combining marks
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)

        // Remove combining diacritical marks (Unicode category Mn)
        return normalized.filter { char ->
            Character.getType(char) != Character.COMBINING_SPACING_MARK.toInt() &&
            Character.getType(char) != Character.NON_SPACING_MARK.toInt()
        }
    }

    /**
     * Convert text to ASCII using transliteration and diacritic removal.
     *
     * @param text Text to convert
     * @return ASCII-only text
     */
    fun toAscii(text: String): String {
        // First remove diacritics via normalization
        val noDiacritics = removeDiacritics(text)

        // Then apply custom transliteration map for remaining special chars
        val transliterated = transliterate(noDiacritics)

        // Finally, filter to ASCII range
        return transliterated.filter { it.code <= 127 }
    }

    /**
     * Convert text from one charset to another.
     *
     * @param text Text to convert
     * @param sourceCharset Source character set
     * @param targetCharset Target character set
     * @return Converted text
     */
    fun convertCharset(text: String, sourceCharset: Charset, targetCharset: Charset): String {
        return try {
            val bytes = text.toByteArray(sourceCharset)
            String(bytes, targetCharset)
        } catch (e: Exception) {
            logE("Failed to convert charset from ${sourceCharset.name()} to ${targetCharset.name()}", e)
            text
        }
    }

    /**
     * Detect likely encoding of byte array.
     *
     * @param bytes Byte array to analyze
     * @return Detected charset with confidence
     */
    fun detectEncoding(bytes: ByteArray): Pair<Charset, Float> {
        // Simple heuristic-based detection
        // In production, you'd use a library like ICU4J or juniversalchardet

        // Check for UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            callback?.onEncodingDetected(UTF_8, 1.0f)
            return Pair(UTF_8, 1.0f)
        }

        // Check for UTF-16 BOM
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                callback?.onEncodingDetected(UTF_16BE, 1.0f)
                return Pair(UTF_16BE, 1.0f)
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                callback?.onEncodingDetected(UTF_16LE, 1.0f)
                return Pair(UTF_16LE, 1.0f)
            }
        }

        // Check if valid UTF-8
        if (isValidUtf8(bytes)) {
            callback?.onEncodingDetected(UTF_8, 0.8f)
            return Pair(UTF_8, 0.8f)
        }

        // Default to ISO-8859-1 (Latin-1) as fallback
        callback?.onEncodingDetected(ISO_8859_1, 0.5f)
        return Pair(ISO_8859_1, 0.5f)
    }

    /**
     * Check if byte array is valid UTF-8.
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            val decoder = UTF_8.newDecoder()
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            decoder.decode(buffer)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if text contains only ASCII characters.
     *
     * @param text Text to check
     * @return true if ASCII-only, false otherwise
     */
    fun isAscii(text: String): Boolean {
        return text.all { it.code <= 127 }
    }

    /**
     * Check if character is emoji.
     *
     * @param char Character to check
     * @return true if emoji, false otherwise
     */
    fun isEmoji(char: Char): Boolean {
        return char in EMOJI_RANGE_1 || char in EMOJI_RANGE_2 ||
               char in EMOJI_RANGE_3 || char in EMOJI_RANGE_4
    }

    /**
     * Check if text contains emoji.
     *
     * @param text Text to check
     * @return true if contains emoji, false otherwise
     */
    fun containsEmoji(text: String): Boolean {
        return text.any { isEmoji(it) }
    }

    /**
     * Remove emoji from text.
     *
     * @param text Text to filter
     * @return Text without emoji
     */
    fun removeEmoji(text: String): String {
        return text.filterNot { isEmoji(it) }
    }

    /**
     * Check if character is a control character.
     *
     * @param char Character to check
     * @return true if control character, false otherwise
     */
    fun isControlChar(char: Char): Boolean {
        return Character.isISOControl(char)
    }

    /**
     * Remove control characters from text.
     *
     * @param text Text to filter
     * @return Text without control characters
     */
    fun removeControlChars(text: String): String {
        return text.filterNot { isControlChar(it) }
    }

    /**
     * Check if character is whitespace.
     *
     * @param char Character to check
     * @return true if whitespace, false otherwise
     */
    fun isWhitespace(char: Char): Boolean {
        return Character.isWhitespace(char)
    }

    /**
     * Normalize whitespace in text (collapse multiple spaces to single space).
     *
     * @param text Text to normalize
     * @return Normalized text
     */
    fun normalizeWhitespace(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Get character category.
     *
     * @param char Character to classify
     * @return Character category name
     */
    fun getCharCategory(char: Char): String {
        return when (Character.getType(char).toByte()) {
            Character.UPPERCASE_LETTER -> "Uppercase Letter"
            Character.LOWERCASE_LETTER -> "Lowercase Letter"
            Character.TITLECASE_LETTER -> "Titlecase Letter"
            Character.MODIFIER_LETTER -> "Modifier Letter"
            Character.OTHER_LETTER -> "Other Letter"
            Character.NON_SPACING_MARK -> "Non-Spacing Mark"
            Character.ENCLOSING_MARK -> "Enclosing Mark"
            Character.COMBINING_SPACING_MARK -> "Combining Spacing Mark"
            Character.DECIMAL_DIGIT_NUMBER -> "Decimal Digit"
            Character.LETTER_NUMBER -> "Letter Number"
            Character.OTHER_NUMBER -> "Other Number"
            Character.SPACE_SEPARATOR -> "Space Separator"
            Character.LINE_SEPARATOR -> "Line Separator"
            Character.PARAGRAPH_SEPARATOR -> "Paragraph Separator"
            Character.CONTROL -> "Control"
            Character.FORMAT -> "Format"
            Character.PRIVATE_USE -> "Private Use"
            Character.SURROGATE -> "Surrogate"
            Character.DASH_PUNCTUATION -> "Dash Punctuation"
            Character.START_PUNCTUATION -> "Start Punctuation"
            Character.END_PUNCTUATION -> "End Punctuation"
            Character.CONNECTOR_PUNCTUATION -> "Connector Punctuation"
            Character.OTHER_PUNCTUATION -> "Other Punctuation"
            Character.MATH_SYMBOL -> "Math Symbol"
            Character.CURRENCY_SYMBOL -> "Currency Symbol"
            Character.MODIFIER_SYMBOL -> "Modifier Symbol"
            Character.OTHER_SYMBOL -> "Other Symbol"
            Character.INITIAL_QUOTE_PUNCTUATION -> "Initial Quote"
            Character.FINAL_QUOTE_PUNCTUATION -> "Final Quote"
            else -> "Unassigned"
        }
    }

    /**
     * Set default character set.
     *
     * @param charset Default charset to use
     */
    fun setDefaultCharset(charset: Charset) {
        defaultCharset = charset
        logD("Default charset set to: ${charset.name()}")
    }

    /**
     * Get default character set.
     *
     * @return Default charset
     */
    fun getDefaultCharset(): Charset = defaultCharset

    /**
     * Get all supported charsets.
     *
     * @return Map of charset names to Charset objects
     */
    fun getSupportedCharsets(): Map<String, Charset> = Charset.availableCharsets()

    /**
     * Set callback for character set events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing CharacterSetManager resources...")

        try {
            callback = null
            logD("✅ CharacterSetManager resources released")
        } catch (e: Exception) {
            logE("Error releasing character set manager resources", e)
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
