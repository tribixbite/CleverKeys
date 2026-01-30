package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.onnx.MemoryPool

/**
 * Pure JVM benchmark for the swipe prediction pipeline components.
 *
 * Measures latency of pre/post-processing stages that run on every swipe:
 * - SwipeResampler: trajectory downsampling (3 modes)
 * - VocabularyTrie: dictionary prefix lookups + allowed chars
 * - VocabularyUtils: scoring, fuzzy matching, Levenshtein distance
 * - AccentNormalizer: multilang accent stripping
 * - MemoryPool: buffer allocation for ONNX tensors
 *
 * NOTE: ONNX encoder/decoder inference requires Android native libs
 * and cannot run in pure JVM. This benchmark covers all other stages.
 *
 * Run: ./scripts/run-pure-tests.sh PipelineBenchmarkTest
 */
class PipelineBenchmarkTest {

    companion object {
        private const val NUM_SWIPES = 100
        private const val WARMUP_ITERATIONS = 20
        private const val TARGET_SEQ_LENGTH = 32
        private const val NUM_FEATURES = 4 // x, y, dx, dy
        private const val VOCAB_SIZE = 5000
        private const val BEAM_WIDTH = 6
    }

    // =========================================================================
    // Full pipeline benchmark (100 swipes)
    // =========================================================================

    @Test
    fun `benchmark full pre-post processing pipeline 100 swipes`() {
        // Setup: build vocabulary trie
        val trie = buildTestTrie()
        val swipes = generateSwipes(NUM_SWIPES)

        // Warm up JIT
        repeat(WARMUP_ITERATIONS) {
            runPipelineIteration(swipes[it % swipes.size], trie)
        }

        // Benchmark
        val latencies = LongArray(NUM_SWIPES)
        var totalCorrectPrefix = 0

        for (i in 0 until NUM_SWIPES) {
            val start = System.nanoTime()
            val result = runPipelineIteration(swipes[i], trie)
            latencies[i] = System.nanoTime() - start

            if (result.hasValidPrefix) totalCorrectPrefix++
        }

        // Compute stats
        latencies.sort()
        val avgUs = latencies.average() / 1000.0
        val medianUs = latencies[NUM_SWIPES / 2] / 1000.0
        val p95Us = latencies[(NUM_SWIPES * 0.95).toInt()] / 1000.0
        val p99Us = latencies[(NUM_SWIPES * 0.99).toInt()] / 1000.0
        val minUs = latencies.first() / 1000.0
        val maxUs = latencies.last() / 1000.0

        val prefixAccuracy = totalCorrectPrefix.toFloat() / NUM_SWIPES * 100

        // Print results
        println("═══════════════════════════════════════════════════════")
        println("  Pipeline Benchmark Results ($NUM_SWIPES swipes)")
        println("═══════════════════════════════════════════════════════")
        println("  Pre/Post-Processing Latency (excludes ONNX inference):")
        println("    Average:  ${"%.1f".format(avgUs)} µs")
        println("    Median:   ${"%.1f".format(medianUs)} µs")
        println("    P95:      ${"%.1f".format(p95Us)} µs")
        println("    P99:      ${"%.1f".format(p99Us)} µs")
        println("    Min:      ${"%.1f".format(minUs)} µs")
        println("    Max:      ${"%.1f".format(maxUs)} µs")
        println("  Trie Prefix Accuracy: ${"%.1f".format(prefixAccuracy)}%")
        println("═══════════════════════════════════════════════════════")

        // Assertions — pipeline should be fast
        assertThat(avgUs).isLessThan(5000.0) // < 5ms avg per swipe
        assertThat(p95Us).isLessThan(10000.0) // < 10ms p95
        assertThat(prefixAccuracy).isGreaterThan(0f)
    }

    // =========================================================================
    // Per-stage benchmarks
    // =========================================================================

    @Test
    fun `benchmark resampling stage 100 swipes`() {
        val swipes = generateSwipes(NUM_SWIPES)

        // Warm up
        repeat(WARMUP_ITERATIONS) {
            SwipeResampler.resample(swipes[it % swipes.size], TARGET_SEQ_LENGTH, SwipeResampler.ResamplingMode.MERGE)
        }

        val latencies = LongArray(NUM_SWIPES)
        for (i in 0 until NUM_SWIPES) {
            val start = System.nanoTime()
            SwipeResampler.resample(swipes[i], TARGET_SEQ_LENGTH, SwipeResampler.ResamplingMode.MERGE)
            latencies[i] = System.nanoTime() - start
        }

        latencies.sort()
        val avgUs = latencies.average() / 1000.0
        val medianUs = latencies[NUM_SWIPES / 2] / 1000.0
        println("  Resampling:  avg=${"%.1f".format(avgUs)}µs  median=${"%.1f".format(medianUs)}µs")

        assertThat(avgUs).isLessThan(1000.0) // < 1ms
    }

