package tribixbite.keyboard2

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * Tokenizer for neural swipe prediction
 * Handles character-to-index mapping for ONNX model input
 * Matches the tokenizer configuration from the web demo
 */
class SwipeTokenizer {
    companion object {
        private const val TAG = "SwipeTokenizer"

        // Special token indices (matching web demo)
        const val PAD_IDX = 0
        const val UNK_IDX = 1
        const val SOS_IDX = 2
        const val EOS_IDX = 3
    }

    // Character mappings
    private val charToIdx: MutableMap<Char, Int> = mutableMapOf()
    private val idxToChar: MutableMap<Int, Char> = mutableMapOf()
    private var isLoadedFlag = false

    // Helper class for Gson parsing
    private data class TokenizerConfig(
        val char_to_idx: Map<String, Int>?,
        val idx_to_char: Map<String, String>?
    )

    /**
     * Load tokenizer configuration from assets
     */
    fun loadFromAssets(context: Context): Boolean {
        return try {
            Log.d(TAG, "Loading tokenizer configuration from assets")

            val inputStream = context.assets.open("models/tokenizer_config.json")
            val reader = BufferedReader(InputStreamReader(inputStream))

            val gson = Gson()
            val config = gson.fromJson(reader, TokenizerConfig::class.java)
            reader.close()

            charToIdx.clear()
            idxToChar.clear()

            // First, load idx_to_char if present
            config.idx_to_char?.forEach { (key, value) ->
                // Skip special tokens like <pad>, <sos>, <eos>, <unk>
                if (value.length == 1) {
                    val idx = key.toInt()
                    val ch = value[0]
                    idxToChar[idx] = ch
                    // Build reverse mapping
                    charToIdx[ch] = idx
                }
            }

            // If char_to_idx is explicitly provided, use it (overrides auto-generated)
            config.char_to_idx?.forEach { (key, value) ->
                if (key.isNotEmpty()) {
                    charToIdx[key[0]] = value
                }
            }

            isLoadedFlag = true
            Log.d(TAG, "Tokenizer loaded with ${charToIdx.size} characters")
            true
        } catch (e: IOException) {
            Log.w(TAG, "Could not load tokenizer from assets, using defaults: ${e.message}")
            isLoadedFlag = false
            false
        }
    }

    /**
     * Convert character to token index
     */
    fun charToIndex(c: Char): Int {
        val ch = c.lowercaseChar()
        return charToIdx[ch] ?: UNK_IDX
    }

    /**
     * Convert token index to character
     */
    fun indexToChar(idx: Int): Char {
        return idxToChar[idx] ?: '?'
    }

    /**
     * Get vocabulary size
     */
    fun getVocabSize(): Int {
        return charToIdx.size
    }

    /**
     * Check if tokenizer is loaded
     */
    fun isLoaded(): Boolean {
        return isLoadedFlag
    }

    private fun addMapping(idx: Int, ch: Char) {
        charToIdx[ch] = idx
        idxToChar[idx] = ch
    }

    /**
     * Get character-to-index mapping (for debugging)
     */
    fun getCharToIdxMapping(): Map<Char, Int> {
        return charToIdx.toMap()
    }
}
