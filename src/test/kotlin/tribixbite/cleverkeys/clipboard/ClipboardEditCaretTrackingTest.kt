package tribixbite.cleverkeys.clipboard

import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.EditEntryResult
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.entry

/**
 * Maintainer 2026-11-26 (inline clipboard editor, a URL that wraps mid-word): the caret "is just
 * in the wrong place". A tap, a handle drag or a long press moves the field's selection inside
 * the platform; the view tracked only its OWN edits (`editingCursorPosition`) and every re-bind of
 * the row — a refused save showing its reason, a page move, a load landing during the edit — set
 * the field's text again and put the caret back at that stale position. [ClipboardEditField]
 * reports every selection change; the tracked caret follows it.
 */
class ClipboardEditCaretTrackingTest {

    private val url = "https://github.com/rustdesk/rustdesk-server"
    private lateinit var rowFixture: ClipboardEditorRowFixture
    private var listener: ((Int, Int) -> Unit)? = null

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        val field = mockk<ClipboardEditField>(relaxed = true)
        every { field.selectionListener = any() } answers { listener = firstArg() }
        every { field.selectionListener } answers { listener }
        rowFixture = ClipboardEditorRowFixture(listOf(entry(url)), field = field)
        // The real ClipboardEditField calls its listener from TextView.onSelectionChanged.
        rowFixture.onSelectionChanged = { start, end -> listener?.invoke(start, end) }
    }

    @After
    fun teardown() {
        rowFixture.clear()
        unmockkAll()
    }

    @Test
    fun aTappedCaretSurvivesARebindOfTheRow() {
        rowFixture.view.edit_entry(0)
        rowFixture.render()
        val afterOrg = url.indexOf("rustdesk") + "rustdesk".length

        rowFixture.userPlacesCaret(afterOrg)  // a tap after the first "rustdesk"
        rowFixture.render()                   // e.g. a refused save re-binding the row

        assertWithMessage("the caret stays where the user put it")
            .that(rowFixture.selectionEnd).isEqualTo(afterOrg)
        assertWithMessage("and it is a caret, not a range").that(rowFixture.selectionStart).isEqualTo(afterOrg)
    }

    @Test
    fun typingAfterATapAndARefusedSaveInsertsAtTheTap() {
        every { rowFixture.service.editEntryContent(any(), any(), any()) } returns EditEntryResult.DuplicateConflict
        rowFixture.view.edit_entry(0)
        rowFixture.render()
        val org = url.indexOf("rustdesk")

        rowFixture.userPlacesCaret(org)
        rowFixture.view.save_edit()   // refused: the row re-binds to show why
        rowFixture.render()
        rowFixture.view.insertEditText("tribixbite/")

        assertWithMessage("the text goes where the user tapped, not where the keyboard last typed")
            .that(rowFixture.text).isEqualTo("https://github.com/tribixbite/rustdesk/rustdesk-server")
    }

    @Test
    fun theKeyboardsOwnCaretMovesStillHoldThroughARebind() {
        rowFixture.view.edit_entry(0)
        rowFixture.render()

        rowFixture.view.dispatchKeyToEditText(android.view.KeyEvent.KEYCODE_MOVE_HOME)
        rowFixture.render()

        assertWithMessage("Home put the caret at 0; the re-bind keeps it there")
            .that(rowFixture.selectionEnd).isEqualTo(0)
    }
}
