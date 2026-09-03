package tribixbite.cleverkeys.clipboard

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardHistoryView
import tribixbite.cleverkeys.ClipboardManager
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.EmojiGridView
import tribixbite.cleverkeys.EmojiGroupButtonsBar
import tribixbite.cleverkeys.EmojiSearchManager
import tribixbite.cleverkeys.R
import java.io.File

/**
 * Two v1.2.8 release-record rows about the content panes' chrome.
 *
 * | note | anchor |
 * |---|---|
 * | "History, Pinned and Todos tabs with icons" | `clipboard/PinnedEntry.kt#PinnedEntry` |
 * | "Close buttons for the emoji and clipboard panes (#80)" | `emoji/EmojiSearchManager.kt#setOnCloseCallback` |
 *
 * Both were PRESENT-UNTESTED. Each has a behavioural half and a presentational half, and they
 * are pinned differently on purpose:
 *
 *  - **Behaviour** (which tab is active, what a close tap does) runs through the real
 *    `ClipboardManager` / `EmojiSearchManager` bodies with mocked views.
 *  - **Presence of the chrome itself** — three tab icons and a close button in each pane — is a
 *    layout fact, so it is asserted against the checked-in XML. That is what a `findViewById`
 *    returning null would silently break: the manager keeps working, the button simply is not
 *    there, and no behavioural test notices.
 */
class ClipboardTabsAndPaneCloseTest {

    private val objenesis = ObjenesisStd()

    private lateinit var history: ImageView
    private lateinit var pinned: ImageView
    private lateinit var todos: ImageView
    private lateinit var listView: ClipboardHistoryView

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        history = mockk(relaxed = true)
        pinned = mockk(relaxed = true)
        todos = mockk(relaxed = true)
        listView = mockk(relaxed = true)
        every { listView.isEditing() } returns false
        every { listView.hasActiveFilters() } returns false
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    /**
     * A ClipboardManager with only the tab wiring seeded. `getClipboardPane` inflates a layout
     * (an android.jar stub) so the constructor and initialisation path are skipped with
     * Objenesis; `switchToTab` and `updateTabHighlighting` then run for real.
     */
    private fun manager(startTab: ClipboardTab = ClipboardTab.HISTORY): ClipboardManager {
        val mgr = objenesis.newInstance(ClipboardManager::class.java)
        mgr.setField("tabHistory", history)
        mgr.setField("tabPinned", pinned)
        mgr.setField("tabTodos", todos)
        mgr.setField("clipboardHistoryView", listView)
        mgr.setField("currentTab", startTab)
        mgr.setField("tagMode", false)
        return mgr
    }

    private fun ClipboardManager.switchTo(tab: ClipboardTab) =
        ClipboardManager::class.java
            .getDeclaredMethod("switchToTab", ClipboardTab::class.java)
            .apply { isAccessible = true }
            .invoke(this, tab)

    // ------------------------------------------------- the three tabs, and only three

    @Test
    fun theClipboardHasExactlyTheThreeAnnouncedTabsInOrder() {
        assertWithMessage(
            "the v1.2.8 note names History, Pinned and Todos. Adding a fourth tab or reordering " +
                "them changes a persisted ordinal-free enum but also the pane the user sees."
        ).that(ClipboardTab.values().map { it.name })
            .containsExactly("HISTORY", "PINNED", "TODOS").inOrder()
    }

    @Test
    fun switchingTabRetargetsTheListAndMovesTheHighlight() {
        val mgr = manager(startTab = ClipboardTab.HISTORY)

        mgr.switchTo(ClipboardTab.PINNED)

        assertThat(mgr.getCurrentTab()).isEqualTo(ClipboardTab.PINNED)
        verify(exactly = 1) { listView.setTab(ClipboardTab.PINNED) }
        // Active tab is fully opaque, the other two are dimmed — the only visual cue for
        // which list is on screen.
        verify { pinned.alpha = 1.0f }
        verify { history.alpha = 0.5f }
        verify { todos.alpha = 0.5f }
    }

    @Test
    fun everyTabCanBeReachedAndCarriesTheHighlightWithIt() {
        val mgr = manager(startTab = ClipboardTab.HISTORY)

        mgr.switchTo(ClipboardTab.TODOS)
        assertThat(mgr.getCurrentTab()).isEqualTo(ClipboardTab.TODOS)
        verify { todos.alpha = 1.0f }

        mgr.switchTo(ClipboardTab.HISTORY)
        assertThat(mgr.getCurrentTab()).isEqualTo(ClipboardTab.HISTORY)
        verify(exactly = 1) { listView.setTab(ClipboardTab.HISTORY) }
        verify { history.alpha = 1.0f }
    }

    @Test
    fun retappingTheActiveTabIsANoOp() {
        val mgr = manager(startTab = ClipboardTab.PINNED)

        mgr.switchTo(ClipboardTab.PINNED)

        // Re-tapping the current tab must not rebuild the list — it would reset scroll
        // position and cancel an in-progress edit for no reason.
        verify(exactly = 0) { listView.setTab(any()) }
        assertThat(mgr.getCurrentTab()).isEqualTo(ClipboardTab.PINNED)
    }

