package tribixbite.keyboard2

import android.util.Log
import java.text.Normalizer

/**
 * Manages Unicode text normalization for consistent comparison and autocorrect.
 *
 * Provides comprehensive Unicode normalization handling to ensure consistent
 * text representation regardless of how accented characters are encoded.
 * Critical for autocorrect, search, and text comparison with international text.
 *
 * Features:
 * - NFC (Canonical Composition) normalization
 * - NFD (Canonical Decomposition) normalization
 * - NFKC (Compatibility Composition) normalization
 * - NFKD (Compatibility Decomposition) normalization
 * - Automatic form selection based on use case
 * - Text comparison with normalization
 * - Combining mark handling
 * - Case-insensitive normalized comparison
 * - Width normalization (fullwidth/halfwidth)
 * - Smart normalization for search and autocorrect
 *
 * Bug #351 - HIGH: Complete implementation of missing UnicodeNormalizer.java
 */
class UnicodeNormalizer {
    companion object {
        private const val TAG = "UnicodeNormalizer"

        /**
         * Unicode normalization forms.
         *
         * NFC: Canonical Composition - Characters are composed (é = U+00E9)
         * NFD: Canonical Decomposition - Characters are decomposed (é = e + ´ = U+0065 U+0301)
         * NFKC: Compatibility Composition - Like NFC but also decomposes compatibility chars
         * NFKD: Compatibility Decomposition - Like NFD but also decomposes compatibility chars
         */
        enum class Form {
            /**
             * NFC - Canonical Composition.
             * Most compact form. Recommended for storage and transmission.
             * Example: é = U+00E9 (single character)
             */
            NFC,

            /**
             * NFD - Canonical Decomposition.
             * Fully decomposed form. Good for text processing and comparison.
             * Example: é = e + ´ = U+0065 + U+0301 (base + combining mark)
             */
            NFD,

            /**
             * NFKC - Compatibility Composition.
             * Decomposes compatibility characters then composes.
             * Good for search and comparison where visual equivalence matters.
             * Example: ﬁ (ligature) → fi
             */
            NFKC,

            /**
             * NFKD - Compatibility Decomposition.
             * Fully decomposes including compatibility characters.
             * Good for analysis and base character extraction.
             * Example: ﬁ (ligature) → fi, ² → 2
             */
            NFKD
        }

        /**
         * Normalization strategy for different use cases.
         */
        enum class Strategy {
            /**
             * Storage/transmission - Use NFC (most compact).
             */
            STORAGE,

            /**
             * Comparison/equality - Use NFD (canonical decomposition).
             */
            COMPARISON,

            /**
             * Search/autocorrect - Use NFKD (full decomposition + compatibility).
             */
            SEARCH,

            /**
             * Display/rendering - Use NFC (composed form).
             */
            DISPLAY,

            /**
             * Analysis/processing - Use NFD (decomposed form).
             */
            ANALYSIS
        }
    }

    /**
     * Callback interface for normalization events.
     */
    interface Callback {
        /**
         * Called when text is normalized.
         *
         * @param original Original text
         * @param normalized Normalized text
         * @param form Normalization form used
         */
        fun onNormalized(original: String, normalized: String, form: Form)

        /**
         * Called when normalization detects difference.
         *
         * @param text1 First text
         * @param text2 Second text
         * @param areEqual Whether texts are equal after normalization
         */
        fun onComparisonResult(text1: String, text2: String, areEqual: Boolean)
    }

    // Current state
    private var callback: Callback? = null
    private var defaultForm: Form = Form.NFC
    private var defaultStrategy: Strategy = Strategy.STORAGE

    init {
        logD("UnicodeNormalizer initialized (default: $defaultForm)")
    }

    /**
     * Normalize text using specified form.
     *
     * @param text Text to normalize
     * @param form Normalization form (default: NFC)
     * @return Normalized text
     */
    fun normalize(text: String, form: Form = defaultForm): String {
        if (text.isEmpty()) {
            return text
        }

        val javaForm = when (form) {
            Form.NFC -> Normalizer.Form.NFC
            Form.NFD -> Normalizer.Form.NFD
            Form.NFKC -> Normalizer.Form.NFKC
            Form.NFKD -> Normalizer.Form.NFKD
        }

        val normalized = Normalizer.normalize(text, javaForm)

        if (normalized != text) {
            callback?.onNormalized(text, normalized, form)
        }

        return normalized
    }

    /**
     * Normalize text using strategy-based form selection.
     *
     * @param text Text to normalize
     * @param strategy Normalization strategy
     * @return Normalized text
     */
    fun normalize(text: String, strategy: Strategy): String {
        val form = when (strategy) {
            Strategy.STORAGE -> Form.NFC
            Strategy.COMPARISON -> Form.NFD
            Strategy.SEARCH -> Form.NFKD
            Strategy.DISPLAY -> Form.NFC
            Strategy.ANALYSIS -> Form.NFD
        }

        return normalize(text, form)
    }

