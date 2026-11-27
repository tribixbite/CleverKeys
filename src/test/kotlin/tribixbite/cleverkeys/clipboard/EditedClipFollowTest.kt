package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.clipboard.EditedClipFollow.SystemClip

/**
 * [EditedClipFollow]: an edited clipping replaces Android's clip only when it IS that clip
 * (maintainer 2026-11-23: the paste short swipe pasted the unedited system clip after the top
 * entry was edited), or when Android's clipboard is empty and the edit is one clipping
 * (maintainer 2026-11-24: "cleaned a clipping and then tried to paste via subkey function and
 * nothing happened" — Android clears its clipboard an hour after the last copy). Pure tier; the
 * end-to-end paste is `EditedClipPasteInstrumentedTest`.
 */
class EditedClipFollowTest {

    /** As `ClipboardHistoryService.storeClip` keeps a copy: here, a tracking parameter dropped. */
    private val storedForm: (String) -> String = { it.replace("?utm_source=x", "").trim() }

    private fun put(
        tab: ClipboardTab = ClipboardTab.HISTORY,
        isPrivate: Boolean? = false,
        systemClip: SystemClip = SystemClip.Text("meet at 5"),
        old: String = "meet at 5",
        new: String = "meet at 6",
        fillEmpty: Boolean = true,
    ) = EditedClipFollow.textToPut(tab, isPrivate, systemClip, old, new, fillEmpty, storedForm)

    @Test
    fun theEditedHistoryRowThatIsTheSystemClipReplacesIt() {
        assertThat(put()).isEqualTo("meet at 6")
        assertWithMessage("several clippings cleaned at once still follow the one that is the clip")
            .that(put(fillEmpty = false)).isEqualTo("meet at 6")
    }

    @Test
    fun aSystemClipTheHistoryStoredCleanedOrTrimmedStillCounts() {
        assertWithMessage("URL cleaning on: the row holds the cleaned text")
            .that(put(systemClip = SystemClip.Text("https://a.b/c?utm_source=x"), old = "https://a.b/c", new = "https://a.b/d"))
            .isEqualTo("https://a.b/d")
        assertWithMessage("the row holds the clip trimmed")
            .that(put(systemClip = SystemClip.Text("  meet at 5\n"))).isEqualTo("meet at 6")
    }

    @Test
    fun anotherSystemClipIsNeverOverwritten() {
        assertWithMessage("a clip CleverKeys did not record, e.g. a password manager's")
            .that(put(systemClip = SystemClip.Text("hunter2"))).isNull()
        assertWithMessage("a clip this keyboard could not read, or an image, is someone else's")
            .that(put(systemClip = SystemClip.Unknown)).isNull()
    }

    @Test
    fun anEmptySystemClipboardTakesOneEditedClipping() {
        // Android 13+ clears the clipboard an hour after the last copy (device_config clipboard
        // auto_clear_enabled, true on the S25U and the Pixel): nothing is there to protect.
        assertThat(put(systemClip = SystemClip.Empty)).isEqualTo("meet at 6")
        assertWithMessage("an empty clipboard takes the edit whichever row it is")
            .that(put(systemClip = SystemClip.Empty, old = "an old note", new = "an old note, fixed"))
            .isEqualTo("an old note, fixed")
    }

    @Test
    fun severalCleanedAtOnceLeaveAnEmptyClipboardEmpty() {
        // Clean on a selection of many rows is tidying up, not choosing one to paste.
        assertThat(put(systemClip = SystemClip.Empty, fillEmpty = false)).isNull()
    }

    @Test
    fun aPrivateRowNeverReachesTheSystemClipboard() {
        assertThat(put(isPrivate = true)).isNull()
        assertWithMessage("privacy unknown counts as private").that(put(isPrivate = null)).isNull()
        assertWithMessage("not even an empty clipboard").that(put(isPrivate = true, systemClip = SystemClip.Empty)).isNull()
    }

    @Test
    fun pinnedAndToDoCopiesDoNotChangeTheSystemClip() {
        assertThat(put(tab = ClipboardTab.PINNED)).isNull()
        assertThat(put(tab = ClipboardTab.TODOS)).isNull()
        assertThat(put(tab = ClipboardTab.PINNED, systemClip = SystemClip.Empty)).isNull()
    }

    // ── Maintainer decision 2026-11-26: a fragment cut or copied in the editor stays ──────────
    // "anything cut or copied appears above the entry being edited". Cutting or copying INSIDE the
    // editor puts that fragment on Android's clipboard; the edit's Save never replaces it (the
    // fragment is not the row's old text), so the paste gives the fragment and History shows it as
    // a new row above the edited entry. (c9d61bcdd had made Save replace it; removed.)

    private val url = "https://github.com/rustdesk/rustdesk-server"
    private val urlEdited = "https://github.com/tribixbite/rustdesk-server"

    @Test
    fun aFragmentTheEditorCutOrCopiedIsNeverReplacedBySave() {
        assertThat(put(systemClip = SystemClip.Text("rustdesk"), old = url, new = urlEdited)).isNull()
        assertWithMessage("even a fragment that is the row's last word")
            .that(put(systemClip = SystemClip.Text("rustdesk-server"), old = url, new = urlEdited)).isNull()
    }

    @Test
    fun theRowAsOpenedOnTheClipboardStillFollows() {
        assertThat(put(systemClip = SystemClip.Text(url), old = url, new = urlEdited)).isEqualTo(urlEdited)
    }

    @Test
    fun theSystemClipIsReadAsEmptyOnlyWhenNothingIsOnIt() {
        assertWithMessage("cleared (AOSP clearPrimaryClip, the hourly auto-clear) or never set")
            .that(SystemClip.of(itemCount = null, firstText = null, firstHasNonText = false)).isEqualTo(SystemClip.Empty)
        assertThat(SystemClip.of(itemCount = 0, firstText = null, firstHasNonText = false)).isEqualTo(SystemClip.Empty)
        assertWithMessage("API 21-27 Clear clipboard leaves one empty text item")
            .that(SystemClip.of(itemCount = 1, firstText = "", firstHasNonText = false)).isEqualTo(SystemClip.Empty)
        assertThat(SystemClip.of(itemCount = 1, firstText = "meet at 5", firstHasNonText = false))
            .isEqualTo(SystemClip.Text("meet at 5"))
        assertWithMessage("an image or another non-text clip is not ours to replace")
            .that(SystemClip.of(itemCount = 1, firstText = null, firstHasNonText = true)).isEqualTo(SystemClip.Unknown)
        assertWithMessage("several items: not a clip CleverKeys recorded as one")
            .that(SystemClip.of(itemCount = 2, firstText = "", firstHasNonText = false)).isEqualTo(SystemClip.Unknown)
    }
}
