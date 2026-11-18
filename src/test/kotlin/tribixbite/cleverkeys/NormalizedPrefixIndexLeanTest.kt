package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 2026-11-18: the Dictionary Manager ran the keyboard's process out of memory while building a
 * full [NormalizedPrefixIndex] per language only to list its words. It now builds one with
 * `indexPrefixes = false`: the canonical forms and their ranks, no prefix buckets.
 */
class NormalizedPrefixIndexLeanTest {

    private fun index(indexPrefixes: Boolean) = NormalizedPrefixIndex(indexPrefixes = indexPrefixes).apply {
        addWord("café", 3)
        addWord("cafe", 9)
        addWord("été", 1)
    }

    @Test
    fun aCanonicalOnlyIndexKeepsNoPrefixBuckets() {
        val lean = index(indexPrefixes = false)
        assertThat(lean.prefixCount()).isEqualTo(0)
        assertThat(lean.wordsWithIndexedPrefix("c")).isNull()
        assertThat(index(indexPrefixes = true).prefixCount()).isGreaterThan(0)
    }

    @Test
    fun aCanonicalOnlyIndexAnswersLookupsLikeTheFullOne() {
        val lean = index(indexPrefixes = false)
        val full = index(indexPrefixes = true)
        assertThat(lean.normalizedWords()).containsExactlyElementsIn(full.getAllNormalizedWords())
        for (normalized in full.getAllNormalizedWords()) {
            assertThat(lean.lookup(normalized)).isEqualTo(full.lookup(normalized))
            // What the Dictionary Manager used to read: the prefix scan's own entry.
            assertThat(lean.lookup(normalized)).isEqualTo(full.getWordsWithPrefix(normalized).first { it.normalized == normalized })
        }
        assertThat(lean.lookup("cafe")!!.bestCanonical).isEqualTo("café")
    }
}
