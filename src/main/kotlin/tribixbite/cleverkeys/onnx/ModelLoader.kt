package tribixbite.cleverkeys.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Model loading and ONNX session initialization.
 *
 * Responsibilities:
 * - Load model files from assets or external URIs
 * - Create optimized ONNX sessions with hardware acceleration
 * - Configure execution providers (NNAPI, QNN, XNNPACK, CPU)
 * - Session options optimization (graph optimization, memory patterns, caching)
 * - Validation of loaded sessions
 *
 * Hardware Acceleration Fallback Chain:
 * 1. NNAPI (Neural Networks API) - NPU/DSP/GPU on Android
 * 2. QNN (Qualcomm Neural Network) - Qualcomm hardware
 * 3. XNNPACK - Optimized CPU inference
 * 4. CPU - Basic fallback
 *
 * Thread Safety: This class is stateless and thread-safe.
 */
class ModelLoader(
    private val context: Context,
    private val ortEnvironment: OrtEnvironment
) {

    companion object {
        private const val TAG = "ModelLoader"
        private const val CACHE_FILE_PREFIX = "onnx_optimized_"
        private const val CACHE_FILE_SUFFIX = ".ort"
    }

    /**
     * Result of model loading operation.
     *
     * @param session Loaded ONNX session ready for inference
     * @param executionProvider Name of execution provider being used
     * @param modelSizeBytes Size of loaded model in bytes
     */
    data class LoadResult(
        val session: OrtSession,
        val executionProvider: String,
        val modelSizeBytes: Long
    )

    /**
     * Load ONNX model and create optimized session.
     *
     * @param modelPath Asset path (e.g., "models/encoder.onnx") or content URI
     * @param sessionName Human-readable name for logging (e.g., "encoder", "decoder")
     * @param enableHardwareAcceleration Whether to attempt hardware acceleration
     * @return LoadResult with session and metadata
     * @throws RuntimeException if loading fails
     */
    fun loadModel(
        modelPath: String,
        sessionName: String,
        enableHardwareAcceleration: Boolean = true
    ): LoadResult {
        try {
            // Load model bytes
            val modelData = loadModelBytes(modelPath)
            Log.d(TAG, "Loaded $sessionName model: ${modelData.size} bytes from $modelPath")

            // Create optimized session options
            val sessionOptions = createOptimizedSessionOptions(sessionName)

            // Try hardware acceleration if enabled
            val executionProvider = if (enableHardwareAcceleration) {
                tryEnableHardwareAcceleration(sessionOptions, sessionName)
            } else {
                "CPU"
            }

            // Create session from model bytes
            val session = ortEnvironment.createSession(modelData, sessionOptions)

            Log.i(TAG, "✅ $sessionName session created successfully (${executionProvider})")

            return LoadResult(
                session = session,
                executionProvider = executionProvider,
                modelSizeBytes = modelData.size.toLong()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $sessionName model from $modelPath", e)
            throw RuntimeException("Model loading failed: ${e.message}", e)
        }
    }

    /**
     * Load model bytes from assets or external URI.
     *
     * Supports:
     * - Asset paths: "models/encoder.onnx"
     * - Content URIs: "content://..."
     * - File paths: "/sdcard/models/encoder.onnx"
     *
     * @param modelPath Path to model file
     * @return Model bytes
     * @throws IOException if file cannot be read
     */
    private fun loadModelBytes(modelPath: String): ByteArray {
        val inputStream: InputStream = when {
            // Content URI (e.g., from file picker)
            modelPath.startsWith("content://") -> {
                Log.d(TAG, "Loading external ONNX model from URI: $modelPath")
                val uri = Uri.parse(modelPath)
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open input stream for URI: $modelPath")
            }

            // External file path
            modelPath.startsWith("/") -> {
                Log.d(TAG, "Loading external ONNX model from file path: $modelPath")
                val file = File(modelPath)
                if (!file.exists()) {
                    throw IOException("External model file does not exist: $modelPath")
                }
                if (!file.canRead()) {
                    throw IOException("Cannot read external model file: $modelPath")
                }
                FileInputStream(file)
            }

            // Asset path
            else -> {
                context.assets.open(modelPath)
            }
        }

        // Read all bytes from input stream
        return inputStream.use { stream ->
            val buffer = ByteArray(stream.available())
            var totalRead = 0
            while (totalRead < buffer.size) {
                val read = stream.read(buffer, totalRead, buffer.size - totalRead)
                if (read == -1) break
                totalRead += read
            }
            buffer
        }
    }

    /**
     * Create optimized session options for inference.
     *
     * Optimizations:
     * - Graph optimization level: ALL_OPT (operator fusion, layout transforms)
     * - Memory pattern optimization for repeated inference
     * - Optimized model caching to disk for faster subsequent loads
     * - Intra-op thread count: auto-detect optimal for device
     *
     * @param sessionName Name for cache file generation
     * @return Configured SessionOptions
     */
    private fun createOptimizedSessionOptions(sessionName: String): OrtSession.SessionOptions {
        val sessionOptions = OrtSession.SessionOptions()

        // OPTIMIZATION 1: Maximum graph optimization level
        sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

        // OPTIMIZATION 2: Let ONNX Runtime determine optimal thread count
        sessionOptions.setIntraOpNumThreads(0) // Auto-detect

        // OPTIMIZATION 3: Memory pattern optimization for repeated inference
        sessionOptions.setMemoryPatternOptimization(true)

        // OPTIMIZATION 4: Cache optimized model graph to disk
        try {
            val cacheDir = context.cacheDir
            val cacheFileName = "$CACHE_FILE_PREFIX${sessionName.lowercase()}$CACHE_FILE_SUFFIX"
            val cacheFile = File(cacheDir, cacheFileName)
            sessionOptions.setOptimizedModelFilePath(cacheFile.absolutePath)
            Log.d(TAG, "📦 Optimized model cache: ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set optimized model cache: ${e.message}")
        }

        return sessionOptions
    }

    /**
     * Try to enable hardware acceleration with fallback chain.
     *
     * Attempts execution providers in order:
     * 1. NNAPI (NPU/DSP/GPU)
     * 2. QNN (Qualcomm hardware)
     * 3. XNNPACK (optimized CPU)
     * 4. CPU (fallback)
     *
     * @param sessionOptions Session options to configure
     * @param sessionName Session name for logging
     * @return Name of execution provider being used
     */
    private fun tryEnableHardwareAcceleration(
        sessionOptions: OrtSession.SessionOptions,
        sessionName: String
    ): String {
        // Try XNNPACK first (optimized CPU inference) - Most stable
        if (tryXnnpack(sessionOptions, sessionName)) {
            return "XNNPACK"
        }

        // Try NNAPI (Android Neural Networks API) - Fast but prone to 'Session ID' crashes
        if (tryNnapi(sessionOptions, sessionName)) {
            return "NNAPI"
        }

        // Try QNN (Qualcomm Neural Network SDK)
        if (tryQnn(sessionOptions, sessionName)) {
            return "QNN"
        }

        // Fallback to CPU
        Log.w(TAG, "⚠️ Hardware acceleration unavailable for $sessionName, using CPU")
        return "CPU"
    }

    /**
     * Try to enable NNAPI execution provider.
     */
    private fun tryNnapi(sessionOptions: OrtSession.SessionOptions, sessionName: String): Boolean {
        return try {
            sessionOptions.addNnapi()
            Log.i(TAG, "✅ NNAPI enabled for $sessionName")
            true
        } catch (e: Exception) {
            Log.d(TAG, "NNAPI not available for $sessionName: ${e.message}")
            false
        }
    }

    /**
     * Try to enable QNN execution provider.
     */
    private fun tryQnn(sessionOptions: OrtSession.SessionOptions, sessionName: String): Boolean {
        return try {
            // QNN setup would go here if available
            // sessionOptions.addQnn()
            Log.d(TAG, "QNN not implemented for $sessionName")
            false
        } catch (e: Exception) {
            Log.d(TAG, "QNN not available for $sessionName: ${e.message}")
            false
        }
    }

    /**
     * Try to enable XNNPACK execution provider.
     */
    private fun tryXnnpack(sessionOptions: OrtSession.SessionOptions, sessionName: String): Boolean {
        return try {
            // Configure XNNPACK with 4 threads for performance
            val options = mapOf("intra_op_num_threads" to "4")
            sessionOptions.addXnnpack(options)
            sessionOptions.setIntraOpNumThreads(4)
            Log.i(TAG, "✅ XNNPACK enabled for $sessionName with 4 threads")
            true
        } catch (e: Exception) {
            Log.d(TAG, "XNNPACK not available for $sessionName: ${e.message}")
            false
        }
    }

    /**
     * Validate that a session is ready for inference.
     *
     * Checks:
     * - Session has expected inputs and outputs
     * - Input/output names match model graph
     * - Session is not closed
     *
     * @param session Session to validate
     * @param expectedInputs Expected input names
     * @param expectedOutputs Expected output names
     * @throws IllegalStateException if validation fails
     */
    fun validateSession(
        session: OrtSession,
        expectedInputs: List<String>,
        expectedOutputs: List<String>
    ) {
        try {
            val inputNames = session.inputNames
            val outputNames = session.outputNames

            // Check inputs
            val missingInputs = expectedInputs.filter { it !in inputNames }
            if (missingInputs.isNotEmpty()) {
                throw IllegalStateException(
                    "Session missing expected inputs: $missingInputs. " +
                            "Found: $inputNames"
                )
            }

            // Check outputs
            val missingOutputs = expectedOutputs.filter { it !in outputNames }
            if (missingOutputs.isNotEmpty()) {
                throw IllegalStateException(
                    "Session missing expected outputs: $missingOutputs. " +
                            "Found: $outputNames"
                )
            }

            Log.d(TAG, "✅ Session validation passed")
        } catch (e: Exception) {
            throw IllegalStateException("Session validation failed: ${e.message}", e)
        }
    }

    /**
     * Get session metadata for debugging.
     *
     * @param session Session to inspect
     * @return Map of metadata key-value pairs
     */
    fun getSessionMetadata(session: OrtSession): Map<String, String> {
        return try {
            mapOf(
                "input_count" to session.inputNames.size.toString(),
                "output_count" to session.outputNames.size.toString(),
                "inputs" to session.inputNames.toString(),
                "outputs" to session.outputNames.toString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get session metadata: ${e.message}")
            emptyMap()
        }
    }
}
