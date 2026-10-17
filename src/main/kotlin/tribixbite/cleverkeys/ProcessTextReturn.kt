package tribixbite.cleverkeys

/**
 * Hand-off of a text-processing app's RESULT back to the editor (textAssist / replaceText,
 * docs/research/2026-10-15-ime-editor-actions.md §3.1).
 *
 * An IME cannot receive an activity result, so [ProcessTextActivity] (a transparent activity in
 * this process) starts the ACTION_PROCESS_TEXT chooser with startActivityForResult and hands the
 * returned text here. The editor lost focus meanwhile, so the result is written back only when
 * the IME is connected to the SAME field again and that field still holds the exact selection
 * the request was made from ([decide]); anything else puts the result on the clipboard instead,
 * so it is never written into the wrong place.
 *
 * Process-wide singleton guarded by its own lock: the activity (main thread) and the service
 * (main thread) are the only callers, but the state must survive the activity finishing.
 * Only the latest request counts — starting another supersedes the previous one.
 */
object ProcessTextReturn {

    /** Results longer than this are never written into an editor (the clipboard gets them). */
    const val MAX_RESULT_LENGTH = 65_536

    /**
     * Where a request came from: the field (package + field id + input type) and the editor's
     * exact selection receipt at launch.
     */
    data class Origin<R>(
        val requestId: Long,
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int,
        val receipt: R,
    )

    /** A finished request: its origin and the text the app returned. */
    data class Ready<R>(val origin: Origin<R>, val result: String)

    enum class Decision { INSERT, CLIPBOARD }

    private val lock = Any()
    private var nextId = 1L
    private var pending: Origin<*>? = null
    private var ready: Ready<*>? = null

    /** Register a new request; it supersedes any earlier one (pending or unreturned). */
    fun <R> begin(packageName: String?, fieldId: Int, inputType: Int, receipt: R): Origin<R> = synchronized(lock) {
        val origin = Origin(nextId++, packageName, fieldId, inputType, receipt)
        pending = origin
        ready = null
        origin
    }

    /** Whether [requestId] is still the request being waited for. */
    fun isPending(requestId: Long): Boolean = synchronized(lock) { pending?.requestId == requestId }

    /**
     * Told (outside the lock) when a result becomes ready, so the IME can deliver it even if
     * the editor never restarts input. Set by the IME service; null in tests.
     */
    @Volatile
    var onResultReady: (() -> Unit)? = null

    /** The app returned [result] for [requestId]. False when that request was superseded. */
    fun complete(requestId: Long, result: String): Boolean {
        val accepted = synchronized(lock) {
            val origin = pending
            if (origin == null || origin.requestId != requestId) {
                false
            } else {
                pending = null
                ready = Ready(origin, result)
                true
            }
        }
        if (accepted) onResultReady?.invoke()
        return accepted
    }

    /** The request ended without a result (cancelled, no app, error). */
    fun cancel(requestId: Long) = synchronized(lock) {
        if (pending?.requestId == requestId) pending = null
    }

    /** Whether a returned result is waiting to be delivered. */
    fun hasResult(): Boolean = synchronized(lock) { ready != null }

    /** Take the waiting result (at most once). The receipt type is the caller's. */
    @Suppress("UNCHECKED_CAST")
    fun <R> take(): Ready<R>? = synchronized(lock) { (ready as Ready<R>?).also { ready = null } }

    /** Forget everything (tests). */
    fun reset() = synchronized(lock) { pending = null; ready = null }

    /**
     * INSERT only when the editor now connected is the request's field and reads back the very
     * receipt taken at launch (same selection, same selected text, same text around it), the
     * field is an ordinary editor, and the result is a sane size; otherwise CLIPBOARD.
     */
    fun <R> decide(
        origin: Origin<R>,
        result: String,
        packageName: String?,
        fieldId: Int,
        inputType: Int,
        receiptNow: R?,
        ordinaryEditor: Boolean,
    ): Decision = if (
        ordinaryEditor &&
        result.length <= MAX_RESULT_LENGTH &&
        packageName == origin.packageName && fieldId == origin.fieldId && inputType == origin.inputType &&
        receiptNow != null && receiptNow == origin.receipt
    ) Decision.INSERT else Decision.CLIPBOARD
}
