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
 * here would make lambda ~2 orders of magnitude too weak (study H5). Because [lambda] is
 * tied to the lexicon's frequency SCALE, the shipping preset is selected per language via
 * [presetFor] — see its KDoc for the measured per-scale values.
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
         * λ for the COMPRESSED `en_enhanced.json` byte scale (134–255, `ln f ∈ [4.9, 5.54]`)
         * — the value [tunedV2] was fitted and test-validated at.
         */
        const val LAMBDA_EN_JSON_SCALE = 4.0

        /**
         * λ for the CKDT `freq = max(1, 255 − rank)` scale (`ln f ∈ [0, 5.54]`, ~8× the
         * log spread of the en JSON scale, so frequency needs proportionally less weight).
         *
         * **Two independent sweeps landed here**, which is the strongest thing that can be
         * said for a decode constant: the Latin sweep behind [presetFor]
         * (`docs/eval/2026-08-15-ctc-per-language-lambda.md`, fr / de / es on the app
         * footing around [tunedV2]) and the earlier Cyrillic sweep behind [tunedRuCkdt]
         * (CleverKeys-ML `ctc/PHASE_J.md` §6.9, ru on the benchmark footing around E1).
         * Different script, different corpus, different lexicon build, different base
         * preset — same λ for the same frequency scale. That the value survives a change
         * of base is what makes it a property of the SCALE rather than of either footing.
         */
        const val LAMBDA_CKDT_SCALE = 2.0

        /**
         * The ship preset for [language] — [tunedV2]'s constants with λ selected by the
         * language's LEXICON SCALE, which is the thing λ is actually calibrated against.
         *
         * λ multiplies the trie's `ln(freq)` term, so it depends on how the lexicon's
         * frequencies are distributed, not on the language: `en_enhanced.json`'s
         * compressed 134–255 byte scores need λ = [LAMBDA_EN_JSON_SCALE]; a CKDT `.bin`
         * read at `freq = max(1, 255 − rank)` ([CtcCkdtLexicon]) spans the full 1–255
         * range and needs λ = [LAMBDA_CKDT_SCALE]. The mapping language → scale is
         * [CtcLanguageSupport.SUPPORTED]; an unsupported language falls back to the
         * English value (defense-in-depth — the adapter never decodes one).
         *
         * **Evidence** — `docs/eval/2026-08-15-ctc-per-language-lambda.md`: tune/confirm
         * split sweep over real FUTO human traces on the app's own bundled dictionaries.
         * The English control is monotone increasing to λ 4.0 on both halves (92.30 /
         * 92.72 t1), while every CKDT-scale corpus inverts that ordering — λ 4.0 is never
         * a tune-half winner and costs −1.5 to −3.2 pt on the confirm half for fr /
         * de-german / es. λ 2.0 wins the tune half in 3 of 4 corpora and independently
         * matches the earlier Cyrillic sweep (CleverKeys-ML `PHASE_J.md` §6.9), two
         * unrelated scripts converging on the same value for the same scale.
         *
         * Everything else (γ 0.9, β 0.25, α 0.0, γ_prune 0.25, β_prune 0.9882) is
         * language-INVARIANT — λ is the only constant the sweep varied.
         */
        fun presetFor(
            language: String?,
            beamWidth: Int = 100,
            topK: Int = 4,
        ): CtcScoringParams {
            val lambda = when (CtcLanguageSupport.sourceFor(language)) {
                CtcLanguageSupport.LexiconSource.CKDT_BIN -> LAMBDA_CKDT_SCALE
                CtcLanguageSupport.LexiconSource.EN_JSON, null -> LAMBDA_EN_JSON_SCALE
            }
            return tunedV2(beamWidth = beamWidth, topK = topK).copy(lambda = lambda)
        }

        /**
         * The Cyrillic λ datapoint, recorded on ITS OWN FOOTING: benchmark preset **E1**
         * (`1.05 / 1.1 / 0.2 / 0.3734 / 0.9882`) with **λ raised 1.1 → 2.0**.
         *
         * **This is deliberately NOT what [presetFor] returns for any language, and it is
         * not reachable from the decoder.** [presetFor] builds on [tunedV2] (the app
         * footing); this builds on E1 (the benchmark footing), and the two disagree on
         * γ (0.9 vs 1.05), β (0.25 vs 0.2) and γ_prune (0.25 vs 0.3734). Both sweeps
         * moved λ and only λ — but around different operating points, so neither result
         * transfers to the other's base. Shipping a Cyrillic path therefore starts with a
         * FOOTING decision (which base preset the ru trie is decoded at), not with a λ
         * lookup. It is kept here because the λ it establishes is the independent
         * corroboration cited by [LAMBDA_CKDT_SCALE], and that evidence should not live
         * only in a sibling repo.
         *
         * **Evidence** — CleverKeys-ML `ctc/PHASE_J.md` §6.9 / `ctc/APP_INTEGRATION_PLAN.md`
         * §7.1: tuned on ru val rows `0:4708`, confirmed on the untouched `4708:9416`,
         * over BOTH ru models, λ ∈ {1.1, 2.0, 3.0, 4.0}. λ 2.0 wins on every half of both
         * models and is worth **≈ +1.2 in-dict top-1** (75.73/76.70 → 76.91/77.92 for the
         * shippable synth-only model). The lever is model-independent — it lifted the joint
         * challenger by the same order — which is why it is recorded as a decode constant
         * rather than a property of one artifact. Scale rationale: the ru lexicon is the
         * importable `langpack-ru` CKDT v2 pack, whose per-word byte is `255 − rank`, a
         * compressed scale wanting a larger λ than the raw AOSP counts E1 was fitted on
         * (`ctc/PHASE_I.md` §7.4).
         *
         * **Evidence tier — read before quoting.** These are **val-only** numbers
         * (`eval_cyrillic.py`). No Cyrillic model was ever decoded on test-2400 and the
         * seal is spent permanently, so this can never be upgraded to test-validated.
         *
         * **Caveat that travels with λ:** no campaign evaluation included a user
         * dictionary, and λ multiplies the frequency term, so a larger λ amplifies
         * top-of-scale injected competitors. λ = 2.0 must be re-confirmed with user
         * dictionary entries present before any ru path ships (plan §7.1, §7.3).
         *
         * No Cyrillic CTC encoder ships today and `ru` is absent from
         * [CtcLanguageSupport.SUPPORTED], so the adapter can never build this preset.
         */
        fun tunedRuCkdt(beamWidth: Int = 100, topK: Int = 4): CtcScoringParams =
            CtcScoringParams(
                gamma = 1.05, lambda = LAMBDA_CKDT_SCALE, beta = 0.2, alpha = 0.0,
                gammaPrune = 0.3734, betaPrune = 0.9882,
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
