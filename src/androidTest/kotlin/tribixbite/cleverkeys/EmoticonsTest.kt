package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Emoticons feature (Issue #76).
 * Tests that text emoticons are properly loaded as a separate group in the emoji picker.
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

        assertNotNull("Emoticons group should exist", emoticonsGroup)
        assertTrue("Emoticons group should not be empty", emoticonsGroup.isNotEmpty())

        // Check that the first emoticon is ":)"
        val firstEmoticon = emoticonsGroup[0].kv().getString()
        assertEquals("First emoticon should be ':)'", ":)", firstEmoticon)
    }

    @Test
    fun testEmoticonsGroupHasExpectedSize() {
        val numGroups = Emoji.getNumGroups()
        val emoticonsGroup = Emoji.getEmojisByGroup(numGroups - 1)

        // We added 119 emoticons
        assertTrue("Emoticons group should have at least 100 items", emoticonsGroup.size >= 100)
    }

    // =========================================================================
    // Classic ASCII emoticons tests
    // =========================================================================

    @Test
    fun testClassicSmileyFaceExists() {
        val emoji = Emoji.getEmojiByString(":)")
        assertNotNull("':)' emoticon should exist", emoji)
    }

    @Test
    fun testClassicSadFaceExists() {
        val emoji = Emoji.getEmojiByString(":(")
        assertNotNull("':(' emoticon should exist", emoji)
    }

    @Test
    fun testClassicGrinExists() {
        val emoji = Emoji.getEmojiByString(":D")
        assertNotNull("':D' emoticon should exist", emoji)
    }

    @Test
    fun testClassicWinkExists() {
        val emoji = Emoji.getEmojiByString(";)")
        assertNotNull("';)' emoticon should exist", emoji)
    }

    @Test
    fun testClassicTongueExists() {
        val emoji = Emoji.getEmojiByString(":P")
        assertNotNull("':P' emoticon should exist", emoji)
    }

    @Test
    fun testClassicSurprisedExists() {
        val emoji = Emoji.getEmojiByString(":O")
        assertNotNull("':O' emoticon should exist", emoji)
    }

    @Test
    fun testClassicNeutralExists() {
        val emoji = Emoji.getEmojiByString(":|")
        assertNotNull("':|' emoticon should exist", emoji)
    }

    @Test
    fun testClassicSkepticalExists() {
        val emoji = Emoji.getEmojiByString(":/")
        assertNotNull("':/' emoticon should exist", emoji)
    }

    @Test
    fun testHeartEmoticonExists() {
        val emoji = Emoji.getEmojiByString("<3")
        assertNotNull("'<3' emoticon should exist", emoji)
    }

    @Test
    fun testLaughingEmoticonExists() {
        val emoji = Emoji.getEmojiByString("XD")
        assertNotNull("'XD' emoticon should exist", emoji)
    }

    // =========================================================================
    // Kaomoji tests
    // =========================================================================

    @Test
    fun testShrugExists() {
        val emoji = Emoji.getEmojiByString("¯\\_(ツ)_/¯")
        assertNotNull("Shrug kaomoji should exist", emoji)
    }

    @Test
    fun testTableFlipExists() {
        val emoji = Emoji.getEmojiByString("(╯°□°)╯︵┻━┻")
        assertNotNull("Table flip kaomoji should exist", emoji)
    }

    @Test
    fun testLennyFaceExists() {
        val emoji = Emoji.getEmojiByString("( ͡° ͜ʖ ͡°)")
        assertNotNull("Lenny face should exist", emoji)
    }

    @Test
    fun testDisapprovalFaceExists() {
        val emoji = Emoji.getEmojiByString("ಠ_ಠ")
        assertNotNull("Look of disapproval should exist", emoji)
    }

    @Test
    fun testCuteKaomojiExists() {
        val emoji = Emoji.getEmojiByString("(◕‿◕)")
        assertNotNull("Cute kaomoji should exist", emoji)
    }

    @Test
    fun testCatFaceKaomojiExists() {
        val emoji = Emoji.getEmojiByString("(=^･ω･^=)")
        assertNotNull("Cat face kaomoji should exist", emoji)
    }

    @Test
    fun testBearFaceExists() {
        val emoji = Emoji.getEmojiByString("ʕ•ᴥ•ʔ")
        assertNotNull("Bear face kaomoji should exist", emoji)
    }

    // =========================================================================
    // Emoticon search tests (via keyword index)
    // =========================================================================

    @Test
    fun testSearchEmoticonKeyword() {
        // Initialize keyword index
        EmojiKeywordIndex.init(context.resources)

        // Search for "emoticon"
        val results = Emoji.searchByName("emoticon")
        assertTrue("Searching 'emoticon' should return results", results.isNotEmpty())
    }

    @Test
    fun testSearchShrugKeyword() {
        EmojiKeywordIndex.init(context.resources)

        val results = Emoji.searchByName("shrug")
        assertTrue("Searching 'shrug' should return results", results.isNotEmpty())

        // Check that shrug kaomoji is in results
        val hasShrug = results.any { it.kv().getString() == "¯\\_(ツ)_/¯" }
        assertTrue("Shrug kaomoji should be in search results", hasShrug)
    }

    @Test
    fun testSearchTableFlipKeyword() {
        EmojiKeywordIndex.init(context.resources)

        val results = Emoji.searchByName("tableflip")
        assertTrue("Searching 'tableflip' should return results", results.isNotEmpty())
    }

    @Test
    fun testSearchLennyKeyword() {
        EmojiKeywordIndex.init(context.resources)

        val results = Emoji.searchByName("lenny")
        assertTrue("Searching 'lenny' should return results", results.isNotEmpty())
    }

    @Test
    fun testSearchKaomojiKeyword() {
        EmojiKeywordIndex.init(context.resources)

        val results = Emoji.searchByName("kaomoji")
        assertTrue("Searching 'kaomoji' should return results", results.isNotEmpty())
    }

    // =========================================================================
    // Emoticons don't interfere with regular emoji
    // =========================================================================

    @Test
    fun testRegularSmileyEmojiStillExists() {
        val emoji = Emoji.getEmojiByString("😀")
        assertNotNull("Regular grinning emoji should still exist", emoji)
    }

    @Test
    fun testRegularHeartEmojiStillExists() {
        val emoji = Emoji.getEmojiByString("❤️")
        assertNotNull("Regular heart emoji should still exist", emoji)
    }

    @Test
    fun testEmojiGroupsRemainIntact() {
        // Verify original emoji groups still work
        val smileys = Emoji.getEmojisByGroup(0)
        assertNotNull("Smileys group should exist", smileys)
        assertTrue("Smileys group should have emojis", smileys.isNotEmpty())

        // First emoji in smileys should be 😀
        assertEquals("First smiley should be grinning face", "😀", smileys[0].kv().getString())
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testEmoticonWithVariantExists() {
        // Test emoticon with alternate forms
        val emoji1 = Emoji.getEmojiByString(":-)")
        val emoji2 = Emoji.getEmojiByString(":)")
        assertNotNull("':-)'  emoticon should exist", emoji1)
        assertNotNull("':)' emoticon should exist", emoji2)
    }

    @Test
    fun testEmoticonCaseVariants() {
        val lower = Emoji.getEmojiByString("xd")
        val upper = Emoji.getEmojiByString("XD")
        // Both should exist as separate emoticons
        assertNotNull("'XD' emoticon should exist", upper)
        assertNotNull("'xD' emoticon should exist", Emoji.getEmojiByString("xD"))
    }
}
