package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced swipe typing settings that can be tuned by users
 * These parameters affect swipe recognition accuracy and behavior
 */
class SwipeAdvancedSettings private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: SwipeAdvancedSettings? = null

        @JvmStatic
        fun getInstance(context: Context): SwipeAdvancedSettings {
            return instance ?: synchronized(this) {
                instance ?: SwipeAdvancedSettings(context).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("swipe_advanced", Context.MODE_PRIVATE)

    // Gaussian model parameters (affects key hit detection)
    var gaussianSigmaXFactor = 0.4f  // Default: 40% of key width
        private set
    var gaussianSigmaYFactor = 0.35f // Default: 35% of key height
        private set
    var gaussianMinProbability = 0.01f
        private set

    // Path pruning parameters
    var minPathLengthRatio = 0.3f // Minimum path length relative to word length
        private set
    var maxPathLengthRatio = 3.0f // Maximum path length relative to word length
        private set

    // N-gram model parameters
    var ngramSmoothingFactor = 0.1f // Smoothing for unseen bigrams
        private set
    var contextWindowSize = 2 // Number of previous words to consider
        private set

    // Beam Search Parameters (New)
    var neuralBeamAlpha = 1.2f
        private set
    var neuralBeamPruneConfidence = 0.8f
        private set
    var neuralBeamScoreGap = 5.0f
        private set

    init {
        loadSettings()
    }

    private fun loadSettings() {
        gaussianSigmaXFactor = Config.safeGetFloat(prefs, "gaussian_sigma_x", 0.4f)
        gaussianSigmaYFactor = Config.safeGetFloat(prefs, "gaussian_sigma_y", 0.35f)
        gaussianMinProbability = Config.safeGetFloat(prefs, "gaussian_min_prob", 0.01f)

        minPathLengthRatio = Config.safeGetFloat(prefs, "min_path_length_ratio", 0.3f)
        maxPathLengthRatio = Config.safeGetFloat(prefs, "max_path_length_ratio", 3.0f)

        ngramSmoothingFactor = Config.safeGetFloat(prefs, "ngram_smoothing", 0.1f)
        contextWindowSize = prefs.getInt("context_window", 2)

        neuralBeamAlpha = Config.safeGetFloat(prefs, "neural_beam_alpha", 1.2f)
        neuralBeamPruneConfidence = Config.safeGetFloat(prefs, "neural_beam_prune_confidence", 0.8f)
        neuralBeamScoreGap = Config.safeGetFloat(prefs, "neural_beam_score_gap", 5.0f)
    }

    fun saveSettings() {
        prefs.edit().apply {
            putFloat("gaussian_sigma_x", gaussianSigmaXFactor)
            putFloat("gaussian_sigma_y", gaussianSigmaYFactor)
            putFloat("gaussian_min_prob", gaussianMinProbability)

            putFloat("min_path_length_ratio", minPathLengthRatio)
            putFloat("max_path_length_ratio", maxPathLengthRatio)

            putFloat("ngram_smoothing", ngramSmoothingFactor)
            putInt("context_window", contextWindowSize)

            putFloat("neural_beam_alpha", neuralBeamAlpha)
            putFloat("neural_beam_prune_confidence", neuralBeamPruneConfidence)
            putFloat("neural_beam_score_gap", neuralBeamScoreGap)

            apply()
        }
    }

    // Setters with validation
    fun setGaussianSigmaXFactor(value: Float) {
        gaussianSigmaXFactor = max(0.1f, min(1.0f, value))
        saveSettings()
    }

    fun setGaussianSigmaYFactor(value: Float) {
        gaussianSigmaYFactor = max(0.1f, min(1.0f, value))
        saveSettings()
    }

    fun setGaussianMinProbability(value: Float) {
        gaussianMinProbability = max(0.001f, min(0.1f, value))
        saveSettings()
    }

    fun setMinPathLengthRatio(value: Float) {
        minPathLengthRatio = max(0.1f, min(1.0f, value))
        saveSettings()
    }

    fun setMaxPathLengthRatio(value: Float) {
        maxPathLengthRatio = max(1.5f, min(5.0f, value))
        saveSettings()
    }

    fun setNgramSmoothingFactor(value: Float) {
        ngramSmoothingFactor = max(0.01f, min(1.0f, value))
        saveSettings()
    }

    fun setContextWindowSize(value: Int) {
        contextWindowSize = max(1, min(5, value))
        saveSettings()
    }

    fun setNeuralBeamAlpha(value: Float) {
        neuralBeamAlpha = max(0.0f, min(5.0f, value))
        saveSettings()
    }

    fun setNeuralBeamPruneConfidence(value: Float) {
        neuralBeamPruneConfidence = max(0.0f, min(1.0f, value))
        saveSettings()
    }

    fun setNeuralBeamScoreGap(value: Float) {
        neuralBeamScoreGap = max(0.0f, min(20.0f, value))
        saveSettings()
    }

    /**
     * Reset all settings to defaults
     */
    fun resetToDefaults() {
        gaussianSigmaXFactor = 0.4f
        gaussianSigmaYFactor = 0.35f
        gaussianMinProbability = 0.01f

        minPathLengthRatio = 0.3f
        maxPathLengthRatio = 3.0f

        ngramSmoothingFactor = 0.1f
        contextWindowSize = 2

        neuralBeamAlpha = 1.2f
        neuralBeamPruneConfidence = 0.8f
        neuralBeamScoreGap = 5.0f

        saveSettings()
    }
}
