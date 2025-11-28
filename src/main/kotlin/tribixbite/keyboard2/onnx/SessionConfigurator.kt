package tribixbite.keyboard2.onnx

import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.util.HashMap

/**
 * Configuration logic for ONNX sessions.
 * Handles SessionOptions creation and hardware acceleration setup.
 */
object SessionConfigurator {
    private const val TAG = "SessionConfigurator"

    fun createOptimizedSessionOptions(
        context: Context?,
        sessionName: String
    ): OrtSession.SessionOptions {
        val sessionOptions = OrtSession.SessionOptions()

        try {
            // 1. Optimization Level
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            // 2. Threading
            sessionOptions.setIntraOpNumThreads(0) // Auto

            // 3. Memory
            sessionOptions.setMemoryPatternOptimization(true)

            // 4. Caching
            if (context != null) {
                try {
                    val cacheDir = context.cacheDir
                    val cacheFileName = "onnx_optimized_${sessionName.lowercase()}.ort"
                    val cacheFile = File(cacheDir, cacheFileName)
                    sessionOptions.setOptimizedModelFilePath(cacheFile.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Cache setup failed: ${e.message}")
                }
            }

            // 5. Hardware Acceleration
            tryEnableHardwareAcceleration(sessionOptions, sessionName)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure session options", e)
        }

        return sessionOptions
    }

    private fun tryEnableHardwareAcceleration(
        sessionOptions: OrtSession.SessionOptions,
        sessionName: String
    ) {
        // Try NNAPI
        try {
            sessionOptions.addNnapi()
            Log.i(TAG, "✅ NNAPI enabled for $sessionName")
            return
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI failed, trying fallbacks", e)
        }

        // Try QNN (Qualcomm)
        try {
            val qnnOptions = HashMap<String, String>()
            qnnOptions["backend_path"] = "libQnnHtp.so"
            qnnOptions["htp_performance_mode"] = "burst"
            qnnOptions["qnn_context_priority"] = "high"
            
            for ((k, v) in qnnOptions) {
                sessionOptions.addConfigEntry("qnn_$k", v)
            }
            // sessionOptions.addQnn(qnnOptions) // If available in API
            return
        } catch (e: Exception) {
             Log.w(TAG, "QNN failed", e)
        }

        // Try XNNPACK
        try {
            val xnnOptions = HashMap<String, String>()
            xnnOptions["intra_op_num_threads"] = "4"
            sessionOptions.addXnnpack(xnnOptions)
            sessionOptions.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            sessionOptions.setIntraOpNumThreads(4)
            Log.i(TAG, "✅ XNNPACK enabled for $sessionName")
        } catch (e: Exception) {
            Log.w(TAG, "XNNPACK failed, using CPU", e)
        }
    }
}
