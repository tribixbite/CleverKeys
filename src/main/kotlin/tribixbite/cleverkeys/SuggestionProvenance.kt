package tribixbite.cleverkeys

import tribixbite.cleverkeys.swipe.SwipeEngineRouter
import kotlin.math.ln1p
import kotlin.math.max

/**
 * Pipeline-transparency data model (audit 2026-08-06 §2, Task B): per-suggestion
 * provenance so users can see WHICH stage produced each suggestion and WHY it
 * scored the way it did.
 *
 * - [SuggestionOrigin] tags every bar entry at creation time (the categories are
 *   all distinguishable where the candidate is injected and were previously
 *   anonymous by the time they reached the bar — §2.2).
 * - [ScoreBreakdown] carries every per-signal component of
 *   `WordPredictor.calculateUnifiedScore`, produced by the pure [UnifiedScore]
 *   combiner so the displayed breakdown can never drift from the real score.
 * - [SuggestionMeta] rides alongside the bar's parallel words/scores lists.
 * - [ProvenanceFormatter] renders the long-press provenance sheet text.
 *
 * Everything in this file is pure JVM (unit-tested in SuggestionProvenanceTest).
 */

/** Which pipeline stage produced a suggestion (audit §2.3 data model). */
enum class SuggestionOrigin {
    /** Neural ONNX beam-search swipe output. */
    NEURAL_BEAM,

    /** Geometric swipe-decoder output. */
    GEOMETRIC,

    /** CTC trie-beam swipe-decoder output (G5 `ctc` engine mode). */
    CTC,

    /** Dictionary prefix completion of the typed partial (WordPredictor). */
    DICTIONARY_PREFIX,

    /** Contraction injection ("dont" → "don't", paired variants). */
    CONTRACTION,

    /** Possessive augmentation of a swipe prediction ("cat" → "cat's"). */
    POSSESSIVE,

    /** #42 exact-typed-word tap-to-add entry. */
    EXACT_ADD,

    /** Context-only next-word prediction from the learned n-gram LM. */
    NEXT_WORD,

    /** Autocorrect undo prompt (original + corrected word after an autocorrect). */
    AUTOCORRECT;

    companion object {
        /**
         * LEGACY FALLBACK: origin tag for the swipe path derived from the configured
         * engine mode ("neural" | "hybrid" | "geometric" | "ctc"). Hybrid and ctc
         * route per-layout/-language at swipe time, so this is only an approximation
         * (a non-QWERTY swipe under ctc mode is actually geometric but tagged CTC).
         * Audit M2: production threads the ROUTED engine's origin
         * ([forRoutedEngine]) from InputCoordinator through
         * `handleSwipePredictionResults`; this derivation remains only as the null
         * default for callers that don't thread an origin.
         */
        fun forSwipeEngineMode(mode: String?): SuggestionOrigin =
            when (mode) {
                "geometric" -> GEOMETRIC
                "ctc" -> CTC
                else -> NEURAL_BEAM
            }

        /**
         * Origin tag for the engine that ACTUALLY decoded a swipe (audit M2) —
         * the [SwipeEngineRouter.Engine] the router selected at dispatch time.
         * [SwipeEngineRouter.Engine.NONE] never produces suggestions; mapping it
         * to NEURAL_BEAM keeps this total without inventing a bar-reachable tag.
         */
        fun forRoutedEngine(engine: SwipeEngineRouter.Engine): SuggestionOrigin =
            when (engine) {
                SwipeEngineRouter.Engine.GEOMETRIC -> GEOMETRIC
                SwipeEngineRouter.Engine.CTC -> CTC
                SwipeEngineRouter.Engine.NEURAL, SwipeEngineRouter.Engine.NONE -> NEURAL_BEAM
            }
    }
}

/** Which context model won the static-vs-learned combination (audit §2.2). */
enum class ContextWinner { STATIC, LEARNED, NONE }

/**
 * Per-signal components of one unified score (audit §2.3). Field semantics
 * mirror `WordPredictor.calculateUnifiedScore` exactly — see [UnifiedScore.combine],
 * the single implementation both the hot loop and this breakdown flow through.
 */
