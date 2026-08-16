package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.KeyboardData

/**
 * WP9 R-1 step 7 — swipe engine selection, mode-based (v1.1).
 *
 * The user-facing `swipe_engine_mode` pref (Settings → Swipe Typing → "Prediction Engine")
 * selects a [Mode]; routing then depends only on the layout:
 *  - [Mode.NEURAL] (default): QWERTY-Latin → [Engine.NEURAL]; every other layout →
 *    [Engine.NONE] (the long-standing behavior — the transformer is QWERTY-trained, see
 *    `Config.isSwipeTypingSupportedForLayout`'s #9 rationale).
 *  - [Mode.HYBRID]: QWERTY-Latin → [Engine.NEURAL]; every other layout → [Engine.GEOMETRIC]
 *    (best of both: transformer accuracy where it was trained, SHARK2 everywhere else).
 *  - [Mode.GEOMETRIC]: ALL layouts → [Engine.GEOMETRIC] (including QWERTY — measured ~84%
 *    top-1 on synthetic QWERTY in the spec; useful for comparison and battery-lean decoding).
 *  - [Mode.CTC]: ANY Latin-script layout → [Engine.CTC]; non-Latin/unknown-script layouts
 *    → [Engine.GEOMETRIC] (so selecting CTC never removes swipe anywhere).
 *    LAYOUT dimension (gate widened 2026-08-15): unlike the QWERTY-trained transformer,
 *    the CTC encoder is layout-agnostic — key geometry is a model input (`layout_keys`) —
 *    and the ship model was validated on alt-layouts during training: dvorak 89.87 /
 *    dvorak-app-geometry 88.98 top-1 (3 seeds, en lexicon — the SAME `en_enhanced` trie +
 *    tunedV2 λ the app ships). So Latin non-QWERTY layouts (Dvorak, Colemak, AZERTY, …)
 *    route CTC instead of geometric (~77% top-1), a ~13 pt gain for English users there.
 *    A Latin layout the adapter cannot serve (missing an a–z letter → no `CtcLayout`)
 *    falls through to geometric at dispatch time via `CtcEngineAdapter.supportsLayout` —
 *    the router stays layout-metadata-only and doesn't see key inventories.
 *    LANGUAGE dimension (audit M1): the v1 CTC model/lexicon is English-only, and language
 *    is runtime state the layout-only router deliberately doesn't see —
 *    `InputCoordinator.performCtcSwipeTyping` reads the active language BEFORE dispatch and
 *    falls through per layout: the SAME neural flow [Engine.NEURAL] takes on QWERTY, the
 *    geometric path on non-QWERTY Latin (the transformer cannot decode that geometry).
 *    Net ctc semantics: CTC(en, any full-a–z Latin layout) / neural(non-en QWERTY) /
 *    geometric(everywhere else) — never less coverage than HYBRID or than pre-widening ctc.
 *
 * A single engine owns each swipe end-to-end. Scores are NEVER compared across engines —
 * geometric scores are engine-relative softmax×1000 (see `SwipeDecodingEngine` KDoc) and
 * numerically incomparable to neural confidences. Rank-merge experiments (e.g. QWERTY-en
 * short-word blending, measured in the spec's head-to-head) are explicitly phase-2 and
 * would need their own oracle round.
 *
 * Both engines emit the same candidate shape and feed the SAME single pipeline seam:
 * `SuggestionHandler.handleSwipePredictionResults` (via `InputCoordinator.handlePredictionResults`),
 * so the geometric path inherits the password guard, possessive augmentation, shift/caps
 * transform, and THE commit engine with zero geo-specific presentation code.
 */
object SwipeEngineRouter {

    enum class Engine {
        /** QWERTY-trained transformer path (existing behavior). */
        NEURAL,

        /** Pure-JVM geometric (SHARK2) engine. */
        GEOMETRIC,

        /** CTC trie-beam engine (ONNX encoder + pure-JVM beam, `swipe/ctc/`). */
        CTC,

        /** No engine — non-QWERTY layout in NEURAL-only mode. */
        NONE,
    }

    /** User-selected engine mode (the `swipe_engine_mode` pref). */
    enum class Mode {
        /** Neural on QWERTY, no swipe elsewhere (default — pre-geo behavior). */
        NEURAL,

        /** Neural on QWERTY, geometric on every other layout. */
        HYBRID,

        /** Geometric on ALL layouts, including QWERTY. */
        GEOMETRIC,

        /**
         * G5: CTC on ANY Latin-script layout when the active language is English
         * (gate widened 2026-08-15 — the encoder is layout-agnostic and was
         * validated on alt-layouts: dvorak 89.87 / dvorak-app-geometry 88.98
         * top-1, 3 seeds, the shipped en lexicon+λ), geometric on non-Latin
         * layouts — and NEURAL for non-English languages on QWERTY (audit M1:
         * the language fallthrough lives in `InputCoordinator.performCtcSwipeTyping`
         * because language is runtime state this layout-only router doesn't see;
         * non-English on non-QWERTY Latin falls through to geometric there too).
         * Selecting CTC therefore never yields less coverage than [HYBRID].
         */
        CTC;

        companion object {
            /**
             * Parse the pref string. Unknown/legacy values fall back to [NEURAL] (the
             * default) — never crash the router on a corrupted pref.
             */
            @JvmStatic
            fun fromPref(value: String?): Mode = when (value?.lowercase()) {
                "hybrid" -> HYBRID
                "geometric" -> GEOMETRIC
                "ctc" -> CTC
                else -> NEURAL
            }
        }
    }

    /**
     * Route a swipe on [layout] under [mode]. A null layout is the unresolved SystemLayout,
     * which defaults to QWERTY → the neural-capable branch (mirrors
     * `Config.isSwipeTypingSupportedForLayout`).
     */
    @JvmStatic
    fun route(layout: KeyboardData?, mode: Mode): Engine {
        if (mode == Mode.GEOMETRIC) return Engine.GEOMETRIC
        // A null layout is unresolved SystemLayout → QWERTY-Latin default (mirrors
        // Config.isSwipeTypingSupportedForLayout's null contract) → the neural-capable branch.
        if (layout == null) return if (mode == Mode.CTC) Engine.CTC else Engine.NEURAL
        return route(layout.name, layout.script, mode)
    }

    /** String-based overload for pure-JVM tests (mirrors Config's testing overload). */
    @JvmStatic
    fun route(layoutName: String?, script: String?, mode: Mode): Engine {
        if (mode == Mode.GEOMETRIC) return Engine.GEOMETRIC
        if (Config.isSwipeTypingSupportedForLayout(layoutName, script)) {
            return if (mode == Mode.CTC) Engine.CTC else Engine.NEURAL
        }
        // Gate widening 2026-08-15 (ctc mode only): the CTC encoder is layout-agnostic
        // (key geometry is a model input) and was validated on alt-layouts (dvorak 89.87
        // top-1 — see the class KDoc), so ANY known-Latin layout routes CTC. The
        // dispatch-time CtcEngineAdapter.supportsLayout check guards letter-incomplete
        // Latin layouts back to geometric. Non-Latin/unknown scripts can never build an
        // a–z CtcLayout → geometric, unchanged.
        if (mode == Mode.CTC && isLatinScript(script)) return Engine.CTC
        return if (mode == Mode.HYBRID || mode == Mode.CTC) Engine.GEOMETRIC else Engine.NONE
    }

    /** Script-metadata Latin check (same case posture as Config's QWERTY-Latin gate). */
    private fun isLatinScript(script: String?): Boolean =
        script != null && script.equals("latin", ignoreCase = true)
}
