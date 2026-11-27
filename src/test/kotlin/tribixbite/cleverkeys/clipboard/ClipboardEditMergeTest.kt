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
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.EditEntryResult
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.entry
import tribixbite.cleverkeys.clipboard.ClipboardEditorRowFixture.Companion.getField

/**
 * Maintainer decision 2026-11-26: a History edit whose new text is already another History row
 * MERGES into it. Their case (device-confirmed on the Note10+, release r22): History held
 * `https://github.com/rustdesk/rustdesk-server` (the current clip) and
 * `https://github.com/tribixbite/rustdesk-server`; editing the first into the second was refused as
 * a duplicate, and with the refusal hidden the checkmark did nothing. Now Save succeeds, the edited
 * row is removed, the existing row moves to the top as a fresh copy of the text would, no error is
 * shown, and when the edited row was the clip (or the existing row is) Android's clipboard holds
 * the merged text, so a paste right after gives it. Pinned and To-dos keep refusing (and say why);
 * Clean keeps counting a duplicate as failed.
 *
 * REAL view and REAL service over a fake clipboard and History ([ClipboardServiceFixture]).
 */
class ClipboardEditMergeTest {

    private val rustdesk = "https://github.com/rustdesk/rustdesk-server"
    private val fork = "https://github.com/tribixbite/rustdesk-server"

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
    }

    @After
    fun teardown() {
        if (::rowFixture.isInitialized) rowFixture.clear()
        unmockkAll()
    }

    /** History [history] (newest first) on [tab]; the editor shows the rustdesk URL's row. */
    private fun host(history: List<String>, clip: String?, tab: ClipboardTab = ClipboardTab.HISTORY) {
        services = ClipboardServiceFixture(initialHistory = history)
        services.systemClip = clip
        rowFixture = ClipboardEditorRowFixture(listOf(entry(rustdesk)), service = services.service, tab = tab, context = services.context)
    }

    /** The maintainer's edit: the org segment `rustdesk` → `tribixbite`, then Save. */
    private fun editTheOrgAndSave() {
        rowFixture.view.edit_entry(0)
        rowFixture.render()
        val org = rustdesk.indexOf("rustdesk")
        rowFixture.userPlacesCaret(org, org + "rustdesk".length)
        rowFixture.view.insertEditText("tribixbite")
        assertWithMessage("precondition: the field holds the edit").that(rowFixture.text).isEqualTo(fork)
        rowFixture.view.save_edit()
        rowFixture.render()  // what a refusal's re-bind would show
    }

    @Test
    fun theMaintainersEditMergesIntoTheForkRowAndThePasteGivesIt() {
        host(history = listOf(rustdesk, fork), clip = rustdesk)  // just copied the rustdesk URL

        editTheOrgAndSave()

        assertWithMessage("Save succeeded and the edit ended").that(rowFixture.view.isEditing()).isFalse()
        assertWithMessage("no error: a merge is not a refusal").that(rowFixture.errorShown).isFalse()
        assertWithMessage("one row remains, at the top").that(services.history).containsExactly(fork)
        assertWithMessage("a paste right after gives the merged text").that(services.systemClip).isEqualTo(fork)
    }

    @Test
    fun whenTheExistingRowIsTheClipItStaysTheClipWithoutAWrite() {
        host(history = listOf(fork, rustdesk), clip = fork)

        editTheOrgAndSave()

        assertThat(services.history).containsExactly(fork)
        assertWithMessage("the clipboard already holds the merged text").that(services.systemClip).isEqualTo(fork)
        assertWithMessage("nothing rewrote it").that(services.clipWrites).isEqualTo(0)
    }

    @Test
    fun anEmptyClipboardTakesTheMergedText() {
        host(history = listOf(rustdesk, fork), clip = null)  // Android's hourly auto-clear

        editTheOrgAndSave()

        assertThat(services.history).containsExactly(fork)
        assertThat(services.systemClip).isEqualTo(fork)
    }

    @Test
    fun aMergeOfAnOldRowLeavesAnotherClipAlone() {
        host(history = listOf("meet at 5", rustdesk, fork), clip = "meet at 5")

        editTheOrgAndSave()

        assertWithMessage("the edited row is gone and the fork's row is on top")
            .that(services.history).containsExactly(fork, "meet at 5").inOrder()
        assertWithMessage("the user's clip is not theirs to lose").that(services.systemClip).isEqualTo("meet at 5")
    }

    @Test
    fun aPrivateMergedRowNeverReachesTheClipboard() {
        host(history = listOf(rustdesk, fork), clip = rustdesk)
        every { services.database.historyEntryIsPrivate(fork) } returns true

        editTheOrgAndSave()

        assertThat(services.history).containsExactly(fork)
        assertWithMessage("#156: a private row's text never goes onto Android's clipboard")
            .that(services.systemClip).isEqualTo(rustdesk)
    }

    @Test
    fun theMergedRowGetsAFreshExpiryLikeANewCopy() {
        host(history = listOf(rustdesk, fork), clip = rustdesk)
        val before = System.currentTimeMillis()

        editTheOrgAndSave()

        verify(exactly = 1) { services.database.updateHistoryEntryContentMerging(rustdesk, fork, any()) }
        assertWithMessage("expiry = now + the History duration (never expired on arrival)")
            .that(services.mergeExpiry!!).isAtLeast(before)
    }

    @Test
    fun aPinnedEditIntoAnotherPinnedRowIsStillRefusedAndSaysWhy() {
        host(history = emptyList(), clip = rustdesk, tab = ClipboardTab.PINNED)
        every { services.database.updatePinnedEntryContent(rustdesk, fork) } returns EditEntryResult.DuplicateConflict

        editTheOrgAndSave()

        assertWithMessage("Pinned rows are curated copies: no merge").that(rowFixture.view.isEditing()).isTrue()
        assertThat(rowFixture.view.getField("editingErrorRes")).isEqualTo(R.string.clipboard_edit_duplicate)
        assertWithMessage("and the reason is on screen").that(rowFixture.errorShown).isTrue()
        verify(exactly = 0) { services.database.updateHistoryEntryContentMerging(any(), any(), any()) }
    }

    @Test
    fun cleanStillCountsADuplicateAsFailed() {
        host(history = listOf("a  b", "a b"), clip = null)
        val plan = ClipboardBulkPlans.planClean(listOf(ClipboardEntry("a  b", 1L)))

        val result = services.service.applyClean(ClipboardTab.HISTORY, plan).getOrThrow()

        assertThat(result.cleaned).isEqualTo(0)
        assertWithMessage("Clean is tidying up: its rows are not merged").that(services.history).containsExactly("a  b", "a b").inOrder()
        verify(exactly = 0) { services.database.updateHistoryEntryContentMerging(any(), any(), any()) }
    }
}
