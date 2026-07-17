package tribixbite.cleverkeys.clipboard

/**
 * #156 Private copy — sliding-window rate limiter for the PROCESS_TEXT entry point (design §6.5).
 *
 * Anti-annoyance defense-in-depth on TOP of platform background-activity-launch restrictions — NOT
 * a security boundary. Enforces **10 accepts per calling package per minute** AND **30 total per
 * minute**. Excess launches are dropped silently (the caller logs; no toast — don't give a flooding
 * app a UI channel). State is in-memory (process-lifetime); a process restart resetting it is
 * acceptable and intended. Genuine human toolbar usage never approaches 10/min from one app.
 *
 * Pure JVM with an injectable clock — unit-tested by PrivateCopyRateLimiterTest.
 *
 * @param clock  monotonic-ish millisecond source (default `System.currentTimeMillis`); injected in
 *               tests to drive the window deterministically.
 */
class PrivateCopyRateLimiter(
    private val perKeyLimit: Int = PER_KEY_LIMIT,
    private val globalLimit: Int = GLOBAL_LIMIT,
    private val windowMs: Long = WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    // Accept timestamps per calling package (bounded by the sliding window on each check).
    private val perKeyTimestamps = HashMap<String, ArrayDeque<Long>>()
    // Accept timestamps across all callers, for the global cap.
    private val globalTimestamps = ArrayDeque<Long>()

    /**
     * Record an attempt from [key] (the calling package) and report whether it is allowed.
     * When allowed, the accept is counted toward both windows. When denied, nothing is recorded
     * (a denied attempt must not push out a legitimate future accept).
     *
     * Thread-safe: PROCESS_TEXT launches arrive on the main thread, but guard anyway since the
     * limiter is a process-wide singleton.
     */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val cutoff = now - windowMs

        // Evict expired timestamps from the global window.
        while (globalTimestamps.isNotEmpty() && globalTimestamps.first() <= cutoff) {
            globalTimestamps.removeFirst()
        }
        // Evict expired timestamps from this key's window.
        val keyWindow = perKeyTimestamps.getOrPut(key) { ArrayDeque() }
        while (keyWindow.isNotEmpty() && keyWindow.first() <= cutoff) {
            keyWindow.removeFirst()
        }

        // Deny if either cap is already reached within the window.
        if (keyWindow.size >= perKeyLimit) return false
        if (globalTimestamps.size >= globalLimit) return false

        // Accept: record in both windows.
        keyWindow.addLast(now)
        globalTimestamps.addLast(now)
        return true
    }

    companion object {
        const val PER_KEY_LIMIT = 10   // accepts per calling package per window
        const val GLOBAL_LIMIT = 30    // accepts across all callers per window
        const val WINDOW_MS = 60_000L  // 1 minute sliding window
    }
}
