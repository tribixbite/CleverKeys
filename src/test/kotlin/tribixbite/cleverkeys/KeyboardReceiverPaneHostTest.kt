package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * gh #148 (stale-bot buried, verified live 2026-09-06) — content panes must NEVER replace
 * the whole input view.
 *
 * The container fix (cb7cebd4, ARC-002) makes PredictionViewSetup build the
 * topPane/contentPaneContainer hierarchy even with word prediction AND swipe typing both
 * off, so KeyboardReceiver's pane openers always have a host. What remained at HEAD were
 * the `keyboard2.setInputView(<bare pane>)` fallbacks in the openers themselves: dead in
 * the normal lifecycle, but exactly the #148 symptom (keyboard body vanishes, pane lands
 * behind the nav bar because the bare pane bypasses the aafec4da inset ladder) should any
 * propagation regression hand the receiver a null container again.
 *
 * This test pins the behavior at the receiver seam, mock-tier:
 *  1. With a container present, a pane open hosts the pane IN the container
 *     (container.addView + SuggestionBarPane.switchToContentPaneMode) and never calls
 *     keyboard2.setInputView.
 *  2. With the container absent (the pre-cb7cebd4 both-off state), the opener REFUSES —
 *     it must not fall back to setInputView(pane). The keyboard stays; the pane simply
 *     does not open.
 *  3. SWITCH_BACK with the container absent likewise never calls setInputView.
 *
 * RED (2026-09-06, pre-fix): cases 2/3 failed — SWITCH_CLIPBOARD/SWITCH_EMOJI/SWITCH_GIF
 * each called keyboard2.setInputView(<pane>) via their `?: run { … }` fallbacks, and
 * SWITCH_BACK_* called setInputView(keyboardView).
 */
class KeyboardReceiverPaneHostTest {

    private val objenesis = org.objenesis.ObjenesisStd()

    private lateinit var context: Context
    private lateinit var keyboard2: CleverKeysService
    // ClipboardManager lives in clipboard/ (directory-only grouping, ARC-048) but declares
    // this package — unqualified name resolves to the project class, not android.content's.
    private lateinit var clipboardManager: ClipboardManager

    private lateinit var receiver: KeyboardReceiver

    private lateinit var clipboardPane: ViewGroup
    private lateinit var inflatedPane: ViewGroup

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        context = mockk(relaxed = true)
        keyboard2 = mockk(relaxed = true)
        clipboardManager = mockk(relaxed = true)

        // Device is unlocked (clipboard pane is not blocked)
        mockkObject(DirectBootManager.Companion)
        val dbm = mockk<DirectBootManager>()
        every { DirectBootManager.getInstance(any()) } returns dbm
        every { dbm.isDeviceLocked } returns false

        // GIF panel enabled
        mockkObject(Config.Companion)
        val cfg = mockk<Config>(relaxed = true)
        every { Config.globalConfig() } returns cfg
        // gif_enabled / gif_thumbnail_columns are @JvmField — assign directly, not via every.
        cfg.gif_enabled = true
        cfg.gif_thumbnail_columns = 3

        // Pane view doubles. findViewById returns null so the wiring after the host
        // decision degrades to safe-call no-ops.
        clipboardPane = mockk(relaxed = true)
        every { clipboardPane.parent } returns null
        inflatedPane = mockk(relaxed = true)
        every { inflatedPane.parent } returns null
        every { inflatedPane.findViewById<View>(any()) } returns null

        every { clipboardManager.getClipboardPane(any()) } returns clipboardPane
        every { keyboard2.inflate_view(any()) } returns inflatedPane

        // switchTo*Mode are @JvmStatic object functions — Kotlin call sites dispatch through
        // the static bridge, so mockkStatic (not mockkObject) is what intercepts them. The
        // real bodies construct FrameLayout.LayoutParams, which the android.jar stub throws on.
        mockkStatic(SuggestionBarPane::class)
        every { SuggestionBarPane.switchToContentPaneMode(any(), any(), any(), any()) } just Runs
        every { SuggestionBarPane.switchToSuggestionBarMode(any(), any(), any(), any()) } just Runs