    /**
     * Normalize to NFC (Canonical Composition).
     * Most compact form. Recommended for storage.
     *
     * @param text Text to normalize
     * @return NFC-normalized text
     */
    fun toNFC(text: String): String = normalize(text, Form.NFC)

    /**
     * Normalize to NFD (Canonical Decomposition).
     * Fully decomposed. Good for comparison.
     *
     * @param text Text to normalize
     * @return NFD-normalized text
     */
    fun toNFD(text: String): String = normalize(text, Form.NFD)

    /**
     * Normalize to NFKC (Compatibility Composition).
     * Compatibility decomposition then composition.
     *
     * @param text Text to normalize
     * @return NFKC-normalized text
     */
    fun toNFKC(text: String): String = normalize(text, Form.NFKC)

    /**
     * Normalize to NFKD (Compatibility Decomposition).
     * Full decomposition including compatibility chars.
     *
     * @param text Text to normalize
     * @return NFKD-normalized text
     */
    fun toNFKD(text: String): String = normalize(text, Form.NFKD)

    /**
     * Check if text is already normalized in specified form.
     *
     * @param text Text to check
     * @param form Normalization form
     * @return true if already normalized, false otherwise
     */
    fun isNormalized(text: String, form: Form = defaultForm): Boolean {
        val javaForm = when (form) {
            Form.NFC -> Normalizer.Form.NFC
            Form.NFD -> Normalizer.Form.NFD
            Form.NFKC -> Normalizer.Form.NFKC
            Form.NFKD -> Normalizer.Form.NFKD
        }

        return Normalizer.isNormalized(text, javaForm)
    }

    /**
     * Compare two texts with normalization.
     * Uses NFD for canonical comparison.
     *
     * @param text1 First text
     * @param text2 Second text
     * @return true if equal after normalization, false otherwise
     */
    fun equals(text1: String, text2: String): Boolean {
        val normalized1 = toNFD(text1)
        val normalized2 = toNFD(text2)
        val areEqual = normalized1 == normalized2

        callback?.onComparisonResult(text1, text2, areEqual)
        return areEqual
    }

    /**
     * Compare two texts with case-insensitive normalization.
     *
     * @param text1 First text
     * @param text2 Second text
     * @return true if equal (ignoring case) after normalization
     */
    fun equalsIgnoreCase(text1: String, text2: String): Boolean {
        val normalized1 = toNFD(text1).lowercase()
        val normalized2 = toNFD(text2).lowercase()
        val areEqual = normalized1 == normalized2

        callback?.onComparisonResult(text1, text2, areEqual)
        return areEqual
    }

    /**
     * Normalize for search/autocorrect purposes.
     * Uses NFKD to decompose everything for best matching.
     *
     * @param text Text to prepare for search
     * @return Search-optimized normalized text
     */
    fun normalizeForSearch(text: String): String {
        // NFKD decomposes everything including ligatures and compatibility chars
        return toNFKD(text)
    }

    /**
     * Normalize for autocorrect comparison.
     * Handles café vs café type issues.
     *
     * @param text Text to prepare for autocorrect
     * @return Autocorrect-optimized normalized text
     */
    fun normalizeForAutocorrect(text: String): String {
        // Use NFKD then lowercase for best autocorrect matching
        return toNFKD(text).lowercase()
    }

    /**
     * Normalize for storage.
     * Uses NFC for most compact representation.
     *
     * @param text Text to prepare for storage
     * @return Storage-optimized normalized text
     */
    fun normalizeForStorage(text: String): String {
        return toNFC(text)
    }

    /**
     * Normalize for display.
     * Uses NFC for proper rendering.
     *
     * @param text Text to prepare for display
     * @return Display-optimized normalized text
     */
    fun normalizeForDisplay(text: String): String {
        return toNFC(text)
    }

    /**
     * Get base characters by removing combining marks.
     * Useful for getting "cafe" from "café".
     *
     * @param text Text with possible combining marks
     * @return Text with combining marks removed
     */
    fun getBaseCharacters(text: String): String {
        // NFD separates base characters from combining marks
        val decomposed = toNFD(text)

        // Remove combining marks (Unicode category Mn and Mc)
        return decomposed.filter { char ->
            val type = Character.getType(char).toByte()
            type != Character.NON_SPACING_MARK &&
            type != Character.COMBINING_SPACING_MARK
        }
    }

    /**
     * Normalize width (fullwidth ↔ halfwidth conversion).
     * Converts fullwidth Latin/digits to halfwidth.
     *
     * @param text Text with possible fullwidth characters
     * @return Text with normalized width
     */
    fun normalizeWidth(text: String): String {
        // NFKC handles fullwidth/halfwidth normalization
        return toNFKC(text)
    }

