package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.StringRes
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.R

/**
 * #156 Private copy — the SINGLE in-IME dispatch shared by both entry-point-A surfaces:
 * [tribixbite.cleverkeys.KeyEventHandler.handlePrivateCopy] (physical/editing-command routing) and
 * [tribixbite.cleverkeys.Keyboard2View]'s editing-pane action. Previously these were near-verbatim
 * copies that had already drifted (different empty-selection wording); unifying here makes the two
 * surfaces structurally incapable of diverging (Finding 9).
 *
 * Behavior (unchanged from both originals): read the current selection via
 * [InputConnection.getSelectedText]; on empty/no selection report [Outcome.NO_SELECTION]; otherwise store
 * the selection PRIVATELY via [ClipboardHistoryService.privateCopy] with the target editor's package
 * as provenance and report [Outcome.STORED] / [Outcome.UNAVAILABLE].
 *
 * SECURITY: delegates only to [ClipboardHistoryService.privateCopy] (the no-setPrimaryClip path) — it
 * NEVER touches the OS clipboard. Feedback is delivered via the [feedback] callback so each caller can
 * route it to the suggestion bar (Toasts are IME-suppressed on Android 13+).
 */
object PrivateCopyDispatch {

    /**
     * Outcome of a private-copy attempt, carrying the string resource of its suggestion-bar message.
     * The message is resolved against the caller's [Context] inside [execute] so the feedback shown
     * to the user follows the app locale (the suggestion bar renders whatever String it is given).
     */
    enum class Outcome(@StringRes val messageRes: Int) {
        /** There is no active selection to copy. Identical wording across both surfaces. */
        NO_SELECTION(R.string.private_copy_no_selection),

        /** The selection was stored privately. */
        STORED(R.string.private_copy_stored),

        /** The selection could not be stored (service unavailable / context missing). */
        UNAVAILABLE(R.string.private_copy_unavailable)
    }

    /**
     * Execute a private copy of the current selection in [ic].
     *
     * @param ctx         a Context for resolving the singleton clipboard service.
     * @param ic          the active input connection to read the selection from.
     * @param editorInfo  the target editor's [EditorInfo]; its `packageName` is recorded as the
     *                    private entry's provenance (`source_package`). May be null.
     * @param feedback    invoked with the localized user-facing status message (routed to the suggestion bar).
     */
    fun execute(
        ctx: Context,
        ic: InputConnection,
        editorInfo: EditorInfo?,
        feedback: (String) -> Unit
    ) {
        val text = ic.getSelectedText(0)?.toString()
        if (text.isNullOrEmpty()) {
            feedback(ctx.getString(Outcome.NO_SELECTION.messageRes))
            return
        }
        // Provenance = the target editor's package (EditorInfo.packageName).
        val sourcePackage = editorInfo?.packageName
        val stored = ClipboardHistoryService.privateCopy(ctx, text, sourcePackage)
        val outcome = if (stored) Outcome.STORED else Outcome.UNAVAILABLE
        feedback(ctx.getString(outcome.messageRes))
    }
}
