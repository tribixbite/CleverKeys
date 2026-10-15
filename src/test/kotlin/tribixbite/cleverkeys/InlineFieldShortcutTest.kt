package tribixbite.cleverkeys

import android.content.ClipData
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import java.lang.reflect.Proxy

/**
 * While one of the IME's own inline fields owns typing (clipboard entry editor, clipboard
 * search, tag entry, emoji / GIF search), keyboard shortcuts and paste must act on THAT field
 * and never on the app hidden behind the panel (Saga 2026-10-13: Ctrl+V with the clipboard
 * editor open pasted into the Chrome textarea behind it, while a custom-swipe paste correctly
 * went to the editor).
 *
 * A Ctrl/Alt/Meta-modified letter reaches [KeyEventHandler.key_up] as a key EVENT
 * (KeyModifier.applyCtrl), not as text, so the [KeyEventHandler] text routing chain never saw
 * it. Harness: a real [KeyEventHandler] (Objenesis + spy so `send_key_down_up`, whose
 * android.jar `KeyEvent` constructor throws "Stub!", records instead) and a recording receiver.
 */
class InlineFieldShortcutTest {

    private val objenesis = ObjenesisStd()
    private val log = mutableListOf<String>()

    /** Which inline field is active; null = none (typing goes to the app). */
    private var mode: String? = null

    private lateinit var handler: KeyEventHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        val context = mockk<Context>(relaxed = true)
        val clipItem = mockk<ClipData.Item>()
        every { clipItem.coerceToText(any()) } returns "clip"
        val clip = mockk<ClipData>()
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns clipItem
        val clipboard = mockk<android.content.ClipboardManager>(relaxed = true)
        every { clipboard.primaryClip } returns clip
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        val editorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val inputConnection = recordingProxy(InputConnection::class.java, "app.") { name ->
            when (name) {
                "getTextBeforeCursor", "getTextAfterCursor" -> ""
                else -> true
            }
        }
        val receiver = recordingProxy(KeyEventHandler.IReceiver::class.java, "") { name ->
            when (name) {
                "getCurrentInputConnection" -> inputConnection
                "getCurrentEditorInfo" -> editorInfo
                "getContext" -> context
                "isClipboardEditMode" -> mode == "edit"
                "isClipboardSearchMode" -> mode == "search"
                "isClipboardTagMode" -> mode == "tag"
                "isEmojiPaneOpen" -> mode == "emoji"
                "isGifPaneOpen" -> mode == "gif"
                else -> null
            }
        }
        handler = spyk(objenesis.newInstance(KeyEventHandler::class.java).apply {
            setField("recv", receiver)
            setField("autocap", mockk<Autocapitalisation>(relaxed = true))
            setField("mods", Pointers.Modifiers.EMPTY)
        })
        every { handler.send_key_down_up(any()) } answers { log += "app.keyDownUp(${firstArg<Int>()})" }
        every { handler.send_key_down_up(any(), any()) } answers { log += "app.keyDownUp(${firstArg<Int>()})" }
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    /** Press [keyCode]'s key event with Ctrl held (the state `updateMetaState` leaves behind). */
    private fun ctrl(keyCode: Int, shift: Boolean = false): List<String> {
        log.clear()
        val shiftMeta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        handler.setField("metaState", KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or shiftMeta)
        handler.key_up(KeyValue.keyeventKey("k", keyCode, 0), Pointers.Modifiers.EMPTY, false)
        return log.filter { !it.startsWith("is") && !it.startsWith("get") }
    }

    private fun pasteKey(): List<String> = editingKey("paste")

    /** Press the layout editing key named [name] (paste, undo, redo, copy …). */
    private fun editingKey(name: String): List<String> {
        log.clear()
        handler.setField("metaState", 0)
        handler.key_up(KeyValue.getKeyByName(name), Pointers.Modifiers.EMPTY, false)
        return log.filter { !it.startsWith("is") && !it.startsWith("get") }
    }

