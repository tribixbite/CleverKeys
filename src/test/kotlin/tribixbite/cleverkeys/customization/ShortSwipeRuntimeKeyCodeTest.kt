package tribixbite.cleverkeys.customization

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.KeyValue

/**
 * Bugs A and C of 2026-10-30 (maintainer screenshots 9/10, "adding/removing voice input as a
 * subkey caused bizarre behaviour thereafter").
 *
 * Short Swipe Customization stored a mapping under the TEXT the tapped key typed: Enter → "\n",
 * Space → " ". The keyboard looks mappings up by the key's main value string — Enter's and
 * Space's are their key-font glyphs — so the voice-input mapping the user put on Enter's NW never
 * fired: that swipe kept the bottom row's own NW subkey (voice_typing, which switches to ANOTHER
 * keyboard's voice input), while the customization dialog showed the mic as mapped. The same split
 * applied to an upper-case code: [ShortSwipeCustomizationManager.getMapping] lowercased its query
 * but the per-key draw index kept the raw code, so such a mapping fired but was never drawn.
 *
 * The manager now stores and looks up every mapping under the runtime code
 * ([ShortSwipeKeyCodes.canonical]), including mappings saved before the fix.
 *
 * Mock tier, same harness as [ShortSwipeChangeListenerTest].
 */
class ShortSwipeRuntimeKeyCodeTest {

    private lateinit var scratch: File
    private lateinit var context: Context
    private lateinit var manager: ShortSwipeCustomizationManager

    private val enter: String = KeyValue.getKeyByName("enter").getString()
    private val space: String = KeyValue.getKeyByName("space").getString()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        scratch = Files.createTempDirectory("ck-shortswipe-keycode").toFile()
        context = mockk()
        every { context.filesDir } returns scratch
        every { context.applicationContext } returns context
        manager = newManager()
    }

    private fun newManager(): ShortSwipeCustomizationManager =
        ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)

    @After
    fun teardown() {
        unmockkAll()
        scratch.deleteRecursively()
    }

    private fun voiceInput(keyCode: String) =
        ShortSwipeMapping(keyCode, SwipeDirection.NW, "", ActionType.COMMAND, "voice_input", useKeyFont = true)

    @Test
    fun aMappingCapturedAsNewlineFiresAndDrawsOnTheEnterKey() = runBlocking {
        manager.setMapping(voiceInput("\n"))
        assertWithMessage("swipe lookup on Enter's runtime code")
            .that(manager.getMapping(enter, SwipeDirection.NW)).isNotNull()
        assertWithMessage("draw index on Enter's runtime code")
            .that(manager.getMappingsForKey(enter).keys).contains(SwipeDirection.NW)
    }

    @Test
    fun aMappingCapturedAsSpaceFiresOnTheSpaceBar() = runBlocking {
        manager.setMapping(voiceInput(" "))
        assertWithMessage("swipe lookup on Space's runtime code")
            .that(manager.getMapping(space, SwipeDirection.NW)).isNotNull()
    }

    @Test
    fun removingItThroughEitherCodeRemovesIt() = runBlocking {
        manager.setMapping(voiceInput("\n"))
        manager.removeMapping(enter, SwipeDirection.NW)
        assertWithMessage("after removal by the runtime code")
            .that(manager.getMapping("\n", SwipeDirection.NW)).isNull()
        assertWithMessage("draw index after removal").that(manager.getMappingsForKey(enter)).isEmpty()
    }

    @Test
    fun anUpperCaseCodeIsDrawnAsWellAsFired() = runBlocking {
        manager.setMapping(ShortSwipeMapping("ABC", SwipeDirection.NE, "@", ActionType.TEXT, "@"))
        assertWithMessage("fires").that(manager.getMapping("ABC", SwipeDirection.NE)).isNotNull()
        assertWithMessage("drawn (per-key index)").that(manager.getMappingsForKey("ABC").keys).contains(SwipeDirection.NE)
    }

    @Test
    fun aMappingSavedBeforeTheFixUnderNewlineLoadsOntoEnter() = runBlocking {
        File(scratch, "short_swipe_customizations.json").writeText(
            """{"version":2,"mappings":{"\n":{"NW":{"displayText":"","actionType":"COMMAND","actionValue":"voice_input","useKeyFont":true}}}}"""
        )
        val fresh = newManager()
        fresh.loadMappings()
        assertWithMessage("loaded under Enter's runtime code")
            .that(fresh.getMapping(enter, SwipeDirection.NW)?.actionValue).isEqualTo("voice_input")
        assertWithMessage("drawn on Enter").that(fresh.getMappingsForKey(enter).keys).contains(SwipeDirection.NW)
    }
}
