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
 *  - [Mode.CTC]: QWERTY-Latin → [Engine.CTC]; every other layout → [Engine.GEOMETRIC]
 *    (same non-QWERTY coverage as HYBRID, so selecting CTC never removes swipe elsewhere).
 *    LANGUAGE dimension (audit M1): the v1 CTC model/lexicon is English-only, and language
 *    is runtime state the layout-only router deliberately doesn't see —
 *    `InputCoordinator.performCtcSwipeTyping` reads the active language BEFORE dispatch and
 *    falls through to the SAME neural flow [Engine.NEURAL] takes when it isn't English.
 *    Net ctc semantics: CTC(en QWERTY) / neural(non-en QWERTY) / geometric(non-QWERTY) —
 *    never less coverage than HYBRID.
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
         * G5: CTC on QWERTY-Latin (the layouts the shipped encoder was trained
         * for) when the active language is English, geometric on every other
         * layout — and NEURAL for non-English languages on QWERTY (audit M1:
         * the language fallthrough lives in `InputCoordinator.performCtcSwipeTyping`
         * because language is runtime state this layout-only router doesn't see).
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
        if (Config.isSwipeTypingSupportedForLayout(layout)) {
            return if (mode == Mode.CTC) Engine.CTC else Engine.NEURAL
        }
        return if (mode == Mode.HYBRID || mode == Mode.CTC) Engine.GEOMETRIC else Engine.NONE
    }

    /** String-based overload for pure-JVM tests (mirrors Config's testing overload). */
    @JvmStatic
    fun route(layoutName: String?, script: String?, mode: Mode): Engine {
        if (mode == Mode.GEOMETRIC) return Engine.GEOMETRIC
        if (Config.isSwipeTypingSupportedForLayout(layoutName, script)) {
            return if (mode == Mode.CTC) Engine.CTC else Engine.NEURAL
        }
        return if (mode == Mode.HYBRID || mode == Mode.CTC) Engine.GEOMETRIC else Engine.NONE
    }
}
