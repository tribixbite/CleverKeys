package tribixbite.cleverkeys.swipe

/**
 * Finger-occlusion compensation — the ONE place `finger_occlusion_offset` becomes a
 * view-pixel Y shift, shared by both swipe adapters (ARC-005).
 *
 * The fingertip hides the key it is over, so touches tend to land ABOVE the key the user
 * aimed at. The pref corrects that by shifting where a swipe is READ relative to the
 * fingertip. It is a signed PERCENT OF ONE KEY ROW so it means the same thing at any
 * keyboard height, and signed because the correction runs both ways: a positive value
 * moves the read point DOWN in view coordinates (for traces that land above the keys), a
 * negative value moves it UP.
 *
 * The setting is engine-agnostic in the UI (`gesture_finger_occlusion_*`, Gesture Tuning),
 * and it is a property of the finger and the screen rather than of a decoder — so both
 * engines must apply the SAME shift to the SAME trace. Until 2026-08-28 only
 * [CtcEngineAdapter] did, which made the slider a dead knob for every geometric-served
 * cell (non-Latin script, letter-incomplete layout, CTC-unsupported language, or
 * `swipe_engine_mode=geometric`).
 *
 * Both adapters therefore derive the shift from the same two facts about the live layout:
 * the LETTER-KEY BOUNDING BOX height in view px, and how many keyboard rows those letter
 * keys occupy. The box is the same rect-bounds union both adapters already compute from
 * [tribixbite.cleverkeys.a11y.KeyboardGeometry.computeKeyRects], so on any layout both
 * engines can serve, the two shifts are identical by construction.
 *
 * Deliberately knob-free and Android-free: the pure `swipe.geometric` / `swipe.ctc` engines
 * never see the pref — the adapters apply the shift at ingest, before handing the trace to
 * the engine, exactly like every other user knob that crosses the impurity boundary.
 *
 * Range is enforced upstream ([tribixbite.cleverkeys.Config] clamps to −25..25); this
 * object applies whatever it is given and only guards against degenerate geometry.
 */
object FingerOcclusion {

    /**
     * Letter rows spanned by a full a–z Latin layout — the row count [CtcEngineAdapter]
     * passes, since its a–z gate only admits layouts whose letter keys are the three
     * QWERTY-shaped rows. [GeometricEngineAdapter] counts its own letter rows instead: it
     * serves layouts this constant would be wrong for.
     */
    const val LATIN_LETTER_ROWS = 3

    /**
     * The signed view-pixel Y shift to add to every raw trace sample.
     *
     * @param offsetPercent `Config.finger_occlusion_offset` — percent of one key row,
     *   positive to read the swipe lower on the keyboard than the finger reported.
     * @param letterBoxHeightPx height in view px of the letter keys' bounding box.
     * @param letterRowCount keyboard rows those letter keys occupy (≥ 1).
     * @return 0f for the default (0) offset and for degenerate geometry, so the common case
     *   costs nothing and a layout with no usable box can never shift a trace.
     */
    fun yShiftPx(offsetPercent: Int, letterBoxHeightPx: Float, letterRowCount: Int): Float {
        if (offsetPercent == 0 || letterBoxHeightPx <= 0f || letterRowCount <= 0) return 0f
        val rowHeightPx = letterBoxHeightPx / letterRowCount
        return rowHeightPx * (offsetPercent / 100f)
    }
}
