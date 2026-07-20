package tribixbite.cleverkeys.swipe.geometric

/**
 * A single raw touch sample of a swipe trace.
 *
 * **Coordinate contract (normative).** [x] / [y] are RAW PIXELS in the
 * key-area-local frame — origin at the top-left of the key area, the same region
 * [LayoutGeometry] describes. They are NOT normalized here; the enclosing
 * [GeometricSwipeRequest] carries `keyAreaWidthPx` / `keyAreaHeightPx` so the
 * [GesturePreprocessor] can compute `u = x / keyAreaWidthPx`,
 * `v = y / keyAreaHeightPx` ∈ [0,1]².
 *
 * `android.graphics.PointF` is deliberately NOT used (it is a stubbed landmine on
 * the JVM — every coord reads back as (0,0)); the pipeline works in plain floats.
 *
 * @param x horizontal position in key-area-local pixels.
 * @param y vertical position in key-area-local pixels.
 * @param tMillis capture timestamp in milliseconds (monotone within a trace);
 *   used only by trace synthesis/analysis, never by the deterministic decoder.
 */
data class TracePoint(val x: Float, val y: Float, val tMillis: Long)