    @Test
    fun `benchmark trie lookup stage 100 swipes`() {
        val trie = buildTestTrie()
        val prefixes = generatePrefixes(NUM_SWIPES)

        // Warm up
        repeat(WARMUP_ITERATIONS) {
            trie.hasPrefix(prefixes[it % prefixes.size])
            trie.getAllowedNextChars(prefixes[it % prefixes.size])
        }

        val latencies = LongArray(NUM_SWIPES)
        for (i in 0 until NUM_SWIPES) {
            val start = System.nanoTime()
            trie.hasPrefix(prefixes[i])
            trie.getAllowedNextChars(prefixes[i])
            trie.containsWord(prefixes[i])
            latencies[i] = System.nanoTime() - start
        }

        latencies.sort()
        val avgUs = latencies.average() / 1000.0
        val medianUs = latencies[NUM_SWIPES / 2] / 1000.0
        println("  Trie Lookup: avg=${"%.1f".format(avgUs)}µs  median=${"%.1f".format(medianUs)}µs")

        assertThat(avgUs).isLessThan(500.0) // < 500µs
    }

    @Test
    fun `benchmark scoring stage 100 swipes`() {
        // Warm up
        repeat(WARMUP_ITERATIONS) {
            VocabularyUtils.calculateCombinedScore(0.8f, 0.5f, 1.15f, 0.6f, 0.4f)
            VocabularyUtils.fuzzyMatch("hello", "hallo", 0.67f, 2, 1, 3)
            VocabularyUtils.calculateMatchQuality("hello", "hallo", true)
        }

        val candidates = generateCandidateWords()
        val latencies = LongArray(NUM_SWIPES)

        for (i in 0 until NUM_SWIPES) {
            val start = System.nanoTime()
            // Simulate scoring N beam candidates per swipe
            val beamWord = candidates[i % candidates.size]
            for (candidate in candidates) {
                VocabularyUtils.calculateCombinedScore(
                    confidence = 0.8f,
                    frequency = (candidate.length % 10) / 10f,
                    boost = if (candidate.length > 4) 1.15f else 1.0f,
                    confidenceWeight = 0.6f,
                    frequencyWeight = 0.4f
                )
                VocabularyUtils.fuzzyMatch(beamWord, candidate, 0.67f, 2, 1, 3)
                VocabularyUtils.calculateMatchQuality(beamWord, candidate, true)
            }
            latencies[i] = System.nanoTime() - start
        }

        latencies.sort()
        val avgUs = latencies.average() / 1000.0
        val medianUs = latencies[NUM_SWIPES / 2] / 1000.0
        println("  Scoring:     avg=${"%.1f".format(avgUs)}µs  median=${"%.1f".format(medianUs)}µs")

        assertThat(avgUs).isLessThan(2000.0) // < 2ms
    }

    @Test
    fun `benchmark accent normalization 100 swipes`() {
        val words = listOf(
            "résumé", "naïve", "café", "über", "größe", "fiancée",
            "cliché", "exposé", "soupçon", "jalapeño", "piñata",
            "schön", "straße", "fjörður", "smörgåsbord"
        )

        // Warm up
        repeat(WARMUP_ITERATIONS) {
            AccentNormalizer.normalize(words[it % words.size])
        }

        val latencies = LongArray(NUM_SWIPES)
        for (i in 0 until NUM_SWIPES) {
            val word = words[i % words.size]
            val start = System.nanoTime()
            AccentNormalizer.normalize(word)
            latencies[i] = System.nanoTime() - start
        }

        latencies.sort()
        val avgUs = latencies.average() / 1000.0
        val medianUs = latencies[NUM_SWIPES / 2] / 1000.0
        println("  AccentNorm:  avg=${"%.1f".format(avgUs)}µs  median=${"%.1f".format(medianUs)}µs")

        assertThat(medianUs).isLessThan(200.0) // < 200µs median (avg skewed by JIT warmup)
    }

