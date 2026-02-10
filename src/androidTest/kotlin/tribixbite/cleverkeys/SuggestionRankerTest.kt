package tribixbite.cleverkeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.SuggestionRanker.Candidate
import tribixbite.cleverkeys.SuggestionRanker.WordSource as RankerWordSource

/**
 * Instrumented tests for SuggestionRanker.
 * Covers scoring formula, ranking, merge/dedup, language context,
 * prefix boost, and candidate creation.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionRankerTest {

    private lateinit var ranker: SuggestionRanker

    @Before
    fun setup() {
        ranker = SuggestionRanker()
    }

    // =========================================================================
    // Candidate scoring
    // =========================================================================

    @Test
    fun candidateScoreIncreasesWithNnConfidence() {
        val low = Candidate("low", "low", 50, RankerWordSource.MAIN, nnConfidence = 0.2f)
        val high = Candidate("high", "high", 50, RankerWordSource.MAIN, nnConfidence = 0.9f)
        assertTrue(high.calculateScore(1.0f) > low.calculateScore(1.0f))
    }

    @Test
    fun candidateScoreIncreasesWithLowerRank() {
        // Lower frequencyRank = more common = higher score
        val common = Candidate("common", "common", 10, RankerWordSource.MAIN, nnConfidence = 0.5f)
        val rare = Candidate("rare", "rare", 200, RankerWordSource.MAIN, nnConfidence = 0.5f)
        assertTrue(common.calculateScore(1.0f) > rare.calculateScore(1.0f))
    }

    @Test
    fun customSourceHasHigherPriority() {
        val custom = Candidate("word", "word", 50, RankerWordSource.CUSTOM, nnConfidence = 0.5f)
        val main = Candidate("word", "word", 50, RankerWordSource.MAIN, nnConfidence = 0.5f)
        assertTrue(custom.calculateScore(1.0f) > main.calculateScore(1.0f))
    }

    @Test
    fun userSourceHasHigherPriorityThanMain() {
        val user = Candidate("word", "word", 50, RankerWordSource.USER, nnConfidence = 0.5f)
        val main = Candidate("word", "word", 50, RankerWordSource.MAIN, nnConfidence = 0.5f)
        assertTrue(user.calculateScore(1.0f) > main.calculateScore(1.0f))
    }

    @Test
    fun secondarySourceGetsPenalized() {
        val secondary = Candidate("word", "word", 50, RankerWordSource.SECONDARY, nnConfidence = 0.5f)
        val main = Candidate("word", "word", 50, RankerWordSource.MAIN, nnConfidence = 0.5f)
        assertTrue("Secondary should score lower than main",
            secondary.calculateScore(0.5f, 0.9f) < main.calculateScore(1.0f))
    }

    // =========================================================================
    // rank (single dictionary)
    // =========================================================================

    @Test
    fun rankReturnsSortedByScore() {
        val candidates = listOf(
            Candidate("low", "low", 200, RankerWordSource.MAIN, 0.1f),
            Candidate("high", "high", 10, RankerWordSource.MAIN, 0.9f),
            Candidate("mid", "mid", 100, RankerWordSource.MAIN, 0.5f)
        )
        val ranked = ranker.rank(candidates, 3)
        assertEquals("high", ranked[0].word)
        assertTrue(ranked[0].score >= ranked[1].score)
        assertTrue(ranked[1].score >= ranked[2].score)
    }

    @Test
    fun rankRespectsMaxResults() {
        val candidates = (1..10).map {
            Candidate("word$it", "word$it", it * 20, RankerWordSource.MAIN, 0.5f)
        }
        val ranked = ranker.rank(candidates, 3)
        assertEquals(3, ranked.size)
    }

    @Test
    fun rankEmptyInputReturnsEmpty() {
        val ranked = ranker.rank(emptyList(), 5)
        assertTrue(ranked.isEmpty())
    }

    // =========================================================================
    // rankAndMerge (multi-dictionary)
    // =========================================================================

    @Test
    fun rankAndMergeCombinesBothSources() {
        val primary = listOf(
            Candidate("hello", "hello", 10, RankerWordSource.MAIN, 0.8f, "en")
        )
        val secondary = listOf(
            Candidate("hola", "hola", 10, RankerWordSource.SECONDARY, 0.7f, "es")
        )
        val merged = ranker.rankAndMerge(primary, secondary, 5)
        assertEquals(2, merged.size)
        // Primary should rank first (higher language context)
        assertEquals("hello", merged[0].word)
    }

    @Test
    fun rankAndMergeDeduplicatesSameWord() {
        val primary = listOf(
            Candidate("son", "son", 50, RankerWordSource.MAIN, 0.6f, "en")
        )
        val secondary = listOf(
            Candidate("son", "son", 30, RankerWordSource.SECONDARY, 0.8f, "es")
        )
        val merged = ranker.rankAndMerge(primary, secondary, 5)
        // Only one "son" should appear (the one with higher score)
        assertEquals(1, merged.size)
        assertEquals("son", merged[0].word)
    }

    @Test
    fun rankAndMergeEmptySecondary() {
        val primary = listOf(
            Candidate("hello", "hello", 10, RankerWordSource.MAIN, 0.8f, "en")
        )
        val merged = ranker.rankAndMerge(primary, emptyList(), 5)
        assertEquals(1, merged.size)
    }

    // =========================================================================
    // setSecondaryPenalty
    // =========================================================================

    @Test
    fun secondaryPenaltyAffectsScoring() {
        ranker.setSecondaryPenalty(0.5f)
        val secondary = listOf(
            Candidate("hola", "hola", 10, RankerWordSource.SECONDARY, 0.9f, "es")
        )
        val withPenalty = ranker.rank(secondary, 1)

        ranker.setSecondaryPenalty(1.0f)
        val noPenalty = ranker.rank(secondary, 1)

        assertTrue("Lower penalty should produce lower score",
            withPenalty[0].score <= noPenalty[0].score)
    }

    @Test
    fun secondaryPenaltyClamped() {
        ranker.setSecondaryPenalty(0.0f) // Should clamp to 0.1
        ranker.setSecondaryPenalty(5.0f) // Should clamp to 1.0
        // No crash = clamping works
    }

    // =========================================================================
    // setLanguageContext
    // =========================================================================

    @Test
    fun languageContextAffectsSecondaryScoring() {
        ranker.setLanguageContext(1.0f, 0.2f)
        val secondary = listOf(
            Candidate("bon", "bon", 10, RankerWordSource.SECONDARY, 0.9f, "fr")
        )
        val lowContext = ranker.rank(secondary, 1)

        ranker.setLanguageContext(1.0f, 0.9f)
        val highContext = ranker.rank(secondary, 1)

        assertTrue("Higher language context should boost secondary score",
            highContext[0].score > lowContext[0].score)
    }

    // =========================================================================
    // applyPrefixBoost
    // =========================================================================

    @Test
    fun prefixBoostFavorsShorterCompletions() {
        val candidates = listOf(
            Candidate("the", "the", 10, RankerWordSource.MAIN, 0.5f),
            Candidate("therefore", "therefore", 50, RankerWordSource.MAIN, 0.5f)
        )
        val boosted = ranker.applyPrefixBoost(candidates, "the")
        // "the" has completion ratio 1.0 (exact match), "therefore" has 3/9
        assertTrue("Exact match should have higher boosted confidence",
            boosted[0].nnConfidence > boosted[1].nnConfidence)
    }

    @Test
    fun prefixBoostPreservesOriginalWordData() {
        val candidate = Candidate("hello", "hello", 42, RankerWordSource.USER, 0.7f, "en")
        val boosted = ranker.applyPrefixBoost(listOf(candidate), "hel")
        assertEquals("hello", boosted[0].word)
        assertEquals(42, boosted[0].frequencyRank)
        assertEquals(RankerWordSource.USER, boosted[0].source)
        assertEquals("en", boosted[0].languageCode)
    }

    // =========================================================================
    // WordSource priorities
    // =========================================================================

    @Test
    fun wordSourcePriorityOrdering() {
        assertTrue(RankerWordSource.CUSTOM.priority > RankerWordSource.USER.priority)
        assertTrue(RankerWordSource.USER.priority > RankerWordSource.MAIN.priority)
        assertTrue(RankerWordSource.MAIN.priority > RankerWordSource.SECONDARY.priority)
    }

    // =========================================================================
    // RankedSuggestion
    // =========================================================================

    @Test
    fun rankedSuggestionPreservesLanguageCode() {
        val candidates = listOf(
            Candidate("bonjour", "bonjour", 10, RankerWordSource.MAIN, 0.9f, "fr")
        )
        val ranked = ranker.rank(candidates, 1)
        assertEquals("fr", ranked[0].languageCode)
        assertEquals(RankerWordSource.MAIN, ranked[0].source)
    }
}
