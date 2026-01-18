package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM tests for VocabularyTrie.
 *
 * Tests trie operations without Android dependencies.
 * Note: logStats() is not tested as it requires android.util.Log
 */
class VocabularyTrieTest {

    private lateinit var trie: VocabularyTrie

    @Before
    fun setUp() {
        trie = VocabularyTrie()
    }

    // =========================================================================
    // insert() tests
    // =========================================================================

    @Test
    fun `insert single word`() {
        trie.insert("hello")

        assertThat(trie.containsWord("hello")).isTrue()
        assertThat(trie.getStats().first).isEqualTo(1)
    }

    @Test
    fun `insert multiple words`() {
        trie.insert("hello")
        trie.insert("help")
        trie.insert("world")

        assertThat(trie.containsWord("hello")).isTrue()
        assertThat(trie.containsWord("help")).isTrue()
        assertThat(trie.containsWord("world")).isTrue()
        assertThat(trie.getStats().first).isEqualTo(3)
    }

    @Test
    fun `insert duplicate word does not increase count`() {
        trie.insert("hello")
        trie.insert("hello")
        trie.insert("hello")

        assertThat(trie.getStats().first).isEqualTo(1)
    }

    @Test
    fun `insert is case insensitive`() {
        trie.insert("Hello")
        trie.insert("HELLO")
        trie.insert("hello")

        assertThat(trie.getStats().first).isEqualTo(1)
        assertThat(trie.containsWord("hello")).isTrue()
        assertThat(trie.containsWord("HELLO")).isTrue()
        assertThat(trie.containsWord("HeLLo")).isTrue()
    }

    @Test
    fun `insert empty string is ignored`() {
        trie.insert("")

        assertThat(trie.getStats().first).isEqualTo(0)
    }

    // =========================================================================
    // hasPrefix() tests
    // =========================================================================

    @Test
    fun `hasPrefix returns true for valid prefix`() {
        trie.insert("hello")
        trie.insert("helicopter")

        assertThat(trie.hasPrefix("h")).isTrue()
        assertThat(trie.hasPrefix("he")).isTrue()
        assertThat(trie.hasPrefix("hel")).isTrue()
        assertThat(trie.hasPrefix("heli")).isTrue()
    }

    @Test
    fun `hasPrefix returns true for complete word`() {
        trie.insert("hello")

        assertThat(trie.hasPrefix("hello")).isTrue()
    }

    @Test
    fun `hasPrefix returns false for invalid prefix`() {
        trie.insert("hello")

        assertThat(trie.hasPrefix("x")).isFalse()
        assertThat(trie.hasPrefix("hx")).isFalse()
        assertThat(trie.hasPrefix("hellox")).isFalse()
    }

    @Test
    fun `hasPrefix is case insensitive`() {
        trie.insert("hello")

        assertThat(trie.hasPrefix("HE")).isTrue()
        assertThat(trie.hasPrefix("HELLO")).isTrue()
        assertThat(trie.hasPrefix("HeLLo")).isTrue()
    }

    @Test
    fun `hasPrefix returns true for empty prefix`() {
        trie.insert("hello")

        assertThat(trie.hasPrefix("")).isTrue()
    }

    @Test
    fun `hasPrefix on empty trie returns false except for empty prefix`() {
        assertThat(trie.hasPrefix("")).isTrue()
        assertThat(trie.hasPrefix("a")).isFalse()
    }

    // =========================================================================
    // containsWord() tests
    // =========================================================================

    @Test
    fun `containsWord returns true for exact match`() {
        trie.insert("hello")
        trie.insert("help")

        assertThat(trie.containsWord("hello")).isTrue()
        assertThat(trie.containsWord("help")).isTrue()
    }

    @Test
    fun `containsWord returns false for prefix only`() {
        trie.insert("hello")

        assertThat(trie.containsWord("hel")).isFalse()
        assertThat(trie.containsWord("hell")).isFalse()
    }

    @Test
    fun `containsWord returns false for non-existent word`() {
        trie.insert("hello")

        assertThat(trie.containsWord("world")).isFalse()
        assertThat(trie.containsWord("helloworld")).isFalse()
    }

    @Test
    fun `containsWord is case insensitive`() {
        trie.insert("Hello")

        assertThat(trie.containsWord("hello")).isTrue()
        assertThat(trie.containsWord("HELLO")).isTrue()
        assertThat(trie.containsWord("HeLLo")).isTrue()
    }

    @Test
    fun `containsWord returns false for empty string`() {
        trie.insert("hello")

        assertThat(trie.containsWord("")).isFalse()
    }

    // =========================================================================
    // getAllowedNextChars() tests
    // =========================================================================

    @Test
    fun `getAllowedNextChars returns correct characters`() {
        trie.insert("hello")
        trie.insert("help")
        trie.insert("heap")

        val nextChars = trie.getAllowedNextChars("he")

        assertThat(nextChars).containsExactly('l', 'a')
    }

