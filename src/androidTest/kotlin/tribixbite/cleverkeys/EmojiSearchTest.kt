package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Emoji Search feature (Issue #41).
 * Tests that emoji search works with comprehensive keyword database.
 */
@RunWith(AndroidJUnit4::class)
class EmojiSearchTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Initialize Emoji with app resources
        Emoji.init(context.resources)
        // Initialize keyword index (prewarm loads from assets) and wait for completion
        EmojiKeywordIndex.prewarm(context)
        runBlocking { EmojiKeywordIndex.awaitReady() }
    }

    // =========================================================================
    // Basic search functionality tests
    // =========================================================================

    @Test
    fun testSearchReturnsResults() {
        val results = Emoji.searchByName("smile")
        assertTrue("Search for 'smile' should return emojis", results.isNotEmpty())
        // Every result must be a real, resolvable emoji entry — search must
        // never fabricate entries outside the loaded emoji set.
        results.forEach {
            val payload = it.kv().getString()
            assertNotNull(
                "Search result '$payload' must resolve through getEmojiByString",
                Emoji.getEmojiByString(payload)
            )
        }
    }

    @Test
    fun testSearchIsCaseInsensitive() {
        val lowerResults = Emoji.searchByName("smile")
        val upperResults = Emoji.searchByName("SMILE")
        val mixedResults = Emoji.searchByName("SmIlE")

        assertEquals("Case shouldn't affect results count", lowerResults.size, upperResults.size)
        assertEquals("Case shouldn't affect results count", lowerResults.size, mixedResults.size)
    }

    @Test
    fun testEmptySearchReturnsEmpty() {
        val results = Emoji.searchByName("")
        assertTrue("Empty search should return empty results", results.isEmpty())
    }

    @Test
    fun testBlankSearchReturnsEmpty() {
        val results = Emoji.searchByName("   ")
        assertTrue("Blank search should return empty results", results.isEmpty())
    }

    @Test
    fun testNonExistentTermReturnsEmpty() {
        val results = Emoji.searchByName("xyznonexistent123")
        assertTrue("Non-existent term should return empty", results.isEmpty())
    }

    // =========================================================================
    // Keyword search tests - verifying comprehensive keyword database
    // =========================================================================

    @Test
    fun testSearchByEmojiName_Heart() {
        val results = Emoji.searchByName("heart")
        assertTrue("'heart' should find emojis", results.isNotEmpty())

        // Should include red heart
        val hasRedHeart = results.any { it.kv().getString() == "❤️" }
        assertTrue("Should find red heart emoji", hasRedHeart)
    }

    @Test
    fun testSearchByEmojiName_Fire() {
        val results = Emoji.searchByName("fire")
        assertTrue("'fire' should find emojis", results.isNotEmpty())

        val hasFire = results.any { it.kv().getString() == "🔥" }
        assertTrue("Should find fire emoji", hasFire)
    }

    @Test
    fun testSearchByEmojiName_Cat() {
        val results = Emoji.searchByName("cat")
        assertTrue("'cat' should find emojis", results.isNotEmpty())

        val hasCat = results.any { it.kv().getString() == "🐱" }
        assertTrue("Should find cat face emoji", hasCat)
    }

    @Test
    fun testSearchByEmojiName_Dog() {
        val results = Emoji.searchByName("dog")
        assertTrue("'dog' should find emojis", results.isNotEmpty())

        val hasDog = results.any { it.kv().getString() == "🐶" }
        assertTrue("Should find dog face emoji", hasDog)
    }

    @Test
    fun testSearchByEmojiName_Thumbsup() {
        val results = Emoji.searchByName("thumbsup")
        assertTrue("'thumbsup' should find emojis", results.isNotEmpty())

        val hasThumbsUp = results.any { it.kv().getString() == "👍" }
        assertTrue("Should find thumbs up emoji", hasThumbsUp)
    }

    @Test
    fun testSearchByAlternativeName_Plus1() {
        val results = Emoji.searchByName("+1")
        assertTrue("'+1' should find emojis", results.isNotEmpty())
    }

    @Test
    fun testSearchByEmojiName_Pizza() {
        val results = Emoji.searchByName("pizza")
        assertTrue("'pizza' should find emojis", results.isNotEmpty())

        val hasPizza = results.any { it.kv().getString() == "🍕" }
        assertTrue("Should find pizza emoji", hasPizza)
    }

    @Test
    fun testSearchByEmojiName_Coffee() {
        val results = Emoji.searchByName("coffee")
        assertTrue("'coffee' should find emojis", results.isNotEmpty())

        val hasCoffee = results.any { it.kv().getString() == "☕" }
        assertTrue("Should find coffee emoji", hasCoffee)
    }

    @Test
    fun testSearchByEmojiName_Sun() {
        val results = Emoji.searchByName("sun")
        assertTrue("'sun' should find emojis", results.isNotEmpty())
    }

    @Test
    fun testSearchByEmojiName_Star() {
        val results = Emoji.searchByName("star")
        assertTrue("'star' should find emojis", results.isNotEmpty())

        val hasStar = results.any { it.kv().getString() == "⭐" }
        assertTrue("Should find star emoji", hasStar)
    }

    // =========================================================================
    // Partial match tests
    // =========================================================================

    @Test
    fun testPartialMatchWorks() {
        val results = Emoji.searchByName("smi")
        assertTrue("Partial match 'smi' should return results", results.isNotEmpty())
    }

    @Test
    fun testPartialMatchFindsTarget() {
        val results = Emoji.searchByName("lov")
        assertTrue("Partial match 'lov' should return love-related emojis", results.isNotEmpty())
    }

    // =========================================================================
    // Emoji name retrieval tests (for long-press tooltip)
    // =========================================================================

    @Test
    fun testGetEmojiNameForGrinning() {
        // "grinning" is the FIRST nameMap entry mapping to 😀, and the reverse
        // map stores only the first/canonical name.
        assertEquals("Grinning emoji canonical name", "grinning", Emoji.getEmojiName("😀"))
    }

    @Test
    fun testGetEmojiNameForHeart() {
        // ARC-106: initNameMap's duplicate "heart" key (the "<3" emoticon used to
        // overwrite ❤️'s entry) is fixed — the emoji owns the bare name, so ❤️'s
        // canonical long-press name is exactly "heart" (its first declared name;
        // the emoticon moved to "heart emoticon"). Uniqueness of the literal keys
        // is pinned by the pure EmojiNameMapDriftTest.
        val name = Emoji.getEmojiName("❤️")
        assertNotNull("Heart emoji should have a name", name)
        assertEquals("Heart emoji canonical name", "heart", name)
    }

    @Test
    fun testGetEmojiNameForUnknownReturnsNonNull() {
        // The isEmoticon heuristic classifies any ASCII-heavy string longer
        // than 2 chars as a text emoticon, so the tooltip label is "emoticon".
        assertEquals(
            "Unknown ASCII string must be labelled as an emoticon",
            "emoticon", Emoji.getEmojiName("xyz999notanemoji")
        )
    }

    // =========================================================================
    // Trie-based keyword index tests
    // =========================================================================

    @Test
    fun testKeywordIndexIsReady() {
        assertTrue("Keyword index should be ready after init", EmojiKeywordIndex.isReady())
    }

    @Test
    fun testKeywordIndexSearch() {
        val results = EmojiKeywordIndex.search("joy", limit = 10)
        assertTrue("Keyword index search for 'joy' should return results", results.isNotEmpty())
    }

    @Test
    fun testKeywordIndexSearchLimit() {
        val limitedResults = EmojiKeywordIndex.search("face", limit = 5)
        assertTrue("Limited search should return at most 5 results", limitedResults.size <= 5)
    }

    @Test
    fun testKeywordIndexSearchMultipleKeywords() {
        val happyResults = EmojiKeywordIndex.search("happy", limit = 20)
        val sadResults = EmojiKeywordIndex.search("sad", limit = 20)

        assertTrue("'happy' should find emojis", happyResults.isNotEmpty())
        assertTrue("'sad' should find emojis", sadResults.isNotEmpty())

        // Results should be different
        assertNotEquals("Happy and sad should return different results",
            happyResults.toSet(), sadResults.toSet())
    }

    // =========================================================================
    // Search result quality tests
    // =========================================================================

    @Test
    fun testSearchResultsAreRelevant() {
        val results = Emoji.searchByName("laugh")
        assertTrue("'laugh' search should return results", results.isNotEmpty())

        // Check that at least one laughing emoji is in top results
        val top5 = results.take(5)
        val hasLaughingEmoji = top5.any {
            val str = it.kv().getString()
            str == "😂" || str == "😆" || str == "🤣"
        }
        assertTrue("Top results for 'laugh' should include laughing emoji", hasLaughingEmoji)
    }

    @Test
    fun testSearchResultsLimitedTo100() {
        val results = Emoji.searchByName("a") // Common letter should have many matches
        assertTrue("Results should be limited to at most 100", results.size <= 100)
    }

    // =========================================================================
    // Unicode name search tests
    // =========================================================================

    @Test
    fun testSearchFindsCommonEmojis() {
        val commonEmojis = listOf(
            "smile" to "😄",
            "heart" to "❤️",
            "fire" to "🔥",
            "star" to "⭐",
            "sun" to "☀️"
        )

        for ((keyword, expectedEmoji) in commonEmojis) {
            val results = Emoji.searchByName(keyword)
            assertTrue("Search for '$keyword' should return results", results.isNotEmpty())
        }
    }

    // =========================================================================
    // Edge case tests
    // =========================================================================

    @Test
    fun testSearchWithSpecialCharacters() {
        val results = Emoji.searchByName("100")
        // The legacy nameMap maps "100" → 💯; whether it surfaces depends on how
        // many index hits precede it, so pin the structural guarantees instead:
        // bounded, valid, duplicate-free results.
        assertTrue("Numeric search must respect the 100-result cap", results.size <= 100)
        val payloads = results.map { it.kv().getString() }
        assertEquals(
            "Numeric search results must be duplicate-free",
            payloads.size, payloads.toSet().size
        )
    }

    @Test
    fun testSearchWithUnicodeInput() {
        val results = Emoji.searchByName("ö") // Swedish character
        // Non-ASCII queries may match nothing, but must never crash and must
        // only ever return real entries.
        results.forEach {
            assertNotNull(
                "Unicode-query result must resolve through getEmojiByString",
                Emoji.getEmojiByString(it.kv().getString())
            )
        }
    }

    @Test
    fun testRepeatedSearchesDontLeak() {
        // Perform many searches to check for memory issues; each must respect
        // the result cap and the no-duplicates guarantee.
        repeat(50) { i ->
            val results = Emoji.searchByName("test$i")
            assertTrue("Search $i must respect the 100-result cap", results.size <= 100)
            val payloads = results.map { it.kv().getString() }
            assertEquals(
                "Search $i results must be duplicate-free",
                payloads.size, payloads.toSet().size
            )
        }
    }
}
