package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.StaticContextLmFixtures.dequantised

/**
 * `BigramModel` as the adapter over [StaticContextLm]: once an LM is installed for the active
 * language, the tap multiplier and the next-word seed come from it; other languages, and the
 * pre-load state, keep the hardcoded tables. Uses a fresh instance per test (the singleton is
 * process-wide and would leak installs between tests). `setLanguage` logs through `android.util.Log`,
 * which pure JVM lacks, so language keying is exercised by installing for a NON-active language.
 */
class BigramModelStaticLmTest {

    private val lm = StaticContextLm.parse(
        StaticContextLmFixtures.encode(
            unigrams = mapOf("want" to 0.001, "to" to 0.03, "a" to 0.025, "you" to 0.02, "go" to 0.002),
            table = mapOf("want" to mapOf("to" to 0.6, "a" to 0.05), "you" to mapOf("go" to 0.01)),
        )
    )

    private fun model(): BigramModel = BigramModel()

    @Test
    fun `multiplier is the clamped LM ratio once installed`() {
        val m = model()
        m.installStaticLm("en", lm)
        // a|want: 0.05 / 0.025 = 2 (and only the LAST context word counts).
        assertThat(m.getContextMultiplier("a", listOf("i", "want"))).isWithin(1e-2f).of(dequantised(0.05) / dequantised(0.025))
        // Unlisted word → backoff ratio, which is < 1 here and inside the clamp.
        val unlisted = m.getContextMultiplier("go", listOf("want"))
        assertThat(unlisted).isLessThan(1f)
        assertThat(unlisted).isAtLeast(BigramModel.MIN_CONTEXT_MULTIPLIER)
        // No context → neutral, as before.
        assertThat(m.getContextMultiplier("to", emptyList())).isEqualTo(1f)
        // Unknown previous word → neutral.
        assertThat(m.getContextMultiplier("to", listOf("zebra"))).isEqualTo(1f)
    }

    @Test
    fun `huge ratios are clamped to the historic 10x ceiling`() {
        val m = model()
        m.installStaticLm("en", lm)
        // go|you: 0.01 / 0.002 = 5 — inside; to|want: 0.6/0.03 = 20 — clamped.
        assertThat(m.getContextMultiplier("to", listOf("want"))).isEqualTo(BigramModel.MAX_CONTEXT_MULTIPLIER)
    }

    @Test
    fun `seed serves LM continuations best first, curated pairs fill only empty slots`() {
        val m = model()
        m.installStaticLm("en", lm)
        val top = m.getPredictions("want", 2)
        assertThat(top.map { it.word }).containsExactly("to", "a").inOrder()
        assertThat(top[0].rank).isWithin(1e-6f).of(dequantised(0.6))
        // "you" has ONE LM continuation; the hardcoded pairs for "you" fill the rest, ranked below it.
        val you = m.getPredictions("you", 3)
        assertThat(you.first().word).isEqualTo("go")
        assertThat(you).hasSize(3)
        assertThat(you.drop(1).all { it.rank < you.first().rank }).isTrue()
        assertThat(you.map { it.word }.toSet()).hasSize(3)
    }

    @Test
    fun `an LM installed for another language does not touch English`() {
        val m = model() // active language: en
        m.installStaticLm("de", lm)
        assertThat(m.getContextMultiplier("end", listOf("the")))
            .isEqualTo(LegacyEnglishContext.multiplier("end", "the"))
        assertThat(m.getPredictions("want", 3).map { it.word }).doesNotContain("a")
    }

    @Test
    fun `tap multiplier and seed resolve REPLACE contraction keys to their display form`() {
        val withContraction = StaticContextLm.parse(
            StaticContextLmFixtures.encode(
                unigrams = mapOf("i" to 0.02, "don't" to 0.002, "know" to 0.001, "think" to 0.001),
                table = mapOf("i" to mapOf("don't" to 0.05), "don't" to mapOf("know" to 0.3, "think" to 0.05)),
            )
        ).withReplaceAliases(mapOf("dont" to "don't"))
        val m = model()
        m.installStaticLm("en", withContraction)
        // The tap candidate is the dictionary key `dont`; it must score as the don't the bar shows.
        val boost = m.getContextMultiplier("dont", listOf("i"))
        assertThat(boost).isGreaterThan(1f)
        assertThat(boost).isEqualTo(m.getContextMultiplier("don't", listOf("i")))
        // Context committed as the key reads the display form's continuations; output is display forms.
        assertThat(m.getContextMultiplier("know", listOf("dont")))
            .isEqualTo(m.getContextMultiplier("know", listOf("don't")))
        assertThat(m.getPredictions("dont", 2).map { it.word }).containsExactly("know", "think").inOrder()
    }

    @Test
    fun `without an LM the hardcoded tables are unchanged`() {
        val m = model()
        // Legacy table: the|end is listed → 10x clamp; see LegacyEnglishContext.
        assertThat(m.getContextMultiplier("end", listOf("the")))
            .isEqualTo(LegacyEnglishContext.multiplier("end", "the"))
        assertThat(m.getContextMultiplier("zebra", listOf("the")))
            .isEqualTo(LegacyEnglishContext.multiplier("zebra", "the"))
    }
}
