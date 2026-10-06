package tribixbite.cleverkeys

import android.os.Handler
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * KeyEventHandler's side of the learn-funnel bookkeeping (learning-system audit 2026-09-26,
 * W2 / W5): the events only this class can see are reported to [KeyEventHandler.LearningHooks].
 *
 *  - W5: Enter (a key event) and the IME action (an editor action) end the typed word without
 *    passing through `handle_text_typed`, so the learn funnel never saw that word. The boundary
 *    must be reported BEFORE the newline/action is sent, while the editor still ends with it.
 *  - W2: the #110 backspace undos delete a swiped word / revert an autocorrect — the rejected
 *    word must be reported so its learn is rolled back.
 *
 * The hook's RECEIVER behaviour (what gets learned / rolled back) is covered against the real
 * learn funnel in [LearningFunnelBookkeepingTest].
 *
 * Setup note: `sendKeyevent` constructs an android.view.KeyEvent, whose android.jar stub
 * constructor throws. It returns before that when the InputConnection is null, so the Enter
 * tests hand the hook the connection and every later lookup null.
 */
class KeyEventHandlerLearningHooksTest {

    @Test
    fun customTerminalPackageUsesDirectClipboardPasteForBothKeyRoutes() {
        val context = mockk<Context>()
        val clipboard = mockk<ClipboardManager>()
        val clip = mockk<ClipData>()
        val item = mockk<ClipData.Item>()
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
        every { clipboard.primaryClip } returns clip
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        every { item.coerceToText(context) } returns "terminal-paste-fixture"
        every { recv.getContext() } returns context
        val editor = mockk<EditorInfo>(relaxed = true).apply { packageName = "org.custom.shell" }
        every { recv.getCurrentEditorInfo() } returns editor
        every { Config.globalConfigOrNull() } returns config
        config.custom_terminal_packages = setOf("org.custom.shell")
        release("paste")
        val executor = tribixbite.cleverkeys.customization.CustomShortSwipeExecutor(context)
        val mapping = tribixbite.cleverkeys.customization.ShortSwipeMapping(
            keyCode = "m", direction = tribixbite.cleverkeys.customization.SwipeDirection.SW,
            displayText = "📋", actionType = tribixbite.cleverkeys.customization.ActionType.COMMAND,
            actionValue = "paste"
        )
        org.junit.Assert.assertTrue(executor.execute(mapping, conn, editor))
        verify(exactly = 2) { conn.commitText("terminal-paste-fixture", 1) }
        verify(exactly = 0) { conn.performContextMenuAction(android.R.id.paste) }

        config.custom_terminal_packages = emptySet()
        every { conn.performContextMenuAction(android.R.id.paste) } returns true
        release("paste")
        org.junit.Assert.assertTrue(executor.execute(mapping, conn, editor))
        verify(exactly = 2) { conn.performContextMenuAction(android.R.id.paste) }
        verify(exactly = 2) { conn.commitText("terminal-paste-fixture", 1) }
        // Editor siblings must not inherit the custom package's behavior.
        config.custom_terminal_packages = setOf("org.custom.shell.child")
        release("paste")
        verify(exactly = 3) { conn.performContextMenuAction(android.R.id.paste) }
    }

    private lateinit var recv: KeyEventHandler.IReceiver
    private lateinit var conn: InputConnection
    private lateinit var hooks: KeyEventHandler.LearningHooks
    private lateinit var handler: KeyEventHandler
    private lateinit var config: Config

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.backspace_undo_swipe = true
        config.backspace_undo_autocorrect = true
        every { Config.globalConfig() } returns config

        conn = mockk(relaxed = true)
        recv = mockk(relaxed = true)
        every { recv.getHandler() } returns mockk<Handler>(relaxed = true)
        every { recv.isClipboardTagMode() } returns false
        every { recv.isClipboardEditMode() } returns false
        every { recv.isClipboardSearchMode() } returns false
        every { recv.isEmojiPaneOpen() } returns false
        every { recv.isGifPaneOpen() } returns false
        every { recv.getLastAutocorrectOriginalWord() } returns null
        every { recv.getLastAutoInsertedWord() } returns null
        every { recv.getCurrentInputConnection() } returns conn

        hooks = mockk(relaxed = true)
        handler = KeyEventHandler(recv)
        handler.learningHooks = hooks
    }

    @After
    fun teardown() = unmockkAll()

    private fun release(name: String) =
        handler.key_up(KeyValue.getKeyByName(name), Pointers.Modifiers.EMPTY, false)

    // ================================================================ W5

    @Test
    fun enterReportsAWordBoundaryWithTheLiveConnection() {
        every { recv.getCurrentInputConnection() } returnsMany listOf(conn, null)

        release("enter")

        verify(exactly = 1) { hooks.onEditorWordBoundary(conn) }
    }

    @Test
    fun theImeActionReportsTheBoundaryBeforePerformingTheAction() {
        release("action")

        verifyOrder {
            hooks.onEditorWordBoundary(conn)
            recv.handle_event_key(KeyValue.Event.ACTION)
        }
    }

    @Test
    fun otherKeysReportNoBoundary() {
        every { recv.getCurrentInputConnection() } returns null
        release("tab")
        release("a")

        verify(exactly = 0) { hooks.onEditorWordBoundary(any()) }
    }

    // ================================================================ W2

    @Test
    fun backspaceSwipeUndoReportsTheRejectedWord() {
        every { recv.getLastAutoInsertedWord() } returns "got"
        every { conn.getTextBeforeCursor(any(), any()) } returns "got "

        release("backspace")

        verify { conn.deleteSurroundingText(4, 0) }
        verify(exactly = 1) { hooks.onSwipeWordUndone("got", conn) }
        verify(exactly = 0) { hooks.onAutocorrectUndone(any(), any(), any()) }
    }

    @Test
    fun backspaceAutocorrectUndoReportsCorrectionAndOriginal() {
        every { recv.getLastAutocorrectOriginalWord() } returns "teh"
        every { recv.getLastAutoInsertedWord() } returns "the"
        every { conn.getTextBeforeCursor(any(), any()) } returns "the "

        release("backspace")

        verify { conn.commitText("teh ", 1) }
        verify(exactly = 1) { hooks.onAutocorrectUndone("the", "teh", true) }
        verify(exactly = 0) { hooks.onSwipeWordUndone(any(), any()) }
    }

    @Test
    fun anAutocorrectUndoWithoutTrailingSpaceReportsAnOpenWord() {
        every { recv.getLastAutocorrectOriginalWord() } returns "teh"
        every { recv.getLastAutoInsertedWord() } returns "the"
        every { conn.getTextBeforeCursor(any(), any()) } returns "the"

        release("backspace")

        verify(exactly = 1) { hooks.onAutocorrectUndone("the", "teh", false) }
    }

    @Test
    fun anOrdinaryBackspaceReportsNothing() {
        every { recv.getCurrentInputConnection() } returns null

        release("backspace")

        verify(exactly = 0) { hooks.onSwipeWordUndone(any(), any()) }
        verify(exactly = 0) { hooks.onAutocorrectUndone(any(), any(), any()) }
        verify(exactly = 0) { hooks.onEditorWordBoundary(any()) }
    }
}