data class ScoreBreakdown(
    val prefixScore: Int,
    val adaptationMultiplier: Float,
    val staticContextMultiplier: Float,
    val dynamicContextBoost: Float,
    val contextWinner: ContextWinner,
    /** Raw personalization boost (0–6) BEFORE the multiplier conversion. */
    val personalizationBoost: Float,
    /** 1 + boost×weight/4 — the multiplier actually applied. */
    val personalizationMultiplier: Float,
    val frequencyFactor: Float,
    /** The user's context-boost slider value applied to the combined context signal. */
    val contextBoostSetting: Float,
    val finalScore: Int
)

/**
 * Provenance metadata for one suggestion-bar entry.
 *
 * @property origin which stage produced the suggestion
 * @property breakdown per-signal scores when the origin flows through the
 *   unified scorer (dictionary-prefix path); null for origins scored elsewhere
 *   (neural beam confidence, learned-LM probability, injections)
 * @property note origin-specific human-readable detail (e.g. the learned
 *   next-word statistics: "after 'want': seen 14×, 63%")
 */
data class SuggestionMeta(
    val origin: SuggestionOrigin,
    val breakdown: ScoreBreakdown? = null,
    val note: String? = null
)

/**
 * THE unified-score formula (single source of truth). `WordPredictor.
 * calculateUnifiedScore` resolves the raw signals and delegates here, so the
 * hot-path score and the transparency breakdown are one implementation.
 */
object UnifiedScore {

    /** `context_source` pref values (audit §3.2-2). */
    const val SOURCE_BOTH = "both"
    const val SOURCE_LEARNED_ONLY = "learned_only"
    const val SOURCE_STATIC_ONLY = "static_only"

    /**
     * Combine all prediction signals into a final score + full breakdown.
     *
     * Formula (unchanged from the pre-transparency implementation):
     * `prefixScore × adaptation × personalizationMult × (1 + (contextMult−1)×contextBoost) × freqFactor`
     * where `contextMult` is chosen per [contextSource] (both → max(static, learned)),
     * `personalizationMult = 1 + boost×weight/4`, and
     * `freqFactor = 1 + ln1p(frequency / frequencyScale)`.
     *
     * @param prefixScore base prefix-match quality (0 short-circuits to a zero score)
     * @param adaptationMultiplier selection-adaptation multiplier (1.0 = neutral)
     * @param staticContextMultiplier shipped BigramModel multiplier (1.0 = neutral)
     * @param dynamicContextBoost learned ContextModel boost (1.0 = neutral)
     * @param contextSource "both" | "learned_only" | "static_only"
     * @param personalizationBoost raw 0–6 personalization boost (0 when disabled)
     * @param personalizationWeight continuous strength 0–2 (audit §3.2-1)
     * @param frequency dictionary frequency of the candidate
     * @param frequencyScale `prediction_frequency_scale` setting
     * @param contextBoost `prediction_context_boost` setting
     */
    fun combine(
        prefixScore: Int,
        adaptationMultiplier: Float,
        staticContextMultiplier: Float,
        dynamicContextBoost: Float,
        contextSource: String,
        personalizationBoost: Float,
        personalizationWeight: Float,
        frequency: Int,
        frequencyScale: Float,
        contextBoost: Float
    ): ScoreBreakdown {
        val contextMultiplier = when (contextSource) {
            SOURCE_LEARNED_ONLY -> dynamicContextBoost
            SOURCE_STATIC_ONLY -> staticContextMultiplier
            else -> max(staticContextMultiplier, dynamicContextBoost)
        }

        // Which of the two context models actually supplied the applied signal
        // (the max() at the old WordPredictor:1761, now made inspectable).
        val winner = when {
            contextMultiplier <= 1.0f -> ContextWinner.NONE
            contextSource == SOURCE_LEARNED_ONLY -> ContextWinner.LEARNED
            contextSource == SOURCE_STATIC_ONLY -> ContextWinner.STATIC
            dynamicContextBoost >= staticContextMultiplier -> ContextWinner.LEARNED
            else -> ContextWinner.STATIC
        }

        val personalizationMultiplier = 1.0f + (personalizationBoost * personalizationWeight / 4.0f)
        val frequencyFactor = 1.0f + ln1p((frequency / frequencyScale).toDouble()).toFloat()

        val finalScore = prefixScore *
            adaptationMultiplier *
            personalizationMultiplier *
            (1.0f + (contextMultiplier - 1.0f) * contextBoost) *
            frequencyFactor

        return ScoreBreakdown(
            prefixScore = prefixScore,
            adaptationMultiplier = adaptationMultiplier,
            staticContextMultiplier = staticContextMultiplier,
            dynamicContextBoost = dynamicContextBoost,
            contextWinner = winner,
            personalizationBoost = personalizationBoost,
            personalizationMultiplier = personalizationMultiplier,
            frequencyFactor = frequencyFactor,
            contextBoostSetting = contextBoost,
            finalScore = finalScore.toInt()
        )
    }
}

