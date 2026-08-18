package tribixbite.cleverkeys

import android.content.Context
import android.util.Log

/**
 * Tracks the active language and performs automatic language switching from recent words.
 *
 * ## What this used to be (2026-08-18 trim)
 *
 * Until the neural swipe engine was removed this class also owned a cache of `LanguageModel`
 * bundles, each holding a whole `OptimizedVocabulary` (the 98k-entry word map, its ~231k-node
 * trie and the length buckets) plus two nullable `OrtSession` handles. Both sessions were
 * hard-coded to null — per-language `swipe_encoder_<lang>.onnx` files never existed — and the
 * vocabulary was write-only: `getActiveModel()`, `getLoadedLanguages()`, `getMemoryUsageMB()`
 * and `cleanup()` had no callers anywhere in the app. The cache therefore spent ~29 MB of Java
 * heap per language purely to hold an object nothing read, which is why it had to be capped at
 * one entry after the 2026-08-17 startup `OutOfMemoryError`.
 *
 * With `OptimizedVocabulary` deleted there is nothing left to cache, so the whole model-cache
 * layer is gone and the memory cost with it. The two behaviours [WordPredictor] actually uses —
 * [switchLanguage] and [detectAndSwitch] — are preserved exactly: switching still validates
 * against the supported-language table and still reports failure for an unsupported code.
 *
 * Per-language DICTIONARIES are unaffected; those live in `DictionaryManager` / `WordPredictor`
 * and were never routed through here.
 */
class MultiLanguageManager(
    @Suppress("unused") private val context: Context,
    private val defaultLanguage: String = "en"
) {
    companion object {
        private const val TAG = "MultiLanguageManager"
        private const val SWITCH_LATENCY_TARGET_MS = 100
    }

    // Active language
    @Volatile
    private var activeLanguage: String = defaultLanguage

    // Language detector
    private val detector = LanguageDetector()

    /**
     * Get current active language
     */
    fun getCurrentLanguage(): String = activeLanguage

    /**
     * Get supported languages
     */
    fun getSupportedLanguages(): Array<String> {
        return detector.getSupportedLanguages()
    }

    /**
     * Check if a language is supported
     */
    fun isLanguageSupported(language: String): Boolean {
        return detector.isLanguageSupported(language)
    }

    /**
     * Switch to a different language
     * @return true if switch succeeded, false if language unavailable
     */
    @Synchronized
    fun switchLanguage(newLanguage: String): Boolean {
        if (newLanguage == activeLanguage) {
            Log.d(TAG, "Already using language: $newLanguage")
            return true // Already active
        }

        if (!isLanguageSupported(newLanguage)) {
            Log.w(TAG, "Unsupported language: $newLanguage")
            return false
        }

        val startTime = System.currentTimeMillis()

        // Atomic switch
        val previousLanguage = activeLanguage
        activeLanguage = newLanguage

        val switchTime = System.currentTimeMillis() - startTime
        Log.i(TAG, "Switched language: $previousLanguage → $newLanguage (${switchTime}ms)")

        if (switchTime > SWITCH_LATENCY_TARGET_MS) {
            Log.w(TAG, "Language switch exceeded target latency: ${switchTime}ms > ${SWITCH_LATENCY_TARGET_MS}ms")
        }

        return true
    }

    /**
     * Detect language from recent context and switch if needed
     * @param recentWords List of recently typed words
     * @param confidenceThreshold Minimum confidence to trigger switch (0.0-1.0)
     * @return Detected language code if switched, null if no switch
     */
    fun detectAndSwitch(recentWords: List<String>, confidenceThreshold: Float = 0.7f): String? {
        if (recentWords.isEmpty()) {
            return null
        }

        val result = detector.detectLanguageFromWordsWithConfidence(recentWords)
        if (result != null && result.language != activeLanguage && result.confidence >= confidenceThreshold) {
            Log.i(TAG, "Language detected: ${result.language} (confidence: ${result.confidence})")
            if (switchLanguage(result.language)) {
                return result.language
            }
        }
        return null
    }

    /**
     * Get confidence score for current language detection
     * @param recentWords List of recently typed words
     * @return DetectionResult with language and confidence, or null
     */
    fun getLanguageConfidence(recentWords: List<String>): LanguageDetector.DetectionResult? {
        if (recentWords.isEmpty()) return null
        return detector.detectLanguageFromWordsWithConfidence(recentWords)
    }
}
