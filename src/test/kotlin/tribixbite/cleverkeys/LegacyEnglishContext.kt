package tribixbite.cleverkeys

import kotlin.math.max
import kotlin.math.min

/**
 * A FROZEN copy of the English context tables `BigramModel` shipped before the static context LM
 * (68 hand-authored pairs on a joint scale + a 20-word unigram table), with the exact
 * `getContextualProbability` / `getContextMultiplier` arithmetic they fed.
 *
 * Test-only and deliberately never updated: it is the STATUS-QUO arm of [StaticLmTapEvalTest],
 * so the "is the LM better than what shipped" comparison stays reproducible after the hardcoded
 * English tables are retired from production.
 */
object LegacyEnglishContext {
    private const val LAMBDA = 0.95f
    private const val MIN_PROB = 0.0001f

    /** `prev|next` → joint-scale value, exactly as hardcoded. */
    val PAIRS: Map<String, Float> = mapOf(
        "the|end" to 0.01f, "the|first" to 0.015f, "the|last" to 0.012f, "the|best" to 0.010f,
        "the|world" to 0.008f, "the|time" to 0.007f, "the|day" to 0.006f, "the|way" to 0.005f,
        "a|lot" to 0.02f, "a|little" to 0.015f, "a|few" to 0.012f, "a|good" to 0.010f,
        "a|great" to 0.008f, "a|new" to 0.007f, "a|long" to 0.006f, "to|be" to 0.03f,
        "to|have" to 0.02f, "to|do" to 0.015f, "to|go" to 0.012f, "to|get" to 0.010f,
        "to|make" to 0.008f, "to|see" to 0.007f, "of|the" to 0.05f, "of|course" to 0.02f,
        "of|all" to 0.015f, "of|this" to 0.012f, "of|his" to 0.010f, "of|her" to 0.008f,
        "in|the" to 0.04f, "in|a" to 0.02f, "in|this" to 0.015f, "in|order" to 0.012f,
        "in|fact" to 0.010f, "in|case" to 0.008f, "i|am" to 0.03f, "i|have" to 0.025f,
        "i|will" to 0.02f, "i|was" to 0.018f, "i|can" to 0.015f, "i|would" to 0.012f,
        "i|think" to 0.010f, "i|know" to 0.008f, "i|want" to 0.007f, "you|are" to 0.025f,
        "you|can" to 0.02f, "you|have" to 0.018f, "you|will" to 0.015f, "you|want" to 0.012f,
        "you|know" to 0.010f, "you|need" to 0.008f, "it|is" to 0.04f, "it|was" to 0.025f,
        "it|will" to 0.015f, "it|would" to 0.012f, "it|has" to 0.010f, "it|can" to 0.008f,
        "that|is" to 0.025f, "that|was" to 0.02f, "that|the" to 0.015f, "that|it" to 0.012f,
        "that|you" to 0.010f, "that|he" to 0.008f, "with|the" to 0.03f, "with|a" to 0.02f,
        "with|his" to 0.015f, "with|her" to 0.012f, "with|my" to 0.010f, "with|your" to 0.008f,
    )

    val UNIGRAMS: Map<String, Float> = mapOf(
        "the" to 0.07f, "be" to 0.04f, "to" to 0.035f, "of" to 0.03f, "and" to 0.028f,
        "a" to 0.025f, "in" to 0.022f, "that" to 0.02f, "have" to 0.018f, "i" to 0.017f,
        "it" to 0.015f, "for" to 0.014f, "not" to 0.013f, "on" to 0.012f, "with" to 0.011f,
        "he" to 0.010f, "as" to 0.009f, "you" to 0.009f, "do" to 0.008f, "at" to 0.008f,
    )

    /** Every word these tables can give a non-uniform multiplier. */
    val WORDS: Set<String> = PAIRS.keys.flatMap { it.split('|') }.toSet() + UNIGRAMS.keys

    /** `BigramModel.getContextMultiplier` over the frozen tables (bigram context: last word). */
    fun multiplier(word: String, previous: String?): Float {
        if (previous == null) return 1.0f
        val w = word.lowercase()
        val bigram = PAIRS["${previous.lowercase()}|$w"] ?: 0.0f
        val unigram = UNIGRAMS[w] ?: MIN_PROB
        val contextual = max(LAMBDA * bigram + (1 - LAMBDA) * unigram, MIN_PROB)
        return min(max(contextual / unigram, 0.1f), 10.0f)
    }
}
