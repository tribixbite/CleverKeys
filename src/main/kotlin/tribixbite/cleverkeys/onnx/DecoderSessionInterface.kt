package tribixbite.cleverkeys.onnx

/**
 * Abstraction over the ONNX decoder session for testability.
 *
 * Decouples BeamSearchEngine from ONNX runtime native libraries,
 * allowing beam search logic to be tested with fake/mock decoders
 * that return controlled logits.
 *
 * Production code uses [OrtDecoderSession] which wraps the real ONNX runtime.
 * Tests use [FakeDecoderSession] or similar test doubles.
 */
interface DecoderSessionInterface {

    /**
     * Run sequential decoder inference for a single beam.
     *
     * @param tokens Current token sequence for this beam (e.g. [SOS, 4, 7, 12])
     * @param actualSrcLength Actual non-padded encoder input length
     * @param decoderSeqLength Fixed decoder sequence length (e.g. 20)
     * @return 3D logits array [1][decoderSeqLength][vocabSize]
     */
    fun runSequential(
        tokens: IntArray,
        actualSrcLength: Int,
        decoderSeqLength: Int
    ): Array<Array<FloatArray>>

    /**
     * Run batched decoder inference for multiple beams simultaneously.
     *
     * @param batchedTokens Flattened token array [numBeams * decoderSeqLength]
     * @param numBeams Number of beams in the batch
     * @param actualSrcLength Actual non-padded encoder input length
     * @param decoderSeqLength Fixed decoder sequence length (e.g. 20)
     * @return 3D logits array [numBeams][decoderSeqLength][vocabSize]
     */
    fun runBatched(
        batchedTokens: IntArray,
        numBeams: Int,
        actualSrcLength: Int,
        decoderSeqLength: Int
    ): Array<Array<FloatArray>>

    /**
     * Release any cached resources (tensors, buffers).
     */
    fun cleanup()
}
