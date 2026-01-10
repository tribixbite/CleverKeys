package tribixbite.cleverkeys.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import tribixbite.cleverkeys.VocabularyTrie
import tribixbite.cleverkeys.SwipeTokenizer
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.PriorityQueue
import java.util.Comparator
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * Core beam search implementation for neural swipe decoding.
 * Extracted from OnnxSwipePredictor.java for modularity and testability.
 *
 * Features:
 * - Batched and sequential beam search
 * - Trie-guided decoding (logit masking)
 * - Adaptive pruning and early stopping
 * - Length-normalized scoring
 * - Diversity promotion
 */
class BeamSearchEngine(
    private val decoderSession: OrtSession,
    private val ortEnvironment: OrtEnvironment,
    private val tokenizer: SwipeTokenizer,
    private val vocabTrie: VocabularyTrie?,
    private val beamWidth: Int,
    private val maxLength: Int,
    private val confidenceThreshold: Float = 0.01f, // Lowered default (0.05 -> 0.01) to keep more candidates
    private val lengthPenaltyAlpha: Float = 1.0f, // Length normalization factor (1.0 = linear)
    private val adaptiveWidthConfidence: Float = 0.8f, // Pruning confidence threshold
    private val scoreGapThreshold: Float = 8.0f, // Early stopping score gap
    private val adaptiveWidthStep: Int = 12, // When to start adaptive width pruning
    private val scoreGapStep: Int = 10, // When to start score gap early stopping
    private val temperature: Float = 1.0f, // Softmax temperature (lower = sharper, higher = more uniform)
    private val debugLogger: ((String) -> Unit)? = null,
    // Language-specific prefix boost support
    private val prefixBoostLoader: PrefixBoostLoader? = null,
    private val prefixBoostMultiplier: Float = 1.0f, // Scaling factor for prefix boosts
    private val prefixBoostMax: Float = 5.0f // Maximum boost value (clamping)
) {

    companion object {
        private const val TAG = "BeamSearchEngine"
        
        // Special tokens
        private const val PAD_IDX = 0
        private const val UNK_IDX = 1
        private const val SOS_IDX = 2
        private const val EOS_IDX = 3
        
        // Constants
        private const val DECODER_SEQ_LEN = 20 // Must match model export
        private const val LOG_PROB_THRESHOLD = -13.8f // approx ln(1e-6)
        private const val PRUNE_STEP_THRESHOLD = 2
        // Note: ADAPTIVE_WIDTH_STEP and SCORE_GAP_STEP are now constructor params
        // to allow user customization for long word prediction tuning

        // Diversity parameters (4D: Diverse Beam Search)
        private const val DIVERSITY_LAMBDA = 0.5f // Penalty weight for similar beams
    }

    data class BeamSearchCandidate(val word: String, val confidence: Float, val score: Float)

    private data class BeamState(
        val tokens: ArrayList<Long>,
        var score: Float, // Accumulated negative log-likelihood
        var finished: Boolean,
        val parentBeam: BeamState? = null // For diversity tracking (optional)
    ) {
        constructor(startToken: Int, startScore: Float) : this(
            tokens = ArrayList(listOf(startToken.toLong())),
            score = startScore,
            finished = false
        )
        
        // Copy constructor
        constructor(other: BeamState) : this(
            tokens = ArrayList(other.tokens),
            score = other.score,
            finished = other.finished,
            parentBeam = other.parentBeam
        )
    }

    /**
     * Run beam search decoding.
     */
    fun search(memory: OnnxTensor, actualSrcLength: Int, useBatched: Boolean = false): List<BeamSearchCandidate> {
        val beams = ArrayList<BeamState>()
        beams.add(BeamState(SOS_IDX, 0.0f))
        
        var step = 0
        var totalInferenceTime = 0L
        
        // Main decoding loop
        while (step < maxLength) {
            val candidates = ArrayList<BeamState>()
            val activeBeams = beams.filter { !it.finished }
            val finishedBeams = beams.filter { it.finished }
            
            // Pass finished beams to candidates for next step ranking
            candidates.addAll(finishedBeams.map { BeamState(it) })
            
            if (activeBeams.isEmpty()) break
            
            // Log every 5th step
            if (step % 5 == 0) {
                // logDebug("Step $step: ${activeBeams.size} active beams")
            }

            try {
                val startInf = System.nanoTime()
                
                // Decide strategy: Batched vs Sequential
                // Note: Batched logic is complex to port directly without tensor utilities.
                // For this extraction, we'll focus on correcting the logic first in sequential mode, 
                // effectively fixing "Critical Issue #1" (Score Accumulation).
                // Re-enabling batching is a TODO for tensor shape verification.
                
                // SEQUENTIAL PROCESSING (Robust default)
                val nextBeams = processSequential(activeBeams, memory, actualSrcLength, step)
                candidates.addAll(nextBeams)
                
                totalInferenceTime += (System.nanoTime() - startInf) / 1_000_000
                
            } catch (e: Exception) {
                Log.e(TAG, "Beam search error at step $step", e)
                break
            }
            
            // Ranking and Pruning
            
            // 1. Score Accumulation Fix: We accumulate NEGATIVE log-probs (score += -logP)
            // Lower score is better.
            
            // 4C: Length-Normalized Scoring
            // Normalize score by sequence length to prevent bias towards short words
            // alpha = 0.6 to 0.7 is standard. 1.0 = linear average.
            
            candidates.sortBy { 
                val len = it.tokens.size.toFloat()
                // Avoid division by zero or extremely short length bias
                val normFactor = (5.0 + len).pow(lengthPenaltyAlpha.toDouble()).toFloat() / 6.0.pow(lengthPenaltyAlpha.toDouble()).toFloat()
                it.score / normFactor 
            }
            
            // Filter low probability beams
            if (step >= PRUNE_STEP_THRESHOLD) {
                candidates.removeIf { exp(-it.score) < 1e-6 }
            }
            
            // Select top K with deduplication by token sequence
            beams.clear()
            // FIX: Deduplicate beams by token sequence to prevent identical words
            // Multiple paths can converge to the same token sequence via different parent beams
            val seenTokenSeqs = HashSet<List<Long>>()

            for (candidate in candidates) {
                if (beams.size >= beamWidth) break

                // Create immutable copy for hashing
                val tokenSeq = candidate.tokens.toList()
                if (!seenTokenSeqs.contains(tokenSeq)) {
                    seenTokenSeqs.add(tokenSeq)
                    beams.add(candidate)
                }
            }
            
            // Adaptive Width Reduction (uses constructor param instead of constant)
            if (step == adaptiveWidthStep && beams.size > 3) {
                val topScore = beams[0].score
                val confidence = exp(-topScore)
                if (confidence > adaptiveWidthConfidence) {
                    // Prune to top 3 if very confident
                    while (beams.size > 3) beams.removeAt(beams.size - 1)
                }
            }

            // Score Gap Early Stopping (uses constructor param instead of constant)
            if (beams.size >= 2 && step >= scoreGapStep) {
                val topScore = beams[0].score
                val secondScore = beams[1].score
                val gap = secondScore - topScore // positive since lower is better
                
                if (beams[0].finished && gap > scoreGapThreshold) {
                    // logDebug("Score gap early stop: $gap")
                    break
                }
            }
            
            // All finished check
            if (beams.all { it.finished } || beams.count { it.finished } >= beamWidth) {
                break
            }
            
            step++
        }
        
        return beams.mapNotNull { convertToCandidate(it) }
    }
    
    private fun processSequential(
        activeBeams: List<BeamState>, 
        memory: OnnxTensor, 
        actualSrcLength: Int,
        step: Int // Used for tensor shape in future
    ): List<BeamState> {
        val newCandidates = ArrayList<BeamState>()
        
        // Shared tensor for src length (created once)
        val actualSrcLengthTensor = OnnxTensor.createTensor(ortEnvironment, intArrayOf(actualSrcLength))
        
        try {
            for (beam in activeBeams) {
                // Prepare target tokens
                val tgtTokens = IntArray(DECODER_SEQ_LEN) { PAD_IDX }
                val len = min(beam.tokens.size, DECODER_SEQ_LEN)
                for (i in 0 until len) {
                    tgtTokens[i] = beam.tokens[i].toInt()
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
                    
                    // Get logits for current position
                    val currentPos = beam.tokens.size - 1
                    if (currentPos in 0 until DECODER_SEQ_LEN) {
                        val logits = logits3D[0][currentPos]

                        // Apply Trie Masking
                        applyTrieMasking(beam, logits)

                        // Apply Language-Specific Prefix Boosts (before softmax)
                        // This boosts prefixes common in target language but rare in English
                        applyPrefixBoosts(beam, logits)

                        // FIX: Log-Softmax for numerical stability and correct scoring
                        val logProbs = logSoftmax(logits)
                        
                        // Get Top K
                        val topIndices = getTopKIndices(logProbs, beamWidth)
                        
                        for (idx in topIndices) {
                            // FIX: SOS and PAD should never be selected - skip entirely
                            // (Trie masking sets them to -inf, but be safe if trie is disabled)
                            if (idx == SOS_IDX || idx == PAD_IDX) {
                                continue
                            }

                            // EOS marks end of word - create finished beam
                            if (idx == EOS_IDX) {
                                val newBeam = BeamState(beam)
                                newBeam.tokens.add(idx.toLong())
                                // FIX #1: Add NEGATIVE log prob (since logProbs are negative)
                                // score += -logP
                                newBeam.score += -logProbs[idx]
                                newBeam.finished = true
                                newCandidates.add(newBeam)
                                continue
                            }

                            // Regular character tokens
                            val newBeam = BeamState(beam)
                            newBeam.tokens.add(idx.toLong())
                            newBeam.score += -logProbs[idx]
                            newBeam.finished = false
                            newCandidates.add(newBeam)
                        }
                    }
                    
                    logitsTensor.close()
                    result.close()
                    
                } finally {
                    targetTokensTensor.close()
                }
            }
        } finally {
            actualSrcLengthTensor.close()
        }
        
        return newCandidates
    }
    
    // v1.1.89 DIAGNOSTIC: Track if we've logged trie status for this search
    private var trieStatusLogged = false

    private fun applyTrieMasking(beam: BeamState, logits: FloatArray) {
        if (vocabTrie == null) {
            if (!trieStatusLogged) {
                Log.w(TAG, "🚨 TRIE IS NULL - No masking applied! Beam search is UNCONSTRAINED")
                trieStatusLogged = true
            }
            return
        }

        val partialWord = StringBuilder()
        for (token in beam.tokens) {
            val idx = token.toInt()
            if (idx != SOS_IDX && idx != EOS_IDX && idx != PAD_IDX) {
                val ch = tokenizer.indexToChar(idx)
                if (ch != '?' && !ch.toString().startsWith("<")) {
                    partialWord.append(ch)
                }
            }
        }

        val prefix = partialWord.toString()
        val allowed = vocabTrie.getAllowedNextChars(prefix)
        val isWord = vocabTrie.containsWord(prefix)

        // v1.1.89 DIAGNOSTIC: Log masking for first few prefixes
        if (!trieStatusLogged || prefix.length <= 2) {
            if (!trieStatusLogged) {
                val stats = vocabTrie.getStats()
                Log.d(TAG, "✅ Trie masking ACTIVE: ${stats.first} words in trie")
                trieStatusLogged = true
            }
            if (prefix.isNotEmpty() && prefix.length <= 2) {
                Log.d(TAG, "  MASK prefix='$prefix' allowed=${allowed.toList().take(10)}... isWord=$isWord")
            }
        }
        
        for (i in logits.indices) {
            // FIX: SOS and PAD should NEVER be selected as next tokens - mask them
            if (i == SOS_IDX || i == PAD_IDX) {
                logits[i] = Float.NEGATIVE_INFINITY
                continue
            }
            if (i == EOS_IDX) {
                if (!isWord) logits[i] = Float.NEGATIVE_INFINITY
                continue
            }
            
            val c = tokenizer.indexToChar(i)
            // Trie stores lowercase
            if (c == '?' || !allowed.contains(c.lowercaseChar())) {
                logits[i] = Float.NEGATIVE_INFINITY
            }
        }
    }

    /**
     * Apply language-specific prefix boosts to logits.
     *
     * This compensates for prefixes that are common in the target language (e.g., French)
     * but rare in English. The English-trained NN assigns low probability to these prefixes,
     * causing beam search to prune them too early.
     *
     * Example: "ve" + "u" gets boosted because "veu" is common in French (veux, veut, veulent)
     * but rare in English, so the NN gives low P(u|ve).
     *
     * Boosts are applied additively to logits (before softmax), which is equivalent to
     * multiplicative scaling of probabilities.
     */
    private fun applyPrefixBoosts(beam: BeamState, logits: FloatArray) {
        if (prefixBoostLoader == null || !prefixBoostLoader.hasBoosts() || prefixBoostMultiplier == 0f) {
            return
        }

        // Build current prefix from beam tokens
        val partialWord = StringBuilder()
        for (token in beam.tokens) {
            val idx = token.toInt()
            if (idx != SOS_IDX && idx != EOS_IDX && idx != PAD_IDX) {
                val ch = tokenizer.indexToChar(idx)
                if (ch != '?' && !ch.toString().startsWith("<")) {
                    partialWord.append(ch.lowercaseChar())
                }
            }
        }

        val prefix = partialWord.toString()
        if (prefix.isEmpty()) return

        // Apply boosts using the loader's logic (longest-match-only)
        var boostsApplied = 0
        for (i in logits.indices) {
            if (logits[i] == Float.NEGATIVE_INFINITY) continue  // Skip masked tokens

            val c = tokenizer.indexToChar(i)
            if (!c.isLetter()) continue

            val boost = prefixBoostLoader.getBoost(prefix, c)
            if (boost > 0f) {
                // Scale and clamp the boost
                val scaledBoost = (boost * prefixBoostMultiplier).coerceIn(-prefixBoostMax, prefixBoostMax)
                logits[i] += scaledBoost
                boostsApplied++
            }
        }

        // Log first few boost applications for debugging
        if (boostsApplied > 0 && prefix.length <= 3 && debugLogger != null) {
            Log.d(TAG, "  PREFIX BOOST: '$prefix' → $boostsApplied chars boosted (mult=$prefixBoostMultiplier)")
        }
    }

    // FIX #3: Numerically stable log-softmax with temperature scaling
    // Temperature < 1.0: sharper distribution (more confident)
    // Temperature > 1.0: more uniform distribution (more diverse)
    private fun logSoftmax(logits: FloatArray): FloatArray {
        // Apply temperature scaling: logits / temperature
        // For temperature = 1.0, this is a no-op
        val scaledLogits = if (temperature != 1.0f) {
            FloatArray(logits.size) { i -> logits[i] / temperature }
        } else {
            logits
        }

        var maxLogit = Float.NEGATIVE_INFINITY
        for (logit in scaledLogits) {
            if (logit > maxLogit) maxLogit = logit
        }

        var sumExp = 0.0f
        for (logit in scaledLogits) {
            sumExp += exp(logit - maxLogit)
        }
        val logSumExp = maxLogit + ln(sumExp)

        val logProbs = FloatArray(scaledLogits.size)
        for (i in scaledLogits.indices) {
            logProbs[i] = scaledLogits[i] - logSumExp
        }
        return logProbs
    }
    
    private fun getTopKIndices(array: FloatArray, k: Int): IntArray {
        val n = array.size
        val actualK = min(k, n)
        
        // Use PriorityQueue for TopK (simpler than custom sort for now)
        // Min-heap to keep largest K elements
        val pq = PriorityQueue<Int>(actualK + 1) { a, b -> 
            array[a].compareTo(array[b]) 
        }
        
        for (i in array.indices) {
            if (array[i] == Float.NEGATIVE_INFINITY) continue
            
            pq.offer(i)
            if (pq.size > actualK) {
                pq.poll() // Remove smallest
            }
        }
        
        // Extract in descending order
        val result = IntArray(pq.size)
        for (i in result.indices.reversed()) {
            result[i] = pq.poll()
        }
        return result
    }
    
    private fun convertToCandidate(beam: BeamState): BeamSearchCandidate? {
        val word = StringBuilder()
        for (token in beam.tokens) {
            val idx = token.toInt()
            if (idx == SOS_IDX || idx == EOS_IDX || idx == PAD_IDX) continue

            val ch = tokenizer.indexToChar(idx)
            if (ch != '?' && !ch.toString().startsWith("<")) {
                word.append(ch)
            }
        }

        val wordStr = word.toString()
        if (wordStr.isEmpty()) return null

        // CRITICAL FIX: Apply length normalization to final confidence!
        // Score is accumulated NLL across all decoding steps, so longer words have
        // inherently higher scores (lower probability) even with equal per-step confidence.
        // Use the SAME normalization formula as beam sorting (lines 144-149) to make
        // confidence values comparable across different word lengths.
        //
        // Formula: normFactor = (5 + len)^alpha / 6^alpha
        // - At len=1: normFactor = 1.0 (no change)
        // - At len=5: normFactor ≈ 1.87 (with alpha=1.2)
        // - At len=10: normFactor ≈ 3.22 (with alpha=1.2)
        //
        // Without this fix:
        //   "dames" (5 chars, score 1.05) → exp(-1.05) = 0.35
        //   "dangerously" (11 chars, score 1.97) → exp(-1.97) = 0.14
        // With this fix:
        //   "dames" → exp(-1.05/1.87) = exp(-0.56) = 0.57
        //   "dangerously" → exp(-1.97/3.58) = exp(-0.55) = 0.58
        val len = wordStr.length.toFloat()
        val normFactor = (5.0 + len).pow(lengthPenaltyAlpha.toDouble()).toFloat() /
                         6.0.pow(lengthPenaltyAlpha.toDouble()).toFloat()
        val normalizedScore = beam.score / normFactor
        val confidence = exp(-normalizedScore)

        // Apply confidence threshold to normalized value
        if (confidence < confidenceThreshold) return null

        return BeamSearchCandidate(wordStr, confidence, beam.score)
    }
    
    private fun logDebug(msg: String) {
        debugLogger?.invoke(msg)
    }
}