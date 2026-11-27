package tribixbite.cleverkeys.clipboard

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.entry

/**
 * What a cut or copy INSIDE the clipboard editor leaves on Android's clipboard after Save.
 *
 * Maintainer decision 2026-11-26 (on c9d61bcdd, which made Save replace such a fragment with the
 * edited entry): "the behavior I have installed on this device does it correctly - anything cut
 * or copied appears above the entry being edited. The bug I reported was unrelated and was simply
 * editing the entry." So a fragment cut or copied in the editor (Ctrl+X / Ctrl+C, the cut and copy
 * keys, the field's text toolbar) STAYS on Android's clipboard — the paste key gives it — and is
 * captured as a new History row above the entry being edited; Save never replaces it. An edit with
 * no cut or copy still follows when the row is the clip (EditedClipFollow rules 1–2).
 *
 * REAL view (getView, cut, typing, save) and REAL service (capture, follow decision, writes) over a
 * fake Android clipboard and a fake History table ([ClipboardServiceFixture]).
 */
class ClipboardEditCutFollowTest {

    private val url = "https://github.com/rustdesk/rustdesk-server"
    private val edited = "https://github.com/tribixbite/rustdesk-server"

    private lateinit var services: ClipboardServiceFixture
    private lateinit var rowFixture: ClipboardEditorRowFixture

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        ClipboardServiceFixture.mockStatics()
        services = ClipboardServiceFixture(initialHistory = listOf(url))
        rowFixture = ClipboardEditorRowFixture(listOf(entry(url)), service = services.service, context = services.context)
    }

    @After
    fun teardown() {
        rowFixture.clear()
        unmockkAll()
    }

    /** Replace the row fixture's field with a [ClipboardEditField] mock; returns its toolbar handler. */
    private fun withEditFieldMock(): () -> ((Int) -> Boolean)? {
        var handler: ((Int) -> Boolean)? = null
        val field = mockk<ClipboardEditField>(relaxed = true)
        every { field.textMenuHandler = any() } answers { handler = firstArg() }
        every { field.textMenuHandler } answers { handler }
        rowFixture.clear()
        rowFixture = ClipboardEditorRowFixture(listOf(entry(url)), service = services.service, field = field, context = services.context)
        return { handler }
    }

    /** Open the URL row; select the first `rustdesk` (the org segment). */
    private fun openAndSelectTheOrg() {
        rowFixture.view.edit_entry(0)
        rowFixture.render()
        val org = url.indexOf("rustdesk")
        rowFixture.userPlacesCaret(org, org + "rustdesk".length)
    }

    @Test
    fun aFragmentCutInTheEditorStaysOnTheClipboardAboveTheEditedEntry() {
        services.systemClip = url  // just copied
        openAndSelectTheOrg()

        rowFixture.view.cutFromEditText()
        assertWithMessage("precondition: the cut put the fragment on Android's clipboard").that(services.systemClip).isEqualTo("rustdesk")
        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("the paste key pastes Android's clipboard: it still holds the cut fragment")
            .that(services.systemClip).isEqualTo("rustdesk")
        assertWithMessage("the fragment is a History row above the edited entry")
            .that(services.history).containsExactly("rustdesk", edited).inOrder()
        assertThat(rowFixture.view.isEditing()).isFalse()
    }

    @Test
    fun aFragmentCopiedInTheEditorStaysOnTheClipboardAboveTheEditedEntry() {
        services.systemClip = url
        openAndSelectTheOrg()

        rowFixture.view.copyFromEditText()          // Ctrl+C on "rustdesk"
        rowFixture.view.insertEditText("tribixbite") // then type over the selection
        rowFixture.view.save_edit()

        assertWithMessage("copy then save: the copied fragment is what the paste gives")
            .that(services.systemClip).isEqualTo("rustdesk")
        assertThat(services.history).containsExactly("rustdesk", edited).inOrder()
    }

    @Test
    fun withoutACutTheClipBeingEditedFollowsAsBefore() {
        services.systemClip = url
        openAndSelectTheOrg()

        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("the edited History row that is the clip replaces it (rule 1)").that(services.systemClip).isEqualTo(edited)
        assertWithMessage("and the service's own write adds no second row").that(services.history).containsExactly(edited)
    }

    @Test
    fun anOldRowsCutFragmentStaysOnTheClipboard() {
        services.systemClip = "meet at 5"  // the clip is another clipping; the URL row is an old one
        openAndSelectTheOrg()

        rowFixture.view.cutFromEditText()
        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("an old row's edit never replaces the clip; the user's cut stays there")
            .that(services.systemClip).isEqualTo("rustdesk")
    }

    /**
     * The field's own text toolbar (long press or double tap on `rustdesk`, then Cut) runs the
     * editor's cut: the #156 private confirmation and the editor's undo history apply, and the
     * fragment stays on the clipboard after Save like any other cut in the editor.
     */
    @Test
    fun theTextToolbarsCutIsTheEditorsCut() {
        val handler = withEditFieldMock()
        services.systemClip = url
        openAndSelectTheOrg()

        val handled = handler()?.invoke(android.R.id.cut) ?: false
        assertWithMessage("the editor handles the toolbar's Cut").that(handled).isTrue()
        assertWithMessage("the fragment is on Android's clipboard").that(services.systemClip).isEqualTo("rustdesk")
        assertWithMessage("and out of the field").that(rowFixture.text).isEqualTo("https://github.com//rustdesk-server")
        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("Save leaves the toolbar's cut on the clipboard").that(services.systemClip).isEqualTo("rustdesk")
        assertThat(services.history).containsExactly("rustdesk", edited).inOrder()
    }

    @Test
    fun theTextToolbarLeavesOtherActionsToThePlatform() {
        val handler = withEditFieldMock()
        openAndSelectTheOrg()

        assertWithMessage("select all is the platform's").that(handler()?.invoke(android.R.id.selectAll)).isFalse()
        assertWithMessage("share is the platform's").that(handler()?.invoke(android.R.id.shareText)).isFalse()
    }

    @Test
    fun aCopyMadeElsewhereAfterTheCutIsNeverOverwritten() {
        services.systemClip = url
        openAndSelectTheOrg()

        rowFixture.view.cutFromEditText()
        services.systemClip = "hunter2"  // another app copied meanwhile (a password manager, say)
        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()

        assertWithMessage("a clip CleverKeys' editor did not put there is someone else's").that(services.systemClip).isEqualTo("hunter2")
    }
}
