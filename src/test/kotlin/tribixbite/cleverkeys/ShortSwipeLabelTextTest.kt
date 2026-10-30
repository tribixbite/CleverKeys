package tribixbite.cleverkeys

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.ShortSwipeAssignment
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * Bug B of 2026-10-30 (maintainer screenshot 9): after mapping voice input, the toast read
 * `Mapped Northwest → "茬" (Command: voice_input)` — the mic is a private-use key-font glyph that
 * the toast's system font drew as a CJK ideograph, and the command was shown by its id. Text in
 * the system font now names the command ("Voice input (on device)") and never prints a glyph.
 *
 * Mock tier: a mocked Context whose getString returns the resource ENTRY NAME (format strings
 * get their arguments appended), so assertions read naturally.
 */
class ShortSwipeLabelTextTest {

    private lateinit var context: Context

    /** Resource name of [id] from the generated R class (reflection; test-only). */
    private fun nameOf(id: Int): String =
        R.string::class.java.fields.firstOrNull { it.getInt(null) == id }?.name ?: "#$id"

    @Before
    fun setup() {
        context = mockk()
        every { context.getString(any()) } answers { nameOf(firstArg()) }
        every { context.getString(any(), *anyVararg()) } answers {
            val args = (secondArg<Array<Any?>>()).joinToString("|")
            "${nameOf(firstArg())}[$args]"
        }
    }

    @After
    fun teardown() = unmockkAll()

    private val voiceInput = ShortSwipeMapping(
        "a", SwipeDirection.NW, "", ActionType.COMMAND, "voice_input", useKeyFont = true
    )

    @Test
    fun aCommandIsDescribedByItsName() {
        val text = shortSwipeActionDescription(context, ActionType.COMMAND, "voice_input")
        assertThat(text).isEqualTo("short_swipe_action_desc_command[cmd_voice_input]")
    }

    @Test
    fun anUnknownCommandKeepsItsStoredName() {
        val text = shortSwipeActionDescription(context, ActionType.COMMAND, "SOME_LEGACY")
        assertThat(text).isEqualTo("short_swipe_action_desc_command[SOME_LEGACY]")
    }

    @Test
    fun theMappedToastNeverPrintsAKeyFontGlyph() {
        val text = ShortSwipeAssignment.mappedMessage(context, voiceInput)
        assertThat(text.none { it.code in 0xE000..0xF8FF }).isTrue()
        assertThat(text).isEqualTo(
            "short_swipe_toast_mapped[swipe_direction_northwest|cmd_voice_input|" +
                "short_swipe_action_desc_command[cmd_voice_input]]"
        )
    }

    @Test
    fun aPlainLabelIsKeptInTheToast() {
        val at = ShortSwipeMapping("a", SwipeDirection.NE, "@", ActionType.TEXT, "@")
        assertThat(ShortSwipeAssignment.textLabel(at) { nameOf(it) }).isEqualTo("@")
    }
}
