package tribixbite.cleverkeys.swipe.ctc

/**
 * Frozen inputs for [CtcOnnxLatencyBenchmarkTest] — the exact `en_qwerty` geometry and the
 * exact realistic swipe trace the CleverKeys-ML golden fixture uses, so the on-device
 * latency numbers are measured on the same tensor content the laptop harness measured.
 *
 * Provenance: `CleverKeys-ML/ctc/artifacts/ctc_model_golden.json`
 * (sha256 `a18ea58cd662b0e18b6daadaf417361f93fd0b146ce6478d4d6a62e7e185fa8a`, 140,204 B) —
 *  - [LAYOUT_CX]/[LAYOUT_CY] are the fixture's top-level `layout` block (letters `a..z`,
 *    i.e. emission-column order, centers already in the model's `[0,1]` frame);
 *  - [PATH_X]/[PATH_Y]/[PATH_T] are the `model_keyboard` beam case's 85 raw touch samples
 *    (a full-length "keyboard" trace at a constant 60 Hz — the longest, most beam-expensive
 *    case in the fixture, deliberately chosen as the worst case for the decode-path timing).
 *
 * These are *inputs*, not expectations: this test measures time, it does not assert
 * numerics. Bit-parity of [CtcFeaturizer]/[CtcBeamDecoder] against the Python port is the
 * job of the pure-JVM `CtcParityTest`.
 */
internal object CtcBenchFixture {

    /** Emission-column order for `en_qwerty`: alphabetical a..z. */
    val ALPHABET: CharArray = "abcdefghijklmnopqrstuvwxyz".toCharArray()

    /** Per-key center x in the model's [0,1] frame, aligned to [ALPHABET]. */
    val LAYOUT_CX: FloatArray = floatArrayOf(
        0.100467287f, 0.600467265f, 0.400467277f, 0.300467283f, 0.250000000f, 0.400467277f,
        0.500467300f, 0.600467265f, 0.750000000f, 0.700467288f, 0.800467312f, 0.900467277f,
        0.800467312f, 0.700467288f, 0.850000024f, 0.949999988f, 0.050000001f, 0.349999994f,
        0.200467288f, 0.449999988f, 0.649999976f, 0.500467300f, 0.150000006f, 0.300467283f,
        0.550000012f, 0.200467288f,
    )

    /** Per-key center y in the model's [0,1] frame, aligned to [ALPHABET]. */
    val LAYOUT_CY: FloatArray = floatArrayOf(
        0.500000000f, 0.833333313f, 0.833333313f, 0.500000000f, 0.166666672f, 0.500000000f,
        0.500000000f, 0.500000000f, 0.166666672f, 0.500000000f, 0.500000000f, 0.500000000f,
        0.833333313f, 0.833333313f, 0.166666672f, 0.166666672f, 0.166666672f, 0.166666672f,
        0.500000000f, 0.166666672f, 0.166666672f, 0.833333313f, 0.166666672f, 0.833333313f,
        0.166666672f, 0.833333313f,
    )

    /** Raw sample x in the model's [0,1] frame (85 samples, "keyboard"). */
    val PATH_X: DoubleArray = doubleArrayOf(
        0.800467312, 0.754595041, 0.708722770, 0.662850499, 0.616978168, 0.571105957,
        0.525233626, 0.479361385, 0.433489084, 0.387616813, 0.341744572, 0.295872271,
        0.250000000, 0.275000006, 0.300000012, 0.324999988, 0.350000024, 0.375000000,
        0.400000006, 0.425000012, 0.450000018, 0.475000024, 0.500000000, 0.524999976,
        0.550000012, 0.554205596, 0.558411241, 0.562616825, 0.566822410, 0.571028054,
        0.575233638, 0.579439223, 0.583644867, 0.587850451, 0.592056036, 0.596261680,
        0.600467265, 0.621261656, 0.642056048, 0.662850440, 0.683644831, 0.704439223,
        0.725233674, 0.746028066, 0.766822457, 0.787616849, 0.808411241, 0.829205632,
        0.850000024, 0.787538946, 0.725077868, 0.662616849, 0.600155771, 0.537694693,
        0.475233644, 0.412772596, 0.350311518, 0.287850440, 0.225389421, 0.162928343,
        0.100467287, 0.121261679, 0.142056078, 0.162850469, 0.183644861, 0.204439253,
        0.225233644, 0.246028036, 0.266822428, 0.287616819, 0.308411211, 0.329205602,
        0.349999994, 0.345872283, 0.341744542, 0.337616801, 0.333489090, 0.329361379,
        0.325233638, 0.321105927, 0.316978186, 0.312850475, 0.308722734, 0.304594994,
        0.300467283,
    )

    /** Raw sample y in the model's [0,1] frame (85 samples, "keyboard"). */
    val PATH_Y: DoubleArray = doubleArrayOf(
        0.500000000, 0.472222209, 0.444444448, 0.416666687, 0.388888896, 0.361111104,
        0.333333343, 0.305555582, 0.277777791, 0.250000000, 0.222222239, 0.194444448,
        0.166666672, 0.166666672, 0.166666672, 0.166666672, 0.166666672, 0.166666672,
        0.166666672, 0.166666672, 0.166666672, 0.166666672, 0.166666672, 0.166666672,
        0.166666672, 0.222222224, 0.277777791, 0.333333313, 0.388888896, 0.444444418,
        0.500000000, 0.555555522, 0.611111104, 0.666666627, 0.722222209, 0.777777791,
        0.833333313, 0.777777791, 0.722222209, 0.666666627, 0.611111104, 0.555555582,
        0.500000000, 0.444444448, 0.388888896, 0.333333343, 0.277777791, 0.222222209,
        0.166666672, 0.194444448, 0.222222224, 0.250000000, 0.277777791, 0.305555552,
        0.333333313, 0.361111104, 0.388888896, 0.416666657, 0.444444418, 0.472222209,
        0.500000000, 0.472222209, 0.444444448, 0.416666687, 0.388888896, 0.361111104,
        0.333333343, 0.305555582, 0.277777791, 0.250000000, 0.222222239, 0.194444448,
        0.166666672, 0.194444448, 0.222222224, 0.250000000, 0.277777791, 0.305555552,
        0.333333313, 0.361111104, 0.388888896, 0.416666657, 0.444444418, 0.472222209,
        0.500000000,
    )

    /** Raw sample timestamps in ms (85 samples at a constant 60 Hz, 0..1400 ms). */
    val PATH_T: DoubleArray = DoubleArray(85) { it * (1000.0 / 60.0) }
}
