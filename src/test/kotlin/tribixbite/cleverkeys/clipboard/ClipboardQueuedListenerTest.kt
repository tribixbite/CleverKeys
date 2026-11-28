package tribixbite.cleverkeys.clipboard

import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.entry

/**
 * Android's clipboard listener runs AFTER the write, on the main looper, and re-reads the clip
 * (ClipboardHistoryService.SystemListener): several writes before it runs are all read as the
 * last one. ClipboardServiceFixture delivered each write synchronously, so the service's
 * one-shot skip of its own write after an edit (`ownEditOnSystemClip`) was only ever tested in
 * the order it was written (test-gap audit 2026-11-28, item 5).
 *
 * Save puts the edited row on the clipboard (the follow, marker = that text), then the editor's
 * own cut puts a fragment there before the listener runs. Both callbacks read the fragment. The
 * marker must be spent by the callback that answers the Save's write, so that a later copy of the
 * edited text (here from another app) is captured like any copy.
 */
class ClipboardQueuedListenerTest {

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
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        ClipboardServiceFixture.mockStatics()
        services = ClipboardServiceFixture(initialHistory = listOf(url))
        rowFixture = ClipboardEditorRowFixture(listOf(entry(url)), service = services.service, context = services.context)
        services.systemClip = url // just copied
        services.queueListener = true
    }

    @After
    fun teardown() {
        rowFixture.clear()
        unmockkAll()
    }

    /** Edits the URL row's org segment to `tribixbite` and saves: the follow writes the edited row. */
    private fun editAndSave() {
        rowFixture.view.edit_entry(0)
        rowFixture.render()
        val org = url.indexOf("rustdesk")
        rowFixture.userPlacesCaret(org, org + "rustdesk".length)
        rowFixture.view.insertEditText("tribixbite")
        rowFixture.view.save_edit()
        assertWithMessage("precondition: Save put the edited row on the clipboard").that(services.systemClip).isEqualTo(edited)
    }

    @Test
    fun theSavesOwnWriteIsSkippedWhenItsCallbackComesLate() {
        editAndSave()
        services.drainListener()

        assertWithMessage("the edited row only, no second copy").that(services.history).containsExactly(edited)
    }

    @Test
    fun aCutBeforeTheListenerRanStillLeavesTheNextCopyOfTheEditedTextCaptured() {
        editAndSave()
        // The editor's own cut, before the Save's callback ran: the row is opened again and a
        // fragment cut (the fixture's row still shows the URL it was built with).
        rowFixture.view.edit_entry(0)
        rowFixture.render()
        val org = url.indexOf("rustdesk")
        rowFixture.userPlacesCaret(org, org + "rustdesk".length)
        rowFixture.view.cutFromEditText()
        assertWithMessage("precondition: the cut is on the clipboard").that(services.systemClip).isEqualTo("rustdesk")
        assertWithMessage("precondition: two callbacks owed").that(services.pendingCallbacks).isEqualTo(2)
        services.drainListener()
        assertWithMessage("the cut fragment is a row above the edited one")
            .that(services.history.first()).isEqualTo("rustdesk")

        // Later, another app copies the edited URL: an ordinary copy, captured at the top (#108).
        services.clipboard.setPrimaryClip(ClipboardServiceFixture.clipOf(edited))
        services.drainListener()

        assertWithMessage("the later copy of the edited text is captured (moved to the top)")
            .that(services.history.first()).isEqualTo(edited)
    }
}
