package tribixbite.cleverkeys.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * Production implementation of [DecoderSessionInterface] backed by ONNX Runtime.
 *
 * Wraps OrtSession and OrtEnvironment to provide the decoder interface
 * without exposing ONNX runtime types to BeamSearchEngine's core logic.
 *
 * This class also manages the encoder memory tensor lifecycle — the memory
 * tensor is set once per prediction via [setMemory] and reused across all
 * decoder calls within that prediction.
 */
class OrtDecoderSession(
    private val decoderSession: OrtSession,
    private val ortEnvironment: OrtEnvironment
) : DecoderSessionInterface {

    companion object {
        private const val DECODER_SEQ_LEN = 20
    }

    // Encoder memory tensor — set per prediction, reused across steps
    private var memoryTensor: OnnxTensor? = null

    // Cached src length tensor — reused when length unchanged
    private var cachedSrcLength: Int = -1
    private var cachedSrcLengthTensor: OnnxTensor? = null

    /**
     * Set the encoder memory tensor for the current prediction.
     * Must be called before [runSequential] or [runBatched].
     */
    fun setMemory(memory: OnnxTensor) {
        this.memoryTensor = memory
    }

    override fun runSequential(
        tokens: IntArray,
        actualSrcLength: Int,
        decoderSeqLength: Int
    ): Array<Array<FloatArray>> {
        val memory = memoryTensor ?: throw IllegalStateException("Memory tensor not set. Call setMemory() first.")

        if (actualSrcLength != cachedSrcLength) {
            cachedSrcLengthTensor?.close()
            cachedSrcLengthTensor = OnnxTensor.createTensor(ortEnvironment, intArrayOf(actualSrcLength))
            cachedSrcLength = actualSrcLength
        }

        val targetTokensTensor = OnnxTensor.createTensor(
            ortEnvironment,
            java.nio.IntBuffer.wrap(tokens),
            longArrayOf(1, decoderSeqLength.toLong())
        )

        try {
            val inputs = mapOf(
                "memory" to memory,
                "actual_src_length" to cachedSrcLengthTensor!!,
                "target_tokens" to targetTokensTensor
            )

            val result = decoderSession.run(inputs)
            val logitsTensor = result.get(0) as OnnxTensor
            @Suppress("UNCHECKED_CAST")
            val logits3D = logitsTensor.value as Array<Array<FloatArray>>

            // Deep copy to detach from native tensor memory before closing
            val copy = Array(logits3D.size) { b ->
                Array(logits3D[b].size) { t ->
                    logits3D[b][t].copyOf()
                }
            }

            result.close()
            return copy
        } finally {
            targetTokensTensor.close()
        }
    }

    override fun runBatched(
        batchedTokens: IntArray,
        numBeams: Int,
        actualSrcLength: Int,
        decoderSeqLength: Int
    ): Array<Array<FloatArray>> {
        val memory = memoryTensor ?: throw IllegalStateException("Memory tensor not set. Call setMemory() first.")

        if (actualSrcLength != cachedSrcLength) {
            cachedSrcLengthTensor?.close()
            cachedSrcLengthTensor = OnnxTensor.createTensor(ortEnvironment, intArrayOf(actualSrcLength))
            cachedSrcLength = actualSrcLength
        }

        val batchedShape = longArrayOf(numBeams.toLong(), decoderSeqLength.toLong())
        val batchedTokensTensor = OnnxTensor.createTensor(
            ortEnvironment,
            java.nio.IntBuffer.wrap(batchedTokens),
            batchedShape
        )

        try {
            val inputs = mapOf(
                "memory" to memory,
                "actual_src_length" to cachedSrcLengthTensor!!,
                "target_tokens" to batchedTokensTensor
            )

            val result = decoderSession.run(inputs)
            val logitsTensor = result.get(0) as OnnxTensor
            @Suppress("UNCHECKED_CAST")
            val logits3D = logitsTensor.value as Array<Array<FloatArray>>

            // Deep copy
            val copy = Array(logits3D.size) { b ->
                Array(logits3D[b].size) { t ->
                    logits3D[b][t].copyOf()
                }
            }

            result.close()
            return copy
        } finally {
            batchedTokensTensor.close()
        }
    }

    override fun cleanup() {
        try {
            cachedSrcLengthTensor?.close()
            cachedSrcLengthTensor = null
            cachedSrcLength = -1
        } catch (_: Exception) {}
    }
}
