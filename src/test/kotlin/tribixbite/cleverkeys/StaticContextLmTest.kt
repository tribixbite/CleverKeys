package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import tribixbite.cleverkeys.StaticContextLmFixtures.dequantised

/**
 * The CKLM v1 loader contract, pinned against hand-built models (see [StaticContextLmFixtures]).
 *
 * The shipped asset is covered separately by [StaticLmAssetDriftTest]; these tests hold the
 * FORMAT and the arithmetic still while the asset is rebuilt.
 */
class StaticContextLmTest {

    private val unigrams = mapOf(
        "the" to 0.05, "to" to 0.03, "a" to 0.025, "i" to 0.02, "want" to 0.001,
        "go" to 0.002, "be" to 0.004, "end" to 0.0003, "café" to 0.00001, "don't" to 0.002,
        "know" to 0.001,
    )
    private val table = mapOf(
        "want" to mapOf("to" to 0.6, "a" to 0.1, "the" to 0.05),
        "to" to mapOf("be" to 0.08, "go" to 0.04, "the" to 0.07),
        "the" to mapOf("end" to 0.004, "café" to 0.001),
        "i" to mapOf("don't" to 0.05, "want" to 0.03),
        "don't" to mapOf("know" to 0.3),
    )
    private val lm = StaticContextLm.parse(StaticContextLmFixtures.encode(unigrams, table))

    @Test
    fun `header counts are read back`() {
        assertThat(lm.language).isEqualTo("en")
        assertThat(lm.vocabSize).isEqualTo(unigrams.size)
        assertThat(lm.prevCount).isEqualTo(table.size)
        assertThat(lm.pairCount).isEqualTo(table.values.sumOf { it.size })
    }

    @Test
    fun `listed pairs return the quantised conditional probability`() {
        assertThat(lm.probability("want", "to")).isWithin(1e-6f).of(dequantised(0.6))
        assertThat(lm.probability("to", "the")).isWithin(1e-6f).of(dequantised(0.07))
        // Case-insensitive on the query, like every context lookup.
        assertThat(lm.probability("Want", "TO")).isWithin(1e-6f).of(dequantised(0.6))
    }

    @Test
    fun `unigram is the quantised marginal and zero for unknown words`() {
        assertThat(lm.unigram("the")).isWithin(1e-6f).of(dequantised(0.05))
        assertThat(lm.unigram("zebra")).isEqualTo(0f)
        assertThat(lm.contains("zebra")).isFalse()
        assertThat(lm.contains("the")).isTrue()
    }

    @Test
    fun `non-ascii and apostrophe words resolve`() {
        assertThat(lm.probability("the", "café")).isWithin(1e-7f).of(dequantised(0.001))
        assertThat(lm.probability("i", "don't")).isWithin(1e-6f).of(dequantised(0.05))
        assertThat(lm.probability("don't", "know")).isWithin(1e-6f).of(dequantised(0.3))
    }

    @Test
    fun `top continuations are best first, ties by word, capped at k`() {
        val top = lm.top("to", 2)
        assertThat(top.map { it.word }).containsExactly("be", "the").inOrder()
        assertThat(top[0].probability).isWithin(1e-6f).of(dequantised(0.08))
        assertThat(lm.top("to", 10).map { it.word }).containsExactly("be", "the", "go").inOrder()
        assertThat(lm.top("zebra", 3)).isEmpty()
        assertThat(lm.top("to", 0)).isEmpty()
    }

    @Test
    fun `context ratio is P(w given prev) over P(w) for a listed pair`() {
        val expected = dequantised(0.6) / dequantised(0.03)
        assertThat(lm.contextRatio("want", "to")).isWithin(1e-3f).of(expected)
    }

    @Test
    fun `context ratio for an unlisted word is the previous word's backoff mass`() {
        // alpha(want) = (1 - sum listed P(v|want)) / (1 - sum listed P(v))
        val listedCond = dequantised(0.6) + dequantised(0.1) + dequantised(0.05)
        val listedMarg = dequantised(0.03) + dequantised(0.025) + dequantised(0.05)
        val alpha = (1f - listedCond) / (1f - listedMarg)
        assertThat(lm.contextRatio("want", "go")).isWithin(1e-4f).of(alpha)
        // A word the model has never seen gets the same backoff — it is unlisted too.
        assertThat(lm.contextRatio("want", "zebra")).isWithin(1e-4f).of(alpha)
        // And probability() is alpha x P(w) for a known unlisted word.
        assertThat(lm.probability("want", "go")).isWithin(1e-6f).of(alpha * dequantised(0.002))
    }

    @Test
    fun `listed probability has no backoff`() {
        assertThat(lm.listedProbability("want", "to")).isWithin(1e-6f).of(dequantised(0.6))
        assertThat(lm.listedProbability("want", "go")).isEqualTo(0f)
        assertThat(lm.listedProbability("zebra", "to")).isEqualTo(0f)
    }

    @Test
    fun `unknown previous word is neutral`() {
        assertThat(lm.contextRatio("zebra", "to")).isEqualTo(1f)
        assertThat(lm.contextRatio("", "to")).isEqualTo(1f)
        assertThat(lm.probability("zebra", "to")).isEqualTo(0f)
        assertThat(lm.hasContext("zebra")).isFalse()
        assertThat(lm.hasContext("want")).isTrue()
    }

    @Test
    fun `malformed input is rejected, never half-loaded`() {
        val good = StaticContextLmFixtures.encode(unigrams, table)
        assertThrows(IllegalArgumentException::class.java) {
            StaticContextLm.parse(StaticContextLmFixtures.encode(unigrams, table, magic = "CKLX"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StaticContextLm.parse(StaticContextLmFixtures.encode(unigrams, table, version = 2))
        }
        assertThrows(IllegalArgumentException::class.java) { StaticContextLm.parse(good.copyOf(20)) }
        assertThrows(IllegalArgumentException::class.java) { StaticContextLm.parse(good.copyOf(good.size - 1)) }
    }

    @Test
    fun `retained size accounts for every array it keeps`() {
        assertThat(lm.retainedBytes()).isGreaterThan(0L)
        assertThat(lm.retainedBytes()).isLessThan(64L * 1024)
    }
}
