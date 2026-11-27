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
 * Maintainer 2026-11-24: "cleaned a clipping and then tried to paste via subkey function and
 * nothing happened". Android 13+ clears its clipboard an hour after the last copy (device_config
 * `clipboard/auto_clear_enabled`, on by default; the S25U and the Pixel have it), so the row was no
 * longer the system clip and the paste had nothing to give. An EMPTY clipboard holds nothing to
 * protect: one edited or cleaned clipping now goes there too.
 *
 * Maintainer 2026-11-26: after editing a URL they had just copied ("…/rustdesk/rustdesk-server"
 * into "…/tribixbite/rustdesk-server"), "short swipe paste function didn't paste it". A cut or
 * copy INSIDE the editor (Ctrl+X / Ctrl+C, the cut and copy keys, the field's text toolbar) puts
 * that fragment on Android's clipboard, so at Save the clip was no longer the row's old text and
 * the paste gave the fragment. "The clipping the user is working with" is therefore the row as
 * opened, an empty clipboard, OR what the editor itself put there while the clip it replaced was
 * one of those ([isWorkingClip]; the view passes that as `editorClip`, only from such a cut or
 * copy). An edit of an OLD row (the clip is another clipping) still never replaces the clip, and
 * neither does a cut from such a row: its fragment stays on the clipboard for the user's paste.
 *
 * Pure: [ClipboardHistoryService] reads the system clip and the row's privacy and passes them in
 * (`EditedClipFollowTest`, pure tier).
 */
internal object EditedClipFollow {

    /** What Android's clipboard held when the edit was saved. */
    sealed interface SystemClip {
        /** Nothing: cleared (the hourly auto-clear, Clear clipboard) or never set. */
        data object Empty : SystemClip

        /** A clip whose first item is this non-empty text. */
        data class Text(val text: String) : SystemClip

        /** Something that is not CleverKeys' to replace: an image or other non-text clip, or unreadable. */
        data object Unknown : SystemClip

        companion object {
            /**
             * Classify a read of the primary clip: [itemCount] null when there is no clip,
             * [firstText] the first item's text, [firstHasNonText] when that item carries a URI or
             * an intent. API 21–27 "Clear clipboard" leaves ONE item with empty text (no
             * clearPrimaryClip before API 28), which is empty too.
             */
            fun of(itemCount: Int?, firstText: String?, firstHasNonText: Boolean): SystemClip = when {
                itemCount == null || itemCount == 0 -> Empty
                !firstText.isNullOrEmpty() -> Text(firstText)
                itemCount == 1 && !firstHasNonText -> Empty
                else -> Unknown
            }
        }
    }

    /**
     * [newContent] when it should go onto Android's clipboard, else null:
     *  - a History row (Pinned and To-dos hold copies of a clipping, not the clipping itself);
     *  - known not to be private (#156: a private row's text never reaches the OS clipboard; a
     *    row whose privacy could not be read counts as private);
     *  - and either the system clip is that row's old text, as is or in the form the history
     *    stores a copy in ([storedForm]: URL-cleaned and trimmed, `ClipboardHistoryService.storeClip`),
     *    or the clipboard is [SystemClip.Empty] and [fillEmpty] (one clipping edited or cleaned:
     *    the one the user means to paste; Clean on several rows passes false), or the system clip
     *    is [editorClip]: what this edit's own cut or copy put there while the clip it replaced
     *    was this clipping (null when the edit made none, or made it from an old row).
     * Any other system clip, such as one CleverKeys did not record (a password manager's), is
     * never overwritten.
     */
    fun textToPut(
        tab: ClipboardTab,
        isPrivate: Boolean?,
        systemClip: SystemClip,
        oldContent: String,
        newContent: String,
        fillEmpty: Boolean,
        storedForm: (String) -> String,
        editorClip: String? = null,
    ): String? {
        if (tab != ClipboardTab.HISTORY || isPrivate != false) return null
        return when (systemClip) {
            SystemClip.Empty -> newContent.takeIf { fillEmpty }
            SystemClip.Unknown -> null
            is SystemClip.Text -> newContent.takeIf { isWorkingClip(systemClip, oldContent, editorClip, storedForm) }
        }
    }

    /**
     * Whether [systemClip] is the clipping an edit of [openedContent] works with: that row's text
     * (as is, or in [storedForm]), what the editor itself last put there in this edit
     * ([editorClip]), or nothing at all. Anything else came from somewhere else.
     */
    fun isWorkingClip(
        systemClip: SystemClip,
        openedContent: String,
        editorClip: String?,
        storedForm: (String) -> String,
    ): Boolean = when (systemClip) {
        SystemClip.Empty -> true
        SystemClip.Unknown -> false
        is SystemClip.Text -> systemClip.text == openedContent ||
            (editorClip != null && systemClip.text == editorClip) ||
            storedForm(systemClip.text) == openedContent
    }
}
