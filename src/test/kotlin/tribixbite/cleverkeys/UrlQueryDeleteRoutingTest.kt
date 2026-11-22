package tribixbite.cleverkeys

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Maintainer request 2026-11-22: "make the delete word function delete exactly one key/value pair
 * (& as delimiter) when used on a URL. That would help to clean up long links."
 *
 * Both app-side word deletes are pinned here, on an editor double that answers the bounded
 * `getSurroundingText` read the word commands use (EditorBoundedReadTest):
 * - the `delete_word` key, which sent Ctrl+Backspace and left the boundary to the app (EditText
 *   removed `email`, then `=`, then `utm_medium` …);
 * - `delete_last_word` (the backspace swipe), which removed back to the last space, capped at
 *   50 characters, so a long link lost an arbitrary 50-character slice.
 * Outside a URL query both keep their old behaviour, and a terminal is never read.
 */
class UrlQueryDeleteRoutingTest {

    private val objenesis = ObjenesisStd()
    private val realRequest = EditorSelectionReader.newExtractedTextRequest
    private val realSdk = EditorSelectionReader.platformSdk

    /** The document and its caret (no selection). */
    private val doc = StringBuilder()
    private var caret = 0
    /** What finishComposingText answers; false models an editor that refuses it. */
    private var composingFinished = true

    /** Key events the IME sent: "keyCode/meta". */
    private val keysSent = ArrayList<String>()

    private lateinit var config: Config
    private lateinit var ic: InputConnection

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        // The reader's own seam (EditorBoundedReadTest): the bounded API 31+ read.
        EditorSelectionReader.platformSdk = { 34 }
        EditorSelectionReader.newExtractedTextRequest = { mockk(relaxed = true) }

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.custom_terminal_packages = emptySet()
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config

