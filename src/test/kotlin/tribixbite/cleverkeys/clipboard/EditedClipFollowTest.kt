package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.ClipboardTab

/**
 * [EditedClipFollow]: an edited clipping replaces Android's clip only when it IS that clip
 * (maintainer 2026-11-23: the paste short swipe pasted the unedited system clip after the top
 * entry was edited). Pure tier; the end-to-end paste is `EditedClipPasteInstrumentedTest`.
 */
class EditedClipFollowTest {

    /** As `ClipboardHistoryService.storeClip` keeps a copy: here, a tracking parameter dropped. */
    private val storedForm: (String) -> String = { it.replace("?utm_source=x", "").trim() }

    private fun put(
        tab: ClipboardTab = ClipboardTab.HISTORY,
        isPrivate: Boolean? = false,
        systemClip: String? = "meet at 5",
        old: String = "meet at 5",
        new: String = "meet at 6",
    ) = EditedClipFollow.textToPut(tab, isPrivate, systemClip, old, new, storedForm)

    @Test
    fun theEditedHistoryRowThatIsTheSystemClipReplacesIt() {
        assertThat(put()).isEqualTo("meet at 6")
    }

    @Test
    fun aSystemClipTheHistoryStoredCleanedOrTrimmedStillCounts() {
        assertWithMessage("URL cleaning on: the row holds the cleaned text")
            .that(put(systemClip = "https://a.b/c?utm_source=x", old = "https://a.b/c", new = "https://a.b/d"))
            .isEqualTo("https://a.b/d")
        assertWithMessage("the row holds the clip trimmed")
            .that(put(systemClip = "  meet at 5\n")).isEqualTo("meet at 6")
    }

    @Test
    fun anotherSystemClipIsNeverOverwritten() {
        assertWithMessage("a clip CleverKeys did not record, e.g. a password manager's")
            .that(put(systemClip = "hunter2")).isNull()
        assertThat(put(systemClip = null)).isNull()
    }

    @Test
    fun aPrivateRowNeverReachesTheSystemClipboard() {
        assertThat(put(isPrivate = true)).isNull()
        assertWithMessage("privacy unknown counts as private").that(put(isPrivate = null)).isNull()
    }

    @Test
    fun pinnedAndToDoCopiesDoNotChangeTheSystemClip() {
        assertThat(put(tab = ClipboardTab.PINNED)).isNull()
        assertThat(put(tab = ClipboardTab.TODOS)).isNull()
    }
}
