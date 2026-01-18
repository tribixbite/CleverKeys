package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for beam search data models.
 */
class BeamSearchModelsTest {

    // =========================================================================
    // BeamSearchState tests
    // =========================================================================

    @Test
    fun `create state with start token`() {
        val state = BeamSearchState(startToken = 1)

        assertThat(state.tokens).containsExactly(1L)
        assertThat(state.score).isEqualTo(0.0f)
        assertThat(state.finished).isFalse()
    }

    @Test
    fun `create state with custom values`() {
        val state = BeamSearchState(
            startToken = 5,
            startScore = 0.9f,
            isFinished = true
        )

        assertThat(state.tokens).containsExactly(5L)
        assertThat(state.score).isEqualTo(0.9f)
        assertThat(state.finished).isTrue()
    }

    @Test
    fun `copy constructor creates independent copy`() {
        val original = BeamSearchState(startToken = 1, startScore = 0.5f)
        original.tokens.add(2L)
        original.tokens.add(3L)

        val copy = BeamSearchState(original)

        // Verify copy has same values
        assertThat(copy.tokens).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(copy.score).isEqualTo(0.5f)
        assertThat(copy.finished).isFalse()

        // Verify independence - modifying copy doesn't affect original
        copy.tokens.add(4L)
        copy.score = 0.9f
        copy.finished = true

        assertThat(original.tokens).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(original.score).isEqualTo(0.5f)
        assertThat(original.finished).isFalse()
    }

    @Test
    fun `state tokens are mutable`() {
        val state = BeamSearchState(startToken = 1)

        state.tokens.add(2L)
        state.tokens.add(3L)

        assertThat(state.tokens).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `state score is mutable`() {
        val state = BeamSearchState(startToken = 1)

        state.score = 0.75f

        assertThat(state.score).isEqualTo(0.75f)
    }

    @Test
    fun `state finished is mutable`() {
        val state = BeamSearchState(startToken = 1)

        state.finished = true

        assertThat(state.finished).isTrue()
    }

    // =========================================================================
    // IndexValue tests
    // =========================================================================

    @Test
    fun `create index value`() {
        val iv = IndexValue(index = 5, value = 0.8f)

        assertThat(iv.index).isEqualTo(5)
        assertThat(iv.value).isEqualTo(0.8f)
    }

    @Test
    fun `index value sorts descending by value`() {
        val high = IndexValue(0, 0.9f)
        val low = IndexValue(1, 0.3f)

        // compareTo returns negative if this > other (for descending sort)
        assertThat(high.compareTo(low)).isLessThan(0)
        assertThat(low.compareTo(high)).isGreaterThan(0)
    }

    @Test
    fun `index value equal values compare to zero`() {
        val iv1 = IndexValue(0, 0.5f)
        val iv2 = IndexValue(1, 0.5f)

        assertThat(iv1.compareTo(iv2)).isEqualTo(0)
    }

    @Test
    fun `sort list of index values`() {
        val values = listOf(
            IndexValue(0, 0.3f),
            IndexValue(1, 0.9f),
            IndexValue(2, 0.6f),
            IndexValue(3, 0.1f)
        )

        val sorted = values.sorted()

        assertThat(sorted.map { it.index }).containsExactly(1, 2, 0, 3).inOrder()
        assertThat(sorted.map { it.value }).containsExactly(0.9f, 0.6f, 0.3f, 0.1f).inOrder()
    }

    // =========================================================================
    // BeamSearchCandidate tests
    // =========================================================================

    @Test
    fun `create candidate`() {
        val candidate = BeamSearchCandidate(word = "hello", confidence = 0.95f)

        assertThat(candidate.word).isEqualTo("hello")
        assertThat(candidate.confidence).isEqualTo(0.95f)
    }

    @Test
    fun `candidate sorts descending by confidence`() {
        val high = BeamSearchCandidate("high", 0.9f)
        val low = BeamSearchCandidate("low", 0.3f)

        assertThat(high.compareTo(low)).isLessThan(0)
        assertThat(low.compareTo(high)).isGreaterThan(0)
    }

    @Test
    fun `candidate equal confidence compare to zero`() {
        val c1 = BeamSearchCandidate("apple", 0.8f)
        val c2 = BeamSearchCandidate("banana", 0.8f)

        assertThat(c1.compareTo(c2)).isEqualTo(0)
    }

    @Test
    fun `sort list of candidates`() {
        val candidates = listOf(
            BeamSearchCandidate("low", 0.2f),
            BeamSearchCandidate("high", 0.9f),
            BeamSearchCandidate("medium", 0.5f)
        )

        val sorted = candidates.sorted()

        assertThat(sorted.map { it.word }).containsExactly("high", "medium", "low").inOrder()
    }

    // =========================================================================
    // Data class features
    // =========================================================================

    @Test
    fun `IndexValue equals works correctly`() {
        val iv1 = IndexValue(5, 0.8f)
        val iv2 = IndexValue(5, 0.8f)
        val iv3 = IndexValue(5, 0.9f)

        assertThat(iv1).isEqualTo(iv2)
        assertThat(iv1).isNotEqualTo(iv3)
    }

    @Test
    fun `BeamSearchCandidate equals works correctly`() {
        val c1 = BeamSearchCandidate("test", 0.9f)
        val c2 = BeamSearchCandidate("test", 0.9f)
        val c3 = BeamSearchCandidate("test", 0.8f)

        assertThat(c1).isEqualTo(c2)
        assertThat(c1).isNotEqualTo(c3)
    }

    @Test
    fun `candidates in collection maintain order`() {
        val candidates = mutableListOf<BeamSearchCandidate>()

        candidates.add(BeamSearchCandidate("first", 0.5f))
        candidates.add(BeamSearchCandidate("second", 0.8f))
        candidates.add(BeamSearchCandidate("third", 0.3f))

        // Sort in place
        candidates.sort()

        assertThat(candidates[0].word).isEqualTo("second")
        assertThat(candidates[1].word).isEqualTo("first")
        assertThat(candidates[2].word).isEqualTo("third")
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `candidate with empty word`() {
        val candidate = BeamSearchCandidate("", 0.5f)
        assertThat(candidate.word).isEmpty()
    }

    @Test
    fun `candidate with zero confidence`() {
        val candidate = BeamSearchCandidate("word", 0.0f)
        assertThat(candidate.confidence).isEqualTo(0.0f)
    }

    @Test
    fun `index value with negative value`() {
        val iv = IndexValue(0, -0.5f)
        assertThat(iv.value).isEqualTo(-0.5f)
    }

    @Test
    fun `state with zero token`() {
        val state = BeamSearchState(startToken = 0)
        assertThat(state.tokens).containsExactly(0L)
    }
}
