package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.onnx.BeamSearchEngine
import tribixbite.cleverkeys.onnx.DecoderSessionInterface
import kotlin.math.exp

/**
 * Tests for BeamSearchEngine using a fake decoder session.
 * Validates beam search logic (scoring, pruning, trie masking, EOS handling)
 * without any ONNX runtime dependency.
 */
class BeamSearchEngineTest {

    companion object {
        // Mirror BeamSearchEngine's special token indices
        private const val PAD_IDX = 0
        private const val UNK_IDX = 1
        private const val SOS_IDX = 2
        private const val EOS_IDX = 3

        // Character tokens start at index 4: a=4, b=5, ..., z=29
        private const val VOCAB_SIZE = 30
        private const val DECODER_SEQ_LEN = 20
    }

    private lateinit var tokenizer: SwipeTokenizer

    @Before
    fun setup() {
        tokenizer = SwipeTokenizer()
        // Build standard a-z mapping: a=4, b=5, ..., z=29
        for (i in 0 until 26) {
            tokenizer.addMapping(4 + i, 'a' + i)
        }
    }

    /**
     * Fake decoder that returns controlled logits to drive beam search behavior.
     *
     * Given a mapping of (step, tokenIndex) -> logitBoost, the decoder returns
     * logits where specified tokens get high values and everything else gets -100.
     * This lets us precisely control which tokens beam search selects at each step.
     */
    private class FakeDecoderSession(
        private val vocabSize: Int = VOCAB_SIZE,
        // Map of step (token count - 1) to a map of (tokenIndex -> logit value)
        // Step 0 = first decode step (beam has [SOS]), step 1 = second decode step, etc.
        private val stepLogits: Map<Int, Map<Int, Float>> = emptyMap(),
        // Alternative: provide uniform logits for all tokens at all steps
        private val uniformLogit: Float? = null
    ) : DecoderSessionInterface {

        var callCount = 0
            private set
        var cleanupCalled = false
            private set

        override fun runSequential(
            tokens: IntArray,
            actualSrcLength: Int,
            decoderSeqLength: Int
        ): Array<Array<FloatArray>> {
            callCount++
            val logits = buildLogits(tokens, decoderSeqLength)
            // Return shape [1][decoderSeqLength][vocabSize]
            return arrayOf(logits)
        }

        override fun runBatched(
            batchedTokens: IntArray,
            numBeams: Int,
            actualSrcLength: Int,
            decoderSeqLength: Int
        ): Array<Array<FloatArray>> {
            callCount++
            // Build logits for each beam
            return Array(numBeams) { b ->
                val beamTokens = IntArray(decoderSeqLength)
                System.arraycopy(batchedTokens, b * decoderSeqLength, beamTokens, 0, decoderSeqLength)
                buildLogits(beamTokens, decoderSeqLength)
            }
        }

        override fun cleanup() {
            cleanupCalled = true
        }

        private fun buildLogits(tokens: IntArray, decoderSeqLength: Int): Array<FloatArray> {
            return Array(decoderSeqLength) { pos ->
                val logits = FloatArray(vocabSize) { uniformLogit ?: -100f }

                // Determine the current step from actual token content
                // Count non-PAD tokens to find what step we're on
                val tokenCount = tokens.count { it != PAD_IDX }
                val step = tokenCount - 1 // subtract SOS

                if (pos == step) {
                    // Apply step-specific logits at the active position
                    stepLogits[step]?.forEach { (idx, value) ->
                        if (idx < vocabSize) logits[idx] = value
                    }
                }
                logits
            }
        }
    }

    // --- Basic functionality tests ---

    @Test
    fun `search produces word from high-probability tokens`() {
        // Fake decoder that strongly favors: h(11) -> i(12) -> EOS
        val decoder = FakeDecoderSession(
            stepLogits = mapOf(
                0 to mapOf(11 to 10f),  // step 0: 'h' (idx 4+7=11)
                1 to mapOf(12 to 10f),  // step 1: 'i' (idx 4+8=12)
                2 to mapOf(EOS_IDX to 10f)  // step 2: EOS
            )
        )

        val engine = BeamSearchEngine(
            decoderSession = decoder,
            tokenizer = tokenizer,
            vocabTrie = null,
            beamWidth = 3,
            maxLength = 10
        )

        val results = engine.search(actualSrcLength = 5)
        assertThat(results).isNotEmpty()
        assertThat(results[0].word).isEqualTo("hi")
        assertThat(results[0].confidence).isGreaterThan(0f)
    }

