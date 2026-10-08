package tribixbite.cleverkeys.clipboard

/**
 * Pure decision logic for the clipboard CATCH-UP read (Saga report, 2026-10-07).
 *
 * ## Why a catch-up exists
 *
 * History is normally captured by `ClipboardHistoryService.SystemListener`
 * (`OnPrimaryClipChangedListener`). The platform delivers that callback to the DEFAULT IME
 * whether or not its keyboard is visible (AOSP 13/14 `ClipboardService.clipboardAccessAllowed`:
 * "The default IME is always allowed to access the clipboard"), so a copy made from Chrome's
 * selection toolbar while the keyboard is hidden is normally seen. It is NOT seen while no
 * listener is registered: the IME service is not alive (process reaped by the low-memory
 * killer, another keyboard selected, service mid-restart), or registration bailed because the
 * default-IME check failed at `onCreate` (nothing retried it). A copy in such a gap was lost
 * for good, because the only other read happened at listener registration.
 *
 * The catch-up closes that gap: every time the keyboard is shown, the service reads the
 * current primary clip once and records it when it is a clip the service has not observed
 * yet. "Observed" is tracked by [ClipFingerprint], updated on EVERY read (listener,
 * registration and catch-up) before any filter runs — so a clip that was deliberately skipped
 * (sensitive flag, password manager, too large) and an entry the user deleted from history
 * while it is still on the system clipboard are never resurrected by a later keyboard show.
 */
object ClipboardCatchUp {

    /**
     * Identity of one primary-clip SET event. [setAtMillis] is
     * `ClipDescription.getTimestamp()` (API 26+, stamped by the system on every
     * `setPrimaryClip`; 0 when unavailable), which distinguishes copying the same text twice.
     * When a set time exists it alone is the identity ([contentHash] 0), so the identity can be
     * taken from the description WITHOUT reading the clip's content — the password-manager
     * exclusion relies on that. Without one (API 24–25) [contentHash] covers the items.
     */
    data class ClipFingerprint(val setAtMillis: Long, val contentHash: Int)

    /**
     * Fingerprint from the clip's set time, or — when there is none — each item's text (URI
     * string when the item has no text; null for neither). [items] is ignored when
     * [setAtMillis] is positive.
     */
    fun fingerprint(setAtMillis: Long, items: List<String?>): ClipFingerprint =
        if (setAtMillis > 0) ClipFingerprint(setAtMillis, 0) else ClipFingerprint(0, items.hashCode())

    /**
     * Whether a catch-up read should hand [current] to the normal capture path.
     *
     * - [historyEnabled] false: never — the user turned clipboard monitoring off (private copy
     *   has its own path and never involves the OS clipboard).
     * - [current] null (empty clipboard, read denied, device locked): nothing to record.
     * - [current] equal to [lastSeen]: the service already observed this exact set event.
     *
     * Everything else (size limit, IS_SENSITIVE, password-manager exclusion, dedupe/move-to-top,
     * sanitizer) is applied by the shared capture path, not here.
     */
    fun shouldRecord(historyEnabled: Boolean, current: ClipFingerprint?, lastSeen: ClipFingerprint?): Boolean =
        historyEnabled && current != null && current != lastSeen
}