        // The receiver's own pane-sizing seam constructs FrameLayout.LayoutParams too.
        mockkObject(KeyboardReceiver.Companion)
        every { KeyboardReceiver.paneLayoutParams(any()) } returns mockk(relaxed = true)

        // Objenesis allocation + field seeding (same idiom as ContractionUserWordGuardTest /
        // SuggestionTrailingSpaceRepairTest): a constructed KeyboardReceiver needs a
        // Keyboard2View, whose companion <clinit> builds a RectF — the android.jar stub
        // constructor throws "Stub!" before MockK can intercept anything. The pane paths
        // under test never touch keyboardView, so it stays null.
        receiver = objenesis.newInstance(KeyboardReceiver::class.java)
        receiver.setField("context", context)
        receiver.setField("keyboard2", keyboard2)
        receiver.setField("clipboardManager", clipboardManager)
        // Objenesis skips field initializers; seed the enum-typed pane tracker to its default.
        seedPaneTypeNone(receiver)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = this.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    /** currentPaneType's initializer (`PaneType.NONE`) does not run under Objenesis. */
    private fun seedPaneTypeNone(target: KeyboardReceiver) {
        val field = KeyboardReceiver::class.java.getDeclaredField("currentPaneType")
        field.isAccessible = true
        val enumClass = field.type
        val none = enumClass.enumConstants!!.first { (it as Enum<*>).name == "NONE" }
        field.set(target, none)
    }

    private fun wireContainer(): Triple<ViewGroup, FrameLayout, FrameLayout> {
        val emojiPane = mockk<ViewGroup>(relaxed = true)
        val container = mockk<FrameLayout>(relaxed = true)
        val topPane = mockk<FrameLayout>(relaxed = true)
        val scrollView = mockk<HorizontalScrollView>(relaxed = true)
        receiver.setViewReferences(emojiPane, container, topPane, scrollView, 0, 400)
        return Triple(emojiPane, container, topPane)
    }

    // ── 1. Normal path: pane hosted in the container ─────────────────────────────

    @Test
    fun clipboardPaneIsHostedInTheContentPaneContainer() {
        val (_, container, topPane) = wireContainer()

        receiver.handle_event_key(KeyValue.Event.SWITCH_CLIPBOARD)

        verify(exactly = 1) { container.addView(clipboardPane) }
        verify(exactly = 1) {
            SuggestionBarPane.switchToContentPaneMode(topPane, container, any(), 400)
        }
        verify(exactly = 0) { keyboard2.setInputView(any()) }
    }

    @Test
    fun gifPaneIsHostedInTheContentPaneContainer() {
        val (_, container, _) = wireContainer()

        receiver.handle_event_key(KeyValue.Event.SWITCH_GIF)

        verify(exactly = 1) { container.addView(inflatedPane) }
        verify(exactly = 0) { keyboard2.setInputView(any()) }
    }

    @Test
    fun emojiPaneIsHostedInTheContentPaneContainer() {
        val (_, container, _) = wireContainer()

        receiver.handle_event_key(KeyValue.Event.SWITCH_EMOJI)

        verify(exactly = 1) { container.addView(inflatedPane) }
        verify(exactly = 0) { keyboard2.setInputView(any()) }
    }

    // ── 2. Missing container: refuse, never replace the keyboard ────────────────

    @Test
    fun clipboardOpenWithoutContainerNeverReplacesTheInputView() {
        // No setViewReferences call — the pre-ARC-002 both-off state.
        receiver.handle_event_key(KeyValue.Event.SWITCH_CLIPBOARD)

        verify(exactly = 0) { keyboard2.setInputView(any()) }
    }

