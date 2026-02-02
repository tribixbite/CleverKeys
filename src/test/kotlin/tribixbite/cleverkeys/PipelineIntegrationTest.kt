package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.onnx.BeamSearchEngine
import tribixbite.cleverkeys.onnx.DecoderSessionInterface

/**
 * Integration tests verifying components work together in the prediction pipeline.
 * Uses fake decoder but real implementations of all other components:
 * SwipeResampler, VocabularyTrie, VocabularyUtils, AccentNormalizer, BeamSearchEngine.
 */
class PipelineIntegrationTest {

    companion object {
        private const val PAD_IDX = 0
        private const val SOS_IDX = 2
        private const val EOS_IDX = 3
        private const val VOCAB_SIZE = 30
        private const val DECODER_SEQ_LEN = 20
    }

    private lateinit var tokenizer: SwipeTokenizer
    private lateinit var trie: VocabularyTrie

    @Before
    fun setup() {
        tokenizer = SwipeTokenizer()
        for (i in 0 until 26) {
            tokenizer.addMapping(4 + i, 'a' + i)
        }

        trie = VocabularyTrie()
        // Small vocabulary for integration testing
        listOf("hello", "help", "held", "world", "word", "work",
               "the", "them", "then", "there", "cat", "car", "care").forEach {
            trie.insert(it)
        }
    }

    /**
     * Fake decoder that emits tokens for a specific target word.
     */
    private class WordEmittingDecoder(
        private val targetWord: String,
        private val vocabSize: Int = VOCAB_SIZE
    ) : DecoderSessionInterface {
        override fun runSequential(
            tokens: IntArray, actualSrcLength: Int, decoderSeqLength: Int
        ): Array<Array<FloatArray>> {
            val tokenCount = tokens.count { it != PAD_IDX }
            val step = tokenCount - 1 // subtract SOS
            return arrayOf(buildLogitsForStep(step, decoderSeqLength))
        }

        override fun runBatched(
            batchedTokens: IntArray, numBeams: Int, actualSrcLength: Int, decoderSeqLength: Int
        ): Array<Array<FloatArray>> {
            return Array(numBeams) { b ->
                val beamTokens = IntArray(decoderSeqLength)
                System.arraycopy(batchedTokens, b * decoderSeqLength, beamTokens, 0, decoderSeqLength)
                val tokenCount = beamTokens.count { it != PAD_IDX }
                buildLogitsForStep(tokenCount - 1, decoderSeqLength)
            }
        }

        override fun cleanup() {}

        private fun buildLogitsForStep(step: Int, seqLen: Int): Array<FloatArray> {
            return Array(seqLen) { pos ->
                val logits = FloatArray(vocabSize) { -100f }
                if (pos == step) {
                    if (step < targetWord.length) {
                        // Emit next character of target word
                        val charIdx = 4 + (targetWord[step] - 'a')
                        logits[charIdx] = 10f
                    } else {
                        // Emit EOS after word is complete
                        logits[EOS_IDX] = 10f
                    }
                }
                logits
            }
        }
    }

    // --- Integration tests ---

    @Test
    fun `resampler to beam search pipeline produces valid predictions`() {
        // 1. Resample a swipe trajectory
        val rawSwipe = Array(50) { i ->
            floatArrayOf(i.toFloat() * 6f, 100f + i.toFloat(), 6f, 1f)
        }
        val resampled = SwipeResampler.resample(rawSwipe, 30, SwipeResampler.ResamplingMode.DISCARD)
        assertThat(resampled).isNotNull()
        assertThat(resampled!!).hasLength(30)

        // 2. Run beam search with trie-guided decoding
        val decoder = WordEmittingDecoder("hello")
        val engine = BeamSearchEngine(
            decoderSession = decoder, tokenizer = tokenizer,
            vocabTrie = trie, beamWidth = 5, maxLength = 10
        )
        val results = engine.search(actualSrcLength = resampled.size)

        // 3. Verify beam search produced "hello" (the only trie-valid word matching decoder output)
        assertThat(results).isNotEmpty()
        assertThat(results[0].word).isEqualTo("hello")
    }

