package tribixbite.cleverkeys

import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * An emoji is not part of a word. Device pass 2026-11-18 (Saga, release `e33bfffa`): after two
 * emojis were inserted, cursor sync read them back as the typed word — the bar offered `+😂🍕`,
 * and a letter typed next made the word `😂🍕a`. The tracker's last-resort rule counted anything
 * that is not whitespace or listed punctuation as a word character, so emoji surrogate pairs,
 * the variation selector U+FE0F, skin-tone modifiers and the emoji ZWJ all joined the word.
 */
class EmojiWordBoundaryTest {
    private val objenesis = ObjenesisStd()
    private lateinit var tracker: PredictionContextTracker
    private lateinit var ic: InputConnection
    private val editor = StringBuilder()
    private var afterCursor = ""

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkObject(Config.Companion)
        val config = mockk<Config>(relaxed = true)
        config.custom_terminal_packages = emptySet()
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config
        tracker = PredictionContextTracker()
        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } answers {
            editor.substring(maxOf(0, editor.length - firstArg<Int>()))
        }
        every { ic.getTextAfterCursor(any(), any()) } answers { afterCursor.take(firstArg()) }
    }

    @After
    fun teardown() = unmockkAll()

    private fun field(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.notes"
            inputType = InputType.TYPE_CLASS_TEXT
        }

    private fun wordAt(before: String, after: String = ""): Pair<String, String> {
        editor.setLength(0)
        editor.append(before)
        afterCursor = after
        tracker.synchronizeWithCursor(ic, "en", field())
        return tracker.getCurrentWord() to tracker.getRawSuffix()
    }

    @Test
    fun emojisBeforeTheCursorAreNotTheTypedWord() {
        assertWithMessage("two emojis").that(wordAt("😂🍕").first).isEmpty()
        assertWithMessage("a letter typed after emojis").that(wordAt("😂🍕a").first).isEqualTo("a")
        assertWithMessage("an emoji glued to a word").that(wordAt("hi😂").first).isEmpty()
        assertWithMessage("a word after an emoji").that(wordAt("😂hello").first).isEqualTo("hello")
    }

    @Test
    fun emojiSequencesAreNotWordCharactersEither() {
        // ❤️ (BMP symbol + U+FE0F), 👍🏽 (skin-tone modifier), 👩‍💻 (emoji ZWJ sequence), ☺ (BMP).
        for (emoji in listOf("❤️", "👍🏽", "👩‍💻", "☺", "★")) {
            assertWithMessage("prefix after $emoji").that(wordAt("${emoji}cat").first).isEqualTo("cat")
            assertWithMessage("suffix before $emoji").that(wordAt("ca", "t$emoji").second).isEqualTo("t")
        }
    }

    @Test
    fun lettersOutsideTheBasicPlaneStillJoinTheWord() {
        // A supplementary-plane letter (CJK Ext-B 𠀀 is skipped as CJK, so use Gothic 𐌰𐌱) stays a
        // word character: only symbols leave the word.
        assertWithMessage("Gothic letters").that(wordAt("𐌰𐌱").first).isEqualTo("𐌰𐌱")
    }
}
