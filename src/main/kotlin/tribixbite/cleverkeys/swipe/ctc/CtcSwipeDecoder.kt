package tribixbite.cleverkeys.swipe.ctc

/**
 * Source of per-frame CTC log-emissions for a featurized swipe path.
 *
 * ## The production implementation
 * `swipe/OnnxCtcEmissionModel` implements this over the CleverKeys-trained encoder that
 * ships in the APK (`models/ctc_swipe_encoder.onnx`, 2.91 MB fp16-weight), and it is what
 * the DEFAULT `ctc` swipe mode decodes with — this seam is LIVE, not a placeholder.
 *
 * It stays an interface for two reasons: the ONNX session is an Android/ORT concern that
 * must not leak into this pure-JVM package, and keeping emissions injectable is what lets
 * the golden tests validate the whole featurize→beam chain against matrices frozen from
 * the Python port, with no model and no device.
 *
 * An implementation receives the encoder's `[2, 64]` path tensor plus the layout tensors
 * ([CtcFeaturizer.buildPaddedLayout]) and must return emissions already sliced to the
 * active alphabet (`[frames][alphabetSize + 1]`, blank last — see
 * [CtcEmissions.sliceFromHead]).
 */
interface CtcEmissionModel {
    /**
     * @param features `[2, 64]` flattened path tensor (`[x0..x63, y0..y63]`).
     * @param layout padded key-center + mask tensors for the on-screen layout.
     * @return per-frame log-emissions over the active alphabet + blank.
     */
    fun emit(features: FloatArray, layout: CtcFeaturizer.PaddedLayout): CtcEmissions
}

/**
 * End-to-end CTC swipe decoder facade: featurize raw touch → run the
 * [CtcEmissionModel] → beam-search the lexicon.
 *
 * ## NOT the production decode path — read this before wiring anything to it
 * `swipe/CtcEngineAdapter.decodeAsync` does NOT use this class. It inlines the same three
 * steps itself ([CtcFeaturizer.featurize] → `emit` → [CtcBeamDecoder]) because a real swipe
 * runs the encoder ONCE and then beams each active language against its own trie and preset,
 * which this single-lexicon facade cannot express. Consequently this class has zero
 * production callers and release R8 deletes it from the shipped DEX.
 *
 * What it IS: the convenience wrapper the pure-JVM harnesses decode through — `CtcReplayEngine`
 * (corpus replay / A-B scoring) and `CtcModuleTest` (the golden featurize→beam chain against
 * frozen emission matrices, no model and no device). Keep it in `main` rather than a test
 * source set only so those harnesses share the exact scoring composition the adapter uses.
 *
 * Corollary (ARC-059): do not measure or benchmark this class as a stand-in for swipe latency —
 * time [CtcBeamDecoder] in `decodeAsync`'s order instead, as `CtcLatencyGateTest` now does. And
 * if it ever diverges from what the adapter does, the adapter is right.
 *
 * See `docs/specs/ctc-swipe-engine.md` for how the CTC engine slots behind `swipe_engine_mode`.
 *
 * @property model the CTC emission source — `OnnxCtcEmissionModel` in production.
 * @property layout the on-screen layout geometry (alphabet + key centers).
 * @property trie the lexicon surface to decode against.
 * @property params scoring/beam parameters (a `scoring.json` preset).
 */
class CtcSwipeDecoder(
    private val model: CtcEmissionModel,
    private val layout: CtcLayout,
    private val trie: CtcLexiconTrie,
    private val params: CtcScoringParams,
) {
    data class DecodeResult(
        val candidates: List<CtcCandidate>,
        val greedy: String,
    )

    private val paddedLayout: CtcFeaturizer.PaddedLayout = CtcFeaturizer.buildPaddedLayout(layout)

    init {
        require(layout.alphabet.size == trie.alphabet.size) {
            "layout/trie alphabet size mismatch: ${layout.alphabet.size} vs ${trie.alphabet.size}"
        }
    }

    /**
     * Decode a raw touch path (already in the layout's [0,1] frame) into the top-k slate.
     *
     * @param px normalized x per raw sample.
     * @param py normalized y per raw sample.
     * @param pt timestamps (ms) per raw sample.
     */
    fun decode(px: DoubleArray, py: DoubleArray, pt: DoubleArray): List<CtcCandidate> {
        return decodeDetailed(px, py, pt).candidates
    }

    /** Decode while also exposing the unconstrained greedy surface used by bounded rescue. */
    fun decodeDetailed(px: DoubleArray, py: DoubleArray, pt: DoubleArray): DecodeResult {
        val features = CtcFeaturizer.featurize(px, py, pt)
        val emissions = model.emit(features, paddedLayout)
        return DecodeResult(
            candidates = CtcBeamDecoder.decode(emissions, trie, params),
            greedy = CtcBeamDecoder.greedy(emissions, layout.alphabet),
        )
    }
}
