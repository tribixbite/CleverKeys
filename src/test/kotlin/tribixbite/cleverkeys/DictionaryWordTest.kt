package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for DictionaryWord data class.
 */
class DictionaryWordTest {

    // =========================================================================
    // Creation tests
    // =========================================================================

    @Test
    fun `create word with defaults`() {
        val word = DictionaryWord("hello", source = WordSource.MAIN)

        assertThat(word.word).isEqualTo("hello")
        assertThat(word.frequency).isEqualTo(0)
        assertThat(word.source).isEqualTo(WordSource.MAIN)
        assertThat(word.enabled).isTrue()
    }

    @Test
    fun `create word with all parameters`() {
        val word = DictionaryWord("world", 1000, WordSource.USER, false)

        assertThat(word.word).isEqualTo("world")
        assertThat(word.frequency).isEqualTo(1000)
        assertThat(word.source).isEqualTo(WordSource.USER)
        assertThat(word.enabled).isFalse()
    }

    // =========================================================================
    // Comparable tests (sorting)
    // =========================================================================

    @Test
    fun `compare sorts by frequency descending`() {
        val high = DictionaryWord("high", 1000, WordSource.MAIN)
        val low = DictionaryWord("low", 100, WordSource.MAIN)

        assertThat(high.compareTo(low)).isLessThan(0)
        assertThat(low.compareTo(high)).isGreaterThan(0)
    }

    @Test
    fun `compare with equal frequency sorts alphabetically`() {
        val apple = DictionaryWord("apple", 500, WordSource.MAIN)
        val banana = DictionaryWord("banana", 500, WordSource.MAIN)

        assertThat(apple.compareTo(banana)).isLessThan(0)
        assertThat(banana.compareTo(apple)).isGreaterThan(0)
    }

    @Test
    fun `compare identical words returns zero`() {
        val word1 = DictionaryWord("same", 500, WordSource.MAIN)
        val word2 = DictionaryWord("same", 500, WordSource.USER)

        assertThat(word1.compareTo(word2)).isEqualTo(0)
    }

    @Test
    fun `sort list of words`() {
        val words = listOf(
            DictionaryWord("low", 100, WordSource.MAIN),
            DictionaryWord("high", 1000, WordSource.MAIN),
            DictionaryWord("medium", 500, WordSource.MAIN),
            DictionaryWord("also_medium", 500, WordSource.MAIN)
        )

        val sorted = words.sorted()

        assertThat(sorted.map { it.word }).containsExactly(
            "high", "also_medium", "medium", "low"
        ).inOrder()
    }

    // =========================================================================
    // WordSource enum tests
    // =========================================================================

    @Test
    fun `WordSource has correct values`() {
        assertThat(WordSource.values()).hasLength(3)
        assertThat(WordSource.MAIN).isNotNull()
        assertThat(WordSource.USER).isNotNull()
        assertThat(WordSource.CUSTOM).isNotNull()
    }

    @Test
    fun `WordSource valueOf works`() {
        assertThat(WordSource.valueOf("MAIN")).isEqualTo(WordSource.MAIN)
        assertThat(WordSource.valueOf("USER")).isEqualTo(WordSource.USER)
        assertThat(WordSource.valueOf("CUSTOM")).isEqualTo(WordSource.CUSTOM)
    }

    // =========================================================================
    // Data class features
    // =========================================================================

    @Test
    fun `equals works correctly`() {
        val word1 = DictionaryWord("test", 100, WordSource.MAIN, true)
        val word2 = DictionaryWord("test", 100, WordSource.MAIN, true)
        val word3 = DictionaryWord("test", 200, WordSource.MAIN, true)

        assertThat(word1).isEqualTo(word2)
        assertThat(word1).isNotEqualTo(word3)
    }

    @Test
    fun `hashCode is consistent`() {
        val word1 = DictionaryWord("test", 100, WordSource.MAIN, true)
        val word2 = DictionaryWord("test", 100, WordSource.MAIN, true)

        assertThat(word1.hashCode()).isEqualTo(word2.hashCode())
    }

    @Test
    fun `copy creates modified copy`() {
        val original = DictionaryWord("test", 100, WordSource.MAIN, true)
        val copy = original.copy(frequency = 200, enabled = false)

        assertThat(copy.word).isEqualTo("test")
        assertThat(copy.frequency).isEqualTo(200)
        assertThat(copy.source).isEqualTo(WordSource.MAIN)
        assertThat(copy.enabled).isFalse()
    }

    @Test
    fun `toString contains all fields`() {
        val word = DictionaryWord("test", 100, WordSource.USER, true)
        val str = word.toString()

        assertThat(str).contains("test")
        assertThat(str).contains("100")
        assertThat(str).contains("USER")
        assertThat(str).contains("true")
    }
}