    @Test
    fun emojiOpenWithoutContainerNeverReplacesTheInputView() {
        receiver.handle_event_key(KeyValue.Event.SWITCH_EMOJI)

        verify(exactly = 0) { keyboard2.setInputView(any()) }
    }

    @Test
    fun gifOpenWithoutContainerNeverReplacesTheInputView() {
        receiver.handle_event_key(KeyValue.Event.SWITCH_GIF)

        verify(exactly = 0) { keyboard2.setInputView(any()) }
    }

    // ── 3. SWITCH_BACK without container: no setInputView restore either ────────

    @Test
    fun switchBackWithoutContainerNeverCallsSetInputView() {
        receiver.handle_event_key(KeyValue.Event.SWITCH_CLIPBOARD)
        receiver.handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)

        verify(exactly = 0) { keyboard2.setInputView(any()) }
    }

    // ── gh #149: GIF tap path — never a dead URL ─────────────────────────────────
    //
    // Build.VERSION.SDK_INT is 0 under the android.jar stubs, so the commitContent
    // branch is exercised by GifInsertPolicyTest (pure matrix); here we pin the
    // receiver-level outcomes reachable below API 25: the case-preserved URL text
    // path, and that a legacy pack (case-smashed keywords, no marked ID) commits
    // NOTHING instead of the old dead 404 link.

    @Test
    fun gifTapWithMarkedIdCommitsTheCasePreservedUrl() {
        val ic = mockk<android.view.inputmethod.InputConnection>(relaxed = true)
        every { keyboard2.currentInputConnection } returns ic
        every { keyboard2.currentInputEditorInfo } returns null
        every { context.filesDir } returns java.io.File("/nonexistent-cleverkeys-test")

        receiver.insertGif(
            tribixbite.cleverkeys.gif.Gif(
                id = 7, width = 1, height = 1,
                searchText = "cute cat gid:CdMYfhPEanE9CkV6Ys"
            )
        )

        verify(exactly = 1) {
            ic.commitText("https://media.giphy.com/media/CdMYfhPEanE9CkV6Ys/giphy.gif", 1)
        }
    }

    @Test
    fun gifTapOnLegacyPackNeverCommitsADeadUrl() {
        val ic = mockk<android.view.inputmethod.InputConnection>(relaxed = true)
        every { keyboard2.currentInputConnection } returns ic
        every { keyboard2.currentInputEditorInfo } returns null
        every { context.filesDir } returns java.io.File("/nonexistent-cleverkeys-test")

        // The reporter's pack shape: lowercased keywords, trailing compound token —
        // pre-fix this committed https://media.giphy.com/media/cutecdmyfhpeane9ckv6ys/giphy.gif.
        receiver.insertGif(
            tribixbite.cleverkeys.gif.Gif(
                id = 8, width = 1, height = 1,
                searchText = "cute cat cutecdmyfhpeane9ckv6ys"
            )
        )

        verify(exactly = 0) { ic.commitText(any(), any()) }
        // Feedback goes through the suggestion bar (IME toasts are suppressed on 13+).
        verify(exactly = 1) { keyboard2.showSuggestionBarMessage("GIF media unavailable", any()) }
    }

    // ── 4. Pane close in the hosted path restores suggestion-bar mode ────────────

    @Test
    fun switchBackInHostedPathRestoresSuggestionBarMode() {
        val (_, container, topPane) = wireContainer()

        receiver.handle_event_key(KeyValue.Event.SWITCH_CLIPBOARD)
        receiver.handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)

        verify(exactly = 1) {
            SuggestionBarPane.switchToSuggestionBarMode(topPane, container, any(), any())
        }
        verify(exactly = 0) { keyboard2.setInputView(any()) }
        // The pane state must have reset so the next open is a fresh open, not a toggle-close.
        receiver.handle_event_key(KeyValue.Event.SWITCH_CLIPBOARD)
        verify(exactly = 2) { container.addView(clipboardPane) }
    }
}
