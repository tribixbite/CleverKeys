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
 * **This runs on every CTC swipe.** `swipe/CtcEngineAdapter` builds the layout, the
 * per-language trie and the preset on the Android side, memoizes an instance of this class
 * per (layout, trie, beam width), and calls [decode] from its decode thread — so this is
 * the pure-JVM core of the default swipe engine, not a prototype. See
 * `docs/specs/ctc-swipe-engine.md` for how it slots behind `swipe_engine_mode`.
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
        val features = CtcFeaturizer.featurize(px, py, pt)
        val emissions = model.emit(features, paddedLayout)
        return CtcBeamDecoder.decode(emissions, trie, params)
    }
}