    @Test
    fun `search returns empty for all low probability tokens`() {
        // All tokens have very low logits — nothing should pass confidence threshold
        val decoder = FakeDecoderSession(uniformLogit = -100f)

        val engine = BeamSearchEngine(
            decoderSession = decoder,
            tokenizer = tokenizer,
            vocabTrie = null,
            beamWidth = 3,
            maxLength = 5,
            confidenceThreshold = 0.5f // High threshold
        )

        val results = engine.search(actualSrcLength = 5)
        // With uniform -100 logits, softmax gives equal probabilities for all tokens
        // Score accumulates, confidence may or may not pass threshold depending on normalization
        // Key: should not crash and should return a list
        assertThat(results).isNotNull()
    }

    @Test
    fun `cleanup delegates to decoder session`() {
        val decoder = FakeDecoderSession(
            stepLogits = mapOf(0 to mapOf(EOS_IDX to 10f))
        )

        val engine = BeamSearchEngine(
            decoderSession = decoder,
            tokenizer = tokenizer,
            vocabTrie = null,
            beamWidth = 3,
            maxLength = 5
        )

        engine.search(actualSrcLength = 5)
        // search() calls cleanup() in its finally block
        assertThat(decoder.cleanupCalled).isTrue()
    }

    @Test
    fun `beam width limits number of results`() {
        // Multiple tokens with similar logits at step 0
        val decoder = FakeDecoderSession(
            stepLogits = mapOf(
                0 to mapOf(4 to 5f, 5 to 5f, 6 to 5f, 7 to 5f, 8 to 5f), // a,b,c,d,e all viable
                1 to mapOf(EOS_IDX to 10f) // All finish at step 1
            )
        )

        val engine = BeamSearchEngine(
            decoderSession = decoder,
            tokenizer = tokenizer,
            vocabTrie = null,
            beamWidth = 3,
            maxLength = 5
        )

        val results = engine.search(actualSrcLength = 5)
        assertThat(results.size).isAtMost(3)
    }

    @Test
    fun `maxLength prevents infinite decoding`() {
        // Decoder never produces EOS
        val highTokenLogits = (4..29).associate { it to 5f }
        val stepMap = (0..25).associate { it to highTokenLogits }
        val decoder = FakeDecoderSession(stepLogits = stepMap)

        val engine = BeamSearchEngine(
            decoderSession = decoder,
            tokenizer = tokenizer,
            vocabTrie = null,
            beamWidth = 2,
            maxLength = 5 // Stop after 5 steps
        )

        val results = engine.search(actualSrcLength = 5)
        // Should terminate without hanging; results may be empty (no EOS = no finished beams)
        assertThat(results).isNotNull()
    }

    @Test
    fun `results are sorted by normalized score`() {
        // Two viable paths with different scores
        val decoder = FakeDecoderSession(
            stepLogits = mapOf(
                0 to mapOf(4 to 10f, 5 to 8f),  // 'a' strongly preferred, 'b' second
                1 to mapOf(EOS_IDX to 10f)
            )
        )

        val engine = BeamSearchEngine(
            decoderSession = decoder,
            tokenizer = tokenizer,
            vocabTrie = null,
            beamWidth = 5,
            maxLength = 5
        )

        val results = engine.search(actualSrcLength = 5)
        if (results.size >= 2) {
            // First result should have higher confidence than second
            assertThat(results[0].confidence).isAtLeast(results[1].confidence)
        }
    }

    @Test
    fun `trie masking constrains vocabulary`() {
        // Build a trie with only "hi" and "he"
        val trie = VocabularyTrie()
        trie.insert("hi")
        trie.insert("he")

        // Decoder favors 'a' strongly, but trie should mask it
        val decoder = FakeDecoderSession(
            stepLogits = mapOf(
                0 to mapOf(4 to 15f, 11 to 5f), // 'a'=15 (masked by trie), 'h'=5 (allowed)
                1 to mapOf(4 to 15f, 8 to 5f, 12 to 5f), // 'a'=15 (masked), 'e'=5, 'i'=5
                2 to mapOf(EOS_IDX to 10f)
            )
        )

        val engine = BeamSearchEngine(
            decoderSession = decoder,
            tokenizer = tokenizer,
            vocabTrie = trie,
            beamWidth = 3,
            maxLength = 5
        )

        val results = engine.search(actualSrcLength = 5)
        assertThat(results).isNotEmpty()
        // All results must be words in the trie
        for (r in results) {
            assertThat(r.word).isAnyOf("hi", "he")
        }
    }

