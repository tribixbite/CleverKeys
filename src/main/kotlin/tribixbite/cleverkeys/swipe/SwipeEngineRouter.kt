package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.KeyboardData

/**
 * WP9 R-1 step 7 — swipe engine selection, mode-based (v1.2).
 *
 * The user-facing `swipe_engine_mode` pref (Settings → Swipe Typing → "Prediction Engine")
 * selects a [Mode]; routing then depends only on the layout:
 *  - [Mode.CTC] (default): ANY Latin-script layout → [Engine.CTC]; non-Latin/unknown-script
 *    layouts → [Engine.GEOMETRIC] (so selecting CTC never removes swipe anywhere).
 *  - [Mode.GEOMETRIC]: ALL layouts → [Engine.GEOMETRIC] (including QWERTY — measured ~84%
 *    top-1 on synthetic QWERTY in the spec; useful for comparison and battery-lean decoding).
 *
 * The QWERTY-trained transformer ("neural") that used to own the QWERTY-Latin family was
 * removed on 2026-08-18 — CTC beats it (89.31 vs 74.62 top-1 on test-2400, see
 * `docs/audit/2026-08-17-neural-vs-ctc-parity.md`) and geometric covers what CTC cannot
 * serve, so no (layout, language) cell is left without a swipe engine. Legacy pref values
 * (`"neural"`, `"hybrid"`) and anything else unrecognised land on the [Mode.CTC] default via
 * [Mode.fromPref] — backup import accepts arbitrary strings, so this must never crash.
 *
 * LAYOUT dimension (gate widened 2026-08-15): the CTC encoder is layout-agnostic — key
 * geometry is a model input (`layout_keys`) — and the ship model was validated on alt-layouts
 * during training: dvorak 91.82 / dvorak-app-geometry 91.10 top-1 (3 seeds, en lexicon — the
 * SAME `en_enhanced` trie + tunedV2 λ the app ships). So Latin non-QWERTY layouts (Dvorak,
 * Colemak, AZERTY, …) route CTC. A Latin layout the adapter cannot serve (missing an a–z
 * letter → no `CtcLayout`) falls through to geometric at dispatch time via
 * `CtcEngineAdapter.supportsLayout` — the router stays layout-metadata-only and doesn't see
 * key inventories.
 *
 * LANGUAGE dimension (audit M1): the CTC model is language-agnostic but the LEXICON and the λ
 * preset are per-language, so only validated languages are served —
 * `swipe/ctc/CtcLanguageSupport.SUPPORTED` = en, fr, de, es (2026-08-16). Language is runtime
 * state the layout-only router deliberately doesn't see —
 * `InputCoordinator.performCtcSwipeTyping` reads the active language BEFORE dispatch and falls
 * through to the geometric engine for every other language. Net ctc semantics:
 * CTC(supported language, a–z-complete Latin layout) / geometric(everywhere else).
 *
 * A single engine owns each swipe end-to-end. Scores are NEVER compared across engines —
 * geometric scores are engine-relative softmax×1000 (see `SwipeDecodingEngine` KDoc) and
 * numerically incomparable to CTC log-probabilities. Rank-merge experiments (e.g. QWERTY-en
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
        /** Pure-JVM geometric (SHARK2) engine. */
        GEOMETRIC,

        /** CTC trie-beam engine (ONNX encoder + pure-JVM beam, `swipe/ctc/`). */
        CTC,
    }

    /** User-selected engine mode (the `swipe_engine_mode` pref). */
    enum class Mode {
        /** Geometric on ALL layouts, including QWERTY. */
        GEOMETRIC,

        /**
         * CTC on ANY Latin-script layout when the active language is one CTC serves
         * (en/fr/de/es — `swipe/ctc/CtcLanguageSupport`; the encoder is layout-agnostic
         * and was validated on alt-layouts: dvorak 91.82 / dvorak-app-geometry 91.10
         * top-1, 3 seeds, the shipped en lexicon+λ; azerty 84.53 / qwertz 83.97 /
         * german 81.30 / spanish 89.53 for the 2026-08-16 language additions — all on
         * the `az26` arm, i.e. 26 slots, exactly what this app builds), geometric on
         * non-Latin layouts and for every language CTC does not serve (audit M1: the
         * language fallthrough lives in `InputCoordinator.performCtcSwipeTyping` because
         * language is runtime state this layout-only router doesn't see).
         */
        CTC;

        companion object {
            /**
             * Parse the pref string. Unknown/legacy values — including the removed
             * `"neural"` and `"hybrid"` modes and anything a backup import supplies —
             * fall back to [CTC] (the default); never crash the router on a corrupted pref.
             */
            @JvmStatic
            fun fromPref(value: String?): Mode = when (value?.lowercase()) {
                "geometric" -> GEOMETRIC
                else -> CTC
            }
        }
    }

    /**
     * Route a swipe on [layout] under [mode]. A null layout is the unresolved SystemLayout,
     * which defaults to `latn_qwerty_us` at runtime → Latin → the CTC branch.
     */
    @JvmStatic
    fun route(layout: KeyboardData?, mode: Mode): Engine {
        if (mode == Mode.GEOMETRIC) return Engine.GEOMETRIC
        // A null layout is unresolved SystemLayout → QWERTY-Latin default → CTC-eligible.
        if (layout == null) return Engine.CTC
        return route(layout.name, layout.script, mode)
    }

    /** String-based overload for pure-JVM tests. */
    @JvmStatic
    fun route(layoutName: String?, script: String?, mode: Mode): Engine {
        if (mode == Mode.GEOMETRIC) return Engine.GEOMETRIC
        // Gate widening 2026-08-15: the CTC encoder is layout-agnostic (key geometry is a
        // model input) and was validated on alt-layouts (dvorak 91.82 top-1 — see the class
        // KDoc), so ANY known-Latin layout routes CTC. The dispatch-time
        // CtcEngineAdapter.supportsLayout check guards letter-incomplete Latin layouts back
        // to geometric. Non-Latin/unknown scripts can never build an a–z CtcLayout →
        // geometric. `layoutName` is deliberately unused: it only ever mattered for the
        // QWERTY predicate the removed neural engine needed.
        return if (isLatinScript(script)) Engine.CTC else Engine.GEOMETRIC
    }

    /** Script-metadata Latin check. */
    private fun isLatinScript(script: String?): Boolean =
        script != null && script.equals("latin", ignoreCase = true)
}
