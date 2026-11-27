package tribixbite.cleverkeys.clipboard

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.EditEntryResult
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.entry
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.getField

/**
 * Maintainer 2026-11-26: editing `https://github.com/rustdesk/rustdesk-server` into
 * `https://github.com/tribixbite/rustdesk-server` in the clipboard pane, "the entry cant be saved -
 * the checkmark does nothing". A save the service refuses (here DuplicateConflict: the edited text
 * is already another History row, so the per-table dedup refuses the UPDATE) keeps the edit open
 * and shows why on an inline line (D-7, 2026-09-06; Toasts are invisible in the IME). That line
 * never reached the screen: `showEditError` re-binds the row, and the re-bind's `setText` ran
 * while the PREVIOUS bind's TextWatcher was still on the field — its "the user typed, retire the
 * error" branch cleared the error and hid the line in the same call that was to show it.
 * Every refused save (duplicate, changed meanwhile, empty, database error) looked like a dead
 * checkmark. Drives the REAL getView with a platform-like field ([ClipboardEditorRowFixture]).
 */
class ClipboardEditSaveErrorTest {

    private val original = "https://github.com/rustdesk/rustdesk-server"
    private val edited = "https://github.com/tribixbite/rustdesk-server"
    private lateinit var rowFixture: ClipboardEditorRowFixture

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        rowFixture = ClipboardEditorRowFixture(listOf(entry(original)))
    }

    @After
    fun teardown() {
        rowFixture.clear()
        unmockkAll()
    }

    /** Open the editor on the URL and replace the first `rustdesk` (the org) with `tribixbite`. */
    private fun editTheOrgSegment() {
        val view = rowFixture.view
        view.edit_entry(0)
        rowFixture.render()
        val org = original.indexOf("rustdesk")
        rowFixture.userPlacesCaret(org, org + "rustdesk".length)
        view.insertEditText("tribixbite")
        assertWithMessage("precondition: the field holds the edit").that(rowFixture.text).isEqualTo(edited)
    }

    @Test
    fun aRefusedSaveShowsWhyAfterTheRebind() {
        every { rowFixture.service.editEntryContent(any(), any(), any()) } returns EditEntryResult.DuplicateConflict
        editTheOrgSegment()

        rowFixture.view.save_edit()
        rowFixture.render()  // what showEditError's notifyDataSetChanged makes the list do

        verify(exactly = 1) { rowFixture.service.editEntryContent(original, edited, ClipboardTab.HISTORY) }
        assertWithMessage("the edit stays open").that(rowFixture.view.isEditing()).isTrue()
        assertWithMessage("the reason is kept for the row")
            .that(rowFixture.view.getField("editingErrorRes")).isEqualTo(R.string.clipboard_edit_duplicate)
        assertWithMessage("the reason is on screen after the re-bind").that(rowFixture.errorShown).isTrue()
        assertWithMessage("the edit survives the refusal").that(rowFixture.text).isEqualTo(edited)
    }

    @Test
    fun everyRefusalReasonReachesTheScreen() {
        val reasons = mapOf(
            EditEntryResult.EntryChanged to R.string.clipboard_edit_changed,
            EditEntryResult.InvalidContent to R.string.clipboard_edit_invalid,
            EditEntryResult.Error("disk I/O error") to R.string.clipboard_edit_error,
        )
        for ((result, message) in reasons) {
            rowFixture.clear()
            rowFixture = ClipboardEditorRowFixture(listOf(entry(original)))
            every { rowFixture.service.editEntryContent(any(), any(), any()) } returns result
            editTheOrgSegment()

            rowFixture.view.save_edit()
            rowFixture.render()

            assertWithMessage("$result").that(rowFixture.view.getField("editingErrorRes")).isEqualTo(message)
            assertWithMessage("$result is shown").that(rowFixture.errorShown).isTrue()
        }
    }

    @Test
    fun typingAfterARefusedSaveRetiresTheError() {
        every { rowFixture.service.editEntryContent(any(), any(), any()) } returns EditEntryResult.DuplicateConflict
        editTheOrgSegment()
        rowFixture.view.save_edit()
        rowFixture.render()

        rowFixture.view.insertEditText("x")

        assertThat(rowFixture.view.getField("editingErrorRes")).isNull()
        assertWithMessage("the user is addressing the failed save").that(rowFixture.errorShown).isFalse()
    }

    @Test
    fun aSuccessfulSaveEndsTheEdit() {
        every { rowFixture.service.editEntryContent(any(), any(), any()) } returns EditEntryResult.Success
        editTheOrgSegment()

        rowFixture.view.save_edit()

        verify(exactly = 1) { rowFixture.service.editEntryContent(original, edited, ClipboardTab.HISTORY) }
        assertThat(rowFixture.view.isEditing()).isFalse()
    }
}