    @Test
    fun `benchmark memory pool allocation 100 swipes`() {
        val pool = MemoryPool()

        // Warm up
        pool.initializePreallocatedBuffers(BEAM_WIDTH, 30, VOCAB_SIZE)
        repeat(WARMUP_ITERATIONS) {
            pool.getPreallocProbs()
            pool.getPreallocSrcLengths()
        }

        val latencies = LongArray(NUM_SWIPES)
        for (i in 0 until NUM_SWIPES) {
            val start = System.nanoTime()
            pool.getPreallocProbs()
            pool.getPreallocSrcLengths()
            pool.getPreallocBatchedTokens()
            latencies[i] = System.nanoTime() - start
        }

        latencies.sort()
        val avgUs = latencies.average() / 1000.0
        val medianUs = latencies[NUM_SWIPES / 2] / 1000.0
        println("  MemoryPool:  avg=${"%.1f".format(avgUs)}µs  median=${"%.1f".format(medianUs)}µs")

        assertThat(avgUs).isLessThan(100.0) // < 100µs

        pool.release()
    }

    // =========================================================================
    // Pipeline simulation
    // =========================================================================

    private data class PipelineResult(
        val resampledLength: Int,
        val hasValidPrefix: Boolean,
        val topScore: Float
    )

    private fun runPipelineIteration(
        swipeData: Array<FloatArray>,
        trie: VocabularyTrie
    ): PipelineResult {
        // Stage 1: Resample trajectory
        val resampled = SwipeResampler.resample(
            swipeData, TARGET_SEQ_LENGTH, SwipeResampler.ResamplingMode.MERGE
        )!!

        // Stage 2: Trie prefix lookup (simulating beam search char-by-char)
        val testPrefix = "hel"
        val hasPrefix = trie.hasPrefix(testPrefix)
        val allowedChars = trie.getAllowedNextChars(testPrefix)

        // Stage 3: Accent normalization (multilang path)
        AccentNormalizer.normalize("résumé")

        // Stage 4: Score candidates (simulating post-inference scoring)
        var topScore = 0f
        val candidates = listOf("hello", "help", "held", "helm", "heap")
        for (candidate in candidates) {
            val score = VocabularyUtils.calculateCombinedScore(
                confidence = 0.85f,
                frequency = candidate.length / 10f,
                boost = 1.15f,
                confidenceWeight = 0.6f,
                frequencyWeight = 0.4f
            )
            if (score > topScore) topScore = score

            // Fuzzy match check
            VocabularyUtils.fuzzyMatch(
                "hello", candidate, 0.67f, 2, 1, 3
            )

            // Match quality
            VocabularyUtils.calculateMatchQuality("hello", candidate, true)
        }

        return PipelineResult(
            resampledLength = resampled.size,
            hasValidPrefix = hasPrefix,
            topScore = topScore
        )
    }

    // =========================================================================
    // Test data generators
    // =========================================================================

    private fun generateSwipes(count: Int): List<Array<FloatArray>> {
        val swipeLengths = listOf(40, 60, 80, 100, 120, 150) // varying lengths
        return (0 until count).map { i ->
            val length = swipeLengths[i % swipeLengths.size]
            Array(length) { j ->
                val t = j.toFloat() / length
                floatArrayOf(
                    t * 300f + (i % 7) * 10f,  // x
                    200f + kotlin.math.sin(t * Math.PI).toFloat() * 50f, // y
                    3f + t,                      // dx
                    0.5f - t * 0.3f              // dy
                )
            }
        }
    }

    private fun buildTestTrie(): VocabularyTrie {
        val trie = VocabularyTrie()
        val words = listOf(
            "hello", "help", "held", "helm", "heap", "heat", "hear", "head",
            "the", "there", "their", "then", "them", "they", "these", "those",
            "world", "word", "work", "would", "with", "will", "when", "what",
            "have", "hand", "hang", "hard", "harm", "hate", "half", "hall",
            "about", "above", "after", "again", "along", "also", "among",
            "quick", "quiet", "quite", "queen", "query", "quest", "quote",
            "never", "new", "next", "nice", "nine", "none", "note", "now",
            "going", "good", "great", "group", "grow", "guide", "give"
        )
        for (word in words) {
            trie.insert(word)
        }
        return trie
    }

    private fun generatePrefixes(count: Int): List<String> {
        val prefixes = listOf(
            "h", "he", "hel", "hell", "hello",
            "t", "th", "the", "ther", "there",
            "w", "wo", "wor", "worl", "world",
            "q", "qu", "qui", "quic", "quick",
            "a", "ab", "abo", "abou", "about"
        )
        return (0 until count).map { prefixes[it % prefixes.size] }
    }

    private fun generateCandidateWords(): List<String> {
        return listOf(
            "hello", "help", "held", "helm", "heap",
            "the", "there", "their", "then", "them",
            "world", "word", "work", "would", "with",
            "about", "above", "after", "again", "along",
            "quick", "quiet", "quite", "queen", "query"
        )
    }
}
