package tribixbite.cleverkeys

import android.content.ClipData
import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import java.lang.reflect.Proxy

/**
 * Saga 2026-10-25: a 524,000-char clipboard entry could not be pasted and the user saw nothing.
 * The pane tap sent it in ONE `commitText`; on API 34 that oneway binder transaction carries the
 * text as UTF-8 (parcel 524,156 bytes) and exceeds the receiving process's async binder space
 * (half of its ~1 MiB buffer), so the kernel refused it (FAILED BINDER TRANSACTION) and the
 * IME-side `commitText` returned false, unread. A 200,000-char entry fit and pasted.
 *
 * Every path that commits a clipboard entry into the app must split large text into ordered
 * chunks well below that limit, inside one batch edit, and tell the user when the app's
 * connection keeps refusing a chunk. Harness: a real [KeyEventHandler] (Objenesis) with a recording
 * receiver and a recording InputConnection (the [InlineFieldShortcutTest] idiom).
 */
class ClipboardLargePasteTest {

    private val objenesis = ObjenesisStd()

    /** Every receiver / connection call, in order. */
    private val log = mutableListOf<String>()

    /** The text of every ACCEPTED `commitText` call, in order. */
    private val commits = mutableListOf<String>()

    /** From this 1-based `commitText` attempt on, the IPC refuses every call; 0 = never. */
    private var refuseFromAttempt = 0
    private var attempts = 0

    /** Current batch-edit nesting and the deepest nesting any commit was made at. */
    private var batchDepth = 0
    private val commitDepths = mutableListOf<Int>()

    private var clipText = "clip"
    private lateinit var editorInfo: EditorInfo
    private lateinit var handler: KeyEventHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } answers { "res#${firstArg<Int>()}" }
        val clipItem = mockk<ClipData.Item>()
        every { clipItem.coerceToText(any()) } answers { clipText }
        val clip = mockk<ClipData>()
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns clipItem
        val clipboard = mockk<android.content.ClipboardManager>(relaxed = true)
        every { clipboard.primaryClip } returns clip
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        editorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val inputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
        ) { _, method, args ->
            log += "app." + method.name
            when (method.name) {
                "beginBatchEdit" -> { batchDepth++; true }
                "endBatchEdit" -> { batchDepth--; batchDepth > 0 }
                "commitText" -> {
                    commitDepths += batchDepth
                    val accepted = refuseFromAttempt == 0 || ++attempts < refuseFromAttempt
                    if (accepted) commits += (args!![0] as CharSequence).toString()
                    accepted
                }
                "getTextBeforeCursor", "getTextAfterCursor" -> ""
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
            }
        } as InputConnection
        val receiver = Proxy.newProxyInstance(
            KeyEventHandler.IReceiver::class.java.classLoader, arrayOf(KeyEventHandler.IReceiver::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getCurrentInputConnection" -> inputConnection
                "getCurrentEditorInfo" -> editorInfo
                "getContext" -> context
                "showPrivateCopyFeedback" -> { log += "feedback(${args!![0]})"; null }
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } as KeyEventHandler.IReceiver
        handler = objenesis.newInstance(KeyEventHandler::class.java).apply {
            setField("recv", receiver)
            setField("autocap", mockk<Autocapitalisation>(relaxed = true))
            setField("mods", Pointers.Modifiers.EMPTY)
        }
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    /** The Saga entry's size, with surrogate pairs and line breaks spread through it. */
    private fun hugeEntry(chars: Int = 524_000): String {
        val sb = StringBuilder(chars + 16)
        var line = 0
        while (sb.length < chars) {
            sb.append("CK").append(line++).append(" 😀 ünï\n")
        }
        // Cut on a code-point boundary so the fixture itself is well-formed.
        var end = chars
        if (Character.isHighSurrogate(sb[end - 1])) end--
        return sb.substring(0, end)
    }

    private fun assertChunkedCommit(expected: String) {
        assertTrue("a large entry must be split, got ${commits.size} commit(s)", commits.size > 1)
        assertEquals("chunks must reassemble the entry exactly, in order", expected, commits.joinToString(""))
        for ((i, chunk) in commits.withIndex()) {
            assertTrue("commit $i has ${chunk.length} chars; must stay well below the binder limit",
                chunk.length <= MAX_SAFE_CHARS)
            assertFalse("commit $i ends inside a surrogate pair", Character.isHighSurrogate(chunk.last()))
            assertFalse("commit $i starts inside a surrogate pair", Character.isLowSurrogate(chunk.first()))
        }
        assertTrue("every chunk must be committed inside one batch edit, depths=$commitDepths",
            commitDepths.all { it == 1 })
        assertEquals("one batch edit wraps the whole paste", 1, log.count { it == "app.beginBatchEdit" })
        assertEquals(0, batchDepth)
    }

    @Test
    fun paneTapOfAHugeEntryCommitsOrderedChunksInsideOneBatchEdit() {
        val entry = hugeEntry()
        handler.paste_from_clipboard_pane(entry)
        assertChunkedCommit(entry)
        assertFalse("a complete paste shows no message", log.any { it.startsWith("feedback(") })
    }

    @Test
    fun terminalPasteKeyOfAHugeClipIsChunkedToo() {
        editorInfo.packageName = "com.termux"
        clipText = hugeEntry(300_000)
        handler.key_up(KeyValue.getKeyByName("paste"), Pointers.Modifiers.EMPTY, false)
        assertChunkedCommit(clipText)
    }

    @Test
    fun blindRemoteFieldPasteKeyOfAHugeClipIsChunkedToo() {
        editorInfo.inputType = android.text.InputType.TYPE_NULL
        clipText = hugeEntry(300_000)
        handler.key_up(KeyValue.getKeyByName("paste"), Pointers.Modifiers.EMPTY, false)
        assertChunkedCommit(clipText)
    }

    @Test
    fun aChunkThatStaysRefusedStopsThePasteAndTellsTheUser() {
        refuseFromAttempt = 3
        handler.paste_from_clipboard_pane(hugeEntry())
        assertEquals("nothing is delivered after the refused chunk", 2, commits.size)
        assertEquals("the batch edit is still closed", 0, batchDepth)
        assertTrue("the user is told the paste is incomplete: $log",
            log.contains("feedback(res#${R.string.clipboard_paste_incomplete})"))
    }

    @Test
    fun aRefusedSmallPasteAlsoTellsTheUser() {
        refuseFromAttempt = 1
        handler.paste_from_clipboard_pane("short entry")
        assertTrue(log.contains("feedback(res#${R.string.clipboard_paste_incomplete})"))
    }

    @Test
    fun aSmallEntryIsStillOneCommitInOneBatchEdit() {
        handler.paste_from_clipboard_pane("short entry")
        assertEquals(listOf("short entry"), commits)
        assertEquals(listOf("app.beginBatchEdit", "app.commitText", "app.endBatchEdit"),
            log.filter { it.startsWith("app.") })
    }

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

    private companion object {
        /**
         * Generous upper bound for one commit: 64 Ki chars is at most 192 KiB as UTF-8 (3 bytes
         * per UTF-16 unit) or 128 KiB as UTF-16, well under half of a 1 MiB binder buffer.
         */
        const val MAX_SAFE_CHARS = 64 * 1024
    }
}
