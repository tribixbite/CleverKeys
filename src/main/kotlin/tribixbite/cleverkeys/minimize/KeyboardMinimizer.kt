package tribixbite.cleverkeys.minimize

/**
 * Which view the input window shows: the full keyboard, or its minimized form (gh #175).
 *
 * The service routes every `setInputView` through [resolve], so whatever the keyboard last asked
 * to show (the prediction container, a re-inflated key view after a theme change) is remembered
 * as the full view, and while minimized the minimized view is shown in its place. [expand]
 * restores that remembered view.
 *
 * Minimizing lasts while the keyboard stays up: [reset] (called when the input view finishes,
 * i.e. the keyboard is hidden) shows the full view again, so the next show is full size.
 *
 * Generic over the view type so the state machine is testable without Android views.
 *
 * @param show Shows a view in the input window (the service's `setInputView`).
 * @param createMinimized Builds the minimized view once, on first use.
 */
class KeyboardMinimizer<V : Any>(
    private val show: (V) -> Unit,
    private val createMinimized: () -> V,
) {
    /** The current minimized style, or null while the full keyboard shows. */
    var style: MinimizedStyle? = null
        private set

    private var fullView: V? = null
    private var minimizedView: V? = null

    /** The minimized view, once created. */
    val minimized: V? get() = minimizedView

    /** The view to actually show when the keyboard asks to show [requested]. */
    fun resolve(requested: V): V {
        if (requested === minimizedView) return requested
        fullView = requested
        return if (style != null) minimizedView ?: requested else requested
    }

    /**
     * Minimize to [newStyle]; [prepare] styles the minimized view before it is shown. Ignored
     * until the keyboard has shown a full view (there is nothing to come back to yet).
     */
    fun minimize(newStyle: MinimizedStyle, prepare: (V) -> Unit) {
        if (fullView == null) return
        val view = minimizedView ?: createMinimized().also { minimizedView = it }
        style = newStyle
        prepare(view)
        show(view)
    }

    /** Show the full keyboard again. No-op when it is not minimized. */
    fun expand() {
        if (style == null) return
        style = null
        fullView?.let(show)
    }

    /**
     * The keyboard was hidden: its next appearance is full size. While minimized this puts the
     * full view back in the input window right away; clearing only [style] left the bar/button
     * as the live view with nothing to expand, so a tap on it did nothing until the next
     * `onStartInputView` (audit 2026-10-08).
     */
    fun reset() = expand()
}
