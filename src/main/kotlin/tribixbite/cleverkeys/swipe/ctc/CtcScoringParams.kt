package tribixbite.cleverkeys.swipe.ctc

import java.util.Locale

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

        /**
         * The SHIP preset for the CleverKeys-trained CTC encoder bundled as
         * `models/ctc_swipe_encoder.onnx` (CleverKeys-ML `ctc/` Phase M finalist
         * `phaseM_kd_fresh_w1`, fp16w). Fitted and validated on the APP-TRIE footing —
         * the STRIP trie built from `dictionaries/en_enhanced.json` (98,081 words after
         * a–z stripping) — and TEST-VALIDATED there: the fourth unsealing of test-2400
         * (`ctc/UNSEALING_4.md`) decoded this model at exactly this preset and cleared
         * all five trie-matched FUTO-ceiling bars on every seed (seed-mean top-1 89.31,
         * worst-seed top-5 margin +1.50).
         *
         * λ = 4.0 is deliberately far above the FUTO presets' 0.013–0.018: it multiplies
         * `en_enhanced.json`'s COMPRESSED 134–255 byte-score scale (`ln f ∈ [4.9, 5.54]`,
         * a much narrower log range than the raw AOSP counts FUTO's λ was fitted to), so
         * a large λ is required for frequency to carry comparable weight. The golden
         * fixture (`ctc_golden.json`, top-level `preset` `[0.9, 4.0, 0.25, 0.25,
         * 0.9882]`) is generated at this preset — model, preset and fixture always move
         * together.
         *
         * @param beamWidth commit-phase width. Every campaign accuracy number was
         *   decoded at width **100** (not FUTO's 300), so 100 is the default; the
         *   `ctc_beam_width` pref feeds this.
         * @param topK size of the returned slate.
         */
        fun tunedV2(beamWidth: Int = 100, topK: Int = 4): CtcScoringParams =
            CtcScoringParams(
                gamma = 0.9, lambda = 4.0, beta = 0.25, alpha = 0.0,
                gammaPrune = 0.25, betaPrune = 0.9882,
                beamWidth = beamWidth, topK = topK,
            )

        /**
         * The Cyrillic sibling of [tunedV2]: the benchmark **E1** preset
         * (`1.05 / 1.1 / 0.2 / 0.3734 / 0.9882`) with **λ raised 1.1 → 2.0**.
         *
         * λ is the only term that moves, and it moves because the FREQUENCY SCALE
         * differs, not because the model does. The app's Cyrillic lexicon is the
         * importable `langpack-ru` CKDT v2 pack, whose per-word byte is `255 − rank`
         * — a compressed scale that wants a larger λ than the raw AOSP counts E1 was
         * fitted on (CleverKeys-ML `ctc/PHASE_I.md` §7.4). The sweep is
         * `ctc/PHASE_J.md` §6.9 / `ctc/APP_INTEGRATION_PLAN.md` §7.1: tuned on ru val
         * rows `0:4708`, confirmed on the untouched `4708:9416`, over BOTH ru models,
         * λ ∈ {1.1, 2.0, 3.0, 4.0} — 2.0 wins on every half of both models and is worth
         * **≈ +1.2 in-dict top-1** (75.73/76.70 → 76.91/77.92 for the shippable
         * synth-only model). The lever is model-independent: it lifted the joint
         * challenger by the same order, which is why it is recorded as a decode
         * constant rather than a property of any one Cyrillic artifact.
         *
         * **Evidence tier — read before quoting.** These are **val-only** Cyrillic
         * numbers (`eval_cyrillic.py`); no Cyrillic model was ever decoded on
         * test-2400, and the seal is spent permanently. The preset is registered here
         * so the axis exists; **no Cyrillic CTC encoder ships in the app today**, and
         * [CtcEngineAdapter][tribixbite.cleverkeys.swipe.CtcEngineAdapter]'s en-only
         * gate stays closed until one does (integration plan O5).
         *
         * **Caveat that travels with λ:** no campaign evaluation included a user
         * dictionary, and λ multiplies the frequency term, so a larger λ amplifies
         * top-of-scale injected competitors. λ = 2.0 should be re-confirmed with user
         * dictionary entries present before the ru path ships (plan §7.1, §7.3).
         */
        fun tunedRuCkdt(beamWidth: Int = 100, topK: Int = 4): CtcScoringParams =
            CtcScoringParams(
                gamma = 1.05, lambda = 2.0, beta = 0.2, alpha = 0.0,
                gammaPrune = 0.3734, betaPrune = 0.9882,
                beamWidth = beamWidth, topK = topK,
            )

        /**
         * The per-language preset axis (integration-plan decision **O10**): the decode
         * preset is a property of the (emission model, lexicon FREQUENCY SCALE) pair,
         * and the app serves more than one such scale.
         *
         * | [language] | preset | scale it is fitted to |
         * |---|---|---|
         * | `ru` | [tunedRuCkdt] (λ 2.0) | langpack CKDT v2 `255 − rank` |
         * | anything else (incl. `en`) | [tunedV2] (λ 4.0) | bundled `en_enhanced.json` 134..255 |
         *
         * The default is deliberately [tunedV2] rather than a throw: the only decoder
         * the app can currently build is the English one (the en-only gate upstream),
         * so an unrecognized tag can only ever arrive as a bug, and a working English
         * decode is a better failure mode than a crashed swipe.
         *
         * @param language BCP-47-ish dictionary language tag (`en`, `ru`, `en-US`, …);
         *   only the primary subtag is significant and matching is case-insensitive.
         */
        fun presetFor(
            language: String,
            beamWidth: Int = 100,
            topK: Int = 4,
        ): CtcScoringParams {
            val primary = language
                .substringBefore('-').substringBefore('_')
                .lowercase(Locale.ROOT)
            return when (primary) {
                "ru" -> tunedRuCkdt(beamWidth = beamWidth, topK = topK)
                else -> tunedV2(beamWidth = beamWidth, topK = topK)
            }
        }

        /** `scoring.json` "fallback" — used when no signature-specific set matches. */
        fun fallback(beamWidth: Int = 300, topK: Int = 4): CtcScoringParams =
            CtcScoringParams(
                gamma = 0.4056, lambda = 0.0176, beta = 0.9866, alpha = 1.0,
                gammaPrune = 0.4234, betaPrune = 1.0382,
                beamWidth = beamWidth, topK = topK,
            )
    }
}
