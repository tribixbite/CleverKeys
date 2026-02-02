package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for VocabularyUtils scoring and matching functions.
 */
class VocabularyUtilsTest {

    // =========================================================================
    // calculateCombinedScore tests
    // =========================================================================

    @Test
    fun `combined score with equal weights`() {
        val score = VocabularyUtils.calculateCombinedScore(
            confidence = 0.8f,
            frequency = 0.6f,
            boost = 1.0f,
            confidenceWeight = 0.5f,
            frequencyWeight = 0.5f
        )
        // (0.5 * 0.8 + 0.5 * 0.6) * 1.0 = 0.7
        assertThat(score).isWithin(0.001f).of(0.7f)
    }

    @Test
    fun `combined score with confidence bias`() {
        val score = VocabularyUtils.calculateCombinedScore(
            confidence = 0.9f,
            frequency = 0.3f,
            boost = 1.0f,
            confidenceWeight = 0.8f,
            frequencyWeight = 0.2f
        )
        // (0.8 * 0.9 + 0.2 * 0.3) * 1.0 = 0.78
        assertThat(score).isWithin(0.001f).of(0.78f)
    }

    @Test
    fun `combined score with boost factor`() {
        val score = VocabularyUtils.calculateCombinedScore(
            confidence = 0.5f,
            frequency = 0.5f,
            boost = 2.0f,
            confidenceWeight = 0.5f,
            frequencyWeight = 0.5f
        )
        // (0.5 * 0.5 + 0.5 * 0.5) * 2.0 = 1.0
        assertThat(score).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `combined score with zero confidence`() {
        val score = VocabularyUtils.calculateCombinedScore(
            confidence = 0.0f,
            frequency = 1.0f,
            boost = 1.0f,
            confidenceWeight = 0.5f,
            frequencyWeight = 0.5f
        )
        assertThat(score).isWithin(0.001f).of(0.5f)
    }

    // =========================================================================
    // fuzzyMatch tests
    // =========================================================================

    @Test
    fun `fuzzy match identical words`() {
        val result = VocabularyUtils.fuzzyMatch(
            word1 = "hello",
            word2 = "hello",
            charMatchThreshold = 0.8f,
            maxLengthDiff = 2,
            prefixLength = 1,
            minWordLength = 3
        )
        assertThat(result).isTrue()
    }

    @Test
    fun `fuzzy match similar words`() {
        val result = VocabularyUtils.fuzzyMatch(
            word1 = "hello",
            word2 = "hallo",
            charMatchThreshold = 0.8f,
            maxLengthDiff = 2,
            prefixLength = 1,
            minWordLength = 3
        )
        // 4/5 = 0.8 matches threshold
        assertThat(result).isTrue()
    }

    @Test
    fun `fuzzy match fails on different prefix`() {
        val result = VocabularyUtils.fuzzyMatch(
            word1 = "hello",
            word2 = "jello",
            charMatchThreshold = 0.8f,
            maxLengthDiff = 2,
            prefixLength = 1,
            minWordLength = 3
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `fuzzy match fails on length difference`() {
        val result = VocabularyUtils.fuzzyMatch(
            word1 = "hi",
            word2 = "hello",
            charMatchThreshold = 0.5f,
            maxLengthDiff = 2,
            prefixLength = 1,
            minWordLength = 2
        )
        // Length diff = 3, exceeds maxLengthDiff = 2
        assertThat(result).isFalse()
    }

    @Test
    fun `fuzzy match fails on minimum word length`() {
        val result = VocabularyUtils.fuzzyMatch(
            word1 = "hi",
            word2 = "ho",
            charMatchThreshold = 0.5f,
            maxLengthDiff = 2,
            prefixLength = 1,
            minWordLength = 3
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `fuzzy match with zero prefix length`() {
        val result = VocabularyUtils.fuzzyMatch(
            word1 = "test",
            word2 = "best",
            charMatchThreshold = 0.7f,
            maxLengthDiff = 2,
            prefixLength = 0,
            minWordLength = 3
        )
        // 3/4 = 0.75 >= 0.7, no prefix check
        assertThat(result).isTrue()
    }

    // =========================================================================
    // calculateLevenshteinDistance tests
    // =========================================================================

    @Test
    fun `levenshtein distance identical strings`() {
        val distance = VocabularyUtils.calculateLevenshteinDistance("hello", "hello")
        assertThat(distance).isEqualTo(0)
    }

    @Test
    fun `levenshtein distance empty strings`() {
        assertThat(VocabularyUtils.calculateLevenshteinDistance("", "")).isEqualTo(0)
        assertThat(VocabularyUtils.calculateLevenshteinDistance("abc", "")).isEqualTo(3)
        assertThat(VocabularyUtils.calculateLevenshteinDistance("", "xyz")).isEqualTo(3)
    }

    @Test
    fun `levenshtein distance single substitution`() {
        val distance = VocabularyUtils.calculateLevenshteinDistance("cat", "bat")
        assertThat(distance).isEqualTo(1)
    }

    @Test
    fun `levenshtein distance single insertion`() {
        val distance = VocabularyUtils.calculateLevenshteinDistance("cat", "cart")
        assertThat(distance).isEqualTo(1)
    }

    @Test
    fun `levenshtein distance single deletion`() {
        val distance = VocabularyUtils.calculateLevenshteinDistance("cart", "cat")
        assertThat(distance).isEqualTo(1)
    }

    @Test
    fun `levenshtein distance kitten to sitting`() {
        // Classic example: kitten -> sitten -> sittin -> sitting
        val distance = VocabularyUtils.calculateLevenshteinDistance("kitten", "sitting")
        assertThat(distance).isEqualTo(3)
    }

    @Test
    fun `levenshtein distance completely different`() {
        val distance = VocabularyUtils.calculateLevenshteinDistance("abc", "xyz")
        assertThat(distance).isEqualTo(3)
    }

    // =========================================================================
    // calculateMatchQuality tests
    // =========================================================================

    @Test
    fun `match quality identical words`() {
        val quality = VocabularyUtils.calculateMatchQuality("hello", "hello", useEditDistance = true)
        assertThat(quality).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `match quality with edit distance`() {
        // "hello" vs "hallo": distance = 1, max length = 5
        // quality = 1 - (1/5) = 0.8
        val quality = VocabularyUtils.calculateMatchQuality("hello", "hallo", useEditDistance = true)
        assertThat(quality).isWithin(0.001f).of(0.8f)
    }

    @Test
    fun `match quality without edit distance`() {
        // Position matching: h-h, e-a, l-l, l-l, o-o = 4/5 = 0.8
        val quality = VocabularyUtils.calculateMatchQuality("hello", "hallo", useEditDistance = false)
        assertThat(quality).isWithin(0.001f).of(0.8f)
    }

    @Test
    fun `match quality completely different words`() {
        val quality = VocabularyUtils.calculateMatchQuality("abc", "xyz", useEditDistance = true)
        // distance = 3, max = 3, quality = 0
        assertThat(quality).isWithin(0.001f).of(0.0f)
    }

    @Test
    fun `match quality default uses edit distance`() {
        val withParam = VocabularyUtils.calculateMatchQuality("test", "text", useEditDistance = true)
        val withDefault = VocabularyUtils.calculateMatchQuality("test", "text")
        assertThat(withDefault).isWithin(0.001f).of(withParam)
    }

    @Test
    fun `match quality different lengths with edit distance`() {
        // "cat" vs "cats": distance = 1, max = 4
        // quality = 1 - (1/4) = 0.75
        val quality = VocabularyUtils.calculateMatchQuality("cat", "cats", useEditDistance = true)
        assertThat(quality).isWithin(0.001f).of(0.75f)
    }

    @Test
    fun `match quality empty strings`() {
        // Both empty — perfect match (distance=0, both empty)
        val quality = VocabularyUtils.calculateMatchQuality("", "", useEditDistance = true)
        assertThat(quality).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `match quality different lengths without edit distance`() {
        // Position matching uses maxLen as denominator to penalize length mismatch
        // "cat" vs "cats": c-c, a-a, t-t = 3 matches / max(3,4) = 3/4 = 0.75
        val quality = VocabularyUtils.calculateMatchQuality("cat", "cats", useEditDistance = false)
        assertThat(quality).isWithin(0.001f).of(0.75f)
    }

    @Test
    fun `non-edit-distance penalizes substring match`() {
        // "cat" vs "caterpillar": 3 matches / max(3,11) = 3/11 ≈ 0.273
        val quality = VocabularyUtils.calculateMatchQuality("cat", "caterpillar", useEditDistance = false)
        assertThat(quality).isWithin(0.001f).of(3f / 11f)
    }

    @Test
    fun `non-edit-distance exact match returns 1`() {
        val quality = VocabularyUtils.calculateMatchQuality("hello", "hello", useEditDistance = false)
        assertThat(quality).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `non-edit-distance empty strings`() {
        val quality = VocabularyUtils.calculateMatchQuality("", "", useEditDistance = false)
        assertThat(quality).isWithin(0.001f).of(1.0f)
    }
}