        ic = mockk(relaxed = true)
        every { ic.getSurroundingText(any(), any(), any()) } answers {
            val start = (caret - firstArg<Int>()).coerceAtLeast(0)
            val end = (caret + secondArg<Int>()).coerceAtMost(doc.length)
            val window = mockk<SurroundingText>()
            every { window.text } returns doc.substring(start, end)
            every { window.selectionStart } returns caret - start
            every { window.selectionEnd } returns caret - start
            every { window.offset } returns start
            window
        }
        every { ic.getTextBeforeCursor(any(), any()) } answers {
            doc.substring((caret - firstArg<Int>()).coerceAtLeast(0), caret)
        }
        every { ic.getTextAfterCursor(any(), any()) } answers {
            doc.substring(caret, (caret + firstArg<Int>()).coerceAtMost(doc.length))
        }
        every { ic.getSelectedText(any()) } returns null
        every { ic.finishComposingText() } answers { composingFinished }
        every { ic.deleteSurroundingText(any(), any()) } answers {
            val before = firstArg<Int>().coerceAtMost(caret)
            val after = secondArg<Int>().coerceAtMost(doc.length - caret)
            doc.delete(caret - before, caret + after)
            caret -= before
            true
        }
    }

    @After
    fun teardown() {
        EditorSelectionReader.newExtractedTextRequest = realRequest
        EditorSelectionReader.platformSdk = realSdk
        unmockkAll()
    }

    // ------------------------------------------------------------------ fixtures

    private fun type(text: String, at: Int = text.length) {
        doc.setLength(0)
        doc.append(text)
        caret = at
    }

    private fun editor(packageName: String = "com.example.chat"): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            this.packageName = packageName
            inputType = InputType.TYPE_CLASS_TEXT
        }

    private fun keyHandler(editor: EditorInfo): KeyEventHandler {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns "unavailable"
        val recv = mockk<KeyEventHandler.IReceiver>(relaxed = true)
        every { recv.getHandler() } returns mockk(relaxed = true)
        every { recv.isClipboardTagMode() } returns false
        every { recv.isClipboardEditMode() } returns false
        every { recv.isClipboardSearchMode() } returns false
        every { recv.isEmojiPaneOpen() } returns false
        every { recv.isGifPaneOpen() } returns false
        every { recv.getCurrentInputConnection() } returns ic
        every { recv.getCurrentEditorInfo() } returns editor
        every { recv.getContext() } returns context
        val keys = spyk(KeyEventHandler(recv))
        // android.jar's KeyEvent constructor throws "Stub!" here: record instead.
        every { keys.send_key_down_up(any(), any()) } answers { keysSent += "${firstArg<Int>()}/${secondArg<Int>()}" }
        return keys
    }

    private fun pressDeleteWord(editor: EditorInfo = editor()) =
        keyHandler(editor).key_up(KeyValue.getKeyByName("delete_word"), Pointers.Modifiers.EMPTY, false)

    private fun suggestionHandler(): SuggestionHandler {
        val handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contextTracker", PredictionContextTracker())
        handler.setField("suggestionBar", mockk<SuggestionBar>(relaxed = true))
        handler.setField("config", config)
        handler.setField("keyeventhandler", keyHandler(editor()))
        return handler
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage("field '$name' not found on ${javaClass.simpleName}").that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }

    private val ctrlBackspace = "${KeyEvent.KEYCODE_DEL}/${KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON}"

    private val link = "https://shop.example/item?id=42&utm_source=news&utm_medium=email"

    // ------------------------------------------------------------------ delete_word

    @Test
    fun deleteWordKeyRemovesOneUrlPairPerPress() {
        type(link)

        pressDeleteWord()
        assertThat(doc.toString()).isEqualTo("https://shop.example/item?id=42&utm_source=news")
        pressDeleteWord()
        assertThat(doc.toString()).isEqualTo("https://shop.example/item?id=42")
        pressDeleteWord()
        assertThat(doc.toString()).isEqualTo("https://shop.example/item")

        assertWithMessage("the app's own Ctrl+Backspace is not sent inside a query").that(keysSent).isEmpty()
    }

    @Test
    fun deleteWordKeyRemovesThePairAroundACaretInsideTheLink() {
        type("see $link today", at = "see https://shop.example/item?id=42&utm_so".length)

        pressDeleteWord()

        assertThat(doc.toString()).isEqualTo("see https://shop.example/item?id=42&utm_medium=email today")
        // The pair went with the '&' after it, so the caret sits where utm_source started.
        assertThat(caret).isEqualTo("see https://shop.example/item?id=42&".length)
        assertThat(keysSent).isEmpty()
    }

    @Test
    fun deleteWordKeyOutsideAUrlQueryStillSendsCtrlBackspace() {
        // Ordinary text, a link's path, and key=value text that is not a link.
        for (text in listOf("hello world", "https://shop.example/item", "a=1&b=2")) {
            type(text)
            keysSent.clear()
            pressDeleteWord()
            assertWithMessage(text).that(keysSent).containsExactly(ctrlBackspace)
            assertWithMessage(text).that(doc.toString()).isEqualTo(text)
        }
    }

    @Test
    fun deleteWordKeyInATerminalNeverReadsTheText() {
        type(link)

        pressDeleteWord(editor("com.termux"))

        assertThat(keysSent).containsExactly(ctrlBackspace)
        assertThat(doc.toString()).isEqualTo(link)
        io.mockk.verify(exactly = 0) { ic.getSurroundingText(any(), any(), any()) }
    }

    @Test
    fun anEditorThatRefusesToFinishComposingGetsTheOrdinaryWordDelete() {
        type(link)
        composingFinished = false

        pressDeleteWord()

        assertThat(keysSent).containsExactly(ctrlBackspace)
        assertThat(doc.toString()).isEqualTo(link)
    }

    // ------------------------------------------------------------------ delete_last_word

    @Test
    fun backspaceSwipeRemovesOneUrlPairNotAFiftyCharacterSlice() {
        type("Look: $link ")

        suggestionHandler().handleDeleteLastWord(ic, editor())
        // The space after the link goes with its last pair, as a word takes its trailing space.
        assertThat(doc.toString()).isEqualTo("Look: https://shop.example/item?id=42&utm_source=news")
        suggestionHandler().handleDeleteLastWord(ic, editor())
        assertThat(doc.toString()).isEqualTo("Look: https://shop.example/item?id=42")
        assertThat(keysSent).isEmpty()
    }

    @Test
    fun backspaceSwipeOutsideAUrlQueryDeletesTheWordAsBefore() {
        type("hello world ")

        suggestionHandler().handleDeleteLastWord(ic, editor())

        assertThat(doc.toString()).isEqualTo("hello ")
    }
}
