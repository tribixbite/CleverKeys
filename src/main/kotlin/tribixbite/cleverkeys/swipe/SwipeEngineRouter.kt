package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.KeyboardData

/**
 * WP9 R-1 step 7 — layout-routed swipe engine selection (v1).
 *
 * Routing is LAYOUT-BASED ONLY:
 *  - QWERTY-Latin layouts → [Engine.NEURAL] (the transformer path, unchanged — it is
 *    QWERTY-trained, see `Config.isSwipeTypingSupportedForLayout`'s #9 rationale).
 *  - Every other layout → [Engine.GEOMETRIC] when the `geometric_swipe_engine` pref is
 *    enabled, else [Engine.NONE] (today's behavior: swipe silently disabled).
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

        /** Pure-JVM geometric (SHARK2) engine — non-QWERTY layouts, flag-gated. */
        GEOMETRIC,

        /** No engine — non-QWERTY layout with the geometric engine disabled. */
        NONE,
    }

    /**
     * Route a swipe on [layout]. A null layout is the unresolved SystemLayout, which
     * defaults to QWERTY → NEURAL (mirrors `Config.isSwipeTypingSupportedForLayout`).
     */
    @JvmStatic
    fun route(layout: KeyboardData?, geometricEnabled: Boolean): Engine {
        if (Config.isSwipeTypingSupportedForLayout(layout)) return Engine.NEURAL
        return if (geometricEnabled) Engine.GEOMETRIC else Engine.NONE
    }

    /** String-based overload for pure-JVM tests (mirrors Config's testing overload). */
    @JvmStatic
    fun route(layoutName: String?, script: String?, geometricEnabled: Boolean): Engine {
        if (Config.isSwipeTypingSupportedForLayout(layoutName, script)) return Engine.NEURAL
        return if (geometricEnabled) Engine.GEOMETRIC else Engine.NONE
    }
}
