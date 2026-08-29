package tribixbite.cleverkeys

/**
 * ARC-082 — coalesced background re-warm of the swipe engine after a DICTIONARY mutation.
 *
 * A write to `custom_words_<lang>` / `disabled_words_<lang>`, or a change to the platform user
 * dictionary (ARC-081 put that in the memo key too), changes the lexicon content version and
 * therefore invalidates the memoized trie / template index. Correctness is unaffected — both
 * adapters re-derive from the version — but the rebuild then happens lazily INSIDE the next
 * swipe, on the decode thread, in front of the user. That is the same latency hole ARC-014
 * closed for a mid-session language switch, and it is closed the same way: by asking the
 * existing prewarm to do the rebuild in the adapter's BACKGROUND task slot.
 *
 * ## Why a scheduler rather than a direct call
 *
 * Adding words is bursty. `DictionaryManager.saveUserWords` rewrites the whole preference on
 * every add, so adding five words in quick succession fires five preference-change callbacks;
 * a system-dictionary edit fires its own ContentObserver callback on top. The runner's
 * background slot already supersedes an older prewarm ([PredictionTaskRunner.submitBackground]
 * cancels the previous background task), so a burst could never queue five *concurrent*
 * builds — but the first submission is already running by the time the second arrives, and a
 * CPU-bound trie build does not observe the interrupt, so a burst still costs one wasted full
 * build per pause in the burst. Coalescing on a short window collapses the burst to a single
 * rebuild against the FINAL dictionary state, which is the only state worth building.
 *
 * The window mirrors [tribixbite.cleverkeys.activities.GeometricSettingsActivity]'s
 * slider-tick coalescing — the same latest-wins `removeCallbacks` + `postDelayed` shape.
 *
 * ## Behaviour of a swipe that arrives mid-rebuild
 *
 * Unchanged from the language-switch prewarm, deliberately: [decodeAsync][
 * tribixbite.cleverkeys.swipe.CtcEngineAdapter.decodeAsync] submits in the FOREGROUND slot,
 * which cancels the in-flight prewarm, and then lazily builds whatever the cancelled prewarm
 * had not finished. So a swipe during the rebuild pays the build itself, exactly as it does
 * during a language-switch prewarm today. This class does not change that contract; it only
 * makes the window in which it applies short and rare.
 */
object SwipeRewarmScheduler {

    /**
     * Coalescing window. Long enough to absorb a multi-word add (each word is a separate
     * preference write) and short enough that a user who adds one word and immediately swipes
     * is very likely to find the engine already warm.
     */
    const val DEBOUNCE_MS = 400L

    /**
     * The delayed-posting seam. The production implementation is a main-thread `Handler`;
     * `runPureTests` substitutes a fake, which is the only way to exercise the coalescing rule
     * without an Android Looper.
     */
    interface DelayedPoster {
        /** Drops a request posted but not yet run. Latest-wins. */
        fun cancelPending()

        /** Runs [action] after [delayMs], on the main thread in production. */
        fun postDelayed(delayMs: Long, action: Runnable)
    }

    /**
     * Test override for [DelayedPoster]. Null means the real main-thread handler, which is
     * created lazily so that merely loading this class stays Android-free.
     */
    @Volatile
    internal var poster: DelayedPoster? = null

    /**
     * Test override for the work itself. Null means
     * [CleverKeysService.requestGeometricRewarm], the single entry point that picks the
     * SERVING engine and warms it in the adapter's background slot.
     */
    @Volatile
    internal var rewarmAction: Runnable? = null

    private val mainThreadPoster: DelayedPoster by lazy { MainThreadPoster() }

    /**
     * Request a background re-warm of the swipe engine, coalescing with any request made in
     * the previous [DEBOUNCE_MS]. Safe to call from the main thread; safe to call when no IME
     * is running (the delegate no-ops).
     */
    fun requestRewarm() {
        val target = poster ?: mainThreadPoster
        target.cancelPending()
        target.postDelayed(DEBOUNCE_MS, Runnable { runRewarm() })
    }

    private fun runRewarm() {
        val override = rewarmAction
        if (override != null) {
            override.run()
            return
        }
        CleverKeysService.requestGeometricRewarm()
    }

    /**
     * Main-looper implementation of [DelayedPoster]. A nested class so the `android.os` types
     * are only resolved once [mainThreadPoster] is actually touched.
     */
    private class MainThreadPoster : DelayedPoster {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pending: Runnable? = null

        override fun cancelPending() {
            pending?.let { handler.removeCallbacks(it) }
            pending = null
        }

        override fun postDelayed(delayMs: Long, action: Runnable) {
            pending = action
            handler.postDelayed(action, delayMs)
        }
    }
}
