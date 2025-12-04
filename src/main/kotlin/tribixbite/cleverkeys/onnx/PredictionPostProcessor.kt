package tribixbite.cleverkeys.onnx

import android.util.Log
import tribixbite.cleverkeys.OptimizedVocabulary
import tribixbite.cleverkeys.SwipeInput
import tribixbite.cleverkeys.CandidateWord // Added import
import tribixbite.cleverkeys.SwipeStats // Added import
import tribixbite.cleverkeys.FilteredPrediction // Added import as it might be used here for `Filtered` part
import java.util.ArrayList
import java.util.LinkedHashMap
import kotlin.math.min
import kotlin.math.max // Added import

/**
 * Post-processor for neural prediction results.
 *
 * Responsibilities:
 * - Vocabulary filtering (using OptimizedVocabulary)
 * - Deduplication of results
 * - Ranking (combining NN confidence + frequency score)
 * - Formatting for UI (PredictionResult)
 * - Optional raw beam output inclusion
 */
class PredictionPostProcessor(
    private val vocabulary: OptimizedVocabulary?,
    private val confidenceThreshold: Float,
    private val showRawOutput: Boolean,
    private val debugLogger: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "PredictionPostProcessor"
    }

    data class Candidate(val word: String, val confidence: Float)
    
    // Result matching OnnxSwipePredictor.PredictionResult structure
    data class Result(val words: List<String>, val scores: List<Int>)

    fun process(
        candidates: List<Candidate>,
        input: SwipeInput?,
        swipeShowRawBeamPredictions: Boolean
    ): Result {
        // 1. Use vocabulary filtering if available (optimized path)
        if (vocabulary != null && vocabulary.isLoaded()) { // Fixed: used isLoaded()
            return createOptimizedPredictionResult(candidates, input, swipeShowRawBeamPredictions)
        }

        // 2. Fallback: Basic filtering
        val words = ArrayList<String>()
        val scores = ArrayList<Int>()

        for (candidate in candidates) {
            if (candidate.confidence >= confidenceThreshold) {
                words.add(candidate.word)
                scores.add((candidate.confidence * 1000).toInt())
            }
        }

        // Debug logging for raw outputs
        if (showRawOutput && candidates.isNotEmpty()) {
            val sb = StringBuilder("🔍 Raw NN Beam Search (Fallback):\n")
            val numToShow = min(5, candidates.size)
            for (i in 0 until numToShow) {
                val candidate = candidates[i]
                sb.append("  ${i + 1}. ${candidate.word} ${"%.3f".format(candidate.confidence)}\n")
            }
            debugLogger?.invoke(sb.toString())
        }

        return Result(words, scores)
    }

    private fun createOptimizedPredictionResult(
        candidates: List<Candidate>,
        input: SwipeInput?,
        showRawBeamPredictions: Boolean
    ): Result {
        // Log raw model outputs
        if (debugLogger != null && candidates.isNotEmpty()) {
            val sb = StringBuilder("🤖 MODEL OUTPUT: ")
            val numToShow = min(3, candidates.size)
            for (i in 0 until numToShow) {
                val c = candidates[i]
                if (i > 0) sb.append(", ")
                sb.append("${c.word}(${"%.2f".format(c.confidence)})")
            }
            debugLogger.invoke(sb.toString())
        }

        // Convert to vocabulary candidates
        val vocabCandidates = candidates.map { 
            CandidateWord(it.word, it.confidence) // Fixed: removed OptimizedVocabulary.
        }

        // Extract stats for filtering
        var lastChar = '\u0000'
        if (input?.keySequence?.isNotEmpty() == true) {
            lastChar = input.keySequence.last()
        }

        var firstChar = '\u0000'
        if (input?.keySequence?.isNotEmpty() == true) {
            firstChar = input.keySequence.first()
        }

        val swipeStats = SwipeStats( // Fixed: removed OptimizedVocabulary.
            input?.keySequence?.length ?: 0,
            input?.pathLength ?: 0f,
            input?.averageVelocity ?: 0f,
            firstChar,
            lastChar
        )

        // Apply filtering
        val filtered = vocabulary!!.filterPredictions(vocabCandidates, swipeStats)

        // Deduplicate
        data class WordDisplayPair(val displayText: String, val score: Int)
        val wordScoreMap = LinkedHashMap<String, WordDisplayPair>()

        for (pred in filtered) {
            val wordLower = pred.word.lowercase()
            val score = (pred.score * 1000).toInt()

            if (!wordScoreMap.containsKey(wordLower) || score > wordScoreMap[wordLower]!!.score) {
                wordScoreMap[wordLower] = WordDisplayPair(pred.displayText, score)
            }
        }

        val words = ArrayList<String>()
        val scores = ArrayList<Int>()
        for (entry in wordScoreMap.values) {
            words.add(entry.displayText)
            scores.add(entry.score)
        }

        // Add raw beam predictions if enabled
        if (showRawBeamPredictions && candidates.isNotEmpty()) {
            // FIX: Uncap raw scores
            val numRawToAdd = min(3, candidates.size)

            for (i in 0 until numRawToAdd) {
                val candidate = candidates[i]
                var alreadyIncluded = false
                for (word in words) {
                    if (word.equals(candidate.word, ignoreCase = true)) {
                        alreadyIncluded = true
                        break
                    }
                }

                if (!alreadyIncluded) {
                    val rawScore = (candidate.confidence * 1000).toInt()
                    words.add("raw:${candidate.word}")
                    scores.add(rawScore)
                }
            }

            // Re-sort to rank raw candidates correctly
            if (scores.size > 1) {
                val combined = ArrayList<Pair<String, Int>>()
                for (i in words.indices) combined.add(Pair(words[i], scores[i]))
                combined.sortByDescending { it.second }
                words.clear(); scores.clear()
                for (p in combined) { words.add(p.first); scores.add(p.second) }
            }
        }

        if (showRawOutput && candidates.isNotEmpty()) {
            val sb = StringBuilder("🔍 Raw NN Beam Search (Filtered):\n")
            val numToShow = min(5, candidates.size)
            for (i in 0 until numToShow) {
                val candidate = candidates[i]
                var inFiltered = false
                for (word in words) {
                    if (word.equals(candidate.word, ignoreCase = true)) {
                        inFiltered = true
                        break
                    }
                }
                val marker = if (inFiltered) "[kept]" else "[filtered]"
                sb.append("  ${i + 1}. ${candidate.word} ${"%.3f".format(candidate.confidence)} $marker\n")
            }
            debugLogger?.invoke(sb.toString())
        }

        return Result(words, scores)
    }
}