    @Test
    fun ctrlShortcutsInTheClipboardEditorActOnTheEditor() {
        mode = "edit"
        assertEquals(listOf("pasteToClipboardEdit()"), ctrl(KeyEvent.KEYCODE_V))
        assertEquals(listOf("cutFromClipboardEdit()"), ctrl(KeyEvent.KEYCODE_X))
        assertEquals(listOf("selectAllClipboardEdit()"), ctrl(KeyEvent.KEYCODE_A))
        // Pixel 2026-10-14: Ctrl+Z/Y/C act on the editor like in any text field.
        assertEquals(listOf("undoClipboardEdit()"), ctrl(KeyEvent.KEYCODE_Z))
        assertEquals(listOf("redoClipboardEdit()"), ctrl(KeyEvent.KEYCODE_Y))
        assertEquals(listOf("redoClipboardEdit()"), ctrl(KeyEvent.KEYCODE_Z, shift = true))
        assertEquals(listOf("copyFromClipboardEdit()"), ctrl(KeyEvent.KEYCODE_C))
        // Shortcuts without an editor equivalent are swallowed: never sent to the app behind.
        assertEquals(emptyList<String>(), ctrl(KeyEvent.KEYCODE_W))
        assertEquals(emptyList<String>(), ctrl(KeyEvent.KEYCODE_S))
    }

    @Test
    fun undoRedoAndCopyEditingKeysInTheClipboardEditorActOnTheEditor() {
        mode = "edit"
        assertEquals(listOf("undoClipboardEdit()"), editingKey("undo"))
        assertEquals(listOf("redoClipboardEdit()"), editingKey("redo"))
        assertEquals(listOf("copyFromClipboardEdit()"), editingKey("copy"))
    }

    @Test
    fun undoAndCopyShortcutsInTheOtherInlineFieldsStaySwallowed() {
        for (field in listOf("search", "tag", "emoji", "gif")) {
            mode = field
            assertEquals(field, emptyList<String>(), ctrl(KeyEvent.KEYCODE_Z))
            assertEquals(field, emptyList<String>(), ctrl(KeyEvent.KEYCODE_Y))
            assertEquals(field, emptyList<String>(), ctrl(KeyEvent.KEYCODE_C))
            assertEquals(field, emptyList<String>(), editingKey("undo"))
            assertEquals(field, emptyList<String>(), editingKey("copy"))
        }
    }

    @Test
    fun ctrlVInASearchFieldPastesTheClipIntoThatField() {
        mode = "search"
        assertEquals(listOf("appendToClipboardSearch(clip)"), ctrl(KeyEvent.KEYCODE_V))
        assertEquals(emptyList<String>(), ctrl(KeyEvent.KEYCODE_W))
    }

    @Test
    fun thePasteKeyInASearchOrTagFieldPastesIntoThatField() {
        mode = "emoji"
        assertEquals(listOf("appendToEmojiSearch(clip)"), pasteKey())
        mode = "gif"
        assertEquals(listOf("appendToGifSearch(clip)"), pasteKey())
        mode = "tag"
        assertEquals(listOf("insertToClipboardTag(clip)"), pasteKey())
        mode = "search"
        assertEquals(listOf("appendToClipboardSearch(clip)"), pasteKey())
    }

    @Test
    fun withNoInlineFieldCtrlVStillGoesToTheApp() {
        mode = null
        assertEquals(listOf("app.keyDownUp(${KeyEvent.KEYCODE_V})"), ctrl(KeyEvent.KEYCODE_V).filter { it.startsWith("app.keyDownUp") })
    }

    /** A proxy that logs every call as `prefix + name(args)` and answers via [answer]. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> recordingProxy(type: Class<T>, prefix: String, answer: (String) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            log += prefix + method.name + "(" + (args?.joinToString(",") ?: "") + ")"
            val value = answer(method.name)
            when {
                value != null -> value
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        } as T

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                f.set(this, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("no field $name")
    }
}
