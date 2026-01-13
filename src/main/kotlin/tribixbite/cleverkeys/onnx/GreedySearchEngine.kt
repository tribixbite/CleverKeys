package tribixbite.cleverkeys.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import tribixbite.cleverkeys.SwipeTokenizer
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap

/**
 * Greedy search decoder for fast neural swipe prediction.
 * 
 * Bypasses beam search complexity by always selecting the highest probability token.
 * Useful for low-end devices or battery saving mode.
 */
class GreedySearchEngine(
    private val decoderSession: OrtSession,
    private val ortEnvironment: OrtEnvironment,
    private val tokenizer: SwipeTokenizer,
    private val maxLength: Int,
    private val debugLogger: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GreedySearchEngine"
        private const val PAD_IDX = 0
        private const val SOS_IDX = 2
        private const val EOS_IDX = 3
        private const val DECODER_SEQ_LEN = 20 // Must match model export
    }

    data class GreedyResult(val word: String, val confidence: Float)

    fun search(memory: OnnxTensor, actualSrcLength: Int): List<GreedyResult> {
        val greedyStart = System.nanoTime()
        val tokens = ArrayList<Int>()
        tokens.add(SOS_IDX)
        
        // Shared tensor for src length (created once)
        var actualSrcLengthTensor: OnnxTensor? = null
        
        try {
            actualSrcLengthTensor = OnnxTensor.createTensor(ortEnvironment, intArrayOf(actualSrcLength))
            
            for (step in 0 until maxLength) {
                try {
                    // Prepare target tokens
                    val tgtTokens = IntArray(DECODER_SEQ_LEN) { PAD_IDX }
                    val len = Math.min(tokens.size, DECODER_SEQ_LEN)
                    for (i in 0 until len) {
                        tgtTokens[i] = tokens[i]
                    }
                    
                    val targetTokensTensor = OnnxTensor.createTensor(ortEnvironment, 
                        java.nio.IntBuffer.wrap(tgtTokens), longArrayOf(1, DECODER_SEQ_LEN.toLong()))
                    
                    try {
                        val inputs = mapOf(
                            "memory" to memory,
                            "actual_src_length" to actualSrcLengthTensor,
                            "target_tokens" to targetTokensTensor
                        )
                        
                        val result = decoderSession.run(inputs)
                        val logitsTensor = result.get(0) as OnnxTensor
                        val logits3D = logitsTensor.value as Array<Array<FloatArray>>
                        
                        val currentLogits = logits3D[0][step]
                        
                        // Find token with maximum probability
                        var bestToken = 0
                        var bestProb = Float.NEGATIVE_INFINITY
                        for (i in currentLogits.indices) {
                            if (currentLogits[i] > bestProb) {
                                bestProb = currentLogits[i]
                                bestToken = i
                            }
                        }
                        
                        if (bestToken == EOS_IDX) {
                            break
                        }
                        
                        tokens.add(bestToken)

                        // Only close result - it closes all child tensors including logitsTensor
                        result.close()
                    } finally {
                        targetTokensTensor.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Greedy search error at step $step", e)
                    break
                }
            }
        } finally {
            actualSrcLengthTensor?.close()
        }
        
        // Convert tokens to word
        val word = StringBuilder()
        for (token in tokens) {
            if (token != SOS_IDX && token != EOS_IDX && token != PAD_IDX) {
                val ch = tokenizer.indexToChar(token)
                if (ch != '?' && !ch.toString().startsWith("<")) {
                    word.append(ch)
                }
            }
        }
        
        val greedyTime = (System.nanoTime() - greedyStart) / 1_000_000
        val wordStr = word.toString()
        
        Log.i(TAG, "🏆 Greedy search completed in ${greedyTime}ms: '$wordStr'")
        debugLogger?.invoke("🏆 Greedy search completed in ${greedyTime}ms: '$wordStr'")
        
        val result = ArrayList<GreedyResult>()
        if (wordStr.isNotEmpty()) {
            result.add(GreedyResult(wordStr, 0.9f)) // High confidence for greedy result
        }
        return result
    }
}
