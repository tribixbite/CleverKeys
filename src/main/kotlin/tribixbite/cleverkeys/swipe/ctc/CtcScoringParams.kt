package tribixbite.cleverkeys.swipe.ctc

/**
 * Scoring parameters for the FUTO-style CTC trie beam decoder.
 *
 * These are a 1:1 port of FUTO's `models/scoring.json` (built into `swipe-library`,
 * keyed by the active model-combination signature — see the integration study §3d).
 * Every field maps directly onto a term in either the length-aware pruning key or the
 * final word score computed in [CtcBeamDecoder]:
 *
 * ```
 * prune_score = score / max(depth,1)^gammaPrune + betaPrune * depth      (per frame)
 * final_score = ctc / max(len,1)^gamma + weight * beta * len + lambda*logFreq  (per word)
 * ```
 *
 * The tiny [lambda] (0.006–0.022) is deliberate: it multiplies an AOSP-scale
 * `log_frequency ∈ [1,255]` (see [CtcLexiconTrie]), NOT a normalized `[0,1]` frequency,
 * so the effective frequency contribution is meaningful. Feeding a normalized frequency
 * here would make lambda ~2 orders of magnitude too weak (study H5).
 *
 * [alpha] is the context-LM rerank weight; it is carried for completeness and preset
 * fidelity but is UNUSED by [CtcBeamDecoder], which decodes the CTC core only. A future
 * `hungry_jellyfish`-style reranker (the retrain-fork add-on) would consume it.
 *
 * @property gamma GNMT length-normalization exponent applied to the raw CTC path score.
 * @property lambda Frequency-bonus weight (multiplies AOSP-scale log-frequency).
 * @property beta Per-character length bonus.
 * @property alpha Context-LM rerank weight (unused by the CTC core; see above).
 * @property gammaPrune Length-aware pruning exponent (active in single-stream mode).
 * @property betaPrune Length-aware pruning per-depth bonus.
 * @property beamWidth Maximum hypotheses retained per frame after pruning.
 * @property topK Number of final candidate words returned.
 */
data class CtcScoringParams(
    val gamma: Double,
    val lambda: Double,
    val beta: Double,
    val alpha: Double,
    val gammaPrune: Double,
    val betaPrune: Double,
    val beamWidth: Int,
    val topK: Int,
) {
    init {
        require(beamWidth >= 1) { "beamWidth must be >= 1, was $beamWidth" }
        require(topK >= 1) { "topK must be >= 1, was $topK" }
    }

    companion object {
        /**
         * `scoring.json` "encoder:honorable_sturgeon" — the encoder-only optimum, EN val
         * tuned. This is the preset the offline port ([scripts/futo_decoder_eval.py],
         * `futo_decoder_ceiling.py` config `beamB`) uses as its floor baseline.
         *
         * @param beamWidth commit-phase beam width (FUTO ships 300; caller-tunable).
         * @param topK size of the returned slate (FUTO ships 4 at commit).
         */
        fun encoderOnly(beamWidth: Int = 300, topK: Int = 4): CtcScoringParams =
            CtcScoringParams(
                gamma = 0.4056, lambda = 0.0176, beta = 0.9866, alpha = 0.0,
                gammaPrune = 0.4234, betaPrune = 1.0382,
                beamWidth = beamWidth, topK = topK,
            )

        /**
         * `scoring.json` "encoder:honorable_sturgeon decoder:magic_macaw" — the params
         * used once the per-layout refinement head (`magic_macaw`) has replaced the raw
         * emissions (`futo_decoder_ceiling.py` config `beamD`). Decode topology is
         * identical; only the scoring constants differ.
         */
        fun encoderDecoder(beamWidth: Int = 300, topK: Int = 4): CtcScoringParams =
            CtcScoringParams(
                gamma = 0.5949, lambda = 0.0134, beta = 0.7271, alpha = 0.0,
                gammaPrune = 0.1902, betaPrune = 1.2727,
                beamWidth = beamWidth, topK = topK,
            )

        /** `scoring.json` "fallback" — used when no signature-specific set matches. */
        fun fallback(beamWidth: Int = 300, topK: Int = 4): CtcScoringParams =
            CtcScoringParams(
                gamma = 0.4056, lambda = 0.0176, beta = 0.9866, alpha = 1.0,
                gammaPrune = 0.4234, betaPrune = 1.0382,
                beamWidth = beamWidth, topK = topK,
            )
    }
}
