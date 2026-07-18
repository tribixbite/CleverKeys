package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.ClipboardHistoryService

/**
 * #156 Private copy — the SINGLE in-IME dispatch shared by both entry-point-A surfaces:
 * [tribixbite.cleverkeys.KeyEventHandler.handlePrivateCopy] (physical/editing-command routing) and
 * [tribixbite.cleverkeys.Keyboard2View]'s editing-pane action. Previously these were near-verbatim
 * copies that had already drifted (different empty-selection wording); unifying here makes the two
 * surfaces structurally incapable of diverging (Finding 9).
 *
 * Behavior (unchanged from both originals): read the current selection via
 * [InputConnection.getSelectedText]; on empty/no selection report [MSG_NO_SELECTION]; otherwise store
 * the selection PRIVATELY via [ClipboardHistoryService.privateCopy] with the target editor's package
 * as provenance and report [MSG_STORED] / [MSG_UNAVAILABLE].
 *
 * SECURITY: delegates only to [ClipboardHistoryService.privateCopy] (the no-setPrimaryClip path) — it
 * NEVER touches the OS clipboard. Feedback is delivered via the [feedback] callback so each caller can
 * route it to the suggestion bar (Toasts are IME-suppressed on Android 13+).
 */
object PrivateCopyDispatch {

    /** Shown when there is no active selection to copy. Identical across both surfaces. */
    const val MSG_NO_SELECTION = "No text selected"

    /** Shown after the selection is stored privately. */
    const val MSG_STORED = "Privately copied"

    /** Shown when the selection could not be stored (service unavailable / context missing). */
    const val MSG_UNAVAILABLE = "Private copy unavailable"

    /**
     * Execute a private copy of the current selection in [ic].
     *
     * @param ctx         a Context for resolving the singleton clipboard service.
     * @param ic          the active input connection to read the selection from.
     * @param editorInfo  the target editor's [EditorInfo]; its `packageName` is recorded as the
     *                    private entry's provenance (`source_package`). May be null.
     * @param feedback    invoked with a user-facing status message (routed to the suggestion bar).
     */
    fun execute(
        ctx: Context,
        ic: InputConnection,
        editorInfo: EditorInfo?,
        feedback: (String) -> Unit
    ) {
        val text = ic.getSelectedText(0)?.toString()
        if (text.isNullOrEmpty()) {
            feedback(MSG_NO_SELECTION)
            return
        }
        // Provenance = the target editor's package (EditorInfo.packageName).
        val sourcePackage = editorInfo?.packageName
        val stored = ClipboardHistoryService.privateCopy(ctx, text, sourcePackage)
        feedback(if (stored) MSG_STORED else MSG_UNAVAILABLE)
    }
}
