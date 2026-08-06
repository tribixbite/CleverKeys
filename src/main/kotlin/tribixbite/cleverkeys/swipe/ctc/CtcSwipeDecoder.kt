package tribixbite.cleverkeys.swipe.ctc

/**
 * Source of per-frame CTC log-emissions for a featurized swipe path.
 *
 * ## This is the retrain-fork boundary
 * FUTO's emissions come from a non-autoregressive CTC encoder (`honorable_sturgeon`),
 * optionally sharpened by a per-layout refinement head (`magic_macaw`) — a DIFFERENT
 * model family from CleverKeys' shipped autoregressive transformer. No such model exists
 * in this repo, and producing one is a hard fork (CTC retrain + ONNX/ExecuTorch export;
 * see `docs/specs/ctc-swipe-engine.md`, Phase B). Consequently there is intentionally NO
 * production implementation of this interface here: the featurizer, trie, and beam are all
 * complete and tested, but the module cannot decode a real swipe end-to-end until a CTC
 * model lands. Tests supply emissions directly (golden matrices frozen from the Python
 * port), which is exactly how the decode path is validated today.
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
 * End-to-end FUTO-style CTC swipe decoder facade: featurize raw touch → run the
 * [CtcEmissionModel] → beam-search the lexicon.
 *
 * This wires the three DONE pieces (featurizer, emission model seam, trie beam) into the
 * one call shape a future engine-selector `ctc` mode would invoke. It is deliberately
 * dead code today: without a [CtcEmissionModel] implementation (the retrain fork) it
 * cannot be constructed against a real model, so nothing in the IME references it. See
 * `docs/specs/ctc-swipe-engine.md` for how this slots behind `swipe_engine_mode`.
 *
 * @property model the CTC emission source (the missing piece — see [CtcEmissionModel]).
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
