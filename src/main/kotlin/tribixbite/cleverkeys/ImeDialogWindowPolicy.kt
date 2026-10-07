package tribixbite.cleverkeys

import android.view.WindowManager

/**
 * Window flags for dialogs the keyboard shows over itself ([Utils.show_dialog_on_ime]).
 *
 * Device report 2026-10-07 (Saga, Android 14, typing into Chrome): the dialogs used to be
 * focusable with FLAG_ALT_FOCUSABLE_IM. Touching one gave it window focus; Chrome's window lost
 * focus and requested HIDE_SOFT_INPUT ~30 ms later (ImeTracker ORIGIN_CLIENT_HIDE_SOFT_INPUT),
 * the keyboard finished its input view, and the dialog — attached to the keyboard window — was
 * torn down before its button handler ran: "Delete selected" deleted nothing and the filter
 * dialog (whose focusable controls take focus as soon as it shows) closed itself and the keyboard.
 *
 * The fix is to never take window focus:
 *  - FLAG_NOT_FOCUSABLE: the app's editor keeps window and input focus, so no app or system
 *    path hides the keyboard because of the dialog. Touch still works; TalkBack still reaches
 *    the dialog (accessibility focus does not need input focus). Without FLAG_ALT_FOCUSABLE_IM
 *    the window also may not use the input method, so it never becomes an IME target.
 *  - Non-focusable windows are not touch-modal, so touches outside reach the keyboard below;
 *    FLAG_WATCH_OUTSIDE_TOUCH lets the dialog see them and close as Cancel (AlertDialog's
 *    close-on-touch-outside), instead of lingering while the user types.
 *
 * Consequence for widgets inside these dialogs: anything that opens its own focusable popup
 * (a dropdown Spinner) would steal focus the same way — use [ImeDialogSpinner].
 */
object ImeDialogWindowPolicy {
    const val ADDED_FLAGS: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
}
