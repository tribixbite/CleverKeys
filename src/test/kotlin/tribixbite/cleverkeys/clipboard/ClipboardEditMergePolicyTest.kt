package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.ClipboardEditPolicy
import tribixbite.cleverkeys.ClipboardEditPolicy.DuplicateAction
import tribixbite.cleverkeys.ClipboardEntry

/**
 * Maintainer decision 2026-11-26: an inline History edit whose new text is already another History
 * row MERGES into that row (the edited row goes, the existing row moves to the top as a fresh copy
 * of the text would) instead of being refused. Their case: History held both
 * `https://github.com/rustdesk/rustdesk-server` and `https://github.com/tribixbite/rustdesk-server`;
 * editing the first into the second was refused (DuplicateConflict) and, with the refusal hidden,
 * looked like a dead checkmark (device-confirmed on the Note10+). Pure tier for the decision and
 * the merged row's #156 markers; the SQL is `ClipboardDatabaseTest` (instrumented).
 */
class ClipboardEditMergePolicyTest {

    @Test
    fun anInlineHistoryEditIntoATextRowMerges() {
        assertThat(ClipboardEditPolicy.duplicateAction(mergeRequested = true, existingMimeType = ClipboardEntry.MIME_TEXT_PLAIN))
            .isEqualTo(DuplicateAction.MERGE)
        assertWithMessage("a row from before the mime column defaults to text")
            .that(ClipboardEditPolicy.duplicateAction(mergeRequested = true, existingMimeType = null))
            .isEqualTo(DuplicateAction.MERGE)
    }

    @Test
    fun aMediaRowNeverAbsorbsAnEdit() {
        // A media row's content is its display name ("IMG_2041.png"): text edited into that name is
        // not that picture.
        assertThat(ClipboardEditPolicy.duplicateAction(mergeRequested = true, existingMimeType = "image/png"))
            .isEqualTo(DuplicateAction.REFUSE)
    }

    @Test
    fun whereMergingIsNotAskedForTheDuplicateIsRefused() {
        // Pinned and To-dos (curated copies with their own tags, order and status) and Clean.
        assertThat(ClipboardEditPolicy.duplicateAction(mergeRequested = false, existingMimeType = ClipboardEntry.MIME_TEXT_PLAIN))
            .isEqualTo(DuplicateAction.REFUSE)
    }

    @Test
    fun theMergedRowStaysPrivateIfEitherRowWasAndKeepsTheNewestProvenance() {
        assertWithMessage("an edited private row keeps its text private (sticky, #156)")
            .that(ClipboardEditPolicy.mergedMarkers(existingPrivate = false, existingSource = null, editedPrivate = true, editedSource = "org.vault"))
            .isEqualTo(ClipboardEditPolicy.MergedMarkers(isPrivate = true, sourcePackage = "org.vault"))
        assertWithMessage("an existing private row stays private")
            .that(ClipboardEditPolicy.mergedMarkers(existingPrivate = true, existingSource = "org.vault", editedPrivate = false, editedSource = null))
            .isEqualTo(ClipboardEditPolicy.MergedMarkers(isPrivate = true, sourcePackage = "org.vault"))
        assertThat(ClipboardEditPolicy.mergedMarkers(existingPrivate = false, existingSource = null, editedPrivate = false, editedSource = null))
            .isEqualTo(ClipboardEditPolicy.MergedMarkers(isPrivate = false, sourcePackage = null))
    }
}
