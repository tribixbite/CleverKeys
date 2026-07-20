package tribixbite.cleverkeys.swipe.geometric

/**
 * TEST-ONLY shared helper for the Phase-6 adversarial tests: reconstructs a raw
 * [TracePoint] "ideal swipe" (key-area pixels) from a word's template variant, and maps
 * a word to its dictionary ordinal — the zero-noise reference trace used by
 * [GeoConfusablesTest], [GeoDoubleLetterTest], and [GeoShortWordTest].
 *
 * The trace is exactly the word's own resampled template polyline scaled into the pixel
 * frame, so running it back through the engine's REAL preprocessor exercises the whole
 * pipeline (preprocess → prune → score → rank), not the scorer in isolation. This is the
 * same construction [GeoDecoderCoreTest.idealTrace] uses, promoted to a shared helper so
 * the Phase-6 classes don't duplicate it.
 *
 * Carries no `Test` suffix so `TestRunnerListDriftTest` skips it (NFR-3 test hygiene).
 * Purity: `kotlin.*` + the pure engine only.
 */
object GeoIdealTrace {

    /** Aspect-2.0 pixel frame (matches [GeoLayoutFixtures.DEFAULT_ASPECT]) → clean round-trip. */
    const val WIDTH_PX = 1000f
    const val HEIGHT_PX = 500f

    /**
     * Reconstruct the ideal raw trace for [word] on [layout] from template variant
     * [variant] (0 = plain / loop-primary; the last variant is the loop for doubled
     * letters). Returns `null` if the word is untypeable.
     */
    fun of(
        word: String,
        layout: LayoutGeometry,
        generator: TemplateGenerator,
        variant: Int = 0,
    ): List<TracePoint>? {
        val t = generator.generate(word, 0, layout) ?: return null
        val v = variant.coerceIn(0, t.variantCount - 1)
        val n = t.pointCount
        val buf = FloatArray(n * 2)
        t.copyVariantInto(v, buf)
        val out = ArrayList<TracePoint>(n)
        for (k in 0 until n) {
            out.add(TracePoint(buf[2 * k] * WIDTH_PX, buf[2 * k + 1] * HEIGHT_PX, k.toLong()))
        }
        return out
    }

    /** A [GeometricSwipeRequest] wrapping [trace] in the shared pixel frame. */
    fun request(
        trace: List<TracePoint>, layout: LayoutGeometry, dict: GeometricDictionary,
    ): GeometricSwipeRequest = GeometricSwipeRequest(trace, WIDTH_PX, HEIGHT_PX, layout, dict)

    /** The dictionary ordinal of the exact (case-sensitive) [word], or -1 if absent. */
    fun ordinalOf(word: String, dict: GeometricDictionary): Int {
        for (i in 0 until dict.size) if (dict.word(i) == word) return i
        return -1
    }

    /** 0-based rank of [word] in [result]'s ordered words, or -1 if absent. */
    fun rankOf(word: String, result: tribixbite.cleverkeys.PredictionResult): Int {
        for (k in result.words.indices) if (result.words[k] == word) return k
        return -1
    }
}