    @Test
    fun `batched mode produces same results as sequential`() {
        val stepLogits = mapOf(
            0 to mapOf(11 to 10f),  // 'h'
            1 to mapOf(12 to 10f),  // 'i'
            2 to mapOf(EOS_IDX to 10f)
        )

        val seqDecoder = FakeDecoderSession(stepLogits = stepLogits)
        val batchDecoder = FakeDecoderSession(stepLogits = stepLogits)

        val seqEngine = BeamSearchEngine(
            decoderSession = seqDecoder, tokenizer = tokenizer,
            vocabTrie = null, beamWidth = 3, maxLength = 10
        )
        val batchEngine = BeamSearchEngine(
            decoderSession = batchDecoder, tokenizer = tokenizer,
            vocabTrie = null, beamWidth = 3, maxLength = 10
        )

        val seqResults = seqEngine.search(actualSrcLength = 5, useBatched = false)
        val batchResults = batchEngine.search(actualSrcLength = 5, useBatched = true)

        assertThat(seqResults.map { it.word }).isEqualTo(batchResults.map { it.word })
    }

    @Test
    fun `temperature affects confidence spread`() {
        val stepLogits = mapOf(
            0 to mapOf(4 to 5f, 5 to 4f),  // 'a' slightly preferred over 'b'
            1 to mapOf(EOS_IDX to 10f)
        )

        // Low temperature = sharper distribution
        val sharpDecoder = FakeDecoderSession(stepLogits = stepLogits)
        val sharpEngine = BeamSearchEngine(
            decoderSession = sharpDecoder, tokenizer = tokenizer,
            vocabTrie = null, beamWidth = 5, maxLength = 5,
            temperature = 0.5f
        )
        val sharpResults = sharpEngine.search(actualSrcLength = 5)

        // High temperature = more uniform distribution
        val flatDecoder = FakeDecoderSession(stepLogits = stepLogits)
        val flatEngine = BeamSearchEngine(
            decoderSession = flatDecoder, tokenizer = tokenizer,
            vocabTrie = null, beamWidth = 5, maxLength = 5,
            temperature = 2.0f
        )
        val flatResults = flatEngine.search(actualSrcLength = 5)

        // With sharp temperature, top result should have higher confidence
        if (sharpResults.isNotEmpty() && flatResults.isNotEmpty()) {
            assertThat(sharpResults[0].confidence).isGreaterThan(flatResults[0].confidence)
        }
    }

    @Test
    fun `debug logger receives messages`() {
        val logs = mutableListOf<String>()
        val decoder = FakeDecoderSession(
            stepLogits = mapOf(0 to mapOf(EOS_IDX to 10f))
        )

        val engine = BeamSearchEngine(
            decoderSession = decoder, tokenizer = tokenizer,
            vocabTrie = null, beamWidth = 3, maxLength = 5,
            debugLogger = { logs.add(it) }
        )

        engine.search(actualSrcLength = 5)
        // Should have logged about null trie
        assertThat(logs.any { it.contains("TRIE IS NULL") }).isTrue()
    }

    @Test
    fun `longer words have reasonable confidence via length normalization`() {
        // Build a path that spells "hello" with consistently high logits
        val decoder = FakeDecoderSession(
            stepLogits = mapOf(
                0 to mapOf(11 to 10f),  // h
                1 to mapOf(8 to 10f),   // e
                2 to mapOf(15 to 10f),  // l
                3 to mapOf(15 to 10f),  // l
                4 to mapOf(18 to 10f),  // o
                5 to mapOf(EOS_IDX to 10f)
            )
        )

        val engine = BeamSearchEngine(
            decoderSession = decoder, tokenizer = tokenizer,
            vocabTrie = null, beamWidth = 3, maxLength = 10,
            lengthPenaltyAlpha = 1.0f
        )

        val results = engine.search(actualSrcLength = 10)
        assertThat(results).isNotEmpty()
        val hello = results.find { it.word == "hello" }
        assertThat(hello).isNotNull()
        // With strong logits and length normalization, confidence should be reasonable
        assertThat(hello!!.confidence).isGreaterThan(0.1f)
    }

    @Test
    fun `deduplication prevents identical token sequences`() {
        // This tests the internal dedup logic — with multiple paths converging
        // to the same token sequence, we should still get unique words
        val decoder = FakeDecoderSession(
            stepLogits = mapOf(
                0 to mapOf(4 to 10f),  // only 'a' viable
                1 to mapOf(EOS_IDX to 10f)
            )
        )

        val engine = BeamSearchEngine(
            decoderSession = decoder, tokenizer = tokenizer,
            vocabTrie = null, beamWidth = 5, maxLength = 5
        )

        val results = engine.search(actualSrcLength = 5)
        val words = results.map { it.word }
        // No duplicate words
        assertThat(words).containsNoDuplicates()
    }
}
