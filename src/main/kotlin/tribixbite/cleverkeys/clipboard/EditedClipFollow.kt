package tribixbite.cleverkeys.clipboard

import tribixbite.cleverkeys.ClipboardTab

/**
 * Whether a clipping edited in CleverKeys' pane goes onto Android's clipboard too, and as what.
 *
 * Maintainer 2026-11-23: after editing the top entry, the paste short swipe (south-east on `v`)
 * pasted the unedited system clip. The paste key asks the app to paste Android's clipboard (and
 * RustDesk's own paste and the app's Paste menu read it too), while an edit only changed the
 * history row. So when the edited row IS the clip on Android's clipboard, the edited text replaces
 * it there, as a merged clipping is put there (`placeMergedOnSystemClipboard`).
 *
 * Pure: [ClipboardHistoryService] reads the system clip and the row's privacy and passes them in
 * (`EditedClipFollowTest`, pure tier).
 */
internal object EditedClipFollow {

    /**
     * [newContent] when the edited row is the clip on Android's clipboard now, else null:
     *  - a History row (Pinned and To-dos hold copies of a clipping, not the clipping itself);
     *  - known not to be private (#156: a private row's text never reaches the OS clipboard; a
     *    row whose privacy could not be read counts as private);
     *  - the system clip is that row's old text, as is or in the form the history stores a copy
     *    in ([storedForm]: URL-cleaned and trimmed, `ClipboardHistoryService.storeClip`).
     * Any other system clip, such as one CleverKeys did not record (a password manager's), is
     * never overwritten.
     */
    fun textToPut(
        tab: ClipboardTab,
        isPrivate: Boolean?,
        systemClip: String?,
        oldContent: String,
        newContent: String,
        storedForm: (String) -> String,
    ): String? {
        if (tab != ClipboardTab.HISTORY || isPrivate != false || systemClip == null) return null
        if (systemClip != oldContent && storedForm(systemClip) != oldContent) return null
        return newContent
    }
}
