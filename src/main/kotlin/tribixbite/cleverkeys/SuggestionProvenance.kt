package tribixbite.cleverkeys

import tribixbite.cleverkeys.swipe.SwipeEngineRouter
import java.util.Locale
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

    /**
     * Context-only next-word prediction: learned n-gram LM first, shipped
     * static bigram seed for the slots it cannot fill (ARC-020 cold start).
     */
    NEXT_WORD,

    /** Autocorrect undo prompt (original + corrected word after an autocorrect). */
    AUTOCORRECT;

    companion object {
        /**
         * LEGACY FALLBACK: origin tag for the swipe path derived from the configured
         * engine mode ("geometric" | "ctc", plus any legacy/imported string, which
         * `SwipeEngineRouter.Mode.fromPref` resolves to ctc). Ctc mode routes
         * per-layout/-language at swipe time, so this is only an approximation (a
         * non-Latin swipe under ctc mode is actually geometric but tagged CTC).
         * Audit M2: production threads the ROUTED engine's origin
         * ([forRoutedEngine]) from InputCoordinator through
         * `handleSwipePredictionResults`; this derivation remains only as the null
         * default for callers that don't thread an origin.
         */
        fun forSwipeEngineMode(mode: String?): SuggestionOrigin =
            when (mode) {
                "geometric" -> GEOMETRIC
                else -> CTC
            }

        /**
         * Origin tag for the engine that ACTUALLY decoded a swipe (audit M2) —
         * the [SwipeEngineRouter.Engine] the router selected at dispatch time.
         */
        fun forRoutedEngine(engine: SwipeEngineRouter.Engine): SuggestionOrigin =
            when (engine) {
                SwipeEngineRouter.Engine.GEOMETRIC -> GEOMETRIC
                SwipeEngineRouter.Engine.CTC -> CTC
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
 *   (decoder scores, learned-LM probability, injections)
 * @property note structured origin-specific detail, rendered only after the
 *   Android layer supplies localized templates
 */
data class SuggestionMeta(
    val origin: SuggestionOrigin,
    val breakdown: ScoreBreakdown? = null,
    val note: ProvenanceNote? = null
)

/** Structured details which must not carry display-language text through the pipeline. */
sealed class ProvenanceNote {
    data object PromotedByLearnedContext : ProvenanceNote()
    data class NextWord(
        val context: String,
        val frequency: Int,
        val percent: Int,
        val fromStaticSeed: Boolean
    ) : ProvenanceNote()
    data object TypedWordUndo : ProvenanceNote()
    data class AutocorrectedFrom(val originalWord: String) : ProvenanceNote()
}

/** Structured personalization values used by the localized provenance formatter. */
data class PersonalizationDetails(
    val usageCount: Int,
    val frequencyScore: Float,
    val recencyScore: Float,
    val baseBoost: Float,
    val aggressionLabel: String,
    val aggressionMultiplier: Float,
    val finalBoost: Float
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

    /** Android-resolved wording. Templates use `String.format` argument syntax. */
    data class Strings(
        val locale: Locale,
        val originLabels: Map<SuggestionOrigin, String>,
        val unknown: String,
        val source: String,
        val score: String,
        val scoreComponents: String,
        val prefixMatch: String,
        val adaptation: String,
        val contextBuiltIn: String,
        val contextLearned: String,
        val contextWinnerLearned: String,
        val contextWinnerBuiltIn: String,
        val contextNoBoost: String,
        val contextBoostSetting: String,
        val personalization: String,
        val frequencyFactor: String,
        val finalScore: String,
        val personalUsage: String,
        val usageCount: String,
        val frequencyScore: String,
        val recencyScore: String,
        val baseBoost: String,
        val aggression: String,
        val finalBoost: String,
        val promotedByLearnedContext: String,
        val nextWordBuiltIn: String,
        val nextWordLearned: String,
        val typedWordUndo: String,
        val autocorrectedFrom: String
    )

    /** Short localized human label for an origin. */
    fun originLabel(origin: SuggestionOrigin, strings: Strings): String =
        strings.originLabels.getValue(origin)

    private fun Strings.render(template: String, vararg args: Any): String =
        String.format(locale, template, *args)

    private fun renderNote(note: ProvenanceNote, strings: Strings): String = when (note) {
        ProvenanceNote.PromotedByLearnedContext -> strings.promotedByLearnedContext
        is ProvenanceNote.NextWord -> if (note.fromStaticSeed) {
            strings.render(strings.nextWordBuiltIn, note.context)
        } else {
            strings.render(strings.nextWordLearned, note.context, note.frequency, note.percent)
        }
        ProvenanceNote.TypedWordUndo -> strings.typedWordUndo
        is ProvenanceNote.AutocorrectedFrom ->
            strings.render(strings.autocorrectedFrom, note.originalWord)
    }

    /**
     * Build the full provenance sheet text for one suggestion.
     *
     * @param word the displayed suggestion
     * @param meta origin/breakdown/note metadata (null → origin unknown)
     * @param barScore the score shown/stored in the bar's parallel score list
     * @param personalization structured usage values (null when unavailable)
     * @param strings wording pre-resolved by the Android resource layer
     */
    fun format(
        word: String,
        meta: SuggestionMeta?,
        barScore: Int?,
        personalization: PersonalizationDetails?,
        strings: Strings
    ): String = buildString {
        append("“").append(word).append("”\n")
        append(strings.render(strings.source, meta?.origin?.let { originLabel(it, strings) } ?: strings.unknown)).append('\n')
        barScore?.let { append(strings.render(strings.score, it)).append('\n') }
        meta?.note?.let { append(renderNote(it, strings)).append('\n') }

        meta?.breakdown?.let { b ->
            append('\n')
            append(strings.scoreComponents).append('\n')
            append(strings.render(strings.prefixMatch, b.prefixScore)).append('\n')
            append(strings.render(strings.adaptation, b.adaptationMultiplier)).append('\n')
            append(strings.render(strings.contextBuiltIn, b.staticContextMultiplier)).append('\n')
            append(strings.render(strings.contextLearned, b.dynamicContextBoost)).append('\n')
            append(
                when (b.contextWinner) {
                    ContextWinner.LEARNED -> strings.contextWinnerLearned
                    ContextWinner.STATIC -> strings.contextWinnerBuiltIn
                    ContextWinner.NONE -> strings.contextNoBoost
                }
            ).append('\n')
            append(strings.render(strings.contextBoostSetting, b.contextBoostSetting)).append('\n')
            append(strings.render(strings.personalization, b.personalizationBoost, b.personalizationMultiplier)).append('\n')
            append(strings.render(strings.frequencyFactor, b.frequencyFactor)).append('\n')
            append(strings.render(strings.finalScore, b.finalScore)).append('\n')
        }

        personalization?.let { p ->
            append('\n')
            append(strings.personalUsage).append('\n')
            append(strings.render(strings.usageCount, p.usageCount)).append('\n')
            append(strings.render(strings.frequencyScore, p.frequencyScore)).append('\n')
            append(strings.render(strings.recencyScore, p.recencyScore)).append('\n')
            append(strings.render(strings.baseBoost, p.baseBoost)).append('\n')
            append(strings.render(strings.aggression, p.aggressionLabel, p.aggressionMultiplier)).append('\n')
            append(strings.render(strings.finalBoost, p.finalBoost)).append('\n')
        }
    }.trimEnd()
}