/**
 * Renders the long-press provenance sheet content (audit §2.3 Tier 1).
 * Pure string building — the display surface (SuggestionBar popup) stays dumb.
 */
object ProvenanceFormatter {

    /** Short human label for an origin. */
    fun originLabel(origin: SuggestionOrigin): String = when (origin) {
        SuggestionOrigin.NEURAL_BEAM -> "Neural swipe (beam search)"
        SuggestionOrigin.GEOMETRIC -> "Geometric swipe decoder"
        SuggestionOrigin.CTC -> "CTC swipe (trie beam)"
        SuggestionOrigin.DICTIONARY_PREFIX -> "Dictionary prefix match"
        SuggestionOrigin.CONTRACTION -> "Contraction injection"
        SuggestionOrigin.POSSESSIVE -> "Possessive form"
        SuggestionOrigin.EXACT_ADD -> "Exact typed word (tap to add)"
        SuggestionOrigin.NEXT_WORD -> "Next-word prediction (learned)"
        SuggestionOrigin.AUTOCORRECT -> "Autocorrect prompt"
    }

    /**
     * Build the full provenance sheet text for one suggestion.
     *
     * @param word the displayed suggestion
     * @param meta origin/breakdown/note metadata (null → origin unknown)
     * @param barScore the score shown/stored in the bar's parallel score list
     * @param personalizationExplanation `PersonalizationEngine.explainBoost()`
     *   text (null when the engine is unavailable)
     */
    fun format(
        word: String,
        meta: SuggestionMeta?,
        barScore: Int?,
        personalizationExplanation: String?
    ): String = buildString {
        append("“").append(word).append("”\n")
        append("Source: ").append(meta?.origin?.let { originLabel(it) } ?: "Unknown").append('\n')
        barScore?.let { append("Score: ").append(it).append('\n') }
        meta?.note?.let { append(it).append('\n') }

        meta?.breakdown?.let { b ->
            append('\n')
            append("Score components:\n")
            append("• Prefix match: ${b.prefixScore}\n")
            append("• Adaptation: ${"%.2f".format(b.adaptationMultiplier)}×\n")
            append("• Context (built-in): ${"%.2f".format(b.staticContextMultiplier)}×\n")
            append("• Context (learned): ${"%.2f".format(b.dynamicContextBoost)}×\n")
            append(
                when (b.contextWinner) {
                    ContextWinner.LEARNED -> "• Context winner: your learned patterns\n"
                    ContextWinner.STATIC -> "• Context winner: built-in model\n"
                    ContextWinner.NONE -> "• Context: no boost\n"
                }
            )
            append("• Context boost setting: ${"%.1f".format(b.contextBoostSetting)}×\n")
            append(
                "• Personalization: ${"%.2f".format(b.personalizationBoost)} → " +
                    "${"%.2f".format(b.personalizationMultiplier)}×\n"
            )
            append("• Frequency factor: ${"%.2f".format(b.frequencyFactor)}×\n")
            append("= Final score: ${b.finalScore}\n")
        }

        personalizationExplanation?.let {
            append('\n')
            append("Personal usage:\n")
            append(it)
            append('\n')
        }
    }.trimEnd()
}
