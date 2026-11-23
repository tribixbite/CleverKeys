package tribixbite.cleverkeys

/**
 * Carries what the keyboard held at a rotation — a latched or locked Shift, caps lock, Ctrl, Alt,
 * Fn — to the keyboard shown after it (maintainer report 2026-11-22: Shift and Ctrl were gone
 * after every rotation). Any other configuration change restarts the keyboard the same way and
 * is carried the same way (2026-11-23: the system's dark mode, a font size, a resize).
 *
 * A rotation resets the keyboard view, which drops every modifier ([Keyboard2View.reset]), two or
 * three times: the IME's own restart for the new configuration (`onStartInputView`), the
 * configuration refresh right after it, and, in an app that recreates its window on rotation, the
 * new window's input start a few hundred milliseconds later. [heldFor] therefore offers the same
 * state to every reset in the window, not only the first.
 *
 * Never more than that: the carry is for the field the keyboard was over at the rotation (same
 * app and field id), it ends [WINDOW_MS] after the rotation, at the user's first touch on the
 * keyboard ([onUserInput]: from then on the keyboard shows what they set), when input starts in
 * another app ([onStartInput]) and at teardown ([cancel]). A field that is not the rotated one —
 * the platform's fallback connection while a window is recreated, another field focused first —
 * gets nothing but does not end the carry, so the rotated field still gets it when it is bound.
 *
 * Pure and single-threaded: the service drives it from the main thread with
 * `SystemClock.uptimeMillis()`. [T] is the held state ([Pointers.HeldModifier]s in the service).
 */
class RotationModifierCarry<T : Any> {

    private class Carried<T>(val editor: EditorRef, val held: T, val deadlineMs: Long)

    private var carried: Carried<T>? = null

    /** Whether a rotation's modifiers are still on offer. */
    val isArmed: Boolean get() = carried != null

    /**
     * The configuration (the orientation, the system's dark mode …) changed at [nowMs] with the
     * keyboard over [editor] holding [held] (null: nothing latched or locked). Replaces any earlier
     * carry; nothing held, or no editor, arms nothing.
     */
    fun onConfigurationChanged(editor: EditorRef?, held: T?, nowMs: Long) {
        carried = if (editor != null && held != null) Carried(editor, held, nowMs + WINDOW_MS) else null
    }

    /**
     * What to put back after a reset of the keyboard over [editor] at [nowMs], or null. The carry
     * stays armed for later resets until its window passes.
     */
    fun heldFor(editor: EditorRef?, nowMs: Long): T? {
        val c = carried ?: return null
        if (nowMs > c.deadlineMs) {
            carried = null
            return null
        }
        if (editor == null || editor.packageName != c.editor.packageName || editor.fieldId != c.editor.fieldId) {
            return null
        }
        return c.held
    }

    /** Input started for [packageName]: another app's input ends the carry. */
    fun onStartInput(packageName: String?) {
        val c = carried ?: return
        if (packageName != c.editor.packageName) carried = null
    }

    /** The user touched the keyboard: what it shows now is theirs. */
    fun onUserInput() {
        carried = null
    }

    /** Teardown. */
    fun cancel() {
        carried = null
    }

    companion object {
        /**
         * How long after the rotation its resets are still the rotation's. An app's relaunch
         * restarts input within ~0.4 s on the Pixel 8 Pro ([RotationShowGuard.WINDOW_MS] covers
         * a slow one in 2 s); a little more here, because the first touch on the keyboard ends the
         * carry anyway and a carry that ends too early loses the user's Ctrl.
         */
        const val WINDOW_MS = 3_000L
    }
}
