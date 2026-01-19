package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtSession

/**
 * Manages multiple language models and automatic language switching
 *
 * Supports lazy loading, caching, and fast switching between languages.
 * Target switching latency: <100ms
 */
class MultiLanguageManager(
    private val context: Context,
    private val defaultLanguage: String = "en"
) {
    companion object {
        private const val TAG = "MultiLanguageManager"
        private const val SWITCH_LATENCY_TARGET_MS = 100
    }

    // Active language
    @Volatile
    private var activeLanguage: String = defaultLanguage

    // Cached models (lazy loading)
    private val modelCache = mutableMapOf<String, LanguageModel>()

    // Language detector
    private val detector = LanguageDetector()

    /**
     * Language model bundle (encoder, decoder, vocabulary)
     */
    data class LanguageModel(
        val language: String,
        val encoder: OrtSession?,
        val decoder: OrtSession?,
        val vocabulary: OptimizedVocabulary?
    )

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
     * Load language model (lazy)
     * Returns cached model if already loaded
     */
    @Synchronized
    fun loadLanguageModel(language: String): LanguageModel? {
        // Check cache first
        modelCache[language]?.let {
            Log.d(TAG, "Using cached model: $language")
            return it
        }

        try {
            Log.i(TAG, "Loading language model: $language")
            val startTime = System.currentTimeMillis()

            // ONNX Runtime environment (created once per app instance)
            val ortEnvironment = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val modelLoader = tribixbite.cleverkeys.onnx.ModelLoader(context, ortEnvironment)

            // Try to load encoder
            val encoderPath = "models/swipe_encoder_${language}.onnx"
            val encoder = try {
                val result = modelLoader.loadModel(encoderPath, "encoder_$language")
                result.session
            } catch (e: Exception) {
                Log.w(TAG, "Encoder not found for $language: $encoderPath", e)
                null
            }

            // Try to load decoder
            val decoderPath = "models/swipe_decoder_${language}.onnx"
            val decoder = try {
                val result = modelLoader.loadModel(decoderPath, "decoder_$language")
                result.session
            } catch (e: Exception) {
                Log.w(TAG, "Decoder not found for $language: $decoderPath", e)
                null
            }

            // Load vocabulary with language-specific contractions
            // Note: Primary/secondary dictionaries are loaded via SwipePredictorOrchestrator
            // which calls OptimizedVocabulary.loadPrimaryDictionary() for non-English languages
            val vocabulary = try {
                val vocab = OptimizedVocabulary(context)
                // v1.1.87: Pass language code to load correct contraction mappings
                val success = vocab.loadVocabulary(language)
                if (success) vocab else null
            } catch (e: Exception) {
                Log.w(TAG, "Dictionary not found for $language", e)
                null
            }

            // Create model (may have null components if not available)
            val model = LanguageModel(language, encoder, decoder, vocabulary)
            modelCache[language] = model

            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Loaded language model: $language (${loadTime}ms)")

            return model

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load language model: $language", e)
            return null
        }
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

        // Load new language model (or get from cache)
        val model = loadLanguageModel(newLanguage)
        if (model == null) {
            Log.e(TAG, "Cannot switch to $newLanguage - model loading failed")
            return false
        }

        // Check if model has required components
        if (model.encoder == null && model.decoder == null && model.vocabulary == null) {
            Log.w(TAG, "Cannot switch to $newLanguage - no model components available (will use default)")
            // Allow switch anyway (for future when models are added)
        }

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

    /**
     * Get the active language model
     * @return LanguageModel or null if not loaded
     */
    fun getActiveModel(): LanguageModel? {
        return modelCache[activeLanguage]
    }

    /**
     * Preload language model for faster switching
     * Loads asynchronously in background thread
     */
    fun preloadLanguage(language: String) {
        Thread {
            Log.d(TAG, "Preloading language model: $language")
            loadLanguageModel(language)
        }.start()
    }

    /**
     * Unload unused language models to free memory
     * @param keepActive If true, keeps the active language loaded
     */
    @Synchronized
    fun unloadUnusedModels(keepActive: Boolean = true) {
        val toRemove = mutableListOf<String>()
        for ((lang, _) in modelCache) {
            if (!keepActive || lang != activeLanguage) {
                toRemove.add(lang)
            }
        }

        for (lang in toRemove) {
            unloadLanguage(lang)
        }

        Log.i(TAG, "Unloaded ${toRemove.size} unused language model(s)")
    }

    /**
     * Unload a specific language model
     */
    @Synchronized
    fun unloadLanguage(language: String) {
        modelCache.remove(language)?.let { model ->
            try {
                model.encoder?.close()
                model.decoder?.close()
                Log.i(TAG, "Unloaded language model: $language")
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading language model: $language", e)
            }
        }
    }

    /**
     * Get list of currently loaded languages
     */
    fun getLoadedLanguages(): Set<String> {
        return modelCache.keys.toSet()
    }

    /**
     * Get memory usage estimate in MB
     * Assumes ~10MB per model (encoder + decoder)
     */
    fun getMemoryUsageMB(): Float {
        return modelCache.size * 10.0f
    }

    /**
     * Cleanup all resources
     */
    @Synchronized
    fun cleanup() {
        Log.i(TAG, "Cleaning up all language models...")
        for ((lang, model) in modelCache) {
            try {
                model.encoder?.close()
                model.decoder?.close()
                Log.d(TAG, "Cleaned up language model: $lang")
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up language model: $lang", e)
            }
        }
        modelCache.clear()
        Log.i(TAG, "All language models cleaned up")
    }
}
