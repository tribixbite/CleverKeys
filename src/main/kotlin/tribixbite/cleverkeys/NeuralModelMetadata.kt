package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks and persists metadata about the currently loaded neural models.
 *
 * Provides visibility into which models are active, when they were loaded,
 * and their characteristics. Essential for A/B testing and debugging.
 *
 * @since v1.32.897 - Phase 6.2: Model Versioning
 */
class NeuralModelMetadata(context: Context) {

    // Use Device Encrypted storage for Direct Boot compatibility
    // Model metadata is non-sensitive technical info
    private val prefs: SharedPreferences = if (Build.VERSION.SDK_INT >= 24) {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("neural_model_metadata", Context.MODE_PRIVATE)
    } else {
        context.getSharedPreferences("neural_model_metadata", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_MODEL_TYPE = "model_type"
        private const val KEY_ENCODER_PATH = "encoder_path"
        private const val KEY_DECODER_PATH = "decoder_path"
        private const val KEY_ENCODER_SIZE = "encoder_size_bytes"
        private const val KEY_DECODER_SIZE = "decoder_size_bytes"
        private const val KEY_LOAD_TIMESTAMP = "load_timestamp"
        private const val KEY_LOAD_DURATION = "load_duration_ms"
        private const val KEY_LAST_USED = "last_used_timestamp"
        private const val KEY_TOTAL_INFERENCES = "total_inferences"

        const val MODEL_TYPE_BUILTIN = "builtin_v2"
        const val MODEL_TYPE_CUSTOM = "custom"
        const val MODEL_TYPE_UNKNOWN = "unknown"

        @Volatile
        private var instance: NeuralModelMetadata? = null

        fun getInstance(context: Context): NeuralModelMetadata {
            return instance ?: synchronized(this) {
                instance ?: NeuralModelMetadata(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Record model loading event
     */
    fun recordModelLoad(
        modelType: String,
        encoderPath: String,
        decoderPath: String,
        encoderSize: Long,
        decoderSize: Long,
        loadDuration: Long
    ) {
        synchronized(this) {
            prefs.edit().apply {
                putString(KEY_MODEL_TYPE, modelType)
                putString(KEY_ENCODER_PATH, encoderPath)
                putString(KEY_DECODER_PATH, decoderPath)
                putLong(KEY_ENCODER_SIZE, encoderSize)
                putLong(KEY_DECODER_SIZE, decoderSize)
                putLong(KEY_LOAD_TIMESTAMP, System.currentTimeMillis())
                putLong(KEY_LOAD_DURATION, loadDuration)
                putLong(KEY_TOTAL_INFERENCES, 0) // Reset on new model load
                apply()
            }
        }
    }

    /**
     * Record that model was used for inference
     */
    fun recordInferenceUsage() {
        synchronized(this) {
            prefs.edit().apply {
                putLong(KEY_LAST_USED, System.currentTimeMillis())
                putLong(KEY_TOTAL_INFERENCES, getTotalInferences() + 1)
                apply()
            }
        }
    }

    // Getters

    fun getModelType(): String = prefs.getString(KEY_MODEL_TYPE, MODEL_TYPE_UNKNOWN) ?: MODEL_TYPE_UNKNOWN

    fun getEncoderPath(): String = prefs.getString(KEY_ENCODER_PATH, "") ?: ""

    fun getDecoderPath(): String = prefs.getString(KEY_DECODER_PATH, "") ?: ""

    fun getEncoderSize(): Long = prefs.getLong(KEY_ENCODER_SIZE, 0)

    fun getDecoderSize(): Long = prefs.getLong(KEY_DECODER_SIZE, 0)

    fun getLoadTimestamp(): Long = prefs.getLong(KEY_LOAD_TIMESTAMP, 0)

    fun getLoadDuration(): Long = prefs.getLong(KEY_LOAD_DURATION, 0)

    fun getLastUsedTimestamp(): Long = prefs.getLong(KEY_LAST_USED, 0)

    fun getTotalInferences(): Long = prefs.getLong(KEY_TOTAL_INFERENCES, 0)

    /**
     * Check if model metadata exists
     */
    fun hasMetadata(): Boolean {
        return getModelType() != MODEL_TYPE_UNKNOWN && getLoadTimestamp() > 0
    }

    /**
     * Get total model size (encoder + decoder)
     */
    fun getTotalSize(): Long = getEncoderSize() + getDecoderSize()

    /**
     * Get model type display name
     */
    fun getModelTypeDisplayName(): String {
        return when (getModelType()) {
            MODEL_TYPE_BUILTIN -> "Built-in v2"
            MODEL_TYPE_CUSTOM -> "Custom Model"
            else -> "Unknown"
        }
    }

    /**
     * Get days since model loaded
     */
    fun getDaysSinceLoad(): Int {
        val loadTime = getLoadTimestamp()
        return if (loadTime > 0) {
            val daysSince = (System.currentTimeMillis() - loadTime) / (1000 * 60 * 60 * 24)
            daysSince.toInt()
        } else {
            0
        }
    }

    /**
     * Get hours since last used
     */
    fun getHoursSinceLastUse(): Int {
        val lastUsed = getLastUsedTimestamp()
        return if (lastUsed > 0) {
            val hoursSince = (System.currentTimeMillis() - lastUsed) / (1000 * 60 * 60)
            hoursSince.toInt()
        } else {
            -1 // Never used
        }
    }

    /**
     * Format file size as human-readable string
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Format timestamp as date string
     */
    private fun formatTimestamp(timestamp: Long): String {
        return if (timestamp > 0) {
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
            sdf.format(Date(timestamp))
        } else {
            "Never"
        }
    }

    /**
     * Format metadata as human-readable summary
     */
    fun formatSummary(): String {
        if (!hasMetadata()) {
            return "No model loaded yet.\n\nNeural predictions will load models automatically on first use."
        }

        return buildString {
            appendLine("🧠 Neural Model Information")
            appendLine()
            appendLine("Model Type:")
            appendLine("  ${getModelTypeDisplayName()}")
            appendLine()
            appendLine("Files:")
            appendLine("  Encoder: ${formatSize(getEncoderSize())}")
            appendLine("  Decoder: ${formatSize(getDecoderSize())}")
            appendLine("  Total: ${formatSize(getTotalSize())}")
            appendLine()
            appendLine("Performance:")
            appendLine("  Load time: ${getLoadDuration()}ms")
            appendLine("  Loaded: ${formatTimestamp(getLoadTimestamp())}")
            appendLine("  Days active: ${getDaysSinceLoad()}")
            appendLine()
            appendLine("Usage:")
            appendLine("  Total inferences: ${getTotalInferences()}")
            val hoursSince = getHoursSinceLastUse()
            if (hoursSince >= 0) {
                appendLine("  Last used: ${hoursSince}h ago")
            } else {
                appendLine("  Last used: Never")
            }

            // Show paths for custom models
            if (getModelType() == MODEL_TYPE_CUSTOM) {
                appendLine()
                appendLine("Paths:")
                val encoderPath = getEncoderPath()
                val decoderPath = getDecoderPath()
                if (encoderPath.isNotEmpty()) {
                    appendLine("  Encoder: ...${encoderPath.takeLast(40)}")
                }
                if (decoderPath.isNotEmpty()) {
                    appendLine("  Decoder: ...${decoderPath.takeLast(40)}")
                }
            }
        }
    }

    /**
     * Clear all metadata
     */
    fun clear() {
        synchronized(this) {
            prefs.edit().clear().apply()
        }
    }
}