    /**
     * Check if text contains combining marks.
     *
     * @param text Text to check
     * @return true if contains combining marks
     */
    fun hasCombiningMarks(text: String): Boolean {
        return text.any { char ->
            val type = Character.getType(char).toByte()
            type == Character.NON_SPACING_MARK ||
            type == Character.COMBINING_SPACING_MARK ||
            type == Character.ENCLOSING_MARK
        }
    }

    /**
     * Count combining marks in text.
     *
     * @param text Text to analyze
     * @return Number of combining marks
     */
    fun countCombiningMarks(text: String): Int {
        return text.count { char ->
            val type = Character.getType(char).toByte()
            type == Character.NON_SPACING_MARK ||
            type == Character.COMBINING_SPACING_MARK ||
            type == Character.ENCLOSING_MARK
        }
    }

    /**
     * Get info about text normalization state.
     *
     * @param text Text to analyze
     * @return Map with normalization info
     */
    fun getInfo(text: String): Map<String, Any> {
        return mapOf(
            "length" to text.length,
            "isNFC" to isNormalized(text, Form.NFC),
            "isNFD" to isNormalized(text, Form.NFD),
            "isNFKC" to isNormalized(text, Form.NFKC),
            "isNFKD" to isNormalized(text, Form.NFKD),
            "hasCombiningMarks" to hasCombiningMarks(text),
            "combiningMarkCount" to countCombiningMarks(text),
            "nfcLength" to toNFC(text).length,
            "nfdLength" to toNFD(text).length,
            "nfkcLength" to toNFKC(text).length,
            "nfkdLength" to toNFKD(text).length
        )
    }

    /**
     * Compare multiple normalization forms for text.
     *
     * @param text Text to analyze
     * @return Map of form name to normalized text
     */
    fun getAllForms(text: String): Map<String, String> {
        return mapOf(
            "original" to text,
            "NFC" to toNFC(text),
            "NFD" to toNFD(text),
            "NFKC" to toNFKC(text),
            "NFKD" to toNFKD(text),
            "baseChars" to getBaseCharacters(text),
            "search" to normalizeForSearch(text),
            "autocorrect" to normalizeForAutocorrect(text)
        )
    }

    /**
     * Batch normalize multiple texts.
     *
     * @param texts List of texts to normalize
     * @param form Normalization form
     * @return List of normalized texts
     */
    fun batchNormalize(texts: List<String>, form: Form = defaultForm): List<String> {
        return texts.map { normalize(it, form) }
    }

    /**
     * Check if two texts are visually equivalent after normalization.
     * Uses NFKC which considers compatibility equivalence.
     *
     * @param text1 First text
     * @param text2 Second text
     * @return true if visually equivalent
     */
    fun isVisuallyEquivalent(text1: String, text2: String): Boolean {
        val normalized1 = toNFKC(text1)
        val normalized2 = toNFKC(text2)
        return normalized1 == normalized2
    }

    /**
     * Find differences between original and normalized text.
     *
     * @param text Text to analyze
     * @param form Normalization form
     * @return List of indices where characters differ
     */
    fun findDifferences(text: String, form: Form = defaultForm): List<Int> {
        val normalized = normalize(text, form)
        val differences = mutableListOf<Int>()

        val minLength = minOf(text.length, normalized.length)
        for (i in 0 until minLength) {
            if (text[i] != normalized[i]) {
                differences.add(i)
            }
        }

        // Handle length differences
        if (text.length != normalized.length) {
            for (i in minLength until maxOf(text.length, normalized.length)) {
                differences.add(i)
            }
        }

        return differences
    }

    /**
     * Set default normalization form.
     *
     * @param form Default form to use
     */
    fun setDefaultForm(form: Form) {
        defaultForm = form
        logD("Default form set to: $form")
    }

    /**
     * Get default normalization form.
     *
     * @return Default form
     */
    fun getDefaultForm(): Form = defaultForm

    /**
     * Set default normalization strategy.
     *
     * @param strategy Default strategy to use
     */
    fun setDefaultStrategy(strategy: Strategy) {
        defaultStrategy = strategy
        logD("Default strategy set to: $strategy")
    }

    /**
     * Get default normalization strategy.
     *
     * @return Default strategy
     */
    fun getDefaultStrategy(): Strategy = defaultStrategy

    /**
     * Set callback for normalization events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Reset to default settings.
     */
    fun reset() {
        defaultForm = Form.NFC
        defaultStrategy = Strategy.STORAGE
        logD("Reset to default settings")
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing UnicodeNormalizer resources...")

        try {
            callback = null
            logD("✅ UnicodeNormalizer resources released")
        } catch (e: Exception) {
            logE("Error releasing unicode normalizer resources", e)
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
