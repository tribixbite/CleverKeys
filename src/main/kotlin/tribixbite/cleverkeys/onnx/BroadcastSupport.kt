package tribixbite.cleverkeys.onnx

import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream

/**
 * Handles broadcast-enabled model configuration detection.
 *
 * Broadcast-enabled models expand memory internally during decoder inference,
 * allowing a single memory tensor [1, seq_len, hidden_dim] to be broadcast
 * across multiple beams [num_beams, seq_len, hidden_dim] within the model itself.
 *
 * This eliminates manual memory replication in Java code, reducing memory allocation
 * and improving performance for quantized INT8 models.
 *
 * Configuration is loaded from model_config.json in the model's asset directory.
 *
 * Thread Safety: This class is stateless and thread-safe.
 */
class BroadcastSupport(private val context: Context) {

    companion object {
        private const val TAG = "BroadcastSupport"
        private const val QUANTIZED_MODEL_DIR = "/bs/"
        private const val CONFIG_FILENAME = "model_config.json"
    }

    /**
     * Check if a model has broadcast support enabled.
     *
     * Detection logic:
     * 1. Models in /bs/ directory → check model_config.json for "broadcast_enabled": true
     * 2. Models outside /bs/ → assume broadcast disabled (legacy float32 models)
     *
     * @param modelPath Asset path to encoder or decoder model (e.g., "models/bs/swipe_encoder_android.onnx")
     * @return true if broadcast is enabled, false otherwise
     */
    fun isBroadcastEnabled(modelPath: String): Boolean {
        return if (modelPath.contains(QUANTIZED_MODEL_DIR)) {
            // Quantized models in bs/ directory - check config
            readBroadcastConfigFlag(modelPath)
        } else {
            // Standard float32 models - no broadcast support
            Log.d(TAG, "Using float32 models - broadcast disabled (manual memory replication)")
            false
        }
    }

    /**
     * Read broadcast_enabled flag from model_config.json.
     *
     * Uses simple string parsing to avoid external JSON library dependency.
     *
     * @param modelPath Path to model file (used to derive config path)
     * @return true if broadcast_enabled is true, false otherwise
     */
    private fun readBroadcastConfigFlag(modelPath: String): Boolean {
        return try {
            // Derive config path from model path
            // Example: models/bs/swipe_encoder_android.onnx → models/bs/model_config.json
            val configPath = deriveConfigPath(modelPath)

            // Load and parse JSON config
            val jsonString = loadAssetAsString(configPath)

            // Parse broadcast_enabled flag (simple string search)
            // Example JSON: "broadcast_enabled": true
            val broadcastEnabled = jsonString.contains("\"broadcast_enabled\"") &&
                    jsonString.contains("true")

            if (broadcastEnabled) {
                Log.i(TAG, "✅ Broadcast-enabled models detected")
            } else {
                Log.d(TAG, "Broadcast disabled - manual memory replication")
            }

            broadcastEnabled
        } catch (e: IOException) {
            Log.w(TAG, "Could not read model_config.json - assuming broadcast disabled: ${e.message}")
            false
        }
    }

    /**
     * Derive configuration file path from model path.
     *
     * Extracts directory and appends config filename.
     *
     * @param modelPath Path to ONNX model file
     * @return Path to model_config.json
     */
    private fun deriveConfigPath(modelPath: String): String {
        // Extract directory from model path
        // Example: models/bs/swipe_encoder_android.onnx → models/bs/
        val lastSlashIndex = modelPath.lastIndexOf('/')
        val directory = if (lastSlashIndex >= 0) {
            modelPath.substring(0, lastSlashIndex + 1)
        } else {
            ""
        }

        return directory + CONFIG_FILENAME
    }

    /**
     * Load asset file as UTF-8 string.
     *
     * @param assetPath Path to asset file
     * @return File contents as string
     * @throws IOException if file cannot be read
     */
    private fun loadAssetAsString(assetPath: String): String {
        val inputStream: InputStream = context.assets.open(assetPath)
        val buffer = ByteArray(inputStream.available())
        inputStream.read(buffer)
        inputStream.close()
        return String(buffer, Charsets.UTF_8)
    }

    /**
     * Parse full model configuration from model_config.json.
     *
     * This method is for future expansion - currently only broadcast_enabled is used.
     *
     * @param modelPath Path to model file
     * @return ModelConfig data class (to be implemented in ModelConfig.kt)
     */
    fun readModelConfig(modelPath: String): ModelConfig? {
        return try {
            val configPath = deriveConfigPath(modelPath)
            val jsonString = loadAssetAsString(configPath)

            // Simple JSON parsing (no external library)
            // Extract values from JSON string
            val accuracy = extractJsonValue(jsonString, "accuracy")?.toFloatOrNull() ?: 0f
            val dModel = extractJsonValue(jsonString, "d_model")?.toIntOrNull() ?: 256
            val maxSeqLen = extractJsonValue(jsonString, "max_seq_len")?.toIntOrNull() ?: 250
            val maxWordLen = extractJsonValue(jsonString, "max_word_len")?.toIntOrNull() ?: 20
            val broadcastEnabled = jsonString.contains("\"broadcast_enabled\"") &&
                    jsonString.contains("true")

            ModelConfig(
                accuracy = accuracy,
                dModel = dModel,
                maxSeqLen = maxSeqLen,
                maxWordLen = maxWordLen,
                broadcastEnabled = broadcastEnabled
            )
        } catch (e: IOException) {
            Log.w(TAG, "Could not read model_config.json: ${e.message}")
            null
        }
    }

    /**
     * Extract value from JSON string using simple regex.
     *
     * @param jsonString JSON content
     * @param key Key to extract
     * @return Value as string, or null if not found
     */
    private fun extractJsonValue(jsonString: String, key: String): String? {
        // Match: "key": "value" or "key": value
        val pattern = "\"$key\"\\s*:\\s*\"?([^,}\"]+)\"?".toRegex()
        val match = pattern.find(jsonString)
        return match?.groupValues?.getOrNull(1)?.trim()
    }
}

/**
 * Model configuration data class.
 *
 * Contains metadata and limits from model_config.json.
 */
data class ModelConfig(
    val accuracy: Float,       // Model accuracy (e.g., 0.7337 for 73.37%)
    val dModel: Int,           // Model hidden dimension (d_model)
    val maxSeqLen: Int,        // Maximum encoder sequence length
    val maxWordLen: Int,       // Maximum decoder sequence length
    val broadcastEnabled: Boolean  // Whether broadcast is enabled
)