    @Test
    fun switchingTabCancelsAnInProgressEdit() {
        val mgr = manager(startTab = ClipboardTab.HISTORY)

        mgr.switchTo(ClipboardTab.TODOS)

        // The edited entry belongs to the tab being left; carrying the edit across would apply
        // it to a row that is no longer on screen.
        verify(exactly = 1) { listView.cancelEdit() }
    }

    @Test
    fun thePaneLayoutShipsAllThreeTabIconsAndACloseButton() {
        val layout = File("res/layout/clipboard_pane.xml")
        assertWithMessage("expected ${layout.path} (run from project root)")
            .that(layout.isFile).isTrue()
        val xml = layout.readText()

        for ((id, icon) in listOf(
            "tab_history" to "ic_tab_history",
            "tab_pinned" to "ic_tab_pinned",
            "tab_todos" to "ic_tab_todos",
        )) {
            assertWithMessage("clipboard_pane.xml must declare @+id/$id — findViewById returns " +
                "null otherwise and the tab silently disappears")
                .that(xml).contains("@+id/$id")
            assertWithMessage("the note says 'tabs with icons'; @drawable/$icon is $id's")
                .that(xml).contains("@drawable/$icon")
        }

        assertWithMessage("#80: the clipboard pane needs its own close button")
            .that(xml).contains("@+id/clipboard_close_button")
    }

    // ----------------------------------------------------- #80: the close buttons

    @Test
    fun theEmojiPaneCloseButtonInvokesTheRegisteredCallback() {
        val closeButton = mockk<ImageButton>(relaxed = true)
        val listener = slot<View.OnClickListener>()
        every { closeButton.setOnClickListener(capture(listener)) } returns Unit

        val pane = emojiPane(closeButton)
        val manager = EmojiSearchManager()
        var closed = 0
        manager.setOnCloseCallback { closed++ }
        manager.initialize(pane)

        assertWithMessage("initialize() must wire the close button, or #80 does nothing")
            .that(listener.isCaptured).isTrue()

        listener.captured.onClick(closeButton)

        assertWithMessage("one tap on the close chevron returns the user to the keyboard")
            .that(closed).isEqualTo(1)
    }

    @Test
    fun theEmojiPaneCloseButtonIsInertUntilACallbackIsRegistered() {
        val closeButton = mockk<ImageButton>(relaxed = true)
        val listener = slot<View.OnClickListener>()
        every { closeButton.setOnClickListener(capture(listener)) } returns Unit

        val manager = EmojiSearchManager()
        manager.initialize(emojiPane(closeButton))

        // No callback registered: tapping must be a no-op, not an NPE, because the pane is
        // inflated before the service hands over its hide-pane lambda.
        listener.captured.onClick(closeButton)
    }

    @Test
    fun theClipboardPaneCloseButtonIsWiredToTheSameCallbackSurface() {
        // The clipboard wiring lives inside getClipboardPane, which inflates a layout and so
        // cannot run under runMockTests. The public surface is asserted here and the wiring is
        // pinned at the source; the real inflated click is covered on-device.
        val mgr = manager()
        var closed = 0
        mgr.setOnCloseCallback { closed++ }

        val field = ClipboardManager::class.java.declaredFields
            .firstOrNull { it.name == "onCloseCallback" }
        assertWithMessage("ClipboardManager.onCloseCallback was renamed — re-point this guard")
            .that(field).isNotNull()
        field!!.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(mgr) as () -> Unit).invoke()
        assertThat(closed).isEqualTo(1)

        val source = File("src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardManager.kt")
        assertWithMessage("expected ${source.path} (run from project root)")
            .that(source.isFile).isTrue()
        val text = source.readText()
        assertWithMessage(
            "the inflated clipboard_close_button must invoke the registered callback (#80)"
        ).that(text.replace(Regex("\\s+"), " "))
            .contains("findViewById<ImageButton>(R.id.clipboard_close_button)?.setOnClickListener { onCloseCallback?.invoke() }")
    }

    // ------------------------------------------------------------------ fixtures

    /** An emoji pane whose findViewById answers with mocks, close button included. */
    private fun emojiPane(closeButton: ImageButton): ViewGroup {
        val pane = mockk<ViewGroup>(relaxed = true)
        every { pane.findViewById<EditText>(R.id.emoji_search_input) } returns mockk(relaxed = true)
        every { pane.findViewById<ImageButton>(R.id.emoji_search_clear) } returns mockk(relaxed = true)
        every { pane.findViewById<ImageButton>(R.id.emoji_close_button) } returns closeButton
        every { pane.findViewById<TextView>(R.id.emoji_no_results) } returns mockk(relaxed = true)
        every { pane.findViewById<EmojiGridView>(R.id.emoji_grid) } returns mockk(relaxed = true)
        every { pane.findViewById<EmojiGroupButtonsBar>(R.id.emoji_group_buttons) } returns mockk(relaxed = true)
        return pane
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName} — it was renamed or removed; " +
                "declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }
}
