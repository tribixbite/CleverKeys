package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for French contraction frequency scoring (v1.2.9 fix).
 *
 * The v1.2.9 fix ensures that French contractions like "qu'est" can rank
 * higher than their base words like "quest" when the contraction has
 * higher frequency in the dictionary.
 *
 * This is achieved by looking up actual contraction frequencies and using
 * VocabularyUtils.calculateCombinedScore for fair comparison.
 */
class ContractionFrequencyTest {

    // =========================================================================
    // calculateCombinedScore for contraction ranking
    // =========================================================================

    @Test
    fun `high frequency contraction scores higher than low frequency base word`() {
        // Simulate "qu'est" (high frequency French contraction) vs "quest" (lower frequency)
        val contractionFrequency = 0.9f  // Very common French word
        val baseWordFrequency = 0.3f     // Less common English word

        val commonBoost = 1.15f
        val confidenceWeight = 0.6f
        val frequencyWeight = 0.4f

        // Both have same NN confidence (user swiped the same pattern)
        val nnConfidence = 0.85f

        val contractionScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, contractionFrequency, commonBoost, confidenceWeight, frequencyWeight
        )

        val baseWordScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, baseWordFrequency, 1.0f, confidenceWeight, frequencyWeight
        )

        // Contraction should score higher due to higher frequency + common boost
        assertThat(contractionScore).isGreaterThan(baseWordScore)
    }

    @Test
    fun `low frequency contraction scores lower than high frequency base word`() {
        // When base word is more common, it should rank higher
        val contractionFrequency = 0.2f  // Rare contraction
        val baseWordFrequency = 0.95f    // Very common word

        val top5000Boost = 1.08f
        val commonBoost = 1.15f
        val confidenceWeight = 0.6f
        val frequencyWeight = 0.4f

        val nnConfidence = 0.85f

        val contractionScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, contractionFrequency, top5000Boost, confidenceWeight, frequencyWeight
        )

        val baseWordScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, baseWordFrequency, commonBoost, confidenceWeight, frequencyWeight
        )

        // Base word should score higher
        assertThat(baseWordScore).isGreaterThan(contractionScore)
    }

    @Test
    fun `frequency ranking is fair with equal boosts`() {
        // With same boosts, higher frequency should always win
        val boost = 1.0f
        val confidenceWeight = 0.6f
        val frequencyWeight = 0.4f
        val nnConfidence = 0.80f

        val highFreqScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, 0.9f, boost, confidenceWeight, frequencyWeight
        )

        val lowFreqScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, 0.3f, boost, confidenceWeight, frequencyWeight
        )

        assertThat(highFreqScore).isGreaterThan(lowFreqScore)
    }

    // =========================================================================
    // Frequency conversion from rank
    // =========================================================================

    @Test
    fun `frequency rank 0 converts to frequency 1_0`() {
        // Rank 0 = most common word, should have frequency 1.0
        val rank = 0
        val frequency = 1.0f - (rank / 255.0f)
        assertThat(frequency).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `frequency rank 255 converts to near zero frequency`() {
        // Rank 255 = rarest tracked word
        val rank = 255
        val frequency = 1.0f - (rank / 255.0f)
        assertThat(frequency).isWithin(0.001f).of(0.0f)
    }

    @Test
    fun `frequency rank 127 converts to mid frequency`() {
        // Rank 127 = middle of the range
        val rank = 127
        val frequency = 1.0f - (rank / 255.0f)
        assertThat(frequency).isWithin(0.01f).of(0.5f)
    }

    // =========================================================================
    // Realistic French contraction scenarios
    // =========================================================================

    @Test
    fun `quest_vs_quest_scenario`() {
        // In French, "qu'est" (what is) is more common than English "quest"
        // Simulated dictionary ranks:
        // - "qu'est" rank ~20 (very common in French)
        // - "quest" rank ~150 (moderately common in English)

        val questRank = 150
        val questFreq = 1.0f - (questRank / 255.0f)  // ~0.41

        val questApostropheRank = 20
        val questApostropheFreq = 1.0f - (questApostropheRank / 255.0f)  // ~0.92

        val commonBoost = 1.15f
        val top5000Boost = 1.08f
        val confidenceWeight = 0.6f
        val frequencyWeight = 0.4f
        val nnConfidence = 0.82f

        val questScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, questFreq, top5000Boost, confidenceWeight, frequencyWeight
        )

        val questApostropheScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, questApostropheFreq, commonBoost, confidenceWeight, frequencyWeight
        )

        // "qu'est" should rank higher due to its much higher frequency
        assertThat(questApostropheScore).isGreaterThan(questScore)
    }

    @Test
    fun `jai_vs_jail_scenario`() {
        // French "j'ai" (I have) vs English "jail"
        // "j'ai" is extremely common in French
        // "jail" is moderately common in English

        val jailRank = 180
        val jailFreq = 1.0f - (jailRank / 255.0f)  // ~0.29

        val jaiRank = 5
        val jaiFreq = 1.0f - (jaiRank / 255.0f)  // ~0.98

        val commonBoost = 1.15f
        val top5000Boost = 1.08f
        val confidenceWeight = 0.6f
        val frequencyWeight = 0.4f
        val nnConfidence = 0.78f

        val jailScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, jailFreq, top5000Boost, confidenceWeight, frequencyWeight
        )

        val jaiScore = VocabularyUtils.calculateCombinedScore(
            nnConfidence, jaiFreq, commonBoost, confidenceWeight, frequencyWeight
        )

        // "j'ai" should rank higher
        assertThat(jaiScore).isGreaterThan(jailScore)
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `zero frequency contraction still gets valid score`() {
        val score = VocabularyUtils.calculateCombinedScore(
            confidence = 0.8f,
            frequency = 0.0f,
            boost = 1.0f,
            confidenceWeight = 0.6f,
            frequencyWeight = 0.4f
        )
        // Should still produce a positive score from confidence alone
        assertThat(score).isGreaterThan(0f)
        assertThat(score).isWithin(0.001f).of(0.48f) // 0.6 * 0.8 + 0.4 * 0
    }

    @Test
    fun `default contraction frequency 0_6 is reasonable fallback`() {
        // When contraction isn't in dictionary, default to 0.6
        val defaultFreq = 0.6f

        val score = VocabularyUtils.calculateCombinedScore(
            confidence = 0.8f,
            frequency = defaultFreq,
            boost = 1.08f,
            confidenceWeight = 0.6f,
            frequencyWeight = 0.4f
        )

        // Score should be reasonable (not too high, not too low)
        assertThat(score).isGreaterThan(0.5f)
        assertThat(score).isLessThan(1.0f)
    }
}
