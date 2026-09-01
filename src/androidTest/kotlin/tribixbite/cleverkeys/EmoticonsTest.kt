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
 * Instrumented tests for Emoticons feature (Issue #76).
 * Tests that text emoticons are properly loaded as a separate group in the emoji picker.
 *
 * ARC-044: strengthened from liveness to behavior. `Emoji.getEmojiByString(s)` is a
 * lookup in a map keyed by the raw emoji line, and `Emoji.kv()` wraps that same
 * string as a String-kind KeyValue — so every lookup test now pins the round trip
 * (the returned entry types exactly the queried text) instead of only non-null.
 */
@RunWith(AndroidJUnit4::class)
class EmoticonsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Initialize Emoji with app resources
        Emoji.init(context.resources)
    }

    /** Helper to ensure keyword index is ready for search tests */
    private fun ensureKeywordIndexReady() {
        EmojiKeywordIndex.prewarm(context)
        runBlocking { EmojiKeywordIndex.awaitReady() }
    }

    /**
     * Assert the emoticon exists AND that tapping it would type exactly [s]:
     * the stored KeyValue must be a String key whose payload is the queried text.
     */
    private fun assertEmoticonTypes(s: String) {
        val emoji = Emoji.getEmojiByString(s)
        assertNotNull("'$s' emoticon should exist", emoji)
        val kv = emoji!!.kv()
        assertEquals("'$s' must be stored as a String key", KeyValue.Kind.String, kv.getKind())
        assertEquals("'$s' entry must type exactly the queried text", s, kv.getString())
    }

    // =========================================================================
    // Basic emoticons group tests
    // =========================================================================

    @Test
    fun testEmoticonsGroupExists() {
        // Emoticons is group 10 (0-indexed): after flags and Unicode 17.0 emojis
        val numGroups = Emoji.getNumGroups()
        assertTrue("Should have at least 11 emoji groups (including emoticons)", numGroups >= 11)
    }

    @Test
    fun testEmoticonsGroupContainsTextFaces() {
        // Get the last group (emoticons)
        val numGroups = Emoji.getNumGroups()
        val emoticonsGroup = Emoji.getEmojisByGroup(numGroups - 1)

        assertTrue("Emoticons group should not be empty", emoticonsGroup.isNotEmpty())

        // Check that the first emoticon is ":)"
        val firstEmoticon = emoticonsGroup[0].kv().getString()
        assertEquals("First emoticon should be ':)'", ":)", firstEmoticon)

        // Every entry in the emoticons group must be a usable String key with
        // non-blank payload — a blank entry would render an empty picker cell.
        emoticonsGroup.forEachIndexed { i, e ->
            assertEquals(
                "Emoticon at index $i must be a String key",
                KeyValue.Kind.String, e.kv().getKind()
            )
            assertTrue(
                "Emoticon at index $i must have a non-blank payload",
                e.kv().getString().isNotBlank()
            )
        }
    }

    @Test
    fun testEmoticonsGroupHasExpectedSize() {
        val numGroups = Emoji.getNumGroups()
        val emoticonsGroup = Emoji.getEmojisByGroup(numGroups - 1)

        // We added 119 emoticons
        assertTrue("Emoticons group should have at least 100 items", emoticonsGroup.size >= 100)

        // No duplicate emoticon in the group — a duplicate line in the raw
        // resource would collapse in stringMap but still render twice.
        val payloads = emoticonsGroup.map { it.kv().getString() }
        assertEquals(
            "Emoticons group must contain no duplicates",
            payloads.size, payloads.toSet().size
        )
    }

    // =========================================================================
    // Classic ASCII emoticons tests
    // =========================================================================

    @Test
    fun testClassicSmileyFaceExists() {
        assertEmoticonTypes(":)")
    }

    @Test
    fun testClassicSadFaceExists() {
        assertEmoticonTypes(":(")
    }

    @Test
    fun testClassicGrinExists() {
        assertEmoticonTypes(":D")
    }

    @Test
    fun testClassicWinkExists() {
        assertEmoticonTypes(";)")
    }

    @Test
    fun testClassicTongueExists() {
        assertEmoticonTypes(":P")
    }

    @Test
    fun testClassicSurprisedExists() {
        assertEmoticonTypes(":O")
    }

    @Test
    fun testClassicNeutralExists() {
        assertEmoticonTypes(":|")
    }

    @Test
    fun testClassicSkepticalExists() {
        assertEmoticonTypes(":/")
    }

    @Test
    fun testHeartEmoticonExists() {
        assertEmoticonTypes("<3")
    }

    @Test
    fun testLaughingEmoticonExists() {
        assertEmoticonTypes("XD")
    }

    // =========================================================================
    // Kaomoji tests
    // =========================================================================

    @Test
    fun testShrugExists() {
        assertEmoticonTypes("¯\\_(ツ)_/¯")
    }

    @Test
    fun testTableFlipExists() {
        assertEmoticonTypes("(╯°□°)╯︵┻━┻")
    }

    @Test
    fun testLennyFaceExists() {
        assertEmoticonTypes("( ͡° ͜ʖ ͡°)")
    }

    @Test
    fun testDisapprovalFaceExists() {
        assertEmoticonTypes("ಠ_ಠ")
    }

    @Test
    fun testCuteKaomojiExists() {
        assertEmoticonTypes("(◕‿◕)")
    }

    @Test
    fun testCatFaceKaomojiExists() {
        assertEmoticonTypes("(=^･ω･^=)")
    }

    @Test
    fun testBearFaceExists() {
        assertEmoticonTypes("ʕ•ᴥ•ʔ")
    }

    // =========================================================================
    // Emoticon search tests (via keyword index)
    // =========================================================================

    @Test
    fun testSearchEmoticonKeyword() {
        ensureKeywordIndexReady()

        // Search for "emoticon"
        val results = Emoji.searchByName("emoticon")
        assertTrue("Searching 'emoticon' should return results", results.isNotEmpty())
        // Every result must be a resolvable picker entry (non-blank payload)
        results.forEach {
            assertTrue(
                "Search result must have a non-blank payload",
                it.kv().getString().isNotBlank()
            )
        }
    }

    @Test
    fun testSearchShrugKeyword() {
        ensureKeywordIndexReady()

        val results = Emoji.searchByName("shrug")
        assertTrue("Searching 'shrug' should return results", results.isNotEmpty())

        // Check that shrug kaomoji is in results
        val hasShrug = results.any { it.kv().getString() == "¯\\_(ツ)_/¯" }
        assertTrue("Shrug kaomoji should be in search results", hasShrug)
    }

    @Test
    fun testSearchTableFlipKeyword() {
        ensureKeywordIndexReady()

        val results = Emoji.searchByName("tableflip")
        assertTrue("Searching 'tableflip' should return results", results.isNotEmpty())
        assertTrue(
            "Table flip kaomoji should be in 'tableflip' results",
            results.any { it.kv().getString() == "(╯°□°)╯︵┻━┻" }
        )
    }

    @Test
    fun testSearchLennyKeyword() {
        ensureKeywordIndexReady()

        val results = Emoji.searchByName("lenny")
        assertTrue("Searching 'lenny' should return results", results.isNotEmpty())
        assertTrue(
            "Lenny face should be in 'lenny' results",
            results.any { it.kv().getString() == "( ͡° ͜ʖ ͡°)" }
        )
    }

    @Test
    fun testSearchKaomojiKeyword() {
        ensureKeywordIndexReady()

        val results = Emoji.searchByName("kaomoji")
        assertTrue("Searching 'kaomoji' should return results", results.isNotEmpty())
    }

    // =========================================================================
    // Emoticons don't interfere with regular emoji
    // =========================================================================

    @Test
    fun testRegularSmileyEmojiStillExists() {
        assertEmoticonTypes("😀")
    }

    @Test
    fun testRegularHeartEmojiStillExists() {
        assertEmoticonTypes("❤️")
    }

    @Test
    fun testEmojiGroupsRemainIntact() {
        // Verify original emoji groups still work
        val smileys = Emoji.getEmojisByGroup(0)
        assertTrue("Smileys group should have emojis", smileys.isNotEmpty())

        // First emoji in smileys should be 😀
        assertEquals("First smiley should be grinning face", "😀", smileys[0].kv().getString())

        // Group partition sanity: groups must not be empty and their union is
        // exactly the full emoji list (subList partition by construction).
        val numGroups = Emoji.getNumGroups()
        var totalAcrossGroups = 0
        for (g in 0 until numGroups) {
            val group = Emoji.getEmojisByGroup(g)
            assertTrue("Group $g must not be empty", group.isNotEmpty())
            totalAcrossGroups += group.size
        }
        assertTrue("Groups must jointly contain at least 100 entries", totalAcrossGroups >= 100)
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testEmoticonWithVariantExists() {
        // Test emoticon with alternate forms — both must exist as DISTINCT entries
        assertEmoticonTypes(":-)")
        assertEmoticonTypes(":)")
        assertFalse(
            "':-)' and ':)' must be distinct entries",
            Emoji.getEmojiByString(":-)")!!.kv().sameKey(Emoji.getEmojiByString(":)")!!.kv())
        )
    }

    @Test
    fun testEmoticonCaseVariants() {
        // Case variants are separate emoticons, not case-folded lookups
        assertEmoticonTypes("XD")
        assertEmoticonTypes("xD")
        assertFalse(
            "'XD' and 'xD' must be distinct entries",
            Emoji.getEmojiByString("XD")!!.kv().sameKey(Emoji.getEmojiByString("xD")!!.kv())
        )
    }
}