    @Test
    fun `getAllowedNextChars returns all first letters for empty prefix`() {
        trie.insert("apple")
        trie.insert("banana")
        trie.insert("cherry")

        val nextChars = trie.getAllowedNextChars("")

        assertThat(nextChars).containsExactly('a', 'b', 'c')
    }

    @Test
    fun `getAllowedNextChars returns empty for invalid prefix`() {
        trie.insert("hello")

        val nextChars = trie.getAllowedNextChars("xyz")

        assertThat(nextChars).isEmpty()
    }

    @Test
    fun `getAllowedNextChars returns empty for complete word with no extensions`() {
        trie.insert("hi")

        val nextChars = trie.getAllowedNextChars("hi")

        assertThat(nextChars).isEmpty()
    }

    @Test
    fun `getAllowedNextChars returns chars when word is prefix of other words`() {
        trie.insert("hi")
        trie.insert("high")
        trie.insert("hire")

        val nextChars = trie.getAllowedNextChars("hi")

        assertThat(nextChars).containsExactly('g', 'r')
    }

    // =========================================================================
    // insertAll() tests
    // =========================================================================

    @Test
    fun `insertAll adds all words`() {
        val words = listOf("apple", "banana", "cherry", "date")

        trie.insertAll(words)

        assertThat(trie.getStats().first).isEqualTo(4)
        words.forEach { assertThat(trie.containsWord(it)).isTrue() }
    }

    @Test
    fun `insertAll handles empty collection`() {
        trie.insertAll(emptyList())

        assertThat(trie.getStats().first).isEqualTo(0)
    }

    @Test
    fun `insertAll handles collection with duplicates`() {
        val words = listOf("apple", "banana", "apple", "apple")

        trie.insertAll(words)

        assertThat(trie.getStats().first).isEqualTo(2)
    }

    // =========================================================================
    // getStats() tests
    // =========================================================================

    @Test
    fun `getStats returns zero for empty trie`() {
        val (words, nodes) = trie.getStats()

        assertThat(words).isEqualTo(0)
        assertThat(nodes).isEqualTo(1) // Root node always exists
    }

    @Test
    fun `getStats counts words correctly`() {
        trie.insert("a")
        trie.insert("ab")
        trie.insert("abc")

        val (words, _) = trie.getStats()

        assertThat(words).isEqualTo(3)
    }

    @Test
    fun `getStats counts nodes correctly for linear word`() {
        trie.insert("abc")

        val (_, nodes) = trie.getStats()

        // Root + a + b + c = 4 nodes
        assertThat(nodes).isEqualTo(4)
    }

    @Test
    fun `getStats counts nodes correctly for branching words`() {
        trie.insert("abc")
        trie.insert("abd")

        val (_, nodes) = trie.getStats()

        // Root + a + b + c + d = 5 nodes
        assertThat(nodes).isEqualTo(5)
    }

    // =========================================================================
    // clear() tests
    // =========================================================================

    @Test
    fun `clear removes all words`() {
        trie.insert("hello")
        trie.insert("world")

        trie.clear()

        assertThat(trie.getStats().first).isEqualTo(0)
        assertThat(trie.containsWord("hello")).isFalse()
        assertThat(trie.containsWord("world")).isFalse()
    }

    @Test
    fun `clear allows reinsertion`() {
        trie.insert("hello")
        trie.clear()
        trie.insert("world")

        assertThat(trie.containsWord("hello")).isFalse()
        assertThat(trie.containsWord("world")).isTrue()
        assertThat(trie.getStats().first).isEqualTo(1)
    }

    // =========================================================================
    // Edge cases and real-world scenarios
    // =========================================================================

    @Test
    fun `handles special characters`() {
        trie.insert("don't")
        trie.insert("it's")

        assertThat(trie.containsWord("don't")).isTrue()
        assertThat(trie.containsWord("it's")).isTrue()
        assertThat(trie.hasPrefix("don")).isTrue()
    }

    @Test
    fun `handles unicode characters`() {
        trie.insert("café")
        trie.insert("naïve")

        assertThat(trie.containsWord("café")).isTrue()
        assertThat(trie.containsWord("naïve")).isTrue()
    }

    @Test
    fun `beam search scenario - vocabulary constraint`() {
        // Simulate beam search vocabulary lookup
        val vocabulary = listOf("the", "they", "them", "their", "there", "then", "these")
        trie.insertAll(vocabulary)

        // At prefix "the", which next chars are valid?
        val validNext = trie.getAllowedNextChars("the")
        assertThat(validNext).containsExactly('y', 'm', 'i', 'r', 'n', 's')

        // Check if "thex" would be pruned (no words start with "thex")
        assertThat(trie.hasPrefix("thex")).isFalse()

        // But "ther" is valid (there, their)
        assertThat(trie.hasPrefix("ther")).isTrue()
    }

    @Test
    fun `large vocabulary performance`() {
        // Insert 10000 words
        val words = (1..10000).map { "word$it" }
        trie.insertAll(words)

        assertThat(trie.getStats().first).isEqualTo(10000)
        assertThat(trie.containsWord("word5000")).isTrue()
        assertThat(trie.hasPrefix("word")).isTrue()
    }
}