    @Test
    fun `beam search with trie filters invalid words`() {
        // Decoder tries to produce "helo" (not in trie)
        val decoder = WordEmittingDecoder("helo")
        val engine = BeamSearchEngine(
            decoderSession = decoder, tokenizer = tokenizer,
            vocabTrie = trie, beamWidth = 5, maxLength = 10
        )
        val results = engine.search(actualSrcLength = 10)

        // "helo" is not in trie, so it should be filtered out
        // Trie allows: h->e->l->d (held) or h->e->l->p (help) but not h->e->l->o
        val words = results.map { it.word }
        assertThat(words).doesNotContain("helo")
    }

    @Test
    fun `vocabulary utils scores beam search output correctly`() {
        val decoder = WordEmittingDecoder("hello")
        val engine = BeamSearchEngine(
            decoderSession = decoder, tokenizer = tokenizer,
            vocabTrie = trie, beamWidth = 5, maxLength = 10
        )
        val results = engine.search(actualSrcLength = 10)
        assertThat(results).isNotEmpty()

        // Score the top result against dictionary word
        val topWord = results[0].word
        val matchQuality = VocabularyUtils.calculateMatchQuality(topWord, "hello")
        assertThat(matchQuality).isWithin(0.001f).of(1.0f) // Exact match
    }

    @Test
    fun `accent normalizer works with beam search output`() {
        val decoder = WordEmittingDecoder("the")
        val engine = BeamSearchEngine(
            decoderSession = decoder, tokenizer = tokenizer,
            vocabTrie = trie, beamWidth = 5, maxLength = 10
        )
        val results = engine.search(actualSrcLength = 10)
        assertThat(results).isNotEmpty()

        val word = results[0].word
        // AccentNormalizer should handle standard ASCII output
        val normalized = AccentNormalizer.normalize(word)
        assertThat(normalized).isEqualTo(word)
    }

    @Test
    fun `full pipeline sequential vs batched consistency`() {
        val decoder1 = WordEmittingDecoder("work")
        val decoder2 = WordEmittingDecoder("work")

        val seqEngine = BeamSearchEngine(
            decoderSession = decoder1, tokenizer = tokenizer,
            vocabTrie = trie, beamWidth = 3, maxLength = 10
        )
        val batchEngine = BeamSearchEngine(
            decoderSession = decoder2, tokenizer = tokenizer,
            vocabTrie = trie, beamWidth = 3, maxLength = 10
        )

        val seqResults = seqEngine.search(actualSrcLength = 10, useBatched = false)
        val batchResults = batchEngine.search(actualSrcLength = 10, useBatched = true)

        assertThat(seqResults.map { it.word }).isEqualTo(batchResults.map { it.word })
    }

    @Test
    fun `trie vocabulary stats consistent with inserted words`() {
        val (wordCount, _) = trie.getStats()
        assertThat(wordCount).isEqualTo(13) // We inserted 13 words

        // Verify trie prefix queries work
        assertThat(trie.hasPrefix("hel")).isTrue()
        assertThat(trie.hasPrefix("xyz")).isFalse()
        assertThat(trie.containsWord("hello")).isTrue()
        assertThat(trie.containsWord("helo")).isFalse()
    }

    @Test
    fun `match quality degrades gracefully for similar words`() {
        // Test that VocabularyUtils scoring aligns with trie-constrained beam search
        val decoder = WordEmittingDecoder("help")
        val engine = BeamSearchEngine(
            decoderSession = decoder, tokenizer = tokenizer,
            vocabTrie = trie, beamWidth = 5, maxLength = 10
        )
        val results = engine.search(actualSrcLength = 10)
        assertThat(results).isNotEmpty()

        val topWord = results[0].word
        // Score against the target and a similar word
        val exactScore = VocabularyUtils.calculateMatchQuality(topWord, "help")
        val similarScore = VocabularyUtils.calculateMatchQuality(topWord, "held")
        val differentScore = VocabularyUtils.calculateMatchQuality(topWord, "world")

        assertThat(exactScore).isGreaterThan(similarScore)
        assertThat(similarScore).isGreaterThan(differentScore)
    }
}
